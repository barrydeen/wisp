package com.wisp.app.relay

import android.util.LruCache
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val COOLDOWN_MS = 5 * 60 * 1000L  // 5 minutes
    private var blockedUrls = emptySet<String>()

    companion object {
        const val MAX_PERSISTENT = 50
        const val MAX_EPHEMERAL = 30
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seenEvents = LruCache<String, Boolean>(5000)
    private val seenLock = Any()

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<NostrEvent> = _events

    private val _relayEvents = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 1024)
    val relayEvents: SharedFlow<RelayEvent> = _relayEvents

    private val _eoseSignals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val eoseSignals: SharedFlow<String> = _eoseSignals

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount

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
        relays.filter { it.config.url in blockedUrls }.forEach { it.disconnect() }
        relays.removeAll { it.config.url in blockedUrls }
        dmRelays.filter { it.config.url in blockedUrls }.forEach { it.disconnect() }
        dmRelays.removeAll { it.config.url in blockedUrls }
        ephemeralRelays.keys.filter { it in blockedUrls }.forEach { url ->
            ephemeralRelays.remove(url)?.disconnect()
            ephemeralLastUsed.remove(url)
        }
    }

    fun updateRelays(configs: List<RelayConfig>) {
        val filtered = configs.filter { it.url !in blockedUrls }.take(MAX_PERSISTENT)

        // Disconnect removed relays
        val currentUrls = filtered.map { it.url }.toSet()
        relays.filter { it.config.url !in currentUrls }.forEach { it.disconnect() }
        relays.removeAll { it.config.url !in currentUrls }

        // Add new relays
        val existingUrls = relays.map { it.config.url }.toSet()
        for (config in filtered) {
            if (config.url !in existingUrls) {
                val relay = Relay(config, client)
                relays.add(relay)
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    fun updateDmRelays(urls: List<String>) {
        val filtered = urls.filter { it !in blockedUrls }
        val currentUrls = filtered.toSet()
        dmRelays.filter { it.config.url !in currentUrls }.forEach { it.disconnect() }
        dmRelays.removeAll { it.config.url !in currentUrls }

        val existingUrls = dmRelays.map { it.config.url }.toSet()
        for (url in filtered) {
            if (url !in existingUrls) {
                val relay = Relay(RelayConfig(url, read = true, write = true), client)
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
                            msg.subscriptionId.startsWith("user")
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
    }

    private fun updateConnectedCount() {
        val permanent = relays.count { it.isConnected }
        val ephemeral = ephemeralRelays.values.count { it.isConnected }
        _connectedCount.value = permanent + ephemeral
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
        for (relay in relays) {
            if (relay.config.read) relay.send(message)
        }
    }

    fun sendToAll(message: String) {
        for (relay in relays) relay.send(message)
    }

    fun sendToRelay(url: String, message: String) {
        relays.find { it.config.url == url }?.send(message)
            ?: ephemeralRelays[url]?.send(message)
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
            val relay = Relay(RelayConfig(url, read = true, write = false), client)
            relay.autoReconnect = false
            collectMessages(relay)
            collectEphemeralFailures(relay)
            relay.connect()
            relay
        }
        ephemeralLastUsed[url] = System.currentTimeMillis()
        ephemeral.send(message)
        return true
    }

    private fun collectEphemeralFailures(relay: Relay) {
        scope.launch {
            relay.connectionState.collect { connected ->
                if (!connected) {
                    relayCooldowns[relay.config.url] = System.currentTimeMillis() + COOLDOWN_MS
                    // Clean up the dead ephemeral relay
                    ephemeralRelays.remove(relay.config.url)
                    ephemeralLastUsed.remove(relay.config.url)
                }
            }
        }
    }

    fun closeOnAllRelays(subscriptionId: String) {
        val msg = ClientMessage.close(subscriptionId)
        for (relay in relays) relay.send(msg)
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
    }
}
