package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.RelayMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class Relay(
    val config: RelayConfig,
    private val client: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    var isConnected = false
        private set
    var autoReconnect = true

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<RelayMessage> = _messages

    private val _connectionState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val connectionState: SharedFlow<Boolean> = _connectionState

    private val _connectionErrors = MutableSharedFlow<ConsoleLogEntry>(extraBufferCapacity = 16)
    val connectionErrors: SharedFlow<ConsoleLogEntry> = _connectionErrors

    fun connect() {
        if (isConnected) return
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
                _connectionState.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                RelayMessage.parse(text)?.let { _messages.tryEmit(it) }
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
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("Relay", "Closed ${config.url}: $reason")
                isConnected = false
                _connectionState.tryEmit(false)
                if (code != 1000) {
                    _connectionErrors.tryEmit(ConsoleLogEntry(
                        relayUrl = config.url,
                        type = ConsoleLogType.CONN_CLOSED,
                        message = "Code $code: $reason"
                    ))
                }
            }
        })
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Bye")
        webSocket = null
    }

    private fun reconnect() {
        webSocket = null
        if (!autoReconnect) return
        // Simple reconnect after a delay using OkHttp's thread pool
        client.dispatcher.executorService.execute {
            try {
                Thread.sleep(3000)
                if (!isConnected) connect()
            } catch (_: InterruptedException) {}
        }
    }

    companion object {
        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
            .build()
    }
}
