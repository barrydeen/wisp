package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.PublishResult
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import com.wisp.app.viewmodel.ComposerSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotePublisherTest {
    private val pubkey = "a".repeat(64)
    private val relayA = "wss://a.example"
    private val relayB = "wss://b.example"
    private val note = NostrEvent(
        id = "1".repeat(64), pubkey = pubkey, created_at = 1, kind = 1,
        tags = listOf(listOf("p", "b".repeat(64)), listOf("e", "2".repeat(64), "", "reply")),
        content = "A signed note with a newline\nand a quote: \"hello\"", sig = "c".repeat(128)
    )

    /** Serialize every write so recovery tests cannot share an in-memory publication object. */
    private class Store : NotePublicationStore {
        val records = mutableMapOf<String, String>()
        val receipts = mutableMapOf<String, String>()
        var failWrites = false
        override fun load(): List<NotePublication> = records.values.map { Json.decodeFromString(it) }
        override fun loadReceipts(): List<NotePublicationReceipt> = receipts.values.map { Json.decodeFromString(it) }
        override fun save(publication: NotePublication) {
            if (failWrites) throw IOException("Storage unavailable")
            if (publication.acceptedCount > 0) {
                receipts.remove(publication.event.id)
                receipts[publication.event.id] = Json.encodeToString(publication.receipt())
                while (receipts.size > PUBLICATION_RECEIPT_LIMIT) receipts.remove(receipts.keys.first())
                records.remove(publication.event.id)
            } else records[publication.event.id] = Json.encodeToString(publication)
        }
    }

    private inner class Transport : NotePublicationTransport {
        override val results = MutableSharedFlow<PublishResult>(extraBufferCapacity = 64)
        override val relayCopies = MutableSharedFlow<Pair<NostrEvent, String>>(extraBufferCapacity = 64)
        var targets = linkedSetOf(relayA, relayB)
        val sent = mutableListOf<Pair<String, NostrEvent>>()
        var sendAction: suspend (String, NostrEvent) -> Boolean = { _, _ -> true }
        var recipients: Collection<String> = emptyList()
        override fun targetRelays(inboxPubkeys: Collection<String>): Set<String> {
            recipients = inboxPubkeys
            return targets
        }
        override suspend fun send(relayUrl: String, event: NostrEvent): Boolean {
            sent.add(relayUrl to event)
            return sendAction(relayUrl, event)
        }
        suspend fun acknowledge(url: String, event: NostrEvent, accepted: Boolean, message: String = "") {
            results.emit(PublishResult(url, event.id, accepted, message))
        }
    }

    private fun TestScope.publisher(
        store: Store,
        transport: Transport,
        onStored: (NostrEvent) -> Unit = {},
        deleted: (NostrEvent) -> Boolean = { false },
        known: (NostrEvent) -> Set<String> = { emptySet() }
    ) = NotePublisher(pubkey, store, transport, backgroundScope, onStored, deleted,
        timeoutMs = 1000, ioDispatcher = StandardTestDispatcher(testScheduler), verifiedRelayUrls = known)

    @Test
    fun `save precedes network send and immediate OK is captured`() = runTest {
        val store = Store()
        val transport = Transport().apply { targets = linkedSetOf(relayA) }
        val restored = mutableListOf<NostrEvent>()
        val publisher = publisher(store, transport, restored::add)
        transport.sendAction = { url, event ->
            assertEquals(note, store.load().single().event)
            assertEquals(listOf(note), restored)
            assertTrue(publisher.publications.value.getValue(note.id).inFlight)
            transport.acknowledge(url, event, true)
            true
        }
        val result = publisher.publish(note)
        assertEquals(1, result.acceptedCount)
        assertFalse(result.inFlight)
        assertTrue(store.load().isEmpty())
        assertEquals(result.receipt(), store.loadReceipts().single())
    }

    @Test
    fun `all relay rejections retain signed note and rejection reasons`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        transport.sendAction = { url, event ->
            transport.acknowledge(url, event, false, "restricted: registration required")
            true
        }
        val result = publisher.publish(note)
        assertEquals(0, result.acceptedCount)
        assertEquals(2, result.rejectedCount)
        assertEquals(note, store.load().single().event)
        assertTrue(result.relays.values.all { it.message == "restricted: registration required" })
    }

    @Test
    fun `successful websocket send without OK remains unconfirmed`() = runTest {
        val store = Store()
        val transport = Transport()
        val result = publisher(store, transport).publish(note)
        assertEquals(0, result.acceptedCount)
        assertEquals(2, result.unconfirmedCount)
        assertTrue(result.relays.values.all { it.status == RelayPublicationStatus.UNCONFIRMED })
        assertFalse(result.inFlight)
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `late acceptance after timeout updates the persisted outcome`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        publisher.publish(note)
        transport.acknowledge(relayA, note, true)
        runCurrent()
        assertEquals(1, publisher.publications.value.getValue(note.id).acceptedCount)
        assertTrue(store.load().isEmpty())
        assertEquals(RelayPublicationStatus.ACCEPTED, store.loadReceipts().single().relays.getValue(relayA).status)
    }

    @Test
    fun `duplicate OKs count a relay once and rejection cannot erase acceptance`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        transport.sendAction = { url, event ->
            transport.acknowledge(url, event, url == relayA)
            transport.acknowledge(url, event, url == relayA, "duplicate: already have this event")
            if (url == relayA) transport.acknowledge(url, event, false)
            true
        }
        val result = publisher.publish(note)
        assertEquals(1, result.acceptedCount)
        assertEquals(1, result.rejectedCount)
        assertEquals(RelayPublicationStatus.ACCEPTED, result.relays.getValue(relayA).status)
    }

    @Test
    fun `unrelated event and relay OKs do not confirm publication`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        transport.sendAction = { _, event ->
            transport.acknowledge("wss://unrequested.example", event, true)
            transport.acknowledge(relayA, event.copy(id = "f".repeat(64)), true)
            true
        }
        assertEquals(0, publisher.publish(note).acceptedCount)
    }

    @Test
    fun `no configured relays still saves a recoverable note`() = runTest {
        val store = Store()
        val transport = Transport().apply { targets.clear() }
        val result = publisher(store, transport).publish(note)
        assertTrue(result.relays.isEmpty())
        assertFalse(result.inFlight)
        assertEquals(note, store.load().single().event)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `connection failures finish without claiming acceptance or scheduling retry`() = runTest {
        val store = Store()
        val transport = Transport().apply { sendAction = { _, _ -> false } }
        val publisher = publisher(store, transport)
        val result = publisher.publish(note)
        assertTrue(result.relays.values.all { it.status == RelayPublicationStatus.UNREACHABLE })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, transport.sent.size)
        assertEquals(0, result.acceptedCount)
    }

    @Test
    fun `initial storage failure prevents sending and local success callback`() = runTest {
        val store = Store().apply { failWrites = true }
        val transport = Transport()
        var inserted = false
        val publisher = publisher(store, transport, { inserted = true })
        try {
            publisher.publish(note)
            fail("Expected storage failure")
        } catch (_: IOException) {
            assertTrue(transport.sent.isEmpty())
            assertTrue(publisher.publications.value.isEmpty())
            assertFalse(inserted)
        }
    }

    @Test
    fun `restart restores interrupted note without broadcasting automatically`() = runTest {
        val store = Store()
        store.save(NotePublication(note, listOf("b".repeat(64)), mapOf(relayA to RelayPublication(RelayPublicationStatus.PENDING))))
        val transport = Transport()
        val restored = mutableListOf<NostrEvent>()
        val publisher = publisher(store, transport, restored::add)
        runCurrent()
        val recovered = publisher.publications.value.getValue(note.id)
        assertEquals(note, recovered.event)
        assertFalse(recovered.inFlight)
        assertEquals(RelayPublicationStatus.UNCONFIRMED, recovered.relays.getValue(relayA).status)
        assertEquals(listOf(note), restored)
        assertEquals(recovered, store.load().single())
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `manual rebroadcast sends the exact saved event to current targets`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        publisher.publish(note, listOf("b".repeat(64)))
        transport.targets = linkedSetOf("wss://new.example")
        transport.sendAction = { url, event -> transport.acknowledge(url, event, true); true }
        publisher.rebroadcast(note)
        runCurrent()
        val result = publisher.publications.value.getValue(note.id)
        assertEquals(2, result.attempt)
        assertEquals(1, result.acceptedCount)
        assertEquals("wss://new.example", transport.sent.last().first)
        assertTrue(transport.sent.all { it.second.toJson() == note.toJson() })
        assertEquals(listOf("b".repeat(64)), transport.recipients)
        assertTrue(store.load().isEmpty())
        assertEquals(1, store.loadReceipts().size)
    }

    @Test
    fun `rebroadcast does not erase previously confirmed relays`() = runTest {
        val store = Store()
        val transport = Transport().apply { targets = linkedSetOf(relayA) }
        val publisher = publisher(store, transport)
        transport.sendAction = { url, event -> transport.acknowledge(url, event, true); true }
        publisher.publish(note)
        transport.targets = linkedSetOf(relayB)
        transport.sendAction = { _, _ -> true }
        val result = publisher.publish(note)
        assertEquals(1, result.acceptedCount)
        assertEquals(RelayPublicationStatus.ACCEPTED, result.relays.getValue(relayA).status)
        assertEquals(RelayPublicationStatus.UNCONFIRMED, result.relays.getValue(relayB).status)
    }

    @Test
    fun `concurrent attempts for one event do not duplicate sends`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val first = async { publisher.publish(note) }
        runCurrent()
        val second = async { publisher.publish(note) }
        runCurrent()
        assertTrue(second.await().inFlight)
        assertEquals(2, transport.sent.size)
        first.await()
        assertEquals(1, store.load().single().attempt)
    }

    @Test
    fun `concurrent different notes keep independent outcomes`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val secondNote = note.copy(id = "2".repeat(64), content = "Another note")
        transport.sendAction = { url, event -> transport.acknowledge(url, event, event.id == note.id); true }
        val first = async { publisher.publish(note) }
        val second = async { publisher.publish(secondNote) }
        assertEquals(2, first.await().acceptedCount)
        assertEquals(2, second.await().rejectedCount)
        assertEquals(1, store.load().size)
        assertEquals(1, store.loadReceipts().size)
    }

    @Test
    fun `other accounts private events unsigned rumors and future posts are excluded`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val excluded = listOf(note.copy(pubkey = "d".repeat(64)), note.copy(sig = ""),
            note.copy(kind = 1059), note.copy(kind = 14), note.copy(kind = 4),
            note.copy(created_at = System.currentTimeMillis() / 1000 + 3600))
        for (event in excluded) {
            assertFalse(publisher.canPublish(event))
            try {
                publisher.publish(event)
                fail("Expected excluded event to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(store.load().isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `recovery excludes other accounts and deleted notes`() = runTest {
        val store = Store()
        val other = note.copy(id = "2".repeat(64), pubkey = "d".repeat(64))
        store.save(NotePublication(note, emptyList(), emptyMap()))
        store.save(NotePublication(other, emptyList(), emptyMap()))
        val transport = Transport()
        val restored = mutableListOf<NostrEvent>()
        val publisher = publisher(store, transport, restored::add, deleted = { it.id == note.id })
        runCurrent()
        assertTrue(publisher.publications.value.isEmpty())
        assertTrue(restored.isEmpty())
        assertFalse(publisher.canPublish(note))
    }

    @Test
    fun `failed status writes keep the original event and expose storage failure`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        transport.sendAction = { url, event ->
            store.failWrites = true
            transport.acknowledge(url, event, true)
            true
        }
        val result = publisher.publish(note)
        assertEquals(2, result.acceptedCount)
        assertTrue(result.storageError)
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `cancellation keeps a recoverable signed note`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val job = async { publisher.publish(note) }
        runCurrent()
        job.cancel()
        job.join()
        val saved = store.load().single()
        assertEquals(note, saved.event)
        assertFalse(saved.inFlight)
        assertEquals(2, saved.unconfirmedCount)
    }

    @Test
    fun `confirmed publications survive restart without sending`() = runTest {
        val store = Store()
        store.save(NotePublication(note, emptyList(), mapOf(relayA to RelayPublication(RelayPublicationStatus.ACCEPTED)), inFlight = false))
        val transport = Transport()
        val publisher = publisher(store, transport)
        runCurrent()
        assertTrue(publisher.publications.value.isEmpty())
        assertEquals(1, publisher.publicationFor(note)?.acceptedCount)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `failed recovery status write still exposes the saved note`() = runTest {
        val store = Store()
        store.save(NotePublication(note, emptyList(), mapOf(relayA to RelayPublication(RelayPublicationStatus.PENDING))))
        store.failWrites = true
        val restored = mutableListOf<NostrEvent>()
        val transport = Transport()
        val publisher = publisher(store, transport, restored::add)
        runCurrent()
        val result = publisher.publications.value.getValue(note.id)
        assertFalse(result.inFlight)
        assertTrue(result.storageError)
        assertEquals(listOf(note), restored)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a changed event cannot replace the original under the same ID`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        publisher.publish(note)
        val sentCount = transport.sent.size
        try {
            publisher.publish(note.copy(sig = "d".repeat(128)))
            fail("Expected modified event to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(note, store.load().single().event)
            assertEquals(sentCount, transport.sent.size)
        }
    }

    @Test
    fun `a note observed on a relay never enters durable recovery storage`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport, known = { setOf(relayA) })
        transport.sendAction = { _, _ ->
            assertTrue(store.load().isEmpty())
            true
        }
        val result = publisher.publish(note)
        assertEquals(1, result.acceptedCount)
        assertTrue(store.load().isEmpty())
        assertEquals(1, store.loadReceipts().size)
    }

    @Test
    fun `first acceptance removes the recovery payload while other relays are still pending`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val job = async { publisher.publish(note) }
        runCurrent()
        assertEquals(note, store.load().single().event)
        transport.acknowledge(relayA, note, true)
        runCurrent()
        assertTrue(publisher.publications.value.getValue(note.id).inFlight)
        assertTrue(store.load().isEmpty())
        assertEquals(1, store.loadReceipts().size)
        job.await()
    }

    @Test
    fun `receiving the same signed event removes an unconfirmed recovery payload`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        publisher.publish(note)
        assertEquals(1, store.load().size)
        transport.relayCopies.emit(note to relayA)
        runCurrent()
        assertTrue(store.load().isEmpty())
        assertEquals(1, publisher.publications.value.getValue(note.id).acceptedCount)
        assertEquals(1, store.loadReceipts().size)
    }

    @Test
    fun `an incoming event with the same ID but different signature does not prove delivery`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        publisher.publish(note)
        transport.relayCopies.emit(note.copy(sig = "d".repeat(128)) to relayA)
        runCurrent()
        assertEquals(1, store.load().size)
        assertEquals(0, publisher.publications.value.getValue(note.id).acceptedCount)
    }

    @Test
    fun `confirmed in-memory publications are bounded alongside receipt history`() = runTest {
        val store = Store()
        val transport = Transport().apply { targets = linkedSetOf(relayA) }
        val publisher = publisher(store, transport)
        transport.sendAction = { url, event -> transport.acknowledge(url, event, true); true }
        for (index in 1..PUBLICATION_RECEIPT_LIMIT + 2) {
            publisher.publish(note.copy(id = index.toString(16).padStart(64, '0')))
        }
        assertEquals(PUBLICATION_RECEIPT_LIMIT, publisher.publications.value.size)
        assertEquals(PUBLICATION_RECEIPT_LIMIT, publisher.receipts.value.size)
        assertTrue(store.load().isEmpty())
    }
    @Test
    fun `login without restarting creates an account publisher`() = runTest {
        val store = Store()
        val transport = Transport().apply { targets.clear() }
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, store, transport, backgroundScope, {},
                ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(null)
        assertNull(accounts.publisher.value)
        accounts.switchAccount(pubkey)
        val publisher = requireNotNull(accounts.publisher.value)
        assertTrue(publisher.canPublish(note))
        publisher.submit(note)
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `switch A B A isolates payloads receipts and collectors without automatic sends`() = runTest {
        val otherKey = "b".repeat(64)
        val otherNote = note.copy(id = "2".repeat(64), pubkey = otherKey)
        val stores = mapOf(pubkey to Store(), otherKey to Store())
        val transport = Transport().apply { targets.clear() }
        val cached = mutableListOf<NostrEvent>()
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, stores.getValue(account), transport, backgroundScope, cached::add,
                ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(pubkey)
        val first = requireNotNull(accounts.publisher.value)
        first.submit(note)
        runCurrent()
        accounts.switchAccount(otherKey)
        val second = requireNotNull(accounts.publisher.value)
        assertFalse(first.canPublish(note))
        assertFalse(second.canPublish(note))
        second.submit(otherNote)
        runCurrent()
        transport.acknowledge(relayA, note, true)
        transport.relayCopies.emit(note to relayA)
        runCurrent()
        assertTrue(first.receipts.value.isEmpty())
        assertTrue(second.receipts.value.isEmpty())
        assertEquals(listOf(otherNote), second.publications.value.values.map { it.event })
        accounts.switchAccount(pubkey)
        val restored = requireNotNull(accounts.publisher.value)
        runCurrent()
        assertNotSame(first, restored)
        assertEquals(listOf(note), restored.publications.value.values.map { it.event })
        assertEquals(note, stores.getValue(pubkey).load().single().event)
        assertEquals(otherNote, stores.getValue(otherKey).load().single().event)
        assertTrue(transport.sent.isEmpty())
        assertEquals(listOf(note, otherNote, note), cached)
    }

    @Test
    fun `switch during delivery cancels old sends and keeps its durable payload`() = runTest {
        val stores = mutableMapOf<String, Store>()
        val transport = Transport()
        var cancelled = 0
        transport.sendAction = { _, _ ->
            try { awaitCancellation() } finally { cancelled++ }
        }
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, stores.getOrPut(account) { Store() }, transport, backgroundScope, {},
                ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(pubkey)
        val old = requireNotNull(accounts.publisher.value)
        old.submit(note)
        runCurrent()
        assertEquals(2, transport.sent.size)
        accounts.switchAccount("b".repeat(64))
        runCurrent()
        assertEquals(2, cancelled)
        assertEquals(note, stores.getValue(pubkey).load().single().event)
        assertFalse(stores.getValue(pubkey).load().single().inFlight)
        assertTrue(requireNotNull(accounts.publisher.value).publications.value.isEmpty())
        transport.acknowledge(relayA, note, true)
        runCurrent()
        assertTrue(old.receipts.value.isEmpty())
        assertEquals(1, stores.getValue(pubkey).load().size)
    }

    @Test
    fun `account switch during restoration never inserts the old account into the cache`() = runTest {
        val cached = mutableListOf<NostrEvent>()
        val store = Store().apply { save(NotePublication(note, emptyList(), emptyMap())) }
        lateinit var accounts: NotePublicationAccounts
        var switched = false
        val switchingStore = object : NotePublicationStore by store {
            override fun load(): List<NotePublication> {
                val loaded = store.load()
                if (!switched) {
                    switched = true
                    accounts.switchAccount("b".repeat(64))
                }
                return loaded
            }
        }
        accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, if (account == pubkey) switchingStore else Store(), Transport(),
                backgroundScope, cached::add, ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(pubkey)
        runCurrent()
        assertEquals("b".repeat(64), accounts.publisher.value?.accountPubkey)
        assertTrue(cached.isEmpty())
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `switch during a completed durable write retains payload but does not send or cache it`() = runTest {
        val store = Store()
        val transport = Transport()
        val cached = mutableListOf<NostrEvent>()
        lateinit var accounts: NotePublicationAccounts
        val switchingStore = object : NotePublicationStore by store {
            override fun save(publication: NotePublication) {
                store.save(publication)
                accounts.switchAccount("b".repeat(64))
            }
        }
        accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, if (account == pubkey) switchingStore else Store(), transport,
                backgroundScope, cached::add, ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(pubkey)
        val publishing = async { requireNotNull(accounts.publisher.value).submit(note) }
        runCurrent()
        assertTrue(publishing.isCancelled)
        assertTrue(cached.isEmpty())
        assertTrue(transport.sent.isEmpty())
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `returning to A waits for all predecessor writes before reopening its store`() = runTest {
        val store = Store()
        val transport = Transport()
        val release = CompletableDeferred<Unit>()
        transport.sendAction = { _, _ ->
            try { awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            }
        }
        var loads = 0
        val countedStore = object : NotePublicationStore by store {
            override fun load(): List<NotePublication> { loads++; return store.load() }
        }
        val accounts = NotePublicationAccounts { account, wait ->
            NotePublisher(account, if (account == pubkey) countedStore else Store(), transport,
                backgroundScope, {}, ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = wait)
        }
        accounts.switchAccount(pubkey)
        requireNotNull(accounts.publisher.value).submit(note)
        runCurrent()
        accounts.switchAccount("b".repeat(64))
        accounts.switchAccount(pubkey)
        runCurrent()
        assertEquals(1, loads)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, loads)
        assertFalse(requireNotNull(accounts.publisher.value).publications.value.getValue(note.id).inFlight)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun `account close waits for cleanup of a publication owned by an external caller`() = runTest {
        val store = Store()
        val transport = Transport()
        val release = CompletableDeferred<Unit>()
        transport.sendAction = { _, _ ->
            try { awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            }
        }
        val publisher = publisher(store, transport)
        val publishing = async { publisher.publish(note) }
        runCurrent()
        val stopped = publisher.close()
        runCurrent()
        assertFalse("Account storage cannot be reopened before caller-owned cleanup", stopped.isCompleted)
        assertFalse(publishing.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(stopped.isCompleted)
        assertTrue(publishing.isCancelled)
        assertEquals(note, store.load().single().event)
        assertFalse(store.load().single().inFlight)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun `real provenance with a forged copy cannot discard recovery on retry`() = runTest {
        val store = Store()
        val transport = Transport()
        // This is the production provenance shared by EventRepository and RelayPool, not a URL set.
        val provenance = RelayEventProvenance()
        val publisher = publisher(store, transport, known = provenance::verifiedRelays)
        publisher.publish(note)
        val forged = note.copy(content = "Forged", sig = "d".repeat(128))
        provenance.add(forged.id, relayA) // Optimistic EventRouter ingestion, before verification.
        transport.relayCopies.emit(forged to relayA)
        assertFalse(provenance.verifyAndRecord(forged, relayA))
        runCurrent()
        assertEquals(setOf(relayA), provenance.seen(note.id))
        transport.sendAction = { _, _ -> false }
        val retried = publisher.publish(note)
        assertEquals(0, retried.acceptedCount)
        assertEquals(note, store.load().single().event)
        assertTrue(store.loadReceipts().isEmpty())
    }

    @Test
    fun `verified provenance matching the whole event prevents a new recovery payload`() = runTest {
        val store = Store()
        val transport = Transport()
        val provenance = RelayEventProvenance()
        // Substitute only native verification for this signed fixture; keep real provenance matching.
        assertTrue(provenance.verifyAndRecord(note, relayA) { it == note })
        val publisher = publisher(store, transport, known = provenance::verifiedRelays)
        transport.sendAction = { _, _ ->
            assertTrue(store.load().isEmpty())
            false
        }
        val result = publisher.publish(note)
        assertEquals(1, result.acceptedCount)
        assertTrue(store.load().isEmpty())
        assertEquals(1, store.loadReceipts().size)
    }

    @Test
    fun `verified evidence for another signature with the same id is insufficient`() = runTest {
        val store = Store()
        val transport = Transport().apply { sendAction = { _, _ -> false } }
        val provenance = RelayEventProvenance()
        val other = note.copy(sig = "d".repeat(128))
        provenance.verifyAndRecord(other, relayA) { it == other }
        val result = publisher(store, transport, known = provenance::verifiedRelays).publish(note)
        assertEquals(0, result.acceptedCount)
        assertEquals(note, store.load().single().event)
    }

    @Test
    fun `editor completion waits for durable save but not for relay delivery`() = runTest {
        val store = Store()
        val transport = Transport()
        val publisher = publisher(store, transport)
        val editor = ComposerSession()
        val token = editor.begin()
        var draft = "first draft"
        var popped = 0
        publisher.submit(note)
        assertEquals(note, store.load().single().event)
        assertTrue(publisher.publications.value.getValue(note.id).inFlight)
        editor.complete(token) { draft = ""; popped++ }
        assertEquals("", draft)
        assertEquals(1, popped)
        editor.end(token)
        editor.begin()
        draft = "new draft"
        runCurrent()
        transport.acknowledge(relayA, note, true)
        transport.acknowledge(relayB, note, true)
        runCurrent()
        assertEquals("new draft", draft)
        assertEquals(1, popped)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `leaving while persistence waits cannot pop a profile or clear a new editor`() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val transport = Transport().apply { targets.clear() }
        val publisher = NotePublisher(pubkey, store, transport, backgroundScope, {},
            ioDispatcher = StandardTestDispatcher(testScheduler), beforeRestore = { release.await() })
        val editor = ComposerSession()
        val token = editor.begin()
        var screen = "compose"
        var draft = "old draft"
        val pending = async {
            publisher.submit(note)
            editor.complete(token) { screen = "feed"; draft = "" }
        }
        runCurrent()
        editor.end(token)
        screen = "profile"
        editor.begin()
        draft = "new draft"
        release.complete(Unit)
        pending.await()
        assertEquals("profile", screen)
        assertEquals("new draft", draft)
        assertEquals(note, store.load().single().event)
    }

}
