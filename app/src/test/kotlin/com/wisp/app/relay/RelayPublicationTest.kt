package com.wisp.app.relay

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPublicationTest {
    private fun relay() = Relay(RelayConfig("wss://a.example"), OkHttpClient())

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(relay: Relay, name: String): T =
        Relay::class.java.getDeclaredField(name).apply { isAccessible = true }.get(relay) as T

    private fun setField(relay: Relay, name: String, value: Any) {
        Relay::class.java.getDeclaredField(name).apply { isAccessible = true }.set(relay, value)
    }

    @Test
    fun `a disconnected explicit publication never enters the reconnect queue`() {
        val relay = relay()
        assertFalse(relay.send("[\"EVENT\",{}]", queueIfDisconnected = false))
        assertTrue(field<ConcurrentLinkedQueue<String>>(relay, "pendingMessages").isEmpty())
        // Existing subscription buffering keeps its original behavior.
        assertFalse(relay.send("[\"REQ\",\"feed\",{}]"))
        assertEquals(1, field<ConcurrentLinkedQueue<String>>(relay, "pendingMessages").size)
    }

    @Test
    fun `a websocket rejecting a send does not report success or queue it`() {
        val relay = relay()
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://a.example").build()
            override fun queueSize() = 0L
            override fun send(text: String) = false
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        setField(relay, "webSocket", socket)
        setField(relay, "isConnected", true)
        assertFalse(relay.send("[\"EVENT\",{}]", queueIfDisconnected = false))
        assertTrue(field<ConcurrentLinkedQueue<String>>(relay, "pendingMessages").isEmpty())
    }

    @Test
    fun `a stale connected replay cannot complete awaitConnected`() = runTest {
        val relay = relay()
        val states = field<MutableSharedFlow<Boolean>>(relay, "_connectionState")
        states.tryEmit(true)
        val waiting = async { relay.awaitConnected(timeoutMs = 100) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(waiting.await())
    }

    @Test
    fun `awaitConnected completes only when the connection is actually live`() = runTest {
        val relay = relay()
        val states = field<MutableSharedFlow<Boolean>>(relay, "_connectionState")
        states.tryEmit(true)
        val waiting = async { relay.awaitConnected(timeoutMs = 100) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        setField(relay, "isConnected", true)
        states.tryEmit(true)
        assertTrue(waiting.await())
    }
}
