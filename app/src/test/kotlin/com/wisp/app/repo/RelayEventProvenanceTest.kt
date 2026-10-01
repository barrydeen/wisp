package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import org.junit.Assert.*
import org.junit.Test

class RelayEventProvenanceTest {
    private val event = NostrEvent("1".repeat(64), "a".repeat(64), 1, 1, emptyList(), "note", "c".repeat(128))
    private val relay = "wss://relay.example"

    @Test fun `seen on never means verified`() {
        val source = RelayEventProvenance()
        source.add(event.id, relay)
        assertEquals(setOf(relay), source.seen(event.id))
        assertTrue(source.verifiedRelays(event).isEmpty())
    }

    @Test fun `remove clears both forms of provenance`() {
        val source = RelayEventProvenance()
        source.add(event.id, relay)
        source.verifyAndRecord(event, relay) { true }
        source.remove(event.id)
        assertTrue(source.seen(event.id).isEmpty())
        assertTrue(source.verifiedRelays(event).isEmpty())
    }

    @Test fun `account reset prevents delayed verification from restoring old provenance`() {
        val source = RelayEventProvenance()
        assertFalse(source.verifyAndRecord(event, relay) { source.clear(); true })
        assertTrue(source.seen(event.id).isEmpty())
        assertTrue(source.verifiedRelays(event).isEmpty())
    }

    @Test fun `all signed fields must match the verified copy`() {
        val source = RelayEventProvenance()
        source.verifyAndRecord(event, relay) { true }
        val changed = listOf(event.copy(content = "forged"), event.copy(sig = "d".repeat(128)),
            event.copy(tags = listOf(listOf("p", "b"))), event.copy(created_at = 2),
            event.copy(pubkey = "b".repeat(64)), event.copy(kind = 20))
        changed.forEach { assertTrue(source.verifiedRelays(it).isEmpty()) }
        assertEquals(setOf(relay), source.verifiedRelays(event))
    }

    @Test fun `provenance is bounded including verified fingerprints`() {
        val source = RelayEventProvenance(limit = 2)
        source.verifyAndRecord(event, relay) { true }
        source.add("2".repeat(64), relay)
        source.add("3".repeat(64), relay)
        assertTrue(source.seen(event.id).isEmpty())
        assertTrue(source.verifiedRelays(event).isEmpty())
    }

    @Test fun `each relay copy is verified including duplicates`() {
        val source = RelayEventProvenance()
        source.verifyAndRecord(event, relay) { true }
        assertFalse(source.verifyAndRecord(event.copy(content = "forged"), "wss://bad.example"))
        source.verifyAndRecord(event, "wss://other.example") { true }
        assertEquals(setOf(relay, "wss://other.example"), source.verifiedRelays(event))
    }
}
