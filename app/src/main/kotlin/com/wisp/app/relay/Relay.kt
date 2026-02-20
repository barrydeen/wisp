package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

data class RelayFailure(val relayUrl: String, val httpCode: Int?, val message: String)

class Relay(
    val config: RelayConfig,
    private val client: OkHttpClient,
    private val scope: CoroutineScope? = null
) {
    private var webSocket: WebSocket? = null
    @Volatile var isConnected = false
        private set
    var autoReconnect = true
    @Volatile var cooldownUntil: Long = 0L

    // Connection attempt tracking for automatic backoff
    private val connectAttempts = mutableListOf<Long>()
    private val attemptLock = Any()

    companion object {
        private const val ATTEMPT_WINDOW_MS = 60_000L       // Track attempts in the last 60s
        private const val MAX_ATTEMPTS_IN_WINDOW = 20        // Threshold before backing off
        private const val BACKOFF_COOLDOWN_MS = 5 * 60_000L  // 5 min cooldown when threshold hit

        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
            .build()
    }

    private val pendingMessages = ConcurrentLinkedQueue<String>()
    private val maxPendingMessages = 50

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<RelayMessage> = _messages

    private val _connectionState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val connectionState: SharedFlow<Boolean> = _connectionState

    private val _connectionErrors = MutableSharedFlow<ConsoleLogEntry>(extraBufferCapacity = 16)
    val connectionErrors: SharedFlow<ConsoleLogEntry> = _connectionErrors

    private val _failures = MutableSharedFlow<RelayFailure>(extraBufferCapacity = 16)
    val failures: SharedFlow<RelayFailure> = _failures

    private val _authChallenges = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val authChallenges: SharedFlow<String> = _authChallenges
    @Volatile var lastChallenge: String? = null
        private set

    fun connect() {
        if (isConnected || webSocket != null) return

        // Check if we're in a cooldown period
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            Log.d("Relay", "Skipping connect to ${config.url} — cooled down for ${(cooldownUntil - now) / 1000}s more")
            return
        }

        // Track this attempt and check for excessive reconnections
        synchronized(attemptLock) {
            connectAttempts.add(now)
            // Prune old attempts outside the window
            connectAttempts.removeAll { now - it > ATTEMPT_WINDOW_MS }
            if (connectAttempts.size >= MAX_ATTEMPTS_IN_WINDOW) {
                cooldownUntil = now + BACKOFF_COOLDOWN_MS
                connectAttempts.clear()
                Log.w("Relay", "Too many connection attempts to ${config.url} " +
                    "(${MAX_ATTEMPTS_IN_WINDOW} in ${ATTEMPT_WINDOW_MS / 1000}s), " +
                    "backing off for ${BACKOFF_COOLDOWN_MS / 1000 / 60} min")
                _connectionErrors.tryEmit(ConsoleLogEntry(
                    relayUrl = config.url,
                    type = ConsoleLogType.CONN_FAILURE,
                    message = "Too many reconnect attempts — cooling off for ${BACKOFF_COOLDOWN_MS / 1000 / 60} min"
                ))
                return
            }
        }

        val request = try {
            Request.Builder().url(config.url).build()
        } catch (e: IllegalArgumentException) {
            Log.w("Relay", "Invalid relay URL: ${config.url}")
            return
        }
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("Relay", "Connected to ${config.url}")
                isConnected = true
                // Successful connection — reset attempt tracking
                synchronized(attemptLock) { connectAttempts.clear() }
                _connectionState.tryEmit(true)
                drainPendingMessages(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = RelayMessage.parse(text) ?: return
                if (msg is RelayMessage.Auth) {
                    lastChallenge = msg.challenge
                    _authChallenges.tryEmit(msg.challenge)
                } else {
                    _messages.tryEmit(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Relay", "Failure on ${config.url}: ${t.message}")
                isConnected = false
                _connectionState.tryEmit(false)
                _connectionErrors.tryEmit(ConsoleLogEntry(
                    relayUrl = config.url,
                    type = ConsoleLogType.CONN_FAILURE,
                    message = t.message ?: "Unknown error"
                ))
                _failures.tryEmit(RelayFailure(config.url, response?.code, t.message ?: "Unknown error"))
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("Relay", "Closed ${config.url}: $reason")
                isConnected = false
                this@Relay.webSocket = null
                _connectionState.tryEmit(false)
                if (code != 1000) {
                    _connectionErrors.tryEmit(ConsoleLogEntry(
                        relayUrl = config.url,
                        type = ConsoleLogType.CONN_CLOSED,
                        message = "Code $code: $reason"
                    ))
                    reconnect()
                }
            }
        })
    }

    fun send(message: String): Boolean {
        val ws = webSocket
        if (ws != null && isConnected) {
            return ws.send(message)
        }
        // Queue message for delivery when connected
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
        var msg = pendingMessages.poll()
        while (msg != null) {
            ws.send(msg)
            msg = pendingMessages.poll()
        }
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Bye")
        webSocket = null
    }

    /** Reset backoff state — call when user explicitly reconnects */
    fun resetBackoff() {
        cooldownUntil = 0L
        synchronized(attemptLock) { connectAttempts.clear() }
    }

    private fun reconnect() {
        webSocket = null
        if (!autoReconnect) return
        if (scope != null) {
            scope.launch {
                val now = System.currentTimeMillis()
                val delayMs = maxOf(3000L, cooldownUntil - now)
                delay(delayMs)
                if (!isConnected) connect()
            }
        } else {
            // Fallback for relays created without a scope
            client.dispatcher.executorService.execute {
                try {
                    val now = System.currentTimeMillis()
                    val sleepMs = maxOf(3000L, cooldownUntil - now)
                    Thread.sleep(sleepMs)
                    if (!isConnected) connect()
                } catch (_: InterruptedException) {}
            }
        }
    }

}
