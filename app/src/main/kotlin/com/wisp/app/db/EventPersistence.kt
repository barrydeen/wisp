package com.wisp.app.db

import android.util.Log
import com.wisp.app.nostr.Nip22
import com.wisp.app.nostr.NostrEvent
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class EventPersistence(
    var currentUserPubkey: String?
) : AutoCloseable {
    private val box: Box<EventEntity> = WispObjectBox.store.boxFor(EventEntity::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = BatchWriter<NostrEvent>(
        onFailure = { Log.w("EventPersistence", "Batch write failed", it) }
    ) { batch ->
        val unique = batch.distinctBy { it.id }
        // Checking and inserting must share the write transaction, including across writer instances.
        WispObjectBox.store.runInTx {
            val existing = box.query(
                EventEntity_.eventId.oneOf(unique.map { it.id }.toTypedArray(), StringOrder.CASE_SENSITIVE)
            ).build().use { query ->
                query.property(EventEntity_.eventId).findStrings().toHashSet()
            }
            val novel = unique.filterNot { it.id in existing }.map { it.toEntity() }
            if (novel.isNotEmpty()) box.put(novel)
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
        writer.enqueue(event)
    }

    suspend fun flush() = writer.flush()

    /** Stops accepting events and drains the queue asynchronously; does not close the shared DB. */
    override fun close() = writer.close()

    suspend fun shutdown() = writer.shutdown()

    /**
     * Newest events for feed seeding on startup / account switch. When [authors] is
     * provided, posts are restricted to those authors. Profiles have a separate bounded
     * budget and are restricted to seed authors, so metadata cannot crowd out display events.
     * The result may contain up to [limit] display events plus 2000 profiles.
     */
    fun seedCache(limit: Int = 2000, authors: Set<String>? = null): List<NostrEvent> {
        if (limit <= 0 || authors?.isEmpty() == true) return emptyList()
        return try {
            val displayKinds = EventEntity_.kind.oneOf(DISPLAY_KINDS)
            val cond = if (authors == null) displayKinds else displayKinds.and(
                EventEntity_.pubkey.oneOf(authors.toTypedArray(), StringOrder.CASE_SENSITIVE)
            )
            val entities = box.query(cond)
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            val profileAuthors = entities.mapTo(linkedSetOf()) { it.pubkey }
            authors?.let { profileAuthors.addAll(it) }
            val profiles = if (profileAuthors.isEmpty()) emptyList() else box.query(
                EventEntity_.kind.equal(0).and(
                    EventEntity_.pubkey.oneOf(profileAuthors.toTypedArray(), StringOrder.CASE_SENSITIVE)
                )
            ).order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build().use { it.find(0, 2000) }
            (entities + profiles).mapNotNull { it.toNostrEvent() }
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

    /** Query recent notification-relevant events (kinds 1, 6, 7, 1111, 9735) for seeding NotificationRepository. */
    fun getRecentNotificationEvents(limit: Int = 500): List<NostrEvent> {
        return try {
            val entities = box.query(
                EventEntity_.kind.oneOf(intArrayOf(1, 6, 7, Nip22.KIND_COMMENT, 9735))
            )
                .order(EventEntity_.createdAt, io.objectbox.query.QueryBuilder.DESCENDING)
                .build()
                .use { it.find(0, limit.toLong()) }
            entities.mapNotNull { it.toNostrEvent() }
        } catch (e: Exception) {
            Log.w("EventPersistence", "getRecentNotificationEvents failed: ${e.message}")
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
        private val DISPLAY_KINDS = intArrayOf(1, 6, 20, 21, 22, 1068, 6969, Nip22.KIND_COMMENT, 30023, 36787)
        // 1111 = NIP-22 comments — persisted so thread replies and notifications survive restarts
        private val PERSISTED_KINDS = setOf(0, 1, 6, 7, 9735, 20, 21, 22, 1068, 6969, Nip22.KIND_COMMENT, 30023, 36787)
    }
}
