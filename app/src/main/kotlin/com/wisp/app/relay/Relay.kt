package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.RelayMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue

data class RelayFailure(val relayUrl: String, val httpCode: Int?, val message: String)

class Relay(
    val config: RelayConfig,
    private val client: OkHttpClient,
) {
    @Volatile private var webSocket: WebSocket? = null
    private val connectLock = Any()
    @Volatile var isConnected = false
        private set
    @Volatile var cooldownUntil: Long = 0L

    companion object {
        const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 5 * 60_000L
        /** Minimum time a connection must stay open before its backoff is considered reset. */
        private const val STABLE_CONNECTION_MS = 10_000L

        fun createClient(): OkHttpClient = HttpClientFactory.createRelayClient()
    }

    @Volatile var reconnectDelayMs: Long = INITIAL_RECONNECT_DELAY_MS
        private set
    @Volatile var lastAttemptMs: Long = 0L
        private set

    private val sendLock = Any()
    private val pendingMessages = ConcurrentLinkedQueue<String>()
    private val maxPendingMessages = 50

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<RelayMessage> = _messages

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 4)
    val connectionState: SharedFlow<Boolean> = _connectionState

    private val _connectionErrors = MutableSharedFlow<ConsoleLogEntry>(extraBufferCapacity = 16)
    val connectionErrors: SharedFlow<ConsoleLogEntry> = _connectionErrors

    private val _failures = MutableSharedFlow<RelayFailure>(extraBufferCapacity = 16)
    val failures: SharedFlow<RelayFailure> = _failures

    private val _authChallenges = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val authChallenges: SharedFlow<String> = _authChallenges
    @Volatile var lastChallenge: String? = null
        private set

    /** Optional hooks for byte-level tracking (set by RelayPool). */
    var onBytesReceived: ((url: String, size: Int) -> Unit)? = null
    var onBytesSent: ((url: String, size: Int) -> Unit)? = null

    /**
     * Called synchronously on OkHttp's thread at the start of onOpen, before drainPendingMessages.
     * Use to immediately resend subscriptions so they hit the wire within the relay's idle-timeout
     * window (some uWebSockets-based relays drop connections that send nothing within ~150ms).
     */
    var onConnected: (() -> Unit)? = null

    fun connect() {
        synchronized(connectLock) {
            if (isConnected || webSocket != null) return

            val now = System.currentTimeMillis()
            if (now < cooldownUntil) {
                Log.d("Relay", "Skipping connect to ${config.url} — cooled down for ${(cooldownUntil - now) / 1000}s more")
                return
            }

            lastAttemptMs = now

            val request = try {
                Request.Builder()
                    .url(config.url)
                    .header("User-Agent", "Wisp/1.0 (Android; Nostr)")
                    .build()
            } catch (e: IllegalArgumentException) {
                Log.w("Relay", "Invalid relay URL: ${config.url}")
                return
            }
            val socketId = System.nanoTime()
            Log.d("RLC", "[Relay] connect() creating ws#$socketId for ${config.url}")
            if (config.url.contains(".onion")) {
                Log.d("TorRelay", "[Relay] connect() .onion relay: ${config.url} proxy=${client.proxy} connectTimeout=${client.connectTimeoutMillis}ms")
            }
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                var openedAtMs = 0L

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("RLC", "[Relay] ws#$socketId onOpen ${config.url} | isConnected was=$isConnected")
                    if (config.url.contains(".onion")) {
                        Log.d("TorRelay", "[Relay] .onion connection SUCCESS: ${config.url}")
                    }
                    openedAtMs = System.currentTimeMillis()
                    isConnected = true
                    lastAttemptMs = 0L
                    onConnected?.invoke()
                    drainPendingMessages(webSocket)
                    _connectionState.tryEmit(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onBytesReceived?.invoke(config.url, text.length)
                    val msg = RelayMessage.parse(text) ?: return
                    if (msg is RelayMessage.Auth) {
                        lastChallenge = msg.challenge
                        _authChallenges.tryEmit(msg.challenge)
                    } else {
                        _messages.tryEmit(msg)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val isCurrent = synchronized(connectLock) { this@Relay.webSocket === webSocket }
                    Log.e("RLC", "[Relay] ws#$socketId onFailure ${config.url}: ${t.javaClass.simpleName}: ${t.message} | httpCode=${response?.code} | isCurrent=$isCurrent isConnected=$isConnected")
                    if (config.url.contains(".onion")) {
                        Log.e("TorRelay", "[Relay] .onion connection FAILED: ${config.url} | error=${t.javaClass.simpleName}: ${t.message}", t)
                    }
                    synchronized(connectLock) {
                        if (this@Relay.webSocket === webSocket) {
                            isConnected = false
                            this@Relay.webSocket = null
                        }
                    }
                    if (isCurrent) {
                        val now = System.currentTimeMillis()
                        lastAttemptMs = now
                        val connectedDurationMs = if (openedAtMs > 0) now - openedAtMs else 0L
                        reconnectDelayMs = if (connectedDurationMs >= STABLE_CONNECTION_MS) {
                            INITIAL_RECONNECT_DELAY_MS  // Was stable — reset backoff
                        } else {
                            minOf(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS)  // Fast fail — back off
                        }
                        openedAtMs = 0L
                        _connectionState.tryEmit(false)
                        _connectionErrors.tryEmit(ConsoleLogEntry(
                            relayUrl = config.url,
                            type = ConsoleLogType.CONN_FAILURE,
                            message = t.message ?: t.javaClass.simpleName
                        ))
                        _failures.tryEmit(RelayFailure(config.url, response?.code, t.message ?: t.javaClass.simpleName))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val isCurrent = synchronized(connectLock) { this@Relay.webSocket === webSocket }
                    Log.d("RLC", "[Relay] ws#$socketId onClosed ${config.url} code=$code reason=$reason | isCurrent=$isCurrent isConnected=$isConnected")
                    synchronized(connectLock) {
                        if (this@Relay.webSocket === webSocket) {
                            isConnected = false
                            this@Relay.webSocket = null
                        }
                    }
                    if (isCurrent) {
                        val now = System.currentTimeMillis()
                        val connectedDurationMs = if (openedAtMs > 0) now - openedAtMs else 0L
                        openedAtMs = 0L
                        _connectionState.tryEmit(false)
                        if (code == 1000) {
                            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS  // Clean close — reset backoff
                        } else {
                            lastAttemptMs = now
                            reconnectDelayMs = if (connectedDurationMs >= STABLE_CONNECTION_MS) {
                                INITIAL_RECONNECT_DELAY_MS
                            } else {
                                minOf(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS)
                            }
                            _connectionErrors.tryEmit(ConsoleLogEntry(
                                relayUrl = config.url,
                                type = ConsoleLogType.CONN_CLOSED,
                                message = "Code $code: $reason"
                            ))
                        }
                    }
                }
            })
        }
    }

    /** Returns true if this relay is disconnected and enough time has passed to attempt reconnection. */
    fun needsReconnect(): Boolean {
        if (isConnected || webSocket != null) return false
        val now = System.currentTimeMillis()
        return now >= cooldownUntil && now >= lastAttemptMs + reconnectDelayMs
    }

    /** Calls connect() only if the relay needs reconnection. */
    fun connectIfNeeded() {
        if (needsReconnect()) connect()
    }

    fun send(message: String): Boolean {
        val ws = webSocket
        if (ws != null && isConnected) {
            onBytesSent?.invoke(config.url, message.length)
            synchronized(sendLock) {
                return ws.send(message)
            }
        }
        if (pendingMessages.size < maxPendingMessages) {
            pendingMessages.add(message)
        }
        return false
    }

    fun clearPendingMessages() {
        pendingMessages.clear()
    }

    suspend fun awaitConnected(timeoutMs: Long = 10_000): Boolean {
        if (isConnected) return true
        return withTimeoutOrNull(timeoutMs) {
            connectionState.first { it }
            true
        } ?: false
    }

    private fun drainPendingMessages(ws: WebSocket) {
        synchronized(sendLock) {
            var count = 0
            var msg = pendingMessages.poll()
            while (msg != null) {
                ws.send(msg)
                count++
                msg = pendingMessages.poll()
            }
            if (count > 0) {
                Log.d("RLC", "[Relay] drainPendingMessages(${config.url}): $count msgs drained")
            }
        }
    }

    fun disconnect() {
        synchronized(connectLock) {
            val ws = webSocket
            val wasConnected = isConnected
            Log.d("RLC", "[Relay] disconnect() ${config.url} | wasConnected=$wasConnected hasSocket=${ws != null}")
            isConnected = false
            webSocket = null
            if (ws != null) {
                synchronized(sendLock) { ws.cancel() }
            }
        }
    }

    /** Immediate TCP teardown — no graceful close handshake. Use when replacing the
     *  OkHttpClient (e.g. Tor switch) to avoid duplicate connections from the server's
     *  perspective while the graceful close waits for server ACK. */
    fun forceDisconnect() {
        synchronized(connectLock) {
            val ws = webSocket
            isConnected = false
            webSocket = null
            if (ws != null) {
                synchronized(sendLock) { ws.cancel() }
            }
        }
    }

    /** Reset backoff state — call when user explicitly reconnects */
    fun resetBackoff() {
        cooldownUntil = 0L
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        lastAttemptMs = 0L
    }

}
