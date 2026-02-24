package com.wisp.app.relay

import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.RelayMessage.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

data class RelayEvent(val event: NostrEvent, val relayUrl: String, val subscriptionId: String)

class RelayPool {
    private val client: OkHttpClient = Relay.createClient()
    private val relays = CopyOnWriteArrayList<Relay>()
    private val dmRelays = CopyOnWriteArrayList<Relay>()
    private val ephemeralRelays = java.util.concurrent.ConcurrentHashMap<String, Relay>()
    private val ephemeralLastUsed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val relayCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var pinnedRelayUrls = emptySet<String>()
    private var blockedUrls = emptySet<String>()
    fun getBlockedUrls(): Set<String> = blockedUrls

    @Volatile var appIsActive = false
        set(value) {
            field = value
            // Suppress/enable auto-reconnect on all relays based on app state.
            // When backgrounded, relays that drop shouldn't waste resources retrying —
            // onAppResume handles reconnection.
            setReconnectEnabled(value)
        }
    var healthTracker: RelayHealthTracker? = null

    companion object {
        const val MAX_PERSISTENT = 30
        const val MAX_DM_RELAYS = 10
        const val MAX_EPHEMERAL = 50
        const val COOLDOWN_DOWN_MS = 10 * 60 * 1000L    // 10 min — 5xx, connection failures (ephemeral only)
        const val COOLDOWN_REJECTED_MS = 1 * 60 * 1000L // 1 min — 4xx like 401/403/429
        const val COOLDOWN_NETWORK_MS = 5_000L           // 5s — DNS/network failures on persistent relays
        private const val UNSUPPORTED_THRESHOLD = 3      // Disconnect after N "unsupported" notices
    }

    /** Tracks consecutive "unsupported message" NOTICEs per relay URL. */
    private val unsupportedCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val subscriptionTracker = SubscriptionTracker()
    private val seenEvents = LruCache<String, Boolean>(5000)
    private val seenLock = Any()
    @Volatile private var feedEventCounter = 0
    @Volatile private var feedEventDedupCounter = 0

    /** Relay URL → Relay index for O(1) lookup across all pools. */
    private val relayIndex = java.util.concurrent.ConcurrentHashMap<String, Relay>()

    /** Relay URL → parent Job for all collector coroutines on that relay. */
    private val relayJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Last REQ message per subscription ID per relay URL, for re-sync on reconnect. */
    private val activeSubscriptions = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    /** Subscription prefixes that bypass event deduplication. */
    private val dedupBypassPrefixes = java.util.concurrent.CopyOnWriteArrayList(
        listOf("thread-", "user", "quote-", "editprofile")
    )

    /** Signing lambda for NIP-42 AUTH — set via [setAuthSigner]. */
    private var authSigner: (suspend (relayUrl: String, challenge: String) -> NostrEvent)? = null
    private val authenticatedRelays = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Register a signer for NIP-42 AUTH challenges.
     * The lambda receives the relay URL and challenge string and must return a signed kind-22242 event.
     */
    fun setAuthSigner(signer: suspend (relayUrl: String, challenge: String) -> NostrEvent) {
        authSigner = signer
    }

    fun registerDedupBypass(prefix: String) {
        if (prefix !in dedupBypassPrefixes) dedupBypassPrefixes.add(prefix)
    }

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<NostrEvent> = _events

    private val _relayEvents = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 1024)
    val relayEvents: SharedFlow<RelayEvent> = _relayEvents

    private val _eoseSignals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val eoseSignals: SharedFlow<String> = _eoseSignals

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount

    private val _authCompleted = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits the relay URL after successful NIP-42 AUTH. */
    val authCompleted: SharedFlow<String> = _authCompleted

    private val _consoleLog = MutableStateFlow<List<ConsoleLogEntry>>(emptyList())
    val consoleLog: StateFlow<List<ConsoleLogEntry>> = _consoleLog

    fun clearConsoleLog() {
        _consoleLog.value = emptyList()
    }

    private fun addConsoleEntry(entry: ConsoleLogEntry) {
        _consoleLog.update { entries ->
            (entries + entry).let { if (it.size > 200) it.drop(it.size - 200) else it }
        }
    }

    fun updateBlockedUrls(urls: List<String>) {
        blockedUrls = urls.toSet()
        // Disconnect any currently-connected relays that are now blocked
        relays.filter { it.config.url in blockedUrls }.forEach {
            it.disconnect(); relays.remove(it); relayIndex.remove(it.config.url)
            subscriptionTracker.untrackRelay(it.config.url); cancelRelayJobs(it.config.url)
        }
        dmRelays.filter { it.config.url in blockedUrls }.forEach {
            it.disconnect(); dmRelays.remove(it); relayIndex.remove(it.config.url)
            subscriptionTracker.untrackRelay(it.config.url); cancelRelayJobs(it.config.url)
        }
        ephemeralRelays.keys.filter { it in blockedUrls }.forEach { url ->
            ephemeralRelays.remove(url)?.disconnect()
            ephemeralLastUsed.remove(url)
            relayIndex.remove(url)
            subscriptionTracker.untrackRelay(url)
            cancelRelayJobs(url)
        }
    }

    fun updateRelays(configs: List<RelayConfig>) {
        val badRelays = healthTracker?.getBadRelays() ?: emptySet()
        val filtered = configs.filter { it.url !in blockedUrls && it.url !in badRelays }.take(MAX_PERSISTENT)

        // Disconnect removed relays
        val currentUrls = filtered.map { it.url }.toSet()
        val toRemove = relays.filter { it.config.url !in currentUrls }
        toRemove.forEach {
            it.disconnect()
            relays.remove(it)
            relayIndex.remove(it.config.url)
            subscriptionTracker.untrackRelay(it.config.url)
            cancelRelayJobs(it.config.url)
        }

        // Add new relays
        val existingUrls = relays.map { it.config.url }.toSet()
        for (config in filtered) {
            if (config.url !in existingUrls) {
                val relay = Relay(config, client, scope)
                wireByteTracking(relay)
                relays.add(relay)
                relayIndex[config.url] = relay
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    fun updateDmRelays(urls: List<String>) {
        val filtered = urls.filter { it !in blockedUrls }.take(MAX_DM_RELAYS)
        val currentUrls = filtered.toSet()
        dmRelays.filter { it.config.url !in currentUrls }.forEach {
            it.disconnect()
            dmRelays.remove(it)
            relayIndex.remove(it.config.url)
            subscriptionTracker.untrackRelay(it.config.url)
            cancelRelayJobs(it.config.url)
        }

        val existingUrls = dmRelays.map { it.config.url }.toSet()
        for (url in filtered) {
            if (url !in existingUrls) {
                val relay = Relay(RelayConfig(url, read = true, write = true), client, scope)
                wireByteTracking(relay)
                dmRelays.add(relay)
                relayIndex[url] = relay
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    fun sendToDmRelays(message: String) {
        for (relay in dmRelays) relay.send(message)
    }

    fun hasDmRelays(): Boolean = dmRelays.isNotEmpty()

    private fun wireByteTracking(relay: Relay) {
        relay.onBytesReceived = { url, size ->
            if (appIsActive) healthTracker?.onBytesReceived(url, size)
        }
        relay.onBytesSent = { url, size ->
            if (appIsActive) healthTracker?.onBytesSent(url, size)
        }
    }

    private fun setReconnectEnabled(enabled: Boolean) {
        for (relay in relays) relay.reconnectEnabled = enabled
        for (relay in dmRelays) relay.reconnectEnabled = enabled
        for (relay in ephemeralRelays.values) relay.reconnectEnabled = enabled
        if (!enabled) Log.d("RLC", "[Pool] auto-reconnect suppressed (app backgrounded)")
    }

    private fun cancelRelayJobs(url: String) {
        relayJobs.remove(url)?.cancel()
    }

    private fun collectMessages(relay: Relay) {
        val parentJob = SupervisorJob()
        relayJobs[relay.config.url]?.cancel()
        relayJobs[relay.config.url] = parentJob

        scope.launch(parentJob) {
            relay.messages.collect { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> {
                        // Some subscriptions bypass dedup since events may already
                        // have been seen during feed loading
                        val bypassDedup = dedupBypassPrefixes.any {
                            if (it.endsWith("-")) msg.subscriptionId.startsWith(it)
                            else msg.subscriptionId == it || msg.subscriptionId.startsWith(it)
                        }
                        val shouldEmit = if (bypassDedup) {
                            true
                        } else {
                            // Atomic check-then-put to prevent duplicate events from concurrent relays
                            synchronized(seenLock) {
                                if (seenEvents.get(msg.event.id) == null) {
                                    seenEvents.put(msg.event.id, true)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        if (shouldEmit) {
                            _events.tryEmit(msg.event)
                            _relayEvents.tryEmit(RelayEvent(msg.event, relay.config.url, msg.subscriptionId))
                            if (msg.subscriptionId == "feed") {
                                val count = ++feedEventCounter
                                if (count == 1 || count % 50 == 0) {
                                    Log.d("RLC", "[Pool] feed event #$count: kind=${msg.event.kind} from=${msg.event.pubkey.take(8)} relay=${relay.config.url}")
                                }
                            }
                        } else if (msg.subscriptionId == "feed") {
                            val count = ++feedEventDedupCounter
                            if (count == 1 || count % 50 == 0) {
                                Log.d("RLC", "[Pool] feed event DEDUPED #$count: kind=${msg.event.kind} from=${msg.event.pubkey.take(8)}")
                            }
                        }
                        if (appIsActive) healthTracker?.onEventReceived(relay.config.url, 0)
                        unsupportedCounts.remove(relay.config.url) // Relay works, clear counter
                    }
                    is RelayMessage.Eose -> {
                        if (msg.subscriptionId == "feed") {
                            Log.d("RLC", "[Pool] feed EOSE from ${relay.config.url} (total feed events=$feedEventCounter deduped=$feedEventDedupCounter)")
                        }
                        _eoseSignals.tryEmit(msg.subscriptionId)
                        unsupportedCounts.remove(relay.config.url) // Relay works, clear counter
                    }
                    is RelayMessage.Ok -> {
                        if (!msg.accepted) {
                            addConsoleEntry(ConsoleLogEntry(
                                relayUrl = relay.config.url,
                                type = ConsoleLogType.OK_REJECTED,
                                message = msg.message
                            ))
                        }
                    }
                    is RelayMessage.Notice -> {
                        addConsoleEntry(ConsoleLogEntry(
                            relayUrl = relay.config.url,
                            type = ConsoleLogType.NOTICE,
                            message = msg.message
                        ))
                        if (appIsActive && isRateLimitMessage(msg.message)) {
                            healthTracker?.onRateLimitHit(relay.config.url)
                        }
                        // Detect relays that don't support standard Nostr protocol
                        // (e.g., push notification relays like notify.damus.io)
                        if (isUnsupportedMessage(msg.message)) {
                            val count = unsupportedCounts.merge(relay.config.url, 1) { a, b -> a + b } ?: 1
                            if (count >= UNSUPPORTED_THRESHOLD) {
                                Log.w("RelayPool", "Relay ${relay.config.url} doesn't support standard protocol ($count unsupported notices), disconnecting")
                                addConsoleEntry(ConsoleLogEntry(
                                    relayUrl = relay.config.url,
                                    type = ConsoleLogType.NOTICE,
                                    message = "Disconnected: relay does not support standard Nostr protocol"
                                ))
                                disconnectRelay(relay.config.url)
                                blockedUrls = blockedUrls + relay.config.url
                            }
                        }
                    }
                    is RelayMessage.Closed -> {
                        addConsoleEntry(ConsoleLogEntry(
                            relayUrl = relay.config.url,
                            type = ConsoleLogType.NOTICE,
                            message = "CLOSED [${msg.subscriptionId}]: ${msg.message}"
                        ))
                        if (appIsActive && isRateLimitMessage(msg.message)) {
                            healthTracker?.onRateLimitHit(relay.config.url)
                        }
                    }
                    else -> {}
                }
            }
        }
        scope.launch(parentJob) {
            relay.connectionErrors.collect { addConsoleEntry(it) }
        }
        scope.launch(parentJob) {
            relay.connectionState.collect { connected ->
                Log.d("RLC", "[Pool] connectionState=$connected for ${relay.config.url} | relay.isConnected=${relay.isConnected} appIsActive=$appIsActive")
                updateConnectedCount()
                if (connected) {
                    // Always resync regardless of appIsActive — relays that connect
                    // during the awaitAnyConnected window (before appIsActive=true)
                    // would otherwise miss their subscriptions entirely.
                    resyncSubscriptions(relay)
                    if (appIsActive) healthTracker?.onRelayConnected(relay.config.url)
                } else {
                    if (appIsActive) healthTracker?.closeSession(relay.config.url)
                    subscriptionTracker.untrackRelay(relay.config.url)
                }
            }
        }
        collectRelayFailures(relay, parentJob)
        collectAuthChallenges(relay, parentJob)
    }

    private fun collectAuthChallenges(relay: Relay, parentJob: Job) {
        scope.launch(parentJob) {
            relay.authChallenges.collect { challenge ->
                val signer = authSigner ?: return@collect
                try {
                    val authEvent = signer(relay.config.url, challenge)
                    val msg = ClientMessage.auth(authEvent)
                    relay.send(msg)
                    authenticatedRelays.add(relay.config.url)
                    Log.d("RelayPool", "AUTH response sent to ${relay.config.url}")
                    _authCompleted.tryEmit(relay.config.url)
                } catch (e: Exception) {
                    Log.e("RelayPool", "AUTH failed for ${relay.config.url}: ${e.message}")
                }
            }
        }
    }

    private fun updateConnectedCount() {
        val permanent = relays.count { it.isConnected }
        val dm = dmRelays.count { it.isConnected }
        val ephemeral = ephemeralRelays.values.count { it.isConnected }
        val total = permanent + dm + ephemeral
        val prev = _connectedCount.value
        _connectedCount.value = total
        if (total != prev) {
            Log.d("RLC", "[Pool] connectedCount $prev → $total (persistent=$permanent/${relays.size} dm=$dm/${dmRelays.size} ephemeral=$ephemeral/${ephemeralRelays.size}) appIsActive=$appIsActive")
        }
    }

    fun getAllConnectedUrls(): List<String> {
        val urls = mutableListOf<String>()
        for (relay in relays) {
            if (relay.isConnected) urls.add(relay.config.url)
        }
        for ((url, relay) in ephemeralRelays) {
            if (relay.isConnected) urls.add(url)
        }
        return urls
    }

    fun sendToWriteRelays(message: String) {
        val isEvent = message.startsWith("[\"EVENT\"")
        for (relay in relays) {
            if (relay.config.write) {
                relay.send(message)
                if (isEvent && appIsActive) healthTracker?.onEventSent(relay.config.url, message.length)
            }
        }
    }

    fun sendToReadRelays(message: String) {
        val subId = extractSubId(message)
        var sentCount = 0
        for (relay in relays) {
            if (relay.config.read) {
                if (subId != null) {
                    if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                    subscriptionTracker.track(relay.config.url, subId)
                    trackSubscription(relay.config.url, subId, message)
                }
                relay.send(message)
                sentCount++
            }
        }
        if (subId != null) Log.d("RLC", "[Pool] sendToReadRelays sub=$subId → $sentCount relays")
    }

    fun sendToAll(message: String) {
        val subId = extractSubId(message)
        var sentCount = 0
        for (relay in relays) {
            if (subId != null) {
                if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                subscriptionTracker.track(relay.config.url, subId)
                trackSubscription(relay.config.url, subId, message)
            }
            relay.send(message)
            sentCount++
        }
        if (subId != null) Log.d("RLC", "[Pool] sendToAll sub=$subId → $sentCount relays")
    }

    /** Mark which relay URLs are the user's own pinned relays (from NIP-65). */
    fun setPinnedRelays(urls: Set<String>) {
        pinnedRelayUrls = urls
    }

    fun getPinnedRelayUrls(): Set<String> = pinnedRelayUrls

    /**
     * Send to only the first [maxRelays] connected relays (prioritizing pinned relays).
     * Used for metadata fetches where full broadcast is wasteful.
     */
    fun sendToTopRelays(message: String, maxRelays: Int = 10) {
        val subId = extractSubId(message)
        var sentCount = 0
        // Send to pinned relays first
        for (relay in relays) {
            if (sentCount >= maxRelays) break
            if (relay.config.url in pinnedRelayUrls && relay.isConnected) {
                if (subId != null) {
                    if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                    subscriptionTracker.track(relay.config.url, subId)
                    trackSubscription(relay.config.url, subId, message)
                }
                relay.send(message)
                sentCount++
            }
        }
        // Fill remaining slots with other connected relays
        for (relay in relays) {
            if (sentCount >= maxRelays) break
            if (relay.config.url !in pinnedRelayUrls && relay.isConnected) {
                if (subId != null) {
                    if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                    subscriptionTracker.track(relay.config.url, subId)
                    trackSubscription(relay.config.url, subId, message)
                }
                relay.send(message)
                sentCount++
            }
        }
        if (subId != null) Log.d("RLC", "[Pool] sendToTopRelays sub=$subId → $sentCount/$maxRelays relays")
    }

    fun sendToRelay(url: String, message: String) {
        val subId = extractSubId(message)
        if (subId != null) {
            if (!subscriptionTracker.hasCapacity(url, subId)) {
                Log.d("RLC", "[Pool] sendToRelay($url) SKIPPED sub=$subId — no capacity")
                return
            }
            subscriptionTracker.track(url, subId)
            trackSubscription(url, subId, message)
        }
        val relay = relayIndex[url]
        if (relay == null) {
            Log.d("RLC", "[Pool] sendToRelay($url) SKIPPED — not in relayIndex")
        } else {
            val sent = relay.send(message)
            if (subId != null) {
                Log.d("RLC", "[Pool] sendToRelay($url) sub=$subId sent=$sent connected=${relay.isConnected}")
            }
        }
    }

    /** Extracts subscription ID from a REQ message: ["REQ","subId",...] */
    private fun extractSubId(message: String): String? {
        if (!message.startsWith("[\"REQ\",\"")) return null
        val start = 8 // after ["REQ","
        val end = message.indexOf('"', start)
        return if (end > start) message.substring(start, end) else null
    }

    /** Track a REQ message for a relay so it can be re-sent on reconnect. */
    private fun trackSubscription(relayUrl: String, subId: String, message: String) {
        activeSubscriptions.getOrPut(relayUrl) {
            java.util.concurrent.ConcurrentHashMap()
        }[subId] = message
    }

    /** Remove a tracked subscription for a relay. */
    private fun untrackSubscription(relayUrl: String, subId: String) {
        activeSubscriptions[relayUrl]?.remove(subId)
    }

    fun sendToRelayOrEphemeral(url: String, message: String, skipBadCheck: Boolean = false): Boolean {
        if (url in blockedUrls) return false
        if (!skipBadCheck && healthTracker?.isBad(url) == true) return false
        if (!RelayConfig.isAcceptableUrl(url)) return false

        // Check cooldown for failed relays
        val cooldownUntil = relayCooldowns[url]
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) return false

        // O(1) lookup in persistent/DM relay index
        relayIndex[url]?.let { existing ->
            // Don't use this path for ephemeral relays (they're in relayIndex too)
            if (!ephemeralRelays.containsKey(url)) {
                existing.send(message)
                return true
            }
        }

        // Cap ephemeral relays to prevent connection explosion
        if (!ephemeralRelays.containsKey(url) && ephemeralRelays.size >= MAX_EPHEMERAL) {
            Log.d("RLC", "[Pool] sendToRelayOrEphemeral($url) SKIPPED — ephemeral cap ($MAX_EPHEMERAL) reached")
            return false
        }

        // Create ephemeral relay if needed — computeIfAbsent is atomic on ConcurrentHashMap
        val ephemeral = ephemeralRelays.computeIfAbsent(url) {
            val relay = Relay(RelayConfig(url, read = true, write = false), client, scope)
            relay.autoReconnect = false
            wireByteTracking(relay)
            relayIndex[url] = relay
            collectMessages(relay)
            relay.connect()
            relay
        }
        val subId = extractSubId(message)
        if (subId != null) {
            if (!subscriptionTracker.hasCapacity(url, subId)) return false
            subscriptionTracker.track(url, subId)
            trackSubscription(url, subId, message)
        }
        ephemeralLastUsed[url] = System.currentTimeMillis()
        ephemeral.send(message)
        return true
    }

    private fun cooldownForFailure(httpCode: Int?): Long {
        return if (httpCode != null && httpCode in 400..499) COOLDOWN_REJECTED_MS else COOLDOWN_DOWN_MS
    }

    private fun collectRelayFailures(relay: Relay, parentJob: Job) {
        scope.launch(parentJob) {
            relay.failures.collect { failure ->
                if (appIsActive && failure.httpCode == 429) {
                    healthTracker?.onRateLimitHit(relay.config.url)
                }
                val isEphemeral = ephemeralRelays.containsKey(relay.config.url)
                // Only apply cooldowns to ephemeral relays.
                // Persistent/DM relays just use the default 3s retry in Relay.reconnect()
                // with no additional cooldown — avoids cascading delays on app resume.
                if (isEphemeral) {
                    val cooldownMs = cooldownForFailure(failure.httpCode)
                    val until = System.currentTimeMillis() + cooldownMs
                    relay.cooldownUntil = until
                    relayCooldowns[relay.config.url] = until
                    ephemeralRelays.remove(relay.config.url)
                    ephemeralLastUsed.remove(relay.config.url)
                    relayIndex.remove(relay.config.url)
                    Log.d("RelayPool", "Cooldown ${cooldownMs / 1000}s for ephemeral ${relay.config.url} (http=${failure.httpCode})")
                } else {
                    Log.d("RelayPool", "Failure on persistent relay ${relay.config.url} (http=${failure.httpCode}), will retry in 3s")
                }
            }
        }
    }

    /**
     * Re-send all tracked subscriptions for a relay after it reconnects.
     * This ensures data flows immediately instead of the relay sitting idle.
     */
    private fun resyncSubscriptions(relay: Relay) {
        val subs = activeSubscriptions[relay.config.url] ?: run {
            Log.d("RLC", "[Pool] resync ${relay.config.url}: no tracked subscriptions")
            return
        }
        if (subs.isEmpty()) {
            Log.d("RLC", "[Pool] resync ${relay.config.url}: 0 subscriptions (empty map)")
            return
        }
        val subIds = subs.keys.toList()
        Log.d("RLC", "[Pool] resync ${relay.config.url}: sending ${subIds.size} subs: $subIds")
        for ((_, message) in subs) {
            relay.send(message)
        }
    }

    private fun isRateLimitMessage(message: String): Boolean {
        val lower = message.lowercase()
        return "rate" in lower || "throttle" in lower || "slow down" in lower || "too many" in lower
    }

    private fun isUnsupportedMessage(message: String): Boolean {
        val lower = message.lowercase()
        return "unsupported" in lower || "not supported" in lower || "unknown message" in lower
    }

    fun reconnectAll(): Int {
        Log.d("RLC", "[Pool] reconnectAll() START — persistent=${relays.size} dm=${dmRelays.size} ephemeral=${ephemeralRelays.size} activeSubs=${activeSubscriptions.size}")
        appIsActive = false
        healthTracker?.discardAllSessions()
        // Clear all cooldowns — background failures shouldn't block reconnection
        relayCooldowns.clear()
        // Only keep long-lived subscriptions that won't be re-established by onReconnected.
        // Feed, engagement, loadmore, etc. are transient and will be resent fresh.
        val keepPrefixes = listOf("dms", "notif")
        for (relayMap in activeSubscriptions.values) {
            relayMap.keys.retainAll { subId -> keepPrefixes.any { subId.startsWith(it) } }
        }
        // Tear down and reconnect ALL persistent relays unconditionally.
        // WebSockets can be stale (server-side timeout, NAT rebinding) while
        // OkHttp hasn't detected it yet — subscriptions sent to zombie sockets
        // are silently lost. Unlike forceReconnectAll(), we preserve
        // activeSubscriptions and subscriptionTracker so resync replays them.
        for (relay in relays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Tear down and reconnect ALL DM relays
        for (relay in dmRelays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Evict ALL ephemeral relays — they'll be recreated on demand.
        // Even "connected" ephemerals may be stale and have autoReconnect=false.
        for ((url, relay) in ephemeralRelays) {
            relay.disconnect()
            relayIndex.remove(url)
            cancelRelayJobs(url)
            activeSubscriptions.remove(url)
            subscriptionTracker.untrackRelay(url)
        }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        val count = relays.size + dmRelays.size
        Log.d("RLC", "[Pool] reconnectAll() END — reconnecting $count relays, activeSubs remaining=${activeSubscriptions.size}")
        updateConnectedCount()
        return count
    }

    /**
     * Force-reconnects ALL relays by tearing down every WebSocket (even "connected" ones)
     * and rebuilding from scratch. Use this after a long background pause where server-side
     * subscriptions have been silently dropped.
     */
    fun forceReconnectAll() {
        Log.d("RLC", "[Pool] forceReconnectAll() START — persistent=${relays.size} dm=${dmRelays.size} ephemeral=${ephemeralRelays.size}")
        appIsActive = false
        healthTracker?.closeAllSessions()
        // Server-side subscriptions are dead — clear tracker so fresh REQs are sent
        subscriptionTracker.clear()
        activeSubscriptions.clear()
        // Clear all cooldowns — background failures shouldn't block reconnection
        relayCooldowns.clear()
        // Tear down and reconnect persistent relays
        for (relay in relays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Tear down and reconnect DM relays
        for (relay in dmRelays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Evict all ephemeral relays — they'll be recreated on demand
        for ((url, relay) in ephemeralRelays) {
            relay.disconnect()
            relayIndex.remove(url)
            cancelRelayJobs(url)
        }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        Log.d("RLC", "[Pool] forceReconnectAll() END — all subs/trackers cleared")
        updateConnectedCount()
    }

    /**
     * Suspends until at least [minCount] relays are connected, or [timeoutMs] elapses.
     * Returns the connected count at the time of resolution.
     */
    suspend fun awaitAnyConnected(minCount: Int = 1, timeoutMs: Long = 10_000): Int {
        val current = _connectedCount.value
        if (current >= minCount) {
            Log.d("RLC", "[Pool] awaitAnyConnected($minCount) — already at $current, returning immediately")
            return current
        }
        Log.d("RLC", "[Pool] awaitAnyConnected($minCount) — currently $current, waiting up to ${timeoutMs}ms...")
        withTimeoutOrNull(timeoutMs) {
            _connectedCount.first { it >= minCount }
        }
        val result = _connectedCount.value
        Log.d("RLC", "[Pool] awaitAnyConnected($minCount) — resolved with $result")
        return result
    }

    fun closeOnAllRelays(subscriptionId: String) {
        Log.d("RLC", "[Pool] closeOnAllRelays($subscriptionId)")
        if (subscriptionId == "feed") {
            Log.d("RLC", "[Pool] closing feed sub — total events=$feedEventCounter deduped=$feedEventDedupCounter")
            feedEventCounter = 0
            feedEventDedupCounter = 0
        }
        subscriptionTracker.untrackAll(subscriptionId)
        for (relayMap in activeSubscriptions.values) {
            relayMap.remove(subscriptionId)
        }
        val msg = ClientMessage.close(subscriptionId)
        for (relay in relays) relay.send(msg)
        for (relay in dmRelays) relay.send(msg)
        for (relay in ephemeralRelays.values) relay.send(msg)
    }

    fun cleanupEphemeralRelays() {
        val now = System.currentTimeMillis()
        val stale = ephemeralLastUsed.filter { now - it.value > 5 * 60 * 1000 }.keys
        for (url in stale) {
            ephemeralRelays.remove(url)?.disconnect()
            ephemeralLastUsed.remove(url)
            relayIndex.remove(url)
            cancelRelayJobs(url)
            activeSubscriptions.remove(url)
            subscriptionTracker.untrackRelay(url)
        }
        // Clear expired cooldowns
        val expiredCooldowns = relayCooldowns.filter { now >= it.value }.keys
        for (url in expiredCooldowns) {
            relayCooldowns.remove(url)
        }
    }

    fun disconnectRelay(url: String) {
        relayIndex.remove(url)?.disconnect()
        relays.removeAll { it.config.url == url }
        dmRelays.removeAll { it.config.url == url }
        ephemeralRelays.remove(url)
        ephemeralLastUsed.remove(url)
        subscriptionTracker.untrackRelay(url)
        cancelRelayJobs(url)
        updateConnectedCount()
    }

    fun getRelayUrls(): List<String> = relays.map { it.config.url }

    fun getDmRelayUrls(): List<String> = dmRelays.map { it.config.url }

    fun getReadRelayUrls(): List<String> = relays.filter { it.config.read }.map { it.config.url }

    fun getWriteRelayUrls(): List<String> = relays.filter { it.config.write }.map { it.config.url }

    fun getEphemeralCount(): Int = ephemeralRelays.size

    fun isRelayConnected(url: String): Boolean = relayIndex[url]?.isConnected == true

    /** Returns remaining cooldown in seconds, or 0 if not on cooldown. */
    fun getRelayCooldownRemaining(url: String): Int {
        val until = relayCooldowns[url] ?: return 0
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 1000).toInt() + 1 else 0
    }

    fun clearSeenEvents() {
        synchronized(seenLock) {
            seenEvents.evictAll()
        }
    }

    fun disconnectAll() {
        relays.forEach { it.disconnect() }
        relays.clear()
        dmRelays.forEach { it.disconnect() }
        dmRelays.clear()
        ephemeralRelays.values.forEach { it.disconnect() }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        relayCooldowns.clear()
        relayIndex.clear()
        relayJobs.values.forEach { it.cancel() }
        relayJobs.clear()
        subscriptionTracker.clear()
        activeSubscriptions.clear()
        unsupportedCounts.clear()
        _connectedCount.value = 0
    }
}
