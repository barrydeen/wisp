package com.wisp.app.db

import android.util.Log
import com.wisp.app.nostr.NostrEvent
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class EventPersistence(
    private val currentUserPubkey: String?
) {
    private val box: Box<EventEntity> = WispObjectBox.store.boxFor(EventEntity::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel = Channel<NostrEvent>(Channel.BUFFERED)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Batched write-behind loop: collects events over a 200ms window then bulk-writes
        scope.launch {
            val batch = mutableListOf<NostrEvent>()
            for (event in writeChannel) {
                batch.add(event)
                // Drain any queued events without waiting
                while (true) {
                    val next = writeChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                // Short settle window to collect more concurrent inserts
                if (batch.size < 50) {
                    delay(200)
                    while (true) {
                        val next = writeChannel.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }
                }
                try {
                    val entities = batch.map { it.toEntity() }
                    box.put(entities)
                } catch (e: Exception) {
                    Log.w("EventPersistence", "Batch write failed: ${e.message}")
                }
                batch.clear()
            }
        }
    }

    fun shouldPersist(event: NostrEvent): Boolean {
        // Always persist user's own events
        if (event.pubkey == currentUserPubkey) return true
        // Persist notes (kind 1), profiles (kind 0), reactions (kind 7), zap receipts (kind 9735)
        return event.kind in PERSISTED_KINDS
    }

    fun persistEvent(event: NostrEvent) {
        if (!shouldPersist(event)) return
        writeChannel.trySend(event)
    }

    fun seedCache(limit: Int = 2000): List<NostrEvent> {
        return try {
            val entities = box.query()
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "seedCache failed: ${e.message}")
            emptyList()
        }
    }

    fun searchProfiles(query: String, limit: Int = 500): List<NostrEvent> {
        if (query.isBlank()) return emptyList()
        return try {
            val entities = box.query(
                EventEntity_.kind.equal(0)
                    .and(EventEntity_.content.contains(query, StringOrder.CASE_INSENSITIVE))
            )
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "searchProfiles failed: ${e.message}")
            emptyList()
        }
    }

    fun searchNotes(query: String, limit: Int = 50): List<NostrEvent> {
        if (query.isBlank()) return emptyList()
        return try {
            val entities = box.query(
                EventEntity_.kind.equal(1)
                    .and(EventEntity_.content.contains(query, StringOrder.CASE_INSENSITIVE))
            )
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "searchNotes failed: ${e.message}")
            emptyList()
        }
    }

    fun hasEvent(eventId: String): Boolean {
        return try {
            box.query(EventEntity_.eventId.equal(eventId))
                .build()
                .use { it.count() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    fun getEvent(eventId: String): NostrEvent? {
        return try {
            box.query(EventEntity_.eventId.equal(eventId))
                .build()
                .use { it.findFirst() }
                ?.toNostrEvent()
        } catch (e: Exception) {
            null
        }
    }

    fun getEventsByAuthorAndKind(pubkey: String, kind: Int, limit: Int = 100): List<NostrEvent> {
        return try {
            val entities = box.query(
                EventEntity_.pubkey.equal(pubkey)
                    .and(EventEntity_.kind.equal(kind))
            )
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "getEventsByAuthorAndKind failed: ${e.message}")
            emptyList()
        }
    }

    /** Query all zap receipt events (kind 9735) from ObjectBox. */
    fun getZapReceipts(limit: Int = 500): List<NostrEvent> {
        return try {
            val entities = box.query(EventEntity_.kind.equal(9735))
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "getZapReceipts failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Prune old events to keep the database size bounded.
     * Never prunes the current user's own events.
     */
    fun prune(maxEvents: Long = 50_000, maxAgeDays: Int = 90) {
        try {
            val count = box.count()
            if (count <= maxEvents) return

            val cutoff = System.currentTimeMillis() / 1000 - maxAgeDays * 86400L
            val query = if (currentUserPubkey != null) {
                box.query(
                    EventEntity_.createdAt.less(cutoff)
                        .and(EventEntity_.pubkey.notEqual(currentUserPubkey))
                ).build()
            } else {
                box.query(EventEntity_.createdAt.less(cutoff)).build()
            }
            val removed = query.use { it.remove() }
            Log.d("EventPersistence", "Pruned $removed old events (total was $count)")
        } catch (e: Exception) {
            Log.w("EventPersistence", "prune failed: ${e.message}")
        }
    }

    private fun NostrEvent.toEntity(): EventEntity {
        val tagsJson = json.encodeToString(tags)
        return EventEntity(
            eventId = id,
            pubkey = pubkey,
            createdAt = created_at,
            kind = kind,
            content = content,
            tags = tagsJson,
            sig = sig
        )
    }

    private fun EventEntity.toNostrEvent(): NostrEvent? {
        return try {
            val parsedTags: List<List<String>> = json.decodeFromString(tags)
            NostrEvent(
                id = eventId,
                pubkey = pubkey,
                created_at = createdAt,
                kind = kind,
                content = content,
                tags = parsedTags,
                sig = sig
            )
        } catch (e: Exception) {
            Log.w("EventPersistence", "Failed to deserialize event $eventId: ${e.message}")
            null
        }
    }

    companion object {
        private val PERSISTED_KINDS = setOf(0, 1, 6, 7, 9735)
    }
}
