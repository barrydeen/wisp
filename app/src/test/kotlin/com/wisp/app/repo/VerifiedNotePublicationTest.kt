package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.*
import com.wisp.app.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real relay admission, EventRepository provenance and native Schnorr verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VerifiedNotePublicationTest {
    private class Store : NotePublicationStore {
        val pending = mutableMapOf<String, NotePublication>()
        val receipts = mutableMapOf<String, NotePublicationReceipt>()
        override fun load() = pending.values.toList()
        override fun loadReceipts() = receipts.values.toList()
        override fun removePayload(eventId: String) { pending.remove(eventId) }
        override fun save(publication: NotePublication) {
            if (publication.acceptedCount > 0) {
                pending.remove(publication.event.id)
                receipts[publication.event.id] = publication.receipt()
            } else pending[publication.event.id] = publication
        }
    }

    private suspend fun exercise(observed: Boolean) = coroutineScope {
        val key = Keys.fromPrivkey(ByteArray(32) { 1 })
        val original = LocalSigner(key.privkey, key.pubkey).signEvent(1, "Recoverable original", emptyList(), 1)
        assertTrue(original.verifySignature())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = RelayPool()
        val repo = EventRepository()
        val relay = Relay(RelayConfig("wss://verified.example"), OkHttpClient())
        val url = relay.config.url
        val store = Store()
        val transport = object : NotePublicationTransport {
            override val results = pool.publishResults
            override val relayCopies = pool.relayEvents.map { it.event to it.relayUrl }
            override fun targetRelays(inboxPubkeys: Collection<String>) = setOf(url)
            override suspend fun send(relayUrl: String, event: NostrEvent) = false
        }
        val publisher = NotePublisher(original.pubkey, store, transport, scope, repo::addEvent,
            verifiedRelayUrls = { repo.getEventRelays(it.id) })
        val ingestion = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pool.relayEvents.collect { repo.addEventRelay(it.event.id, it.relayUrl) }
        }
        RelayPool::class.java.getDeclaredMethod("collectMessages", Relay::class.java)
            .apply { isAccessible = true }.invoke(pool, relay)
        @Suppress("UNCHECKED_CAST")
        val messages = Relay::class.java.getDeclaredField("_messages").apply { isAccessible = true }
            .get(relay) as MutableSharedFlow<RelayMessage>
        suspend fun receive(event: NostrEvent) {
            withTimeout(5_000) { messages.subscriptionCount.first { it > 0 } }
            val eose = async(start = CoroutineStart.UNDISPATCHED) { pool.eoseSignals.first { it == "userposts" } }
            messages.emit(RelayMessage.EventMsg("userposts", event))
            messages.emit(RelayMessage.Eose("userposts"))
            withTimeout(5_000) { eose.await() }
        }
        try {
            if (observed) {
                receive(original)
                withTimeout(5_000) { while (repo.getEventRelays(original.id).isEmpty()) delay(10) }
                val result = publisher.publish(original)
                assertEquals(1, result.acceptedCount)
                assertTrue(store.load().isEmpty())
                repo.removeEvent(original.id)
                assertTrue(repo.getEventRelays(original.id).isEmpty())
            } else {
                publisher.publish(original)
                receive(original.copy(content = "Forged contents"))
                receive(original.copy(sig = "f".repeat(128)))
                assertTrue(repo.getEventRelays(original.id).isEmpty())
                val retry = publisher.publish(original)
                assertEquals(0, retry.acceptedCount)
                assertEquals(original, store.load().single().event)
                assertTrue(store.loadReceipts().isEmpty())
            }
        } finally {
            publisher.close().join()
            ingestion.cancelAndJoin()
            scope.cancel()
            pool.disconnectAll()
        }
    }

    @Test fun `forged copied IDs cannot turn repository provenance into acceptance`() = runBlocking { exercise(false) }
    @Test fun `genuine repository provenance avoids recovery storage and deletion clears it`() = runBlocking { exercise(true) }

    @Test fun `another authors deletion cannot remove a recovery payload missing from the event cache`() = runBlocking {
        val author = Keys.fromPrivkey(ByteArray(32) { 1 })
        val other = Keys.fromPrivkey(ByteArray(32) { 2 })
        val event = LocalSigner(author.privkey, author.pubkey).signEvent(1, "Recover this")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = EventRepository()
        val store = Store()
        val transport = object : NotePublicationTransport {
            override val results = emptyFlow<PublishResult>()
            override fun targetRelays(inboxPubkeys: Collection<String>) = emptySet<String>()
            override suspend fun send(relayUrl: String, event: NostrEvent) = false
        }
        val publisher = NotePublisher(event.pubkey, store, transport, scope, repo::addEvent)
        val observedPublisher = org.mockito.Mockito.spy(publisher)
        repo.notePublisher = observedPublisher
        try {
            publisher.publish(event)
            @Suppress("UNCHECKED_CAST")
            val cache = EventRepository::class.java.getDeclaredField("eventCache").apply { isAccessible = true }
                .get(repo) as MutableMap<String, NostrEvent>
            cache.remove(event.id)
            val deletion = LocalSigner(other.privkey, other.pubkey).signEvent(5, "", listOf(listOf("e", event.id)))
            assertTrue(deletion.verifySignature())
            repo.addEvent(deletion)
            org.mockito.Mockito.verify(observedPublisher, org.mockito.Mockito.never()).forget(event.id)
            assertEquals(event, store.load().single().event)
            assertTrue(event.id in publisher.publications.value)
        } finally {
            publisher.close().join()
            scope.cancel()
        }
    }
}
