package com.antz.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.antz.app.nostr.ClientMessage
import com.antz.app.nostr.Filter
import com.antz.app.nostr.Nip02
import com.antz.app.nostr.NostrEvent
import com.antz.app.relay.RelayConfig
import com.antz.app.relay.RelayPool
import com.antz.app.relay.RelayScoreBoard
import com.antz.app.relay.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExtendedNetworkCache(
    val qualifiedPubkeys: Set<String>,
    val firstDegreePubkeys: Set<String>,
    val computedAtEpoch: Long,
    val stats: NetworkStats,
    /** Relay URLs needed to cover the extended network, sorted by coverage count. */
    val relayUrls: List<String> = emptyList(),
    // Legacy fields kept for deserialization compat with old caches
    val authorToRelay: Map<String, String> = emptyMap(),
    val scoredRelayUrls: List<String> = emptyList(),
    val relayHints: Map<String, Set<String>> = emptyMap()
)

@Serializable
data class NetworkStats(
    val firstDegreeCount: Int,
    val totalSecondDegree: Int,
    val qualifiedCount: Int,
    val relaysCovered: Int
)

sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data class FetchingFollowLists(val fetched: Int, val total: Int) : DiscoveryState()
    data class BuildingGraph(val processed: Int, val total: Int) : DiscoveryState()
    data class ComputingNetwork(val uniqueUsers: Int) : DiscoveryState()
    data class Filtering(val qualified: Int) : DiscoveryState()
    data class FetchingRelayLists(val fetched: Int, val total: Int) : DiscoveryState()
    data class Complete(val stats: NetworkStats) : DiscoveryState()
    data class Failed(val reason: String) : DiscoveryState()
}

class ExtendedNetworkRepository(
    private val context: Context,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val relayListRepo: RelayListRepository,
    private val relayPool: RelayPool,
    private val subManager: SubscriptionManager,
    private val relayScoreBoard: RelayScoreBoard,
    private var pubkeyHex: String?,
    private val socialGraphDb: SocialGraphDb
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState

    private val _cachedNetwork = MutableStateFlow<ExtendedNetworkCache?>(null)
    val cachedNetwork: StateFlow<ExtendedNetworkCache?> = _cachedNetwork

    // Temporary storage for kind 3 events received during discovery
    private val pendingFollowLists = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
    @Volatile private var discoveryTotal = 0
    @Volatile private var discoveryInProgress = false


    companion object {
        private const val TAG = "ExtendedNetworkRepo"
        private const val THRESHOLD = 10
        private const val FOLLOW_LIST_TIMEOUT_MS = 5_000L
        private const val RELAY_LIST_CHUNK_SIZE = 500
        private const val RELAY_LIST_TIMEOUT_MS = 8_000L
        private const val MAX_EXTENDED_RELAYS = 100
        private const val MAX_AUTHORS_PER_RELAY = 300
        private const val STALE_HOURS = 24
        private const val STALE_DRIFT_THRESHOLD = 0.10

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "antz_extended_network_$pubkeyHex" else "antz_extended_network"
    }

    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    init {
        loadFromPrefs()
    }

    fun processFollowListEvent(event: NostrEvent) {
        if (event.kind != 3) return
        pendingFollowLists[event.pubkey] = event
        val total = discoveryTotal
        if (total > 0) {
            _discoveryState.value = DiscoveryState.FetchingFollowLists(
                fetched = pendingFollowLists.size,
                total = total
            )
        }
    }

    fun resetDiscoveryState() {
        if (!discoveryInProgress) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    fun isNetworkReady(): Boolean {
        val cache = _cachedNetwork.value ?: return false
        return !isCacheStale(cache)
    }

    fun isCacheStale(cache: ExtendedNetworkCache): Boolean {
        val ageHours = (System.currentTimeMillis() / 1000 - cache.computedAtEpoch) / 3600
        if (ageHours >= STALE_HOURS) return true

        val currentFollows = contactRepo.getFollowList().map { it.pubkey }.toSet()
        if (currentFollows.isEmpty()) return false

        val cachedFollows = cache.firstDegreePubkeys
        val symmetric = (currentFollows - cachedFollows).size + (cachedFollows - currentFollows).size
        val driftRatio = symmetric.toDouble() / cachedFollows.size.coerceAtLeast(1)
        return driftRatio > STALE_DRIFT_THRESHOLD
    }

    suspend fun discoverNetwork() {
        if (discoveryInProgress) return
        discoveryInProgress = true
        try {
            val myPubkey = pubkeyHex ?: run {
                _discoveryState.value = DiscoveryState.Failed("No account logged in")
                return
            }

            val firstDegree = contactRepo.getFollowList().map { it.pubkey }
            if (firstDegree.isEmpty()) {
                _discoveryState.value = DiscoveryState.Failed("Follow list is empty")
                return
            }

            val firstDegreeSet = firstDegree.toSet()
            pendingFollowLists.clear()
            discoveryTotal = firstDegree.size

            // Step 1: Fetch kind 3 events for all follows using scoreboard routing, 3s timeout.
            // Group authors by their optimal relay and fire all requests simultaneously.
            _discoveryState.value = DiscoveryState.FetchingFollowLists(0, firstDegree.size)

            // Send a single subscription to top relays instead of per-relay subscriptions.
            // Per-relay routing created 300+ ephemeral connections and 300+ closeOnAllRelays
            // calls that blocked the main thread and caused OOM kills.
            val subId = "extnet-k3"
            val chunks = firstDegree.chunked(200)
            val allFilters = chunks.map { chunk -> Filter(kinds = listOf(3), authors = chunk) }
            val msg = if (allFilters.size == 1) ClientMessage.req(subId, allFilters[0])
            else ClientMessage.req(subId, allFilters)

            // Collect discovery events directly from the relay pool, bypassing the main
            // event processing pipeline. The main pipeline processes events sequentially
            // and gets congested with feed events (kind 1/6), causing discovery events
            // to trickle through slowly. This dedicated collector runs in parallel.
            val coverageTarget = (firstDegree.size * 0.7).toInt()
            coroutineScope {
                val collectorJob = launch {
                    relayPool.relayEvents.collect { relayEvent: com.antz.app.relay.RelayEvent ->
                        if (relayEvent.subscriptionId == subId && relayEvent.event.kind == 3) {
                            pendingFollowLists[relayEvent.event.pubkey] = relayEvent.event
                            _discoveryState.value = DiscoveryState.FetchingFollowLists(
                                fetched = pendingFollowLists.size,
                                total = firstDegree.size
                            )
                        }
                    }
                }

                val sentCount = relayPool.sendToTopRelays(msg, maxRelays = 15)
                val eoseDeferred = async {
                    subManager.awaitEoseCount(subId, sentCount, FOLLOW_LIST_TIMEOUT_MS)
                }
                var lastFetched = 0
                var lastChangeTime = System.currentTimeMillis()
                val deadline = System.currentTimeMillis() + FOLLOW_LIST_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val fetched = pendingFollowLists.size
                    if (fetched != lastFetched) {
                        lastFetched = fetched
                        lastChangeTime = System.currentTimeMillis()
                    }
                    if (fetched >= coverageTarget) {
                        Log.d(TAG, "Follow list coverage target met: $fetched/$coverageTarget")
                        break
                    }
                    if (eoseDeferred.isCompleted) {
                        Log.d(TAG, "All EOSE received, $fetched follow lists collected")
                        break
                    }
                    // Stall detection: no new events for 1.5s means relays are done
                    if (fetched > 0 && System.currentTimeMillis() - lastChangeTime > 1_500) {
                        Log.d(TAG, "Follow list fetch stalled at $fetched/${firstDegree.size}, proceeding")
                        break
                    }
                    kotlinx.coroutines.delay(200)
                }
                eoseDeferred.cancel()
                collectorJob.cancel()
            }
            subManager.closeSubscription(subId)
            discoveryTotal = 0

            Log.d(TAG, "Fetched ${pendingFollowLists.size} follow lists from ${firstDegree.size} follows")

            // Step 2: Parse follow lists, count 2nd-degree appearances, collect relay hints.
            // Write followedBy pairs to SQLite in batches to avoid OOM.
            val followCount = firstDegree.size
            _discoveryState.value = DiscoveryState.BuildingGraph(0, followCount)
            socialGraphDb.clearAll()
            val relayHints = mutableMapOf<String, MutableSet<String>>()
            var totalDbRows = 0
            val secondDegreeCount = withContext(Dispatchers.Default) {
                val counts = mutableMapOf<String, Int>()
                val batch = mutableListOf<Pair<String, String>>()
                var processed = 0
                for ((_, event) in pendingFollowLists) {
                    val entries = Nip02.parseFollowList(event)
                    for (entry in entries) {
                        val pk = entry.pubkey
                        // Always record followedBy (including for own pubkey)
                        // so the social graph shows followers-in-network on all profiles.
                        batch.add(pk to event.pubkey)

                        if (pk != myPubkey && pk !in firstDegreeSet) {
                            counts[pk] = (counts[pk] ?: 0) + 1
                            val hint = entry.relayHint?.let { normalizeRelayUrl(it) }
                            if (hint != null) {
                                relayHints.getOrPut(pk) { mutableSetOf() }.add(hint)
                            }
                        }
                    }
                    processed++
                    if (batch.size >= 5000) {
                        totalDbRows += batch.size
                        socialGraphDb.insertBatch(batch)
                        batch.clear()
                        _discoveryState.value = DiscoveryState.BuildingGraph(processed, followCount)
                    }
                }
                if (batch.isNotEmpty()) {
                    totalDbRows += batch.size
                    socialGraphDb.insertBatch(batch)
                }
                counts
            }
            _discoveryState.value = DiscoveryState.BuildingGraph(followCount, followCount)

            _discoveryState.value = DiscoveryState.ComputingNetwork(secondDegreeCount.size)
            Log.d(TAG, "Social graph: inserted $totalDbRows followedBy rows, ${secondDegreeCount.size} unique 2nd-degree follows")

            // Step 3: Filter to qualified (threshold) and exclude muted
            val qualified = withContext(Dispatchers.Default) {
                secondDegreeCount
                    .filter { it.value >= THRESHOLD }
                    .filter { !muteRepo.isBlocked(it.key) }
                    .keys
            }

            _discoveryState.value = DiscoveryState.Filtering(qualified.size)
            Log.d(TAG, "Qualified ${qualified.size} pubkeys (threshold >= $THRESHOLD)")

            if (qualified.isEmpty()) {
                val stats = NetworkStats(
                    firstDegreeCount = firstDegree.size,
                    totalSecondDegree = secondDegreeCount.size,
                    qualifiedCount = 0,
                    relaysCovered = 0
                )
                _discoveryState.value = DiscoveryState.Complete(stats)
                return
            }

            // Step 4: Fetch relay lists for qualified pubkeys missing from cache.
            // Split into chunks of 500, send all to all relays, 3s timeout.
            val missingRelayLists = relayListRepo.getMissingPubkeys(qualified.toList())
            if (missingRelayLists.isNotEmpty()) {
                val rlChunks = missingRelayLists.chunked(RELAY_LIST_CHUNK_SIZE)
                val rlSubIds = mutableListOf<String>()
                _discoveryState.value = DiscoveryState.FetchingRelayLists(0, missingRelayLists.size)

                val rlCoverageTarget = (missingRelayLists.size * 0.7).toInt()
                coroutineScope {
                    // Dedicated collector for relay list events — same bypass as follow lists
                    val rlCollected = java.util.concurrent.atomic.AtomicInteger(0)
                    val collectorJob = launch {
                        relayPool.relayEvents.collect { relayEvent: com.antz.app.relay.RelayEvent ->
                            if (relayEvent.subscriptionId.startsWith("extnet-rl-") && relayEvent.event.kind == 10002) {
                                relayListRepo.updateFromEvent(relayEvent.event)
                                val count = rlCollected.incrementAndGet()
                                _discoveryState.value = DiscoveryState.FetchingRelayLists(
                                    count, missingRelayLists.size
                                )
                            }
                        }
                    }

                    val sentCounts = mutableListOf<Pair<String, Int>>()
                    for ((i, chunk) in rlChunks.withIndex()) {
                        val rlSubId = "extnet-rl-$i"
                        rlSubIds.add(rlSubId)
                        val filter = Filter(kinds = listOf(10002), authors = chunk)
                        val sent = relayPool.sendToTopRelays(ClientMessage.req(rlSubId, filter), maxRelays = 10)
                        sentCounts.add(rlSubId to sent)
                    }

                    val eoseDeferreds = sentCounts.map { (subId, count) ->
                        async { subManager.awaitEoseCount(subId, count, RELAY_LIST_TIMEOUT_MS) }
                    }
                    var rlLastFetched = 0
                    var rlLastChangeTime = System.currentTimeMillis()
                    val rlDeadline = System.currentTimeMillis() + RELAY_LIST_TIMEOUT_MS
                    while (System.currentTimeMillis() < rlDeadline) {
                        val fetched = rlCollected.get()
                        if (fetched != rlLastFetched) {
                            rlLastFetched = fetched
                            rlLastChangeTime = System.currentTimeMillis()
                        }
                        if (fetched >= rlCoverageTarget) {
                            Log.d(TAG, "Relay list coverage target met: $fetched/$rlCoverageTarget")
                            break
                        }
                        if (eoseDeferreds.all { it.isCompleted }) {
                            Log.d(TAG, "All relay list EOSE received, $fetched relay lists collected")
                            break
                        }
                        if (fetched > 0 && System.currentTimeMillis() - rlLastChangeTime > 1_500) {
                            Log.d(TAG, "Relay list fetch stalled at $fetched/${missingRelayLists.size}, proceeding")
                            break
                        }
                        kotlinx.coroutines.delay(200)
                    }
                    eoseDeferreds.forEach { it.cancel() }
                    collectorJob.cancel()
                }
                for (rlSubId in rlSubIds) subManager.closeSubscription(rlSubId)
            }

            // Step 5: Greedy set-cover to find optimal relays for the extended network
            val qualifiedHints: Map<String, Set<String>> = relayHints
                .filterKeys { it in qualified }
                .mapValues { it.value.toSet() }

            val relayUrls = withContext(Dispatchers.Default) {
                computeRelaySetCover(qualified, qualifiedHints)
            }

            val stats = NetworkStats(
                firstDegreeCount = firstDegree.size,
                totalSecondDegree = secondDegreeCount.size,
                qualifiedCount = qualified.size,
                relaysCovered = relayUrls.size
            )

            val cache = ExtendedNetworkCache(
                qualifiedPubkeys = qualified,
                firstDegreePubkeys = firstDegreeSet,
                computedAtEpoch = System.currentTimeMillis() / 1000,
                stats = stats,
                relayUrls = relayUrls,
                relayHints = qualifiedHints
            )

            _cachedNetwork.value = cache
            saveToPrefs(cache)
            pendingFollowLists.clear()

            Log.d(TAG, "Discovery complete: ${stats.qualifiedCount} qualified, ${stats.relaysCovered} relays")
            _discoveryState.value = DiscoveryState.Complete(stats)

        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _discoveryState.value = DiscoveryState.Failed(e.message ?: "Unknown error")
        } finally {
            discoveryInProgress = false
        }
    }

    /**
     * Greedy set-cover: pick relay covering most uncovered qualified pubkeys, repeat.
     * Returns the selected relay URLs in coverage order.
     */
    private fun computeRelaySetCover(
        qualified: Set<String>,
        relayHints: Map<String, Set<String>> = emptyMap()
    ): List<String> {
        val relayToAuthors = mutableMapOf<String, MutableSet<String>>()
        var fromRelayLists = 0
        var fromHints = 0
        var uncoveredInput = 0
        for (pubkey in qualified) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey)
            if (writeRelays != null) {
                fromRelayLists++
                for (url in writeRelays) {
                    relayToAuthors.getOrPut(url) { mutableSetOf() }.add(pubkey)
                }
            } else {
                val hints = relayHints[pubkey]
                if (hints != null && hints.isNotEmpty()) {
                    fromHints++
                    for (url in hints) {
                        relayToAuthors.getOrPut(url) { mutableSetOf() }.add(pubkey)
                    }
                } else {
                    uncoveredInput++
                }
            }
        }
        Log.d(TAG, "Set-cover input: $fromRelayLists from relay lists, $fromHints from hints, $uncoveredInput uncovered")
        if (relayToAuthors.isEmpty()) return emptyList()

        val uncovered = qualified.toMutableSet()
        val selected = mutableListOf<String>()
        val remaining = relayToAuthors.toMutableMap()

        while (uncovered.isNotEmpty() && selected.size < MAX_EXTENDED_RELAYS && remaining.isNotEmpty()) {
            var bestUrl: String? = null
            var bestSize = 0
            for ((url, authors) in remaining) {
                val coverSize = authors.count { it in uncovered }
                if (coverSize > bestSize) {
                    bestUrl = url
                    bestSize = coverSize
                }
            }
            if (bestUrl == null || bestSize == 0) break
            selected.add(bestUrl)
            val covered = remaining.remove(bestUrl)!!.filter { it in uncovered }
            if (covered.size <= MAX_AUTHORS_PER_RELAY) {
                uncovered.removeAll(covered.toSet())
            } else {
                // Only claim up to the cap; leave the rest for other relays
                val claimed = covered.take(MAX_AUTHORS_PER_RELAY).toSet()
                uncovered.removeAll(claimed)
            }
        }

        Log.d(TAG, "Set-cover: ${selected.size} relays cover ${qualified.size - uncovered.size}/${qualified.size} pubkeys")
        return selected
    }

    /**
     * Returns read-only relay configs for the extended network relays.
     * Used by FeedViewModel to expand the persistent pool when extended feed is active.
     */
    fun getRelayConfigs(): List<RelayConfig> {
        val cache = _cachedNetwork.value ?: return emptyList()
        return cache.relayUrls.map { RelayConfig(it, read = true, write = false) }
    }

    /**
     * Returns relay URL → count of qualified pubkeys that write to it.
     */
    fun getCoverageCounts(): Map<String, Int> {
        val cache = _cachedNetwork.value ?: return emptyMap()
        val counts = mutableMapOf<String, Int>()
        for (pubkey in cache.qualifiedPubkeys) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey)
            val urls = writeRelays
                ?: cache.relayHints[pubkey]?.toList()
                ?: continue
            for (url in urls) {
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts
    }

    private fun normalizeRelayUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("wss://", ignoreCase = true) &&
            !trimmed.startsWith("ws://", ignoreCase = true)) return null
        return trimmed.lowercase()
    }

    /**
     * Returns the set of first-degree follows who follow the given pubkey.
     */
    fun getFollowedBy(pubkey: String): Set<String> =
        socialGraphDb.getFollowers(pubkey).toSet()

    fun clear() {
        _cachedNetwork.value = null
        _discoveryState.value = DiscoveryState.Idle
        pendingFollowLists.clear()
        socialGraphDb.clearAll()
        discoveryTotal = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        this.pubkeyHex = pubkeyHex
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs(cache: ExtendedNetworkCache) {
        try {
            val data = json.encodeToString(cache)
            prefs.edit().putString("cache", data).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    private fun loadFromPrefs() {
        try {
            val data = prefs.getString("cache", null) ?: return
            val cache = json.decodeFromString<ExtendedNetworkCache>(data)
            _cachedNetwork.value = cache
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache", e)
        }
    }

}
