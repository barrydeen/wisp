package com.wisp.app.viewmodel

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.PublishResult
import com.wisp.app.repo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionPublicationTest {
    private val event = NostrEvent("1".repeat(64), "a".repeat(64), 1, 1, emptyList(), "Recover me", "c".repeat(128))
    private class Store : NotePublicationStore {
        var saved: NotePublication? = null
        override fun load() = listOfNotNull(saved)
        override fun loadReceipts() = emptyList<NotePublicationReceipt>()
        override fun save(publication: NotePublication) { saved = publication }
        override fun removePayload(eventId: String) { saved = null }
    }
    private class Transport : NotePublicationTransport {
        override val results = emptyFlow<PublishResult>()
        override fun targetRelays(inboxPubkeys: Collection<String>) = setOf("wss://a.example")
        var send: suspend () -> Unit = {}
        var sent = 0
        override suspend fun send(relayUrl: String, event: NostrEvent): Boolean { sent++; send(); return false }
    }

    @Test fun `account switch during restoration never caches old account posts and retains their files`() = runTest {
        val session = AccountSessionScope(backgroundScope)
        val store = Store().apply { saved = NotePublication(event, emptyList(), emptyMap()) }
        val gate = CompletableDeferred<Unit>()
        val cached = mutableListOf<NostrEvent>()
        val transport = Transport()
        val publisher = NotePublisher(event.pubkey, store, transport, session, cached::add,
            ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = { gate.await() }, verifyEvent = { true })
        runCurrent()
        publisher.close()
        session.stop()
        gate.complete(Unit)
        runCurrent()
        assertTrue(cached.isEmpty())
        assertEquals(event, store.saved?.event)
        assertEquals(0, transport.sent)
        session.start()
        val restored = NotePublisher(event.pubkey, store, transport, session, cached::add,
            ioDispatcher = StandardTestDispatcher(testScheduler), verifyEvent = { true })
        runCurrent()
        assertEquals(listOf(event), cached)
        assertEquals(event, restored.publications.value.getValue(event.id).event)
        assertEquals(0, transport.sent)
    }

    @Test fun `account session waits for publication cleanup before its store can be reopened`() = runTest {
        val session = AccountSessionScope(backgroundScope)
        val store = Store()
        val gate = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            send = {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { gate.await() } }
            }
        }
        val publisher = NotePublisher(event.pubkey, store, transport, session, {},
            ioDispatcher = StandardTestDispatcher(testScheduler), verifyEvent = { true })
        publisher.submit(event)
        runCurrent()
        publisher.close()
        val stopped = async { session.stop() }
        runCurrent()
        assertFalse(stopped.isCompleted)
        gate.complete(Unit)
        stopped.await()
        assertEquals(event, store.saved?.event)
        assertFalse(requireNotNull(store.saved).inFlight)
        session.start()
        NotePublisher(event.pubkey, store, transport, session, {},
            ioDispatcher = StandardTestDispatcher(testScheduler), verifyEvent = { true })
        runCurrent()
        assertEquals(1, transport.sent)
    }
}
