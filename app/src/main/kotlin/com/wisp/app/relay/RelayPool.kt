package com.wisp.app.relay

import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.RelayMessage.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    companion object {
        const val MAX_PERSISTENT = 50
        const val MAX_EPHEMERAL = 30
        const val COOLDOWN_DOWN_MS = 10 * 60 * 1000L    // 10 min — 5xx, connection failures (ephemeral only)
        const val COOLDOWN_REJECTED_MS = 1 * 60 * 1000L // 1 min — 4xx like 401/403/429
        const val COOLDOWN_NETWORK_MS = 5_000L           // 5s — DNS/network failures on persistent relays
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val subscriptionTracker = SubscriptionTracker()
    private val seenEvents = LruCache<String, Boolean>(5000)
    private val seenLock = Any()

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
        relays.filter { it.config.url in blockedUrls }.forEach { it.disconnect(); relays.remove(it) }
        dmRelays.filter { it.config.url in blockedUrls }.forEach { it.disconnect(); dmRelays.remove(it) }
        ephemeralRelays.keys.filter { it in blockedUrls }.forEach { url ->
            ephemeralRelays.remove(url)?.disconnect()
            ephemeralLastUsed.remove(url)
        }
    }

    fun updateRelays(configs: List<RelayConfig>) {
        val filtered = configs.filter { it.url !in blockedUrls }.take(MAX_PERSISTENT)

        // Disconnect removed relays
        val currentUrls = filtered.map { it.url }.toSet()
        val toRemove = relays.filter { it.config.url !in currentUrls }
        toRemove.forEach { it.disconnect(); relays.remove(it) }

        // Add new relays
        val existingUrls = relays.map { it.config.url }.toSet()
        for (config in filtered) {
            if (config.url !in existingUrls) {
                val relay = Relay(config, client, scope)
                relays.add(relay)
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    fun updateDmRelays(urls: List<String>) {
        val filtered = urls.filter { it !in blockedUrls }
        val currentUrls = filtered.toSet()
        dmRelays.filter { it.config.url !in currentUrls }.forEach { it.disconnect(); dmRelays.remove(it) }

        val existingUrls = dmRelays.map { it.config.url }.toSet()
        for (url in filtered) {
            if (url !in existingUrls) {
                val relay = Relay(RelayConfig(url, read = true, write = true), client, scope)
                dmRelays.add(relay)
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    fun sendToDmRelays(message: String) {
        for (relay in dmRelays) relay.send(message)
    }

    fun hasDmRelays(): Boolean = dmRelays.isNotEmpty()

    private fun collectMessages(relay: Relay) {
        scope.launch {
            relay.messages.collect { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> {
                        // Thread, user-profile, and notification subscriptions bypass dedup
                        // since events may already have been seen during feed loading
                        val bypassDedup = msg.subscriptionId.startsWith("thread-") ||
                            msg.subscriptionId.startsWith("user") ||
                            msg.subscriptionId.startsWith("quote-") ||
                            msg.subscriptionId == "editprofile"
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
                    }
                    else -> {}
                }
            }
        }
        scope.launch {
            relay.connectionErrors.collect { addConsoleEntry(it) }
        }
        scope.launch {
            relay.connectionState.collect { updateConnectedCount() }
        }
        collectRelayFailures(relay)
        collectAuthChallenges(relay)
    }

    private fun collectAuthChallenges(relay: Relay) {
        scope.launch {
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
        for (relay in relays) {
            if (relay.config.write) relay.send(message)
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
        relays.find { it.config.url == url }?.send(message)
            ?: dmRelays.find { it.config.url == url }?.send(message)
            ?: ephemeralRelays[url]?.send(message)
    }

    /** Extracts subscription ID from a REQ message: ["REQ","subId",...] */
    private fun extractSubId(message: String): String? {
        if (!message.startsWith("[\"REQ\",\"")) return null
        val start = 8 // after ["REQ","
        val end = message.indexOf('"', start)
        return if (end > start) message.substring(start, end) else null
    }

    fun sendToRelayOrEphemeral(url: String, message: String): Boolean {
        if (url in blockedUrls) return false
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) return false

        // Check cooldown for failed relays
        val cooldownUntil = relayCooldowns[url]
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) return false

        // Check permanent relays first
        relays.find { it.config.url == url }?.let {
            it.send(message)
            return true
        }

        // Check DM relays
        dmRelays.find { it.config.url == url }?.let {
            it.send(message)
            return true
        }

        // Check or create ephemeral relay (cap at MAX_EPHEMERAL)
        if (!ephemeralRelays.containsKey(url) && ephemeralRelays.size >= MAX_EPHEMERAL) return false
        val ephemeral = ephemeralRelays.getOrPut(url) {
            val relay = Relay(RelayConfig(url, read = true, write = false), client, scope)
            relay.autoReconnect = false
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

    private fun collectRelayFailures(relay: Relay) {
        scope.launch {
            relay.failures.collect { failure ->
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
                    Log.d("RelayPool", "Cooldown ${cooldownMs / 1000}s for ephemeral ${relay.config.url} (http=${failure.httpCode})")
                } else {
                    Log.d("RelayPool", "Failure on persistent relay ${relay.config.url} (http=${failure.httpCode}), will retry in 3s")
                }
            }
        }
    }

    fun reconnectAll(): Int {
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
        }
        if (count > 0) Log.d("RelayPool", "reconnectAll: $count relays reconnecting")
        updateConnectedCount()
        return count
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
        }
        // Clear expired cooldowns
        val expiredCooldowns = relayCooldowns.filter { now >= it.value }.keys
        for (url in expiredCooldowns) {
            relayCooldowns.remove(url)
        }
    }

    fun disconnectRelay(url: String) {
        relays.find { it.config.url == url }?.let {
            it.disconnect()
            relays.remove(it)
        }
        dmRelays.find { it.config.url == url }?.let {
            it.disconnect()
            dmRelays.remove(it)
        }
        ephemeralRelays.remove(url)?.let {
            it.disconnect()
            ephemeralLastUsed.remove(url)
        }
        updateConnectedCount()
    }

    fun getRelayUrls(): List<String> = relays.map { it.config.url }

    fun getDmRelayUrls(): List<String> = dmRelays.map { it.config.url }

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
        subscriptionTracker.clear()
    }
}
