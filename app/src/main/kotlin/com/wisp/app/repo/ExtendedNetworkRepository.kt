package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import kotlinx.coroutines.Dispatchers
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
    val authorToRelay: Map<String, String>,
    val scoredRelayUrls: List<String>,
    val computedAtEpoch: Long,
    val stats: NetworkStats
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
    data class ComputingNetwork(val uniqueUsers: Int) : DiscoveryState()
    data class Filtering(val qualified: Int) : DiscoveryState()
    data class FetchingRelayLists(val fetched: Int, val total: Int) : DiscoveryState()
    data object BuildingRelayMap : DiscoveryState()
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
    private val pubkeyHex: String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState

    private val _cachedNetwork = MutableStateFlow<ExtendedNetworkCache?>(null)
    val cachedNetwork: StateFlow<ExtendedNetworkCache?> = _cachedNetwork

    // Temporary storage for kind 3 events received during discovery
    private val pendingFollowLists = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
    @Volatile private var discoveryTotal = 0

    companion object {
        private const val TAG = "ExtendedNetworkRepo"
        private const val THRESHOLD = 10
        private const val MAX_RELAYS = 100
        private const val MAX_AUTHORS_PER_FILTER = 250
        private const val WAVE_SIZE = 500
        private const val WAVE_TIMEOUT_MS = 12_000L
        private const val MIN_EOSE_COUNT = 5
        private const val STALE_HOURS = 24
        private const val STALE_DRIFT_THRESHOLD = 0.10

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_extended_network_$pubkeyHex" else "wisp_extended_network"
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

            // Step 1: Fetch kind 3 events for all first-degree follows in waves
            val waves = firstDegree.chunked(WAVE_SIZE)
            _discoveryState.value = DiscoveryState.FetchingFollowLists(0, firstDegree.size)

            val eoseTarget = MIN_EOSE_COUNT.coerceAtMost(relayPool.connectedCount.value.coerceAtLeast(1))
            for ((i, wave) in waves.withIndex()) {
                val subId = "extnet-k3-$i"
                val filter = Filter(kinds = listOf(3), authors = wave, limit = wave.size)
                relayPool.sendToAll(ClientMessage.req(subId, filter))

                subManager.awaitEoseCount(subId, eoseTarget, WAVE_TIMEOUT_MS)
                subManager.closeSubscription(subId)
            }
            discoveryTotal = 0

            Log.d(TAG, "Fetched ${pendingFollowLists.size} follow lists from ${firstDegree.size} follows")

            // Step 2: Parse follow lists and count 2nd-degree appearances
            val followMaps = mutableMapOf<String, Set<String>>()
            for ((pubkey, event) in pendingFollowLists) {
                val follows = Nip02.parseFollowList(event).map { it.pubkey }.toSet()
                followMaps[pubkey] = follows
            }

            val secondDegreeCount = mutableMapOf<String, Int>()
            for ((_, follows) in followMaps) {
                for (pk in follows) {
                    if (pk != myPubkey && pk !in firstDegreeSet) {
                        secondDegreeCount[pk] = (secondDegreeCount[pk] ?: 0) + 1
                    }
                }
            }

            _discoveryState.value = DiscoveryState.ComputingNetwork(secondDegreeCount.size)
            Log.d(TAG, "Found ${secondDegreeCount.size} unique 2nd-degree follows")

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

            // Step 4: Fetch relay lists for qualified pubkeys missing from cache
            val missingRelayLists = relayListRepo.getMissingPubkeys(qualified.toList())
            if (missingRelayLists.isNotEmpty()) {
                val rlWaves = missingRelayLists.chunked(WAVE_SIZE)
                var rlFetched = 0
                _discoveryState.value = DiscoveryState.FetchingRelayLists(0, missingRelayLists.size)

                for ((i, wave) in rlWaves.withIndex()) {
                    val subId = "extnet-rl-$i"
                    val filter = Filter(kinds = listOf(10002), authors = wave, limit = wave.size)
                    relayPool.sendToAll(ClientMessage.req(subId, filter))

                    subManager.awaitEoseCount(subId, eoseTarget, WAVE_TIMEOUT_MS)
                    subManager.closeSubscription(subId)

                    rlFetched += wave.size
                    _discoveryState.value = DiscoveryState.FetchingRelayLists(
                        fetched = rlFetched.coerceAtMost(missingRelayLists.size),
                        total = missingRelayLists.size
                    )
                }
            }

            // Step 5: Greedy set-cover algorithm to select optimal relays
            _discoveryState.value = DiscoveryState.BuildingRelayMap

            val (authorToRelay, scoredRelayUrls) = withContext(Dispatchers.Default) {
                computeRelayMap(qualified)
            }

            val stats = NetworkStats(
                firstDegreeCount = firstDegree.size,
                totalSecondDegree = secondDegreeCount.size,
                qualifiedCount = qualified.size,
                relaysCovered = scoredRelayUrls.size
            )

            val cache = ExtendedNetworkCache(
                qualifiedPubkeys = qualified,
                firstDegreePubkeys = firstDegreeSet,
                authorToRelay = authorToRelay,
                scoredRelayUrls = scoredRelayUrls,
                computedAtEpoch = System.currentTimeMillis() / 1000,
                stats = stats
            )

            _cachedNetwork.value = cache
            saveToPrefs(cache)
            pendingFollowLists.clear()

            Log.d(TAG, "Discovery complete: ${stats.qualifiedCount} qualified, ${stats.relaysCovered} relays")
            _discoveryState.value = DiscoveryState.Complete(stats)

        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _discoveryState.value = DiscoveryState.Failed(e.message ?: "Unknown error")
        }
    }

    private fun computeRelayMap(qualified: Set<String>): Pair<Map<String, String>, List<String>> {
        // Build relay -> authors mapping from relay list cache
        val relayToAuthors = mutableMapOf<String, MutableSet<String>>()
        val authorToRelays = mutableMapOf<String, MutableList<String>>()
        for (pubkey in qualified) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey) ?: continue
            for (url in writeRelays) {
                relayToAuthors.getOrPut(url) { mutableSetOf() }.add(pubkey)
                authorToRelays.getOrPut(pubkey) { mutableListOf() }.add(url)
            }
        }

        if (relayToAuthors.isEmpty()) {
            return Pair(emptyMap(), emptyList())
        }

        // Phase 1: Greedy set-cover to select which relays to use
        val uncovered = qualified.toMutableSet()
        val selectedRelays = mutableListOf<String>()
        val remainingRelays = relayToAuthors.toMutableMap()

        while (uncovered.isNotEmpty() && selectedRelays.size < MAX_RELAYS && remainingRelays.isNotEmpty()) {
            var bestUrl: String? = null
            var bestCover: Set<String> = emptySet()
            for ((url, authors) in remainingRelays) {
                val cover = authors.intersect(uncovered)
                if (cover.size > bestCover.size) {
                    bestUrl = url
                    bestCover = cover
                }
            }
            if (bestUrl == null || bestCover.isEmpty()) break

            selectedRelays.add(bestUrl)
            uncovered.removeAll(bestCover)
            remainingRelays.remove(bestUrl)
        }

        // Phase 2: Distribute authors across selected relays for balanced load.
        // Each author is assigned to whichever of their available selected relays
        // currently has the fewest assignments.
        val selectedSet = selectedRelays.toSet()
        val relayCounts = mutableMapOf<String, Int>()
        for (url in selectedRelays) relayCounts[url] = 0

        val inverseIndex = mutableMapOf<String, String>()
        for (pubkey in qualified) {
            val candidates = authorToRelays[pubkey]?.filter { it in selectedSet } ?: continue
            if (candidates.isEmpty()) continue
            val best = candidates.minBy { relayCounts[it] ?: Int.MAX_VALUE }
            inverseIndex[pubkey] = best
            relayCounts[best] = (relayCounts[best] ?: 0) + 1
        }

        return Pair(inverseIndex, selectedRelays)
    }

    /**
     * Route subscriptions for extended network authors to their optimal relays.
     * Groups authors using cached authorToRelay index. Uncovered authors fall back to sendToAll.
     * Returns the set of relay URLs that received subscriptions.
     */
    fun subscribeByAuthors(
        subId: String,
        authors: List<String>,
        vararg templateFilters: Filter
    ): Set<String> {
        val cache = _cachedNetwork.value ?: return emptySet()
        val targetedRelays = mutableSetOf<String>()

        // Group authors by their assigned relay
        val relayToAuthors = mutableMapOf<String, MutableList<String>>()
        val uncoveredAuthors = mutableListOf<String>()

        for (author in authors) {
            val relay = cache.authorToRelay[author]
            if (relay != null) {
                relayToAuthors.getOrPut(relay) { mutableListOf() }.add(author)
            } else {
                uncoveredAuthors.add(author)
            }
        }

        for ((relayUrl, relayAuthors) in relayToAuthors) {
            for ((j, chunk) in relayAuthors.chunked(MAX_AUTHORS_PER_FILTER).withIndex()) {
                val chunkSubId = if (j == 0) subId else "$subId-$j"
                val filters = templateFilters.map { it.copy(authors = chunk) }
                val msg = if (filters.size == 1) ClientMessage.req(chunkSubId, filters[0])
                else ClientMessage.req(chunkSubId, filters)
                if (relayPool.sendToRelayOrEphemeral(relayUrl, msg)) {
                    targetedRelays.add(relayUrl)
                }
            }
        }

        if (uncoveredAuthors.isNotEmpty()) {
            for ((j, chunk) in uncoveredAuthors.chunked(MAX_AUTHORS_PER_FILTER).withIndex()) {
                val chunkSubId = if (j == 0) subId else "$subId-uc-$j"
                val filters = templateFilters.map { it.copy(authors = chunk) }
                val msg = if (filters.size == 1) ClientMessage.req(chunkSubId, filters[0])
                else ClientMessage.req(chunkSubId, filters)
                relayPool.sendToAll(msg)
            }
            targetedRelays.addAll(relayPool.getRelayUrls())
        }

        return targetedRelays
    }

    fun clear() {
        _cachedNetwork.value = null
        _discoveryState.value = DiscoveryState.Idle
        pendingFollowLists.clear()
        discoveryTotal = 0
    }

    fun reload(pubkeyHex: String?) {
        clear()
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
