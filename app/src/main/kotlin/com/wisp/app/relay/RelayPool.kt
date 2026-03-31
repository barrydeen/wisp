package com.wisp.app.relay

import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.repo.DiagnosticLogger
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.RelayMessage.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

data class RelayEvent(val event: NostrEvent, val relayUrl: String, val subscriptionId: String)
data class PublishResult(val relayUrl: String, val eventId: String, val accepted: Boolean, val message: String)
data class BroadcastState(val accepted: Int, val sent: Int)

class RelayPool(private val prefs: SharedPreferences? = null) {
    /** Incremented on every reconnectAll()/forceReconnectAll(). Allows SubscriptionManager
     *  to detect stale EOSE signals from pre-reconnect subscriptions. */
    @Volatile var reconnectGeneration: Long = 0
        private set

    private var client: OkHttpClient = Relay.createClient()
    private var clientBuiltWithTor: Boolean = TorManager.isEnabled()
    private val relays = CopyOnWriteArrayList<Relay>()
    private val dmRelays = CopyOnWriteArrayList<Relay>()
    /** Persistent connections for NIP-29 group chat relays — auto-reconnect enabled. */
    private val groupRelays = java.util.concurrent.ConcurrentHashMap<String, Relay>()
    private val ephemeralRelays = java.util.concurrent.ConcurrentHashMap<String, Relay>()
    private val ephemeralLastUsed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val relayCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var pinnedRelayUrls = emptySet<String>()
    private var blockedUrls = emptySet<String>()
    fun getBlockedUrls(): Set<String> = blockedUrls

    // --- Local relay ---
    private var localRelay: Relay? = null
    private var localRelayConfig: LocalRelayConfig? = null
    @Volatile var localRelayUserPubkey: String? = null

    /** URLs tagged as recipient DM delivery relays (tier 2 for AUTH). */
    private val dmDeliveryTargets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Mark a relay URL as a DM delivery target (tier 2 AUTH). */
    fun markDmDeliveryTarget(url: String) { dmDeliveryTargets.add(url) }

    /** Relay URLs the user has approved for AUTH — persisted across sessions. */
    private val userApprovedAuthRelays: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { set ->
        prefs?.getStringSet(PREF_APPROVED_AUTH_RELAYS, emptySet())?.let { set.addAll(it) }
    }

    data class PendingAuthRequest(val relayUrl: String, val challenge: String)

    private val _pendingAuthRequest = MutableStateFlow<PendingAuthRequest?>(null)
    /** Emitted when a tier-2 (DM delivery) relay needs user approval to authenticate. */
    val pendingAuthRequest: StateFlow<PendingAuthRequest?> = _pendingAuthRequest

    /** Approve a pending AUTH request — signs and sends AUTH, persists approval. */
    fun approveAuth(request: PendingAuthRequest) {
        userApprovedAuthRelays.add(request.relayUrl)
        prefs?.edit()?.putStringSet(PREF_APPROVED_AUTH_RELAYS, userApprovedAuthRelays.toSet())?.apply()
        _pendingAuthRequest.value = null
        scope.launch {
            val signer = authSigner ?: return@launch
            try {
                val relay = relayIndex[request.relayUrl] ?: return@launch
                val authEvent = signer(request.relayUrl, request.challenge)
                relay.send(ClientMessage.auth(authEvent))
                authenticatedRelays.add(request.relayUrl)
                Log.d("RelayPool", "AUTH approved and sent to ${request.relayUrl}")
                _authCompleted.tryEmit(request.relayUrl)
            } catch (e: Exception) {
                Log.e("RelayPool", "AUTH failed after approval for ${request.relayUrl}: ${e.message}")
            }
        }
    }

    /** Deny a pending AUTH request. */
    fun denyAuth(request: PendingAuthRequest) {
        _pendingAuthRequest.value = null
        Log.d("RelayPool", "AUTH denied for ${request.relayUrl}")
    }

    fun isAuthenticated(url: String): Boolean = url in authenticatedRelays

    /**
     * Pre-approve auth for a relay (e.g. scheduler relay) so that when it sends an AUTH
     * challenge it is auto-signed without prompting the user. Must be called before connecting.
     */
    fun autoApproveRelayAuth(url: String) {
        dmDeliveryTargets.add(url)
        userApprovedAuthRelays.add(url)
    }

    /**
     * Ensure the OkHttpClient matches the current Tor state.
     * If Tor was enabled/disabled since the client was built, rebuild it.
     */
    private fun ensureClientCurrent() {
        val torNow = TorManager.isEnabled()
        if (torNow != clientBuiltWithTor) {
            client = HttpClientFactory.createRelayClient()
            clientBuiltWithTor = torNow
        }
    }

    @Volatile var appIsActive = false
        set(value) {
            field = value
            // Suppress/enable auto-reconnect on all relays based on app state.
            // When backgrounded, relays that drop shouldn't waste resources retrying —
            // onAppResume handles reconnection.
            setReconnectEnabled(value)
        }
    /** True while reconnectAll/forceReconnectAll is in progress — guards health tracker
     *  from recording disconnect churn as real failures. */
    @Volatile var isReconnecting = false
    var healthTracker: RelayHealthTracker? = null

    companion object {
        const val MAX_PERSISTENT = 30
        const val MAX_DM_RELAYS = 10
        const val MAX_EPHEMERAL = 50
        const val COOLDOWN_DOWN_MS = 10 * 60 * 1000L    // 10 min — 5xx, connection failures (ephemeral only)
        const val COOLDOWN_REJECTED_MS = 1 * 60 * 1000L // 1 min — 4xx like 401/403/429
        const val COOLDOWN_NETWORK_MS = 5_000L           // 5s — DNS/network failures on persistent relays
        private const val UNSUPPORTED_THRESHOLD = 3      // Disconnect after N "unsupported" notices
        const val PREF_APPROVED_AUTH_RELAYS = "approved_auth_relays"
    }

    /** Tracks consecutive "unsupported message" NOTICEs per relay URL. */
    private val unsupportedCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val subscriptionTracker = SubscriptionTracker()
    private val seenEvents = LruCache<String, Boolean>(10000)
    private val seenLock = Any()
    @Volatile private var feedEventCounter = 0
    @Volatile private var feedEventDedupCounter = 0
    private val subEventCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val subStartTimes  = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Relay URL → Relay index for O(1) lookup across all pools. */
    private val relayIndex = java.util.concurrent.ConcurrentHashMap<String, Relay>()

    /** Relay URL → parent Job for all collector coroutines on that relay. */
    private val relayJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Last REQ message per subscription ID per relay URL, for re-sync on reconnect. */
    private val activeSubscriptions = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    /** Subscription prefixes that bypass event deduplication. */
    private val dedupBypassPrefixes = java.util.concurrent.CopyOnWriteArrayList(
        listOf("thread-", "user", "quote-", "editprofile", "notif", "dms", "search-", "hashtag-")
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

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 4096)
    val events: SharedFlow<NostrEvent> = _events

    private val _relayEvents = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 4096)
    val relayEvents: SharedFlow<RelayEvent> = _relayEvents

    private val _eoseSignals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val eoseSignals: SharedFlow<String> = _eoseSignals

    /** Emitted when a relay sends CLOSED for a group subscription (subId starts with "grp-"). */
    val groupRelayErrors = MutableSharedFlow<Triple<String, String, String>>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount

    private val _authCompleted = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits the relay URL after successful NIP-42 AUTH. */
    val authCompleted: SharedFlow<String> = _authCompleted

    private val _publishResults = MutableSharedFlow<PublishResult>(extraBufferCapacity = 64)
    val publishResults: SharedFlow<PublishResult> = _publishResults

    private val _broadcastState = MutableStateFlow<BroadcastState?>(null)
    val broadcastState: StateFlow<BroadcastState?> = _broadcastState

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
        ensureClientCurrent()
        val badRelays = healthTracker?.getBadRelays() ?: emptySet()
        val filtered = configs.filter {
            it.url !in blockedUrls && it.url !in badRelays && RelayConfig.isConnectableUrl(it.url)
        }.take(MAX_PERSISTENT)

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
        ensureClientCurrent()
        val filtered = urls.filter { it !in blockedUrls && RelayConfig.isConnectableUrl(it) }.take(MAX_DM_RELAYS)
        val currentUrls = filtered.toSet()
        dmRelays.filter { it.config.url !in currentUrls }.forEach {
            it.disconnect()
            dmRelays.remove(it)
            relayIndex.remove(it.config.url)
            subscriptionTracker.untrackRelay(it.config.url)
            cancelRelayJobs(it.config.url)
        }

        val existingUrls = dmRelays.map { it.config.url }.toSet()
        // Bootstrap subscriptions for new relays from an existing DM relay so that
        // resyncSubscriptions() sends the "dms" subscription when the relay connects.
        val templateSubs = dmRelays.firstOrNull()?.config?.url?.let { activeSubscriptions[it] }
        for (url in filtered) {
            if (url !in existingUrls) {
                if (!templateSubs.isNullOrEmpty()) {
                    activeSubscriptions.getOrPut(url) { java.util.concurrent.ConcurrentHashMap() }
                        .putAll(templateSubs)
                }
                val relay = Relay(RelayConfig(url, read = true, write = true), client, scope)
                wireByteTracking(relay)
                dmRelays.add(relay)
                relayIndex[url] = relay
                collectMessages(relay)
                relay.connect()
            }
        }
    }

    /**
     * Ensure a persistent (auto-reconnect) connection to a group chat relay exists.
     * Must be called before sending subscriptions so the relay survives disconnection.
     */
    fun ensureGroupRelay(url: String) {
        ensureClientCurrent()
        if (url in blockedUrls || !RelayConfig.isConnectableUrl(url)) return
        if (groupRelays.containsKey(url)) return
        // Remove any stale ephemeral relay for this URL — otherwise sendToRelayOrEphemeral
        // will skip the group relay fast-path and send REQs on the disconnected ephemeral.
        ephemeralRelays.remove(url)?.disconnect()
        ephemeralLastUsed.remove(url)
        val relay = Relay(RelayConfig(url, read = true, write = true), client, scope)
        // autoReconnect defaults to true — subscriptions will be re-sent on reconnect
        wireByteTracking(relay)
        groupRelays[url] = relay
        relayIndex[url] = relay
        collectMessages(relay)
        relay.connect()
        updateConnectedCount()
    }

    fun removeGroupRelay(url: String) {
        groupRelays.remove(url)?.disconnect()
        relayIndex.remove(url)
        cancelRelayJobs(url)
        activeSubscriptions.remove(url)
        subscriptionTracker.untrackRelay(url)
        updateConnectedCount()
    }

    fun sendToDmRelays(message: String) {
        val subId = extractSubId(message)
        for (relay in dmRelays) {
            if (subId != null) trackSubscription(relay.config.url, subId, message)
            relay.send(message)
        }
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
        for (relay in groupRelays.values) relay.reconnectEnabled = enabled
        for (relay in ephemeralRelays.values.toList()) relay.reconnectEnabled = enabled
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
                            if (msg.event.kind == 1018) {
                                Log.d("POLL", "[Pool] emit kind 1018 id=${msg.event.id.take(12)} sub=${msg.subscriptionId} relay=${relay.config.url}")
                            }
                            _events.tryEmit(msg.event)
                            _relayEvents.tryEmit(RelayEvent(msg.event, relay.config.url, msg.subscriptionId))
                            // Forward to local relay (fire-and-forget)
                            if (localRelay != null && relay !== localRelay && shouldForwardToLocalRelay(msg.event)) {
                                localRelay?.send(ClientMessage.event(msg.event))
                            }
                            subEventCounts.getOrPut(msg.subscriptionId) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
                            if (msg.subscriptionId.startsWith("feed")) {
                                val count = ++feedEventCounter
                                if (count == 1 || count % 50 == 0) {
                                    Log.d("RLC", "[Pool] feed event #$count: kind=${msg.event.kind} from=${msg.event.pubkey.take(8)} relay=${relay.config.url}")
                                }
                            }
                        } else if (msg.subscriptionId.startsWith("feed")) {
                            val count = ++feedEventDedupCounter
                            if (count == 1 || count % 50 == 0) {
                                Log.d("RLC", "[Pool] feed event DEDUPED #$count: kind=${msg.event.kind} from=${msg.event.pubkey.take(8)}")
                            }
                        }
                        if (appIsActive) healthTracker?.onEventReceived(relay.config.url, 0)
                        unsupportedCounts.remove(relay.config.url) // Relay works, clear counter
                    }
                    is RelayMessage.Eose -> {
                        val count   = subEventCounts[msg.subscriptionId]?.get() ?: 0
                        val elapsed = subStartTimes[msg.subscriptionId]?.let { System.currentTimeMillis() - it } ?: -1L
                        Log.d("SUBLOG", "EOSE sub=${msg.subscriptionId} relay=${relay.config.url}: $count events in ${elapsed}ms")
                        _eoseSignals.tryEmit(msg.subscriptionId)
                        unsupportedCounts.remove(relay.config.url) // Relay works, clear counter
                    }
                    is RelayMessage.Ok -> {
                        _publishResults.tryEmit(PublishResult(
                            relayUrl = relay.config.url,
                            eventId = msg.eventId,
                            accepted = msg.accepted,
                            message = msg.message
                        ))
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
                        if (DiagnosticLogger.isEnabled &&
                            (msg.subscriptionId.startsWith("notif") || msg.subscriptionId == "dms")) {
                            DiagnosticLogger.log("CLOSED", "sub=${msg.subscriptionId} relay=${relay.config.url} " +
                                "msg=${msg.message}")
                        }
                        if (appIsActive && isRateLimitMessage(msg.message)) {
                            healthTracker?.onRateLimitHit(relay.config.url)
                        }
                        if (msg.subscriptionId.startsWith("grp-")) {
                            scope.launch {
                                groupRelayErrors.emit(Triple(relay.config.url, msg.subscriptionId, msg.message))
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        scope.launch(parentJob) {
            relay.connectionErrors.collect { if (appIsActive) addConsoleEntry(it) }
        }
        scope.launch(parentJob) {
            relay.connectionState.collect { connected ->
                Log.d("RLC", "[Pool] connectionState=$connected for ${relay.config.url} | relay.isConnected=${relay.isConnected} appIsActive=$appIsActive isReconnecting=$isReconnecting")
                updateConnectedCount()
                if (connected) {
                    // Always resync regardless of appIsActive — relays that connect
                    // during the awaitAnyConnected window (before appIsActive=true)
                    // would otherwise miss their subscriptions entirely.
                    resyncSubscriptions(relay)
                    if (appIsActive && !isReconnecting) healthTracker?.onRelayConnected(relay.config.url)
                } else {
                    if (appIsActive && !isReconnecting) healthTracker?.closeSession(relay.config.url)
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
                val url = relay.config.url
                val dmRelayUrls = dmRelays.map { it.config.url }.toSet()

                // Tier 1: User's own relays — auto-sign silently (if auth enabled on config)
                if (url in pinnedRelayUrls || url in dmRelayUrls) {
                    if (!relay.config.auth) {
                        Log.d("RelayPool", "AUTH challenge discarded — auth disabled for relay $url")
                        return@collect
                    }
                    try {
                        val authEvent = signer(url, challenge)
                        relay.send(ClientMessage.auth(authEvent))
                        authenticatedRelays.add(url)
                        Log.d("RelayPool", "AUTH auto-signed for trusted relay $url")
                        _authCompleted.tryEmit(url)
                    } catch (e: Exception) {
                        Log.e("RelayPool", "AUTH failed for trusted relay $url: ${e.message}")
                    }
                    return@collect
                }

                // Tier 2: Recipient DM delivery relays — prompt user (or auto-sign if already approved)
                if (url in dmDeliveryTargets) {
                    if (url in userApprovedAuthRelays) {
                        try {
                            val authEvent = signer(url, challenge)
                            relay.send(ClientMessage.auth(authEvent))
                            authenticatedRelays.add(url)
                            Log.d("RelayPool", "AUTH auto-signed for approved DM delivery relay $url")
                            _authCompleted.tryEmit(url)
                        } catch (e: Exception) {
                            Log.e("RelayPool", "AUTH failed for approved DM delivery relay $url: ${e.message}")
                        }
                    } else {
                        Log.d("RelayPool", "AUTH challenge from DM delivery relay $url — prompting user")
                        _pendingAuthRequest.value = PendingAuthRequest(url, challenge)
                    }
                    return@collect
                }

                // Tier 3: Everything else — silently discard
                Log.d("RelayPool", "AUTH challenge discarded from untrusted relay $url")
            }
        }
    }

    private fun updateConnectedCount() {
        val permanent = relays.count { it.isConnected }
        val dm = dmRelays.count { it.isConnected }
        val group = groupRelays.values.count { it.isConnected }
        val ephemeral = ephemeralRelays.values.count { it.isConnected }
        val total = permanent + dm + group + ephemeral
        val prev = _connectedCount.value
        _connectedCount.value = total
        if (total != prev) {
            Log.d("RLC", "[Pool] connectedCount $prev → $total (persistent=$permanent/${relays.size} dm=$dm/${dmRelays.size} group=$group/${groupRelays.size} ephemeral=$ephemeral/${ephemeralRelays.size}) appIsActive=$appIsActive")
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

    fun sendToWriteRelays(message: String): Int {
        val isEvent = message.startsWith("[\"EVENT\"")
        var sentCount = 0
        for (relay in relays) {
            if (relay.config.write) {
                if (relay.send(message)) sentCount++
                if (isEvent && appIsActive) healthTracker?.onEventSent(relay.config.url, message.length)
            }
        }
        // Also forward to local relay for own published events
        if (isEvent && localRelay != null && localRelayConfig?.enabled == true) {
            localRelay?.send(message)
        }
        return sentCount
    }

    /** Send an EVENT message to all relays (read + write) so every relay gets it. */
    fun sendToAllRelays(message: String): Int {
        var sentCount = 0
        for (relay in relays) {
            if (relay.send(message)) sentCount++
        }
        return sentCount
    }

    /**
     * Triggers connect() on any disconnected write relays and waits briefly
     * for at least one to become connected. Returns the number of connected
     * write relays after the wait.
     */
    suspend fun ensureWriteRelaysConnected(timeoutMs: Long = 5000): Int {
        val writeRelays = relays.filter { it.config.write }
        val alreadyConnected = writeRelays.count { it.isConnected }
        if (alreadyConnected > 0) return alreadyConnected

        Log.d("RLC", "[Pool] ensureWriteRelaysConnected — 0/${writeRelays.size} connected, triggering reconnect")
        for (relay in writeRelays) {
            if (!relay.isConnected) {
                relay.resetBackoff()
                relay.connect()
            }
        }

        // Poll briefly for a write relay to connect
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connected = writeRelays.count { it.isConnected }
            if (connected > 0) {
                Log.d("RLC", "[Pool] ensureWriteRelaysConnected — $connected write relay(s) now connected")
                return connected
            }
            delay(200)
        }
        val final = writeRelays.count { it.isConnected }
        Log.d("RLC", "[Pool] ensureWriteRelaysConnected — timed out, $final write relay(s) connected")
        return final
    }

    /** Ensure all persistent relays are connected, reconnecting any that are down. */
    suspend fun ensureAllRelaysConnected(timeoutMs: Long = 5000): Int {
        val disconnected = relays.filter { !it.isConnected }
        if (disconnected.isEmpty()) return relays.size

        Log.d("RLC", "[Pool] ensureAllRelaysConnected — ${relays.size - disconnected.size}/${relays.size} connected, reconnecting ${disconnected.size}")
        for (relay in disconnected) {
            relay.resetBackoff()
            relay.connect()
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connected = relays.count { it.isConnected }
            if (connected >= relays.size) {
                Log.d("RLC", "[Pool] ensureAllRelaysConnected — all $connected relay(s) connected")
                return connected
            }
            delay(200)
        }
        val final = relays.count { it.isConnected }
        Log.d("RLC", "[Pool] ensureAllRelaysConnected — timed out, $final/${relays.size} connected")
        return final
    }

    /**
     * Start tracking OK responses for a published event.
     * Updates [broadcastState] as relays respond, then auto-clears after all respond or timeout.
     */
    fun trackPublish(eventId: String, sentCount: Int) {
        _broadcastState.value = BroadcastState(accepted = 0, sent = sentCount)
        scope.launch {
            var accepted = 0
            var received = 0
            withTimeoutOrNull(5000L) {
                _publishResults
                    .filter { it.eventId == eventId }
                    .collect {
                        if (it.accepted) accepted++
                        received++
                        _broadcastState.value = BroadcastState(accepted = accepted, sent = sentCount)
                        if (received >= sentCount) return@collect
                    }
            }
            delay(1500)
            _broadcastState.value = null
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
        if (subId != null) {
            logSubStart(subId, message)
            Log.d("RLC", "[Pool] sendToReadRelays sub=$subId → $sentCount relays")
        }
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
        if (subId != null) {
            logSubStart(subId, message)
            Log.d("RLC", "[Pool] sendToAll sub=$subId → $sentCount relays")
        }
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
    fun sendToTopRelays(message: String, maxRelays: Int = 10): Int {
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
        if (subId != null) {
            logSubStart(subId, message)
            Log.d("RLC", "[Pool] sendToTopRelays sub=$subId → $sentCount/$maxRelays relays")
        }
        return sentCount
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
                logSubStart(subId, message)
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

    private fun logSubStart(subId: String, message: String) {
        if (subStartTimes.putIfAbsent(subId, System.currentTimeMillis()) != null) return // already logged
        // message format: ["REQ","subId",{filter...}]
        val filterStart = 8 + subId.length + 2  // skip past ["REQ","<subId>",
        val filterSummary = if (filterStart < message.length) message.substring(filterStart).take(300) else "(none)"
        Log.d("SUBLOG", "NEW sub=$subId | $filterSummary")
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

    /**
     * Open a connection to a relay without sending any messages — useful to trigger the AUTH
     * handshake before sending an event that requires authentication.
     */
    fun connectEphemeralRelay(url: String) {
        ensureClientCurrent()
        if (url in blockedUrls) return
        if (!RelayConfig.isConnectableUrl(url) && !RelayConfig.isLocalRelayUrl(url)) return
        if (ephemeralRelays.containsKey(url) || relayIndex.containsKey(url)) return
        if (ephemeralRelays.size >= MAX_EPHEMERAL) return
        ephemeralRelays.computeIfAbsent(url) {
            val relay = Relay(RelayConfig(url, read = true, write = false), client, scope)
            relay.autoReconnect = false
            wireByteTracking(relay)
            relayIndex[url] = relay
            collectMessages(relay)
            relay.connect()
            relay
        }
        ephemeralLastUsed[url] = System.currentTimeMillis()
    }

    fun sendToRelayOrEphemeral(url: String, message: String, skipBadCheck: Boolean = false): Boolean {
        ensureClientCurrent()
        if (url in blockedUrls) return false
        if (!skipBadCheck && healthTracker?.isBad(url) == true) return false
        if (!RelayConfig.isConnectableUrl(url) && !RelayConfig.isLocalRelayUrl(url)) return false

        // Check cooldown for failed relays
        val cooldownUntil = relayCooldowns[url]
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) return false

        // O(1) lookup in persistent/DM relay index
        relayIndex[url]?.let { existing ->
            // Don't use this path for ephemeral relays (they're in relayIndex too)
            if (!ephemeralRelays.containsKey(url)) {
                val subId = extractSubId(message)
                if (subId != null) {
                    if (!subscriptionTracker.hasCapacity(url, subId)) {
                        Log.d("RLC", "[Pool] sendToRelayOrEphemeral($url) SKIPPED sub=$subId — no capacity")
                        return false
                    }
                    subscriptionTracker.track(url, subId)
                    trackSubscription(url, subId, message)
                }
                existing.send(message)
                return true
            }
        }

        // Cap ephemeral relays to prevent connection explosion.
        // Evict the least-recently-used ephemeral relay to make room — user-initiated
        // connections (relay feed, thread views) shouldn't fail because stale outbox
        // routing connections filled the pool.
        if (!ephemeralRelays.containsKey(url) && ephemeralRelays.size >= MAX_EPHEMERAL) {
            val lruEntry = ephemeralLastUsed.minByOrNull { it.value }
            if (lruEntry != null) {
                val evictUrl = lruEntry.key
                Log.d("RLC", "[Pool] ephemeral cap reached — evicting LRU ephemeral $evictUrl")
                ephemeralRelays.remove(evictUrl)?.disconnect()
                ephemeralLastUsed.remove(evictUrl)
                relayIndex.remove(evictUrl)
                cancelRelayJobs(evictUrl)
                activeSubscriptions.remove(evictUrl)
                subscriptionTracker.untrackRelay(evictUrl)
                updateConnectedCount()
            } else {
                Log.d("RLC", "[Pool] sendToRelayOrEphemeral($url) SKIPPED — ephemeral cap ($MAX_EPHEMERAL) reached")
                return false
            }
        }

        // Create ephemeral relay if needed — computeIfAbsent is atomic on ConcurrentHashMap
        var isNew = false
        val ephemeral = ephemeralRelays.computeIfAbsent(url) {
            isNew = true
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
            logSubStart(subId, message)
        }
        ephemeralLastUsed[url] = System.currentTimeMillis()
        val sent = ephemeral.send(message)
        return sent || isNew
    }

    private fun cooldownForFailure(httpCode: Int?): Long {
        return if (httpCode != null && httpCode in 400..499) COOLDOWN_REJECTED_MS else COOLDOWN_DOWN_MS
    }

    private fun collectRelayFailures(relay: Relay, parentJob: Job) {
        scope.launch(parentJob) {
            relay.failures.collect { failure ->
                val isEphemeral = ephemeralRelays.containsKey(relay.config.url)
                // Never mark ephemeral relays bad in the health tracker — they're user-initiated
                // (search, outbox routing) and already have an in-memory cooldown. Persisting them
                // as bad would silently block search and outbox queries across sessions.
                if (appIsActive && !isEphemeral) {
                    when {
                        failure.httpCode == 429 -> healthTracker?.onRateLimitHit(relay.config.url)
                        failure.httpCode != null && failure.httpCode in 500..599 ->
                            healthTracker?.onServerError(relay.config.url, failure.httpCode)
                    }
                }
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
                    authenticatedRelays.remove(relay.config.url)
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
        // Snapshot to avoid ConcurrentModificationException — reconnectAll can clear
        // activeSubscriptions on a different thread while we iterate here.
        val snapshot = try { subs.entries.toList() } catch (e: Exception) {
            Log.w("RLC", "[Pool] resync ${relay.config.url}: snapshot failed (concurrent modification)")
            return
        }
        if (snapshot.isEmpty()) {
            Log.d("RLC", "[Pool] resync ${relay.config.url}: 0 subscriptions (empty map)")
            return
        }
        Log.d("RLC", "[Pool] resync ${relay.config.url}: sending ${snapshot.size} subs: ${snapshot.map { it.key }}")
        if (DiagnosticLogger.isEnabled) {
            DiagnosticLogger.log("RESYNC", "relay=${relay.config.url} subs=${snapshot.map { it.key }}")
        }
        for ((_, message) in snapshot) {
            relay.send(message)
        }
    }

    private fun isRateLimitMessage(message: String): Boolean {
        return message.contains("rate", ignoreCase = true) ||
            message.contains("throttle", ignoreCase = true) ||
            message.contains("slow down", ignoreCase = true) ||
            message.contains("too many", ignoreCase = true)
    }

    private fun isUnsupportedMessage(message: String): Boolean {
        return message.contains("unsupported", ignoreCase = true) ||
            message.contains("not supported", ignoreCase = true) ||
            message.contains("unknown message", ignoreCase = true)
    }

    fun reconnectAll(): Int {
        Log.d("RLC", "[Pool] reconnectAll() START — persistent=${relays.size} dm=${dmRelays.size} ephemeral=${ephemeralRelays.size} activeSubs=${activeSubscriptions.size}")
        reconnectGeneration++
        isReconnecting = true
        // Clear all cooldowns — background failures shouldn't block reconnection
        relayCooldowns.clear()
        // Only keep long-lived subscriptions that won't be re-established by onReconnected.
        // Feed, engagement, loadmore, etc. are transient and will be resent fresh.
        val keepPrefixes = listOf("dms", "notif", "grp-")
        for (relayMap in activeSubscriptions.values) {
            relayMap.keys.retainAll { subId -> keepPrefixes.any { subId.startsWith(it) } }
        }
        // Always tear down and reconnect — we cannot trust isConnected after
        // an OS sleep; the socket may be dead even though the flag says true.
        for (relay in relays) {
            relay.resetBackoff()
            relay.disconnect()
            subscriptionTracker.untrackRelay(relay.config.url)
            relay.connect()
            relay.reconnectEnabled = true
        }
        for (relay in dmRelays) {
            relay.resetBackoff()
            relay.disconnect()
            subscriptionTracker.untrackRelay(relay.config.url)
            relay.connect()
            relay.reconnectEnabled = true
        }
        for (relay in groupRelays.values) {
            relay.resetBackoff()
            relay.disconnect()
            subscriptionTracker.untrackRelay(relay.config.url)
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Evict ALL ephemeral relays — they'll be recreated on demand.
        // Even "connected" ephemerals may be stale and have autoReconnect=false.
        for ((url, relay) in ephemeralRelays) {
            relay.reconnectEnabled = false  // Suppress onFailure errors from disconnect()
            relay.disconnect()
            relayIndex.remove(url)
            cancelRelayJobs(url)
            activeSubscriptions.remove(url)
            subscriptionTracker.untrackRelay(url)
            authenticatedRelays.remove(url)
        }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        isReconnecting = false
        val total = relays.size + dmRelays.size
        Log.d("RLC", "[Pool] reconnectAll() END — reconnected $total relays, activeSubs remaining=${activeSubscriptions.size}")
        if (DiagnosticLogger.isEnabled) {
            val retainedSubs = activeSubscriptions.values.flatMap { it.keys }.distinct()
            DiagnosticLogger.log("RECONNECT", "reconnectAll completed — $total relays, retainedSubs=$retainedSubs")
        }
        updateConnectedCount()
        return total
    }

    /**
     * Force-reconnects ALL relays by tearing down every WebSocket (even "connected" ones)
     * and rebuilding from scratch. Use this after a long background pause where server-side
     * subscriptions have been silently dropped.
     */
    fun forceReconnectAll() {
        Log.d("RLC", "[Pool] forceReconnectAll() START — persistent=${relays.size} dm=${dmRelays.size} ephemeral=${ephemeralRelays.size}")
        reconnectGeneration++
        isReconnecting = true
        // Server-side subscriptions are dead — clear tracker so fresh REQs are sent
        subscriptionTracker.clear()
        activeSubscriptions.clear()
        // Clear all cooldowns — background failures shouldn't block reconnection
        relayCooldowns.clear()
        // Tear down and reconnect persistent relays
        for (relay in relays) {
            relay.resetBackoff()
            relay.reconnectEnabled = false  // Suppress onFailure errors from disconnect()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Tear down and reconnect DM relays
        for (relay in dmRelays) {
            relay.resetBackoff()
            relay.reconnectEnabled = false  // Suppress onFailure errors from disconnect()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Tear down and reconnect group chat relays
        for (relay in groupRelays.values) {
            relay.resetBackoff()
            relay.reconnectEnabled = false  // Suppress onFailure errors from disconnect()
            relay.disconnect()
            relay.connect()
            relay.reconnectEnabled = true
        }
        // Evict all ephemeral relays — they'll be recreated on demand
        for ((url, relay) in ephemeralRelays) {
            relay.reconnectEnabled = false  // Suppress onFailure errors from disconnect()
            relay.disconnect()
            relayIndex.remove(url)
            cancelRelayJobs(url)
            authenticatedRelays.remove(url)
        }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        isReconnecting = false
        Log.d("RLC", "[Pool] forceReconnectAll() END — all subs/trackers cleared")
        if (DiagnosticLogger.isEnabled) {
            DiagnosticLogger.log("RECONNECT", "forceReconnectAll completed — all subs cleared")
        }
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
        if (subscriptionId.startsWith("feed")) {
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
            authenticatedRelays.remove(url)
        }
        // Clear expired cooldowns
        val expiredCooldowns = relayCooldowns.filter { now >= it.value }.keys
        for (url in expiredCooldowns) {
            relayCooldowns.remove(url)
        }
    }

    /**
     * Pre-connect an ephemeral relay without sending a message. Use this to start
     * the WebSocket connection early so it's ready when the first REQ arrives.
     */
    fun preConnectEphemeral(url: String) {
        ensureClientCurrent()
        if (url in blockedUrls) return
        if (!RelayConfig.isConnectableUrl(url) && !RelayConfig.isLocalRelayUrl(url)) return
        if (ephemeralRelays.containsKey(url)) return
        if (ephemeralRelays.size >= MAX_EPHEMERAL) return
        val relay = Relay(RelayConfig(url, read = true, write = false), client, scope)
        relay.autoReconnect = false
        wireByteTracking(relay)
        relayIndex[url] = relay
        collectMessages(relay)
        ephemeralRelays[url] = relay
        ephemeralLastUsed[url] = System.currentTimeMillis()
        relay.connect()
        Log.d("RLC", "[Pool] preConnectEphemeral($url)")
    }

    fun disconnectRelay(url: String) {
        relayIndex.remove(url)?.disconnect()
        relays.removeAll { it.config.url == url }
        dmRelays.removeAll { it.config.url == url }
        ephemeralRelays.remove(url)
        ephemeralLastUsed.remove(url)
        authenticatedRelays.remove(url)
        subscriptionTracker.untrackRelay(url)
        cancelRelayJobs(url)
        updateConnectedCount()
    }

    fun getRelayUrls(): List<String> = relays.map { it.config.url }

    fun getDmRelayUrls(): List<String> = dmRelays.map { it.config.url }

    fun getReadRelayUrls(): List<String> = relays.filter { it.config.read }.map { it.config.url }

    fun getWriteRelayUrls(): List<String> = relays.filter { it.config.write }.map { it.config.url }

    fun getEphemeralCount(): Int = ephemeralRelays.size

    fun getEphemeralRelayUrls(): List<String> = ephemeralRelays.keys.toList()

    fun getAllRelayUrls(): List<String> {
        val urls = mutableListOf<String>()
        urls.addAll(relays.map { it.config.url })
        urls.addAll(dmRelays.map { it.config.url })
        urls.addAll(ephemeralRelays.keys)
        return urls
    }

    fun isRelayConnected(url: String): Boolean = relayIndex[url]?.isConnected == true

    /** Clear cooldown for a specific relay so it can be retried immediately. */
    fun clearCooldown(url: String) {
        relayCooldowns.remove(url)
    }

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

    /**
     * Rebuild the OkHttpClient (e.g. after Tor toggle) and reconnect all relays.
     * When [allConfigs] / [allDmUrls] are provided (e.g. from saved prefs), they
     * are used instead of the currently-connected set so that .onion relays are
     * picked up when Tor turns on.
     */
    fun swapClientAndReconnect(
        allConfigs: List<RelayConfig>? = null,
        allDmUrls: List<String>? = null
    ) {
        val configs = allConfigs ?: relays.map { it.config }.toList()
        val dmUrls = allDmUrls ?: dmRelays.map { it.config.url }.toList()
        Log.d("RLC", "[Pool] swapClientAndReconnect — persistent=${configs.size} dm=${dmUrls.size}")

        val oldClient = client
        disconnectAll()
        client = HttpClientFactory.createRelayClient()
        clientBuiltWithTor = TorManager.isEnabled()
        updateRelays(configs)
        updateDmRelays(dmUrls)
        scope.launch { HttpClientFactory.safeShutdownClient(oldClient) }
    }

    // --- Local relay management ---

    fun updateLocalRelay(config: LocalRelayConfig?, userPubkey: String?) {
        localRelayUserPubkey = userPubkey
        val oldRelay = localRelay
        val oldUrl = oldRelay?.config?.url

        if (config == null || !config.enabled) {
            // Remove local relay
            if (oldRelay != null) {
                oldRelay.forceDisconnect()
                cancelRelayJobs(oldRelay.config.url)
                relayIndex.remove(oldRelay.config.url)
                localRelay = null
                localRelayConfig = null
                Log.d("RLC", "[Pool] local relay removed: $oldUrl")
            }
            return
        }

        localRelayConfig = config

        if (oldUrl == config.url && oldRelay != null) {
            // URL unchanged — just update config, reconnect if needed
            if (!oldRelay.isConnected) oldRelay.connect()
            return
        }

        // Disconnect old if URL changed
        if (oldRelay != null) {
            oldRelay.forceDisconnect()
            cancelRelayJobs(oldRelay.config.url)
            relayIndex.remove(oldRelay.config.url)
        }

        // Create new local relay connection
        val relayConfig = RelayConfig(config.url, read = true, write = true)
        val relay = Relay(relayConfig, client)
        localRelay = relay
        relayIndex[config.url] = relay
        collectMessages(relay)

        relay.connect()
        Log.d("RLC", "[Pool] local relay connected: ${config.url}")
    }

    fun getLocalRelayUrl(): String? = localRelayConfig?.url

    /** Send a message to the local relay if configured and connected. Fire-and-forget. */
    fun sendToLocalRelay(message: String): Boolean {
        val relay = localRelay ?: return false
        if (localRelayConfig?.enabled != true) return false
        return relay.send(message)
    }

    private fun shouldForwardToLocalRelay(event: NostrEvent): Boolean {
        val config = localRelayConfig ?: return false
        if (!config.enabled) return false
        if (localRelay == null) return false
        if (event.kind !in config.kinds) return false
        return when (config.writePolicy) {
            LocalRelayWritePolicy.OWN_NOTES -> event.pubkey == localRelayUserPubkey
            LocalRelayWritePolicy.TAGGED -> {
                event.pubkey == localRelayUserPubkey ||
                    event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == localRelayUserPubkey }
            }
            LocalRelayWritePolicy.ALL_NOTES -> true
        }
    }

    fun disconnectAll() {
        relays.forEach { it.forceDisconnect() }
        relays.clear()
        dmRelays.forEach { it.forceDisconnect() }
        dmRelays.clear()
        groupRelays.values.forEach { it.forceDisconnect() }
        groupRelays.clear()
        ephemeralRelays.values.forEach { it.forceDisconnect() }
        ephemeralRelays.clear()
        ephemeralLastUsed.clear()
        relayCooldowns.clear()
        localRelay?.forceDisconnect()
        localRelay = null
        localRelayConfig = null
        relayIndex.clear()
        relayJobs.values.forEach { it.cancel() }
        relayJobs.clear()
        subscriptionTracker.clear()
        activeSubscriptions.clear()
        unsupportedCounts.clear()
        _connectedCount.value = 0
    }
}
