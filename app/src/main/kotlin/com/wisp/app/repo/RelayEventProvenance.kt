package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import java.security.MessageDigest

/** Seen-on metadata is untrusted. Only verified, exact signed copies prove delivery. */
class RelayEventProvenance(private val limit: Int = 15_000) {
    private data class Entry(
        val seen: MutableSet<String> = linkedSetOf(),
        val verified: MutableMap<String, String> = linkedMapOf()
    )
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var generation = 0L

    @Synchronized fun add(eventId: String, relayUrl: String): Boolean = entry(eventId).seen.add(relayUrl)
    @Synchronized fun seen(eventId: String): Set<String> = entries[eventId]?.seen?.toSet().orEmpty()

    /** Uses the existing event ID and Schnorr verification, independently of optimistic ingestion. */
    fun verifyAndRecord(event: NostrEvent, relayUrl: String, verify: (NostrEvent) -> Boolean = NostrEvent::verifySignature): Boolean {
        val version = synchronized(this) { generation }
        if (!verify(event)) return false
        val fingerprint = fingerprint(event)
        return synchronized(this) {
            if (generation != version) return@synchronized false
            entry(event.id).apply {
                verified[relayUrl] = fingerprint
            }
            true
        }
    }

    @Synchronized fun verifiedRelays(event: NostrEvent): Set<String> {
        val fingerprint = fingerprint(event)
        return entries[event.id]?.verified?.filterValues { it == fingerprint }?.keys?.toSet().orEmpty()
    }

    @Synchronized fun remove(eventId: String) { entries.remove(eventId) }
    @Synchronized fun clear() { generation++; entries.clear() }

    private fun entry(id: String): Entry {
        val result = entries.getOrPut(id) { Entry() }
        while (entries.size > limit) entries.remove(entries.keys.first())
        return result
    }

    // Retain a bounded fingerprint of all signed fields, without another full payload cache.
    private fun fingerprint(event: NostrEvent): String = MessageDigest.getInstance("SHA-256")
        .digest(event.toJson().toByteArray(Charsets.UTF_8)).toHex()
}
