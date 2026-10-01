package com.wisp.app.viewmodel

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.PublishResult
import com.wisp.app.repo.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PowManagerTest {
    private class Store : NotePublicationStore {
        val saved = linkedMapOf<String, NotePublication>()
        override fun load() = saved.values.toList()
        override fun loadReceipts() = emptyList<NotePublicationReceipt>()
        override fun save(publication: NotePublication) { saved[publication.event.id] = publication }
    }

    private class Transport : NotePublicationTransport {
        override val results = MutableSharedFlow<PublishResult>(extraBufferCapacity = 16)
        val sent = mutableListOf<NostrEvent>()
        var accept = false
        override fun targetRelays(inboxPubkeys: Collection<String>) = setOf("wss://relay.example")
        override suspend fun send(relayUrl: String, event: NostrEvent): Boolean {
            sent.add(event)
            if (accept) results.emit(PublishResult(relayUrl, event.id, true, ""))
            return true
        }
    }

    private class Signer(override val pubkeyHex: String, val gate: CompletableDeferred<Unit>? = null) : NostrSigner {
        var cancelled = false
        override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
            try { gate?.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            return NostrEvent.createUnsigned(pubkeyHex, kind, content, tags, createdAt).withSignature("c".repeat(128))
        }
        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String = error("Unused")
        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String = error("Unused")
    }

    @Test fun `login rebinds mining without recreating the manager`() = runTest {
        val transport = Transport().apply { accept = true }
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, store, transport, backgroundScope, {}, ioDispatcher = dispatcher, beforeRestore = wait)
        }
        val manager = PowManager({ 0 }, { accounts.publisher.value }, dispatcher)
        val signer = Signer("a".repeat(64))
        manager.submitNote(signer, "anonymous", emptyList())
        runCurrent()
        assertTrue(transport.sent.isEmpty())
        accounts.switchAccount(signer.pubkeyHex)
        manager.submitNote(signer, "logged in", emptyList())
        runCurrent()
        assertEquals("logged in", transport.sent.single().content)
        assertTrue(manager.status.value is PowStatus.Done)
    }

    @Test fun `switch while signing cancels old mining and binds the next submission to B`() = runTest {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val stores = mapOf(a to Store(), b to Store())
        val transport = Transport().apply { accept = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, stores.getValue(account), transport, backgroundScope, {}, ioDispatcher = dispatcher, beforeRestore = wait)
        }
        val manager = PowManager({ 0 }, { accounts.publisher.value }, dispatcher)
        val oldSigner = Signer(a, CompletableDeferred())
        var oldCallback = false
        accounts.switchAccount(a)
        manager.submitNote(oldSigner, "A", emptyList(), onPublished = { oldCallback = true })
        runCurrent()
        accounts.switchAccount(b)
        manager.submitNote(Signer(b), "B", emptyList())
        runCurrent()
        assertTrue(oldSigner.cancelled)
        assertFalse(oldCallback)
        assertTrue(stores.getValue(a).saved.isEmpty())
        assertEquals(b, transport.sent.single().pubkey)
        assertEquals(b, stores.getValue(b).saved.values.single().event.pubkey)
        assertTrue(manager.status.value is PowStatus.Done)
    }

    @Test fun `switch during PoW delivery preserves A recovery without calling its completion`() = runTest {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val stores = mapOf(a to Store(), b to Store())
        val transport = Transport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, stores.getValue(account), transport, backgroundScope, {}, ioDispatcher = dispatcher, beforeRestore = wait)
        }
        val manager = PowManager({ 0 }, { accounts.publisher.value }, dispatcher)
        var completed = false
        accounts.switchAccount(a)
        manager.submitNote(Signer(a), "saved A", emptyList(), onPublished = { completed = true })
        runCurrent()
        assertEquals("saved A", stores.getValue(a).saved.values.single().event.content)
        accounts.switchAccount(b)
        runCurrent()
        assertFalse(completed)
        assertFalse(stores.getValue(a).saved.values.single().inFlight)
        assertTrue(stores.getValue(b).saved.isEmpty())
        assertEquals(1, transport.sent.size)
    }

    @Test fun `stale signer cannot submit through the new account`() = runTest {
        val transport = Transport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, Store(), transport, backgroundScope, {}, ioDispatcher = dispatcher, beforeRestore = wait)
        }
        accounts.switchAccount("b".repeat(64))
        val manager = PowManager({ 0 }, { accounts.publisher.value }, dispatcher)
        manager.submitNote(Signer("a".repeat(64)), "stale", emptyList())
        runCurrent()
        assertTrue(transport.sent.isEmpty())
        assertEquals(PowStatus.Idle, manager.status.value)
    }
    @Test fun `account switch cancels active CPU mining before signing or sending`() = runTest {
        val transport = Transport()
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, store, transport, backgroundScope, {}, ioDispatcher = dispatcher, beforeRestore = wait)
        }
        val manager = PowManager({ 256 }, { accounts.publisher.value })
        accounts.switchAccount("a".repeat(64))
        val old = requireNotNull(accounts.publisher.value)
        manager.submitNote(Signer("a".repeat(64)), "mining", emptyList())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                manager.status.first { it is PowStatus.Mining && it.attempts >= 10_000 }
            }
        }
        accounts.switchAccount("b".repeat(64))
        old.close().join()
        runCurrent()
        assertTrue(store.saved.isEmpty())
        assertTrue(transport.sent.isEmpty())
        assertEquals(PowStatus.Idle, manager.status.value)
    }

    @Test fun `manual cancel before preparation completes must prevent sending`() = runTest {
        val transport = Transport()
        val store = Store()
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publisher = NotePublisher("a".repeat(64), store, transport, backgroundScope, {},
            ioDispatcher = dispatcher, beforeRestore = { release.await() })
        val manager = PowManager({ 0 }, { publisher }, dispatcher)
        assertTrue(manager.submitNote(Signer("a".repeat(64)), "cancelled post", emptyList()))
        runCurrent()
        assertTrue(manager.status.value is PowStatus.Mining)
        assertTrue(transport.sent.isEmpty())
        manager.cancel()
        runCurrent()
        assertEquals(PowStatus.Idle, manager.status.value)
        release.complete(Unit)
        runCurrent()
        assertTrue("Cancelled preparation must not send the post", transport.sent.isEmpty())
    }

    @Test fun `manual cancel during a durable write retains a retryable payload without sending`() = runTest {
        val transport = Transport()
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var manager: PowManager
        var cancelOnWrite = true
        val cancellingStore = object : NotePublicationStore by store {
            override fun save(publication: NotePublication) {
                store.save(publication)
                if (cancelOnWrite) {
                    cancelOnWrite = false
                    manager.cancel()
                }
            }
        }
        val publisher = NotePublisher("a".repeat(64), cancellingStore, transport, backgroundScope, {}, ioDispatcher = dispatcher)
        manager = PowManager({ 0 }, { publisher }, dispatcher)
        manager.submitNote(Signer("a".repeat(64)), "cancel during save", emptyList())
        runCurrent()
        assertTrue(transport.sent.isEmpty())
        assertEquals(PowStatus.Idle, manager.status.value)
        val saved = store.load().single()
        assertFalse(saved.inFlight)
        assertEquals(saved.event, publisher.publications.value.getValue(saved.event.id).event)
        assertFalse(publisher.publications.value.getValue(saved.event.id).inFlight)
        transport.accept = true
        publisher.rebroadcast(saved.event)
        runCurrent()
        assertEquals(listOf(saved.event), transport.sent)
        assertEquals(1, publisher.publications.value.getValue(saved.event.id).acceptedCount)
    }

    @Test fun `manual cancel while caching the stored event prevents delivery handoff`() = runTest {
        val transport = Transport()
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var manager: PowManager
        var cached = 0
        val publisher = NotePublisher("a".repeat(64), store, transport, backgroundScope,
            { cached++; manager.cancel() }, ioDispatcher = dispatcher)
        manager = PowManager({ 0 }, { publisher }, dispatcher)
        manager.submitNote(Signer("a".repeat(64)), "cancel before handoff", emptyList())
        runCurrent()
        assertEquals(1, cached)
        assertTrue(transport.sent.isEmpty())
        assertFalse(store.load().single().inFlight)
        assertEquals(PowStatus.Idle, manager.status.value)
    }

}
