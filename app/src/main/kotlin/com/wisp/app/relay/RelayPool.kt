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
    private var blockedUrls = emptySet<String>()

    @Volatile var appIsActive = false
    var healthTracker: RelayHealthTracker? = null

    companion object {
        const val MAX_PERSISTENT = 150
        const val MAX_DM_RELAYS = 10
        const val COOLDOWN_DOWN_MS = 10 * 60 * 1000L    // 10 min — 5xx, connection failures (ephemeral only)
        const val COOLDOWN_REJECTED_MS = 1 * 60 * 1000L // 1 min — 4xx like 401/403/429
        const val COOLDOWN_NETWORK_MS = 5_000L           // 5s — DNS/network failures on persistent relays
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val subscriptionTracker = SubscriptionTracker()
    private val seenEvents = LruCache<String, Boolean>(5000)
    private val seenLock = Any()

    /** Relay URL → Relay index for O(1) lookup across all pools. */
    private val relayIndex = java.util.concurrent.ConcurrentHashMap<String, Relay>()

    /** Relay URL → parent Job for all collector coroutines on that relay. */
    private val relayJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Subscription prefixes that bypass event deduplication. */
    private val dedupBypassPrefixes = java.util.concurrent.CopyOnWriteArrayList(
        listOf("thread-", "user", "quote-", "editprofile")
    )

    /** Signing lambda for NIP-42 AUTH — set via [setAuthSigner]. */
    private var authSigner: ((relayUrl: String, challenge: String) -> NostrEvent)? = null
    private val authenticatedRelays = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Register a signer for NIP-42 AUTH challenges.
     * The lambda receives the relay URL and challenge string and must return a signed kind-22242 event.
     */
    fun setAuthSigner(signer: (relayUrl: String, challenge: String) -> NostrEvent) {
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
                        }
                        if (appIsActive) healthTracker?.onEventReceived(relay.config.url, 0)
                    }
                    is RelayMessage.Eose -> _eoseSignals.tryEmit(msg.subscriptionId)
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
                updateConnectedCount()
                if (appIsActive) {
                    if (connected) {
                        healthTracker?.onRelayConnected(relay.config.url)
                    } else {
                        healthTracker?.closeSession(relay.config.url)
                    }
                }
                // Clean up tracker when relay disconnects
                if (!connected) subscriptionTracker.untrackRelay(relay.config.url)
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
        _connectedCount.value = permanent + dm + ephemeral
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
        for (relay in relays) {
            if (relay.config.read) {
                if (subId != null) {
                    if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                    subscriptionTracker.track(relay.config.url, subId)
                }
                relay.send(message)
            }
        }
    }

    fun sendToAll(message: String) {
        val subId = extractSubId(message)
        for (relay in relays) {
            if (subId != null) {
                if (!subscriptionTracker.hasCapacity(relay.config.url, subId)) continue
                subscriptionTracker.track(relay.config.url, subId)
            }
            relay.send(message)
        }
    }

    fun sendToRelay(url: String, message: String) {
        val subId = extractSubId(message)
        if (subId != null) {
            if (!subscriptionTracker.hasCapacity(url, subId)) return
            subscriptionTracker.track(url, subId)
        }
        relayIndex[url]?.send(message)
    }

    /** Extracts subscription ID from a REQ message: ["REQ","subId",...] */
    private fun extractSubId(message: String): String? {
        if (!message.startsWith("[\"REQ\",\"")) return null
        val start = 8 // after ["REQ","
        val end = message.indexOf('"', start)
        return if (end > start) message.substring(start, end) else null
    }

    fun sendToRelayOrEphemeral(url: String, message: String, skipBadCheck: Boolean = false): Boolean {
        if (url in blockedUrls) return false
        if (!skipBadCheck && healthTracker?.isBad(url) == true) return false
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) return false

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

        // Create ephemeral relay if needed
        val ephemeral = ephemeralRelays.getOrPut(url) {
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

    private fun isRateLimitMessage(message: String): Boolean {
        val lower = message.lowercase()
        return "rate" in lower || "throttle" in lower || "slow down" in lower || "too many" in lower
    }

    fun reconnectAll(): Int {
        appIsActive = false
        healthTracker?.discardAllSessions()
        var count = 0
        // Reconnect disconnected persistent relays — clear cooldowns since this is explicit resume
        for (relay in relays) {
            if (!relay.isConnected) {
                relay.resetBackoff()
                relayCooldowns.remove(relay.config.url)
                Log.d("RelayPool", "Reconnecting persistent relay: ${relay.config.url}")
                relay.connect()
                count++
            }
        }
        // Reconnect disconnected DM relays
        for (relay in dmRelays) {
            if (!relay.isConnected) {
                relay.resetBackoff()
                relayCooldowns.remove(relay.config.url)
                Log.d("RelayPool", "Reconnecting DM relay: ${relay.config.url}")
                relay.connect()
                count++
            }
        }
        // Clean up dead ephemeral relays so they can be recreated fresh
        val deadEphemerals = ephemeralRelays.filter { !it.value.isConnected }.keys
        for (url in deadEphemerals) {
            ephemeralRelays.remove(url)?.disconnect()
            ephemeralLastUsed.remove(url)
            relayIndex.remove(url)
            cancelRelayJobs(url)
        }
        if (count > 0) Log.d("RelayPool", "reconnectAll: $count relays reconnecting")
        updateConnectedCount()
        return count
    }

    /**
     * Force-reconnects ALL relays by tearing down every WebSocket (even "connected" ones)
     * and rebuilding from scratch. Use this after a long background pause where server-side
     * subscriptions have been silently dropped.
     */
    fun forceReconnectAll() {
        Log.d("RelayPool", "forceReconnectAll: tearing down all connections")
        appIsActive = false
        healthTracker?.closeAllSessions()
        // Server-side subscriptions are dead — clear tracker so fresh REQs are sent
        subscriptionTracker.clear()
        // Clear all cooldowns — background failures shouldn't block reconnection
        relayCooldowns.clear()
        // Tear down and reconnect persistent relays
        for (relay in relays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
        }
        // Tear down and reconnect DM relays
        for (relay in dmRelays) {
            relay.resetBackoff()
            relay.disconnect()
            relay.connect()
        }
        // Evict all ephemeral relays — they'll be recreated on demand
        for ((url, relay) in ephemeralRelays) {
            relay.disconnect()
            relayIndex.remove(url)
            cancelRelayJobs(url)
        }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        updateConnectedCount()
    }

    /**
     * Suspends until at least [minCount] relays are connected, or [timeoutMs] elapses.
     * Returns the connected count at the time of resolution.
     */
    suspend fun awaitAnyConnected(minCount: Int = 1, timeoutMs: Long = 10_000): Int {
        if (_connectedCount.value >= minCount) return _connectedCount.value
        withTimeoutOrNull(timeoutMs) {
            _connectedCount.first { it >= minCount }
        }
        return _connectedCount.value
    }

    fun closeOnAllRelays(subscriptionId: String) {
        subscriptionTracker.untrackAll(subscriptionId)
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
        _connectedCount.value = 0
    }
}
