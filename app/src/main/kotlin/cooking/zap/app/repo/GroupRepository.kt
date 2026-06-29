package cooking.zap.app.repo

import android.content.Context
import cooking.zap.app.db.GroupPersistence
import cooking.zap.app.db.WispObjectBox
import cooking.zap.app.nostr.Nip29
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class GroupMessage(
    val id: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val replyToId: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),  // emoji → list of reactor pubkeys
    val emojiTags: Map<String, String> = emptyMap()  // shortcode → URL from event's emoji tags
)

data class GroupPreview(
    val metadata: Nip29.GroupMetadata?,
    val members: List<String>
)

data class GroupRoom(
    val groupId: String,
    val relayUrl: String,
    val metadata: Nip29.GroupMetadata?,
    val messages: List<GroupMessage>,
    val lastMessageAt: Long,
    val admins: List<String> = emptyList(),
    val members: List<String> = emptyList(),
    val reactionEmojiUrls: Map<String, String> = emptyMap()  // shortcode → URL for custom emoji reactions
)

class GroupRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs = context.getSharedPreferences("group_rooms_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)
    private var ownerPubkey: String? = pubkeyHex
    private val persistence: GroupPersistence? = if (WispObjectBox.isInitialized) GroupPersistence() else null
    private val rooms = ConcurrentHashMap<String, GroupRoom>()
    private val seenMessages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Guards every compound read-modify-write of [rooms] (messages, reactions,
     * metadata, admins, members, add/remove, clear). The relay collector
     * (Dispatchers.Default) and optimistic local sends (also Default / Main) both
     * mutate `rooms[key]`, so a bare ConcurrentHashMap get-then-put can lose updates
     * (last-writer-wins) or corrupt ordering. All mutators must hold this lock so they
     * can't race each other; reads stay lock-free on the ConcurrentHashMap.
     * Critical sections are short (a list copy + trim at most).
     */
    private val roomsLock = Any()

    private val _joinedGroups = MutableStateFlow<List<GroupRoom>>(emptyList())
    val joinedGroups: StateFlow<List<GroupRoom>> = _joinedGroups

    /** Set of "relayUrl|groupId" keys for groups with notifications enabled. */
    private val _notifiedGroups = MutableStateFlow<Set<String>>(emptySet())
    val notifiedGroups: StateFlow<Set<String>> = _notifiedGroups

    /** Map of "relayUrl|groupId" → last-seen message ID. */
    private val lastSeenMessageIds = ConcurrentHashMap<String, String>()

    /** Set of "relayUrl|groupId" keys that have unread messages. */
    private val _unreadGroups = MutableStateFlow<Set<String>>(emptySet())
    val unreadGroups: StateFlow<Set<String>> = _unreadGroups

    init {
        loadPersistedGroups()
        loadNotificationState()
    }

    private fun roomKey(relayUrl: String, groupId: String) = "$relayUrl|$groupId"

    private fun loadPersistedGroups() {
        val stored = prefs.getStringSet("rooms", emptySet()) ?: emptySet()

        // Load persisted metadata + messages from ObjectBox, keyed by "relayUrl|groupId"
        val storedRooms = if (persistence != null && ownerPubkey != null) {
            persistence.loadRooms(ownerPubkey!!).associateBy { roomKey(it.relayUrl, it.groupId) }
        } else emptyMap()

        for (entry in stored) {
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) {
                val relayUrl = parts[0]
                val groupId = parts[1]
                val key = roomKey(relayUrl, groupId)
                val persisted = storedRooms[key]

                val metadata = when {
                    persisted != null && (persisted.name != null || persisted.picture != null) ->
                        Nip29.GroupMetadata(groupId, persisted.name, persisted.picture,
                            persisted.about, persisted.isPrivate, persisted.isClosed,
                            persisted.isRestricted, persisted.isHidden)
                    else -> {
                        // Fall back to locally-stored name in SharedPreferences
                        val localName = prefs.getString("local_name_$key", null)
                        if (!localName.isNullOrEmpty()) Nip29.GroupMetadata(groupId, localName, null, null, false, false) else null
                    }
                }

                synchronized(roomsLock) {
                    // Seed dedup set from persisted messages so we don't re-add on relay replay
                    persisted?.messages?.forEach { seenMessages.add(it.id) }

                    rooms[key] = GroupRoom(
                        groupId = groupId,
                        relayUrl = relayUrl,
                        metadata = metadata,
                        messages = persisted?.messages ?: emptyList(),
                        lastMessageAt = persisted?.lastMessageAt ?: 0L,
                        admins = persisted?.admins ?: emptyList(),
                        members = persisted?.members ?: emptyList()
                    )
                }
            }
        }
        emit()
    }

    fun addGroup(relayUrl: String, groupId: String, localName: String? = null) {
        val key = roomKey(relayUrl, groupId)
        val metadata = if (!localName.isNullOrEmpty()) {
            Nip29.GroupMetadata(groupId, localName, null, null, false, false)
        } else null
        val room = GroupRoom(groupId, relayUrl, metadata, emptyList(), 0L)
        val currentRoom = synchronized(roomsLock) {
            rooms.putIfAbsent(key, room)
            rooms[key] ?: room
        }
        persistRooms()
        if (!localName.isNullOrEmpty()) {
            prefs.edit().putString("local_name_$key", localName).apply()
        }
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, currentRoom) }
        emit()
    }

    fun removeGroup(relayUrl: String, groupId: String) {
        val key = roomKey(relayUrl, groupId)
        synchronized(roomsLock) { rooms.remove(key) }
        prefs.edit().remove("local_name_$key").apply()
        persistRooms()
        ownerPubkey?.let { persistence?.deleteRoom(it, relayUrl, groupId) }
        emit()
    }

    fun updateMetadata(relayUrl: String, groupId: String, metadata: Nip29.GroupMetadata) {
        val key = roomKey(relayUrl, groupId)
        val updated = synchronized(roomsLock) {
            val existing = rooms[key] ?: GroupRoom(groupId, relayUrl, null, emptyList(), 0L)
            val mergedName = metadata.name?.takeIf { it.isNotEmpty() } ?: existing.metadata?.name
            val u = existing.copy(metadata = metadata.copy(name = mergedName))
            rooms[key] = u
            u
        }
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun updateAdmins(relayUrl: String, groupId: String, admins: List<String>) {
        val key = roomKey(relayUrl, groupId)
        val updated = synchronized(roomsLock) {
            val existing = rooms[key] ?: return
            val u = existing.copy(admins = admins)
            rooms[key] = u
            u
        }
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun updateMembers(relayUrl: String, groupId: String, members: List<String>) {
        val key = roomKey(relayUrl, groupId)
        val updated = synchronized(roomsLock) {
            val existing = rooms[key] ?: return
            val u = existing.copy(members = members)
            rooms[key] = u
            u
        }
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun addMessage(relayUrl: String, groupId: String, message: GroupMessage) {
        val key = roomKey(relayUrl, groupId)
        synchronized(roomsLock) {
            if (!seenMessages.add(message.id)) return
            val existing = rooms[key] ?: return
            val merged = insertByCreatedAt(existing.messages, message)
            // Bound the in-memory list (newest kept). Full history stays in ObjectBox;
            // this only caps the Compose-facing list so a high-volume room can't grow
            // memory without limit.
            val capped = if (merged.size > MAX_ROOM_MESSAGES) merged.takeLast(MAX_ROOM_MESSAGES) else merged
            rooms[key] = existing.copy(
                messages = capped,
                lastMessageAt = maxOf(existing.lastMessageAt, message.createdAt)
            )
            // Bound the dedup set: clear + reseed from the messages still retained so it
            // can't grow forever. A trimmed-then-replayed old message may re-enter once
            // before being re-trimmed — rare and harmless.
            if (seenMessages.size > MAX_SEEN_MESSAGES) {
                seenMessages.clear()
                rooms.values.forEach { r -> r.messages.forEach { seenMessages.add(it.id) } }
            }
        }
        ownerPubkey?.let { persistence?.queueMessage(it, relayUrl, groupId, message) }
        // Update unread state for notified groups
        if (key in _notifiedGroups.value) {
            val lastSeen = lastSeenMessageIds[key]
            if (lastSeen == null || lastSeen != message.id) {
                _unreadGroups.value = _unreadGroups.value + key
            }
        }
        emit()
    }

    fun addReaction(relayUrl: String, groupId: String, messageId: String, reactorPubkey: String, emoji: String, emojiUrl: String? = null) {
        val key = roomKey(relayUrl, groupId)
        val changed = synchronized(roomsLock) {
            var existing = rooms[key] ?: return
            if (emojiUrl != null) {
                val shortcode = emoji.removeSurrounding(":")
                existing = existing.copy(reactionEmojiUrls = existing.reactionEmojiUrls + (shortcode to emojiUrl))
            }
            val msgIndex = existing.messages.indexOfFirst { it.id == messageId }
            if (msgIndex < 0) {
                if (emojiUrl != null) { rooms[key] = existing; true } else false
            } else {
                val msg = existing.messages[msgIndex]
                val current = msg.reactions[emoji] ?: emptyList()
                if (reactorPubkey in current) {
                    false
                } else {
                    val updatedMsg = msg.copy(reactions = msg.reactions + (emoji to (current + reactorPubkey)))
                    val updatedMessages = existing.messages.toMutableList().also { it[msgIndex] = updatedMsg }
                    rooms[key] = existing.copy(messages = updatedMessages)
                    true
                }
            }
        }
        if (changed) emit()
    }

    /**
     * Insert [message] into an already-ascending-by-createdAt list, returning a new
     * list. Replaces a full O(n log n) re-sort on every message: chat messages almost
     * always arrive newest-last, so the common path is a tail append; only out-of-order
     * arrivals walk back to find the slot. The O(n) copy is unavoidable — Compose diffs
     * an immutable snapshot.
     */
    private fun insertByCreatedAt(messages: List<GroupMessage>, message: GroupMessage): List<GroupMessage> {
        if (messages.isEmpty() || message.createdAt >= messages.last().createdAt) {
            return messages + message
        }
        val result = ArrayList<GroupMessage>(messages.size + 1)
        var inserted = false
        for (m in messages) {
            if (!inserted && message.createdAt < m.createdAt) {
                result.add(message)
                inserted = true
            }
            result.add(m)
        }
        if (!inserted) result.add(message)
        return result
    }

    fun getRoom(relayUrl: String, groupId: String): GroupRoom? = rooms[roomKey(relayUrl, groupId)]

    /** Look up the relay URL for a group by its ID alone. Returns null if not joined. */
    fun getRelayForGroup(groupId: String): String? =
        rooms.values.firstOrNull { it.groupId == groupId }?.relayUrl

    data class GroupMessageInfo(
        val content: String?,
        val groupName: String?,
        val relayUrl: String?,
        val emojiTags: Map<String, String> = emptyMap()
    )

    /** Look up a message and its room context by group ID and message ID. */
    fun findGroupMessage(groupId: String, messageId: String): GroupMessageInfo? {
        val room = rooms.values.firstOrNull { it.groupId == groupId } ?: return null
        val message = room.messages.firstOrNull { it.id == messageId }
        return GroupMessageInfo(
            content = message?.content,
            groupName = room.metadata?.name ?: groupId,
            relayUrl = room.relayUrl,
            emojiTags = message?.emojiTags ?: emptyMap()
        )
    }

    fun getJoinedGroupKeys(): List<Pair<String, String>> =
        rooms.values.map { Pair(it.relayUrl, it.groupId) }

    // --- Notification subscription & unread tracking ---

    fun isNotified(relayUrl: String, groupId: String): Boolean =
        roomKey(relayUrl, groupId) in _notifiedGroups.value

    fun setNotified(relayUrl: String, groupId: String, enabled: Boolean) {
        val key = roomKey(relayUrl, groupId)
        val current = _notifiedGroups.value.toMutableSet()
        if (enabled) current.add(key) else {
            current.remove(key)
            _unreadGroups.value = _unreadGroups.value - key
        }
        _notifiedGroups.value = current
        prefs.edit().putStringSet("notified_groups", current).apply()
    }

    /** Returns the set of (relayUrl, groupId) pairs that have notifications enabled. */
    fun getNotifiedGroupKeys(): List<Pair<String, String>> {
        return _notifiedGroups.value.mapNotNull { key ->
            val parts = key.split("|", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1]) else null
        }
    }

    fun markRead(relayUrl: String, groupId: String) {
        val key = roomKey(relayUrl, groupId)
        val room = rooms[key] ?: return
        val lastMsg = room.messages.lastOrNull() ?: return
        lastSeenMessageIds[key] = lastMsg.id
        _unreadGroups.value = _unreadGroups.value - key
        prefs.edit().putString("last_seen_$key", lastMsg.id).apply()
    }

    fun hasUnread(relayUrl: String, groupId: String): Boolean =
        roomKey(relayUrl, groupId) in _unreadGroups.value

    private fun loadNotificationState() {
        val notified = prefs.getStringSet("notified_groups", emptySet()) ?: emptySet()
        _notifiedGroups.value = notified
        // Load last-seen message IDs and compute initial unread state
        val unread = mutableSetOf<String>()
        for (key in notified) {
            val lastSeen = prefs.getString("last_seen_$key", null)
            if (lastSeen != null) lastSeenMessageIds[key] = lastSeen
            val room = rooms[key]
            if (room != null) {
                val lastMsg = room.messages.lastOrNull()
                if (lastMsg != null && lastMsg.id != lastSeen) {
                    unread.add(key)
                }
            }
        }
        _unreadGroups.value = unread
    }

    fun clear() {
        synchronized(roomsLock) {
            rooms.clear()
            seenMessages.clear()
        }
        _unreadGroups.value = emptySet()
        emit()
    }

    fun reload(newPubkey: String?) {
        ownerPubkey = newPubkey
        prefs = context.getSharedPreferences("group_rooms_${newPubkey ?: "anon"}", Context.MODE_PRIVATE)
        clear()
        loadPersistedGroups()
        loadNotificationState()
    }

    private fun persistRooms() {
        val entries = rooms.values.map { "${it.relayUrl}|${it.groupId}" }.toSet()
        prefs.edit().putStringSet("rooms", entries).apply()
    }

    private fun emit() {
        _joinedGroups.value = rooms.values
            .sortedByDescending { it.lastMessageAt }
            .toList()
    }

    companion object {
        /** Max messages retained in memory per room (newest kept). Older history stays
         *  in ObjectBox and is reloaded on open; this only bounds the live Compose list. */
        private const val MAX_ROOM_MESSAGES = 1000

        /** Max message IDs in the dedup set before it is cleared and reseeded from the
         *  currently retained messages. */
        private const val MAX_SEEN_MESSAGES = 20_000
    }
}
