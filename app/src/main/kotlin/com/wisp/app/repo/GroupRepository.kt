package com.wisp.app.repo

import android.content.Context
import com.wisp.app.db.GroupPersistence
import com.wisp.app.db.WispObjectBox
import com.wisp.app.nostr.Nip29
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
                            persisted.about, persisted.isPrivate, persisted.isClosed)
                    else -> {
                        // Fall back to locally-stored name in SharedPreferences
                        val localName = prefs.getString("local_name_$key", null)
                        if (!localName.isNullOrEmpty()) Nip29.GroupMetadata(groupId, localName, null, null, false, false) else null
                    }
                }

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
        emit()
    }

    fun addGroup(relayUrl: String, groupId: String, localName: String? = null) {
        val key = roomKey(relayUrl, groupId)
        val metadata = if (!localName.isNullOrEmpty()) {
            Nip29.GroupMetadata(groupId, localName, null, null, false, false)
        } else null
        val room = GroupRoom(groupId, relayUrl, metadata, emptyList(), 0L)
        rooms.putIfAbsent(key, room)
        persistRooms()
        if (!localName.isNullOrEmpty()) {
            prefs.edit().putString("local_name_$key", localName).apply()
        }
        val currentRoom = rooms[key] ?: room
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, currentRoom) }
        emit()
    }

    fun removeGroup(relayUrl: String, groupId: String) {
        val key = roomKey(relayUrl, groupId)
        rooms.remove(key)
        prefs.edit().remove("local_name_$key").apply()
        persistRooms()
        ownerPubkey?.let { persistence?.deleteRoom(it, relayUrl, groupId) }
        emit()
    }

    fun updateMetadata(relayUrl: String, groupId: String, metadata: Nip29.GroupMetadata) {
        val key = roomKey(relayUrl, groupId)
        val existing = rooms[key] ?: GroupRoom(groupId, relayUrl, null, emptyList(), 0L)
        val mergedName = metadata.name?.takeIf { it.isNotEmpty() } ?: existing.metadata?.name
        val updated = existing.copy(metadata = metadata.copy(name = mergedName))
        rooms[key] = updated
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun updateAdmins(relayUrl: String, groupId: String, admins: List<String>) {
        val key = roomKey(relayUrl, groupId)
        val existing = rooms[key] ?: return
        val updated = existing.copy(admins = admins)
        rooms[key] = updated
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun updateMembers(relayUrl: String, groupId: String, members: List<String>) {
        val key = roomKey(relayUrl, groupId)
        val existing = rooms[key] ?: return
        val updated = existing.copy(members = members)
        rooms[key] = updated
        ownerPubkey?.let { persistence?.upsertRoomMeta(it, updated) }
        emit()
    }

    fun addMessage(relayUrl: String, groupId: String, message: GroupMessage) {
        if (!seenMessages.add(message.id)) return
        val key = roomKey(relayUrl, groupId)
        val existing = rooms[key] ?: return
        val updated = (existing.messages + message).sortedBy { it.createdAt }
        val updatedRoom = existing.copy(
            messages = updated,
            lastMessageAt = maxOf(existing.lastMessageAt, message.createdAt)
        )
        rooms[key] = updatedRoom
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
        var existing = rooms[key] ?: return
        if (emojiUrl != null) {
            val shortcode = emoji.removeSurrounding(":")
            existing = existing.copy(reactionEmojiUrls = existing.reactionEmojiUrls + (shortcode to emojiUrl))
        }
        val msgIndex = existing.messages.indexOfFirst { it.id == messageId }
        if (msgIndex < 0) {
            if (emojiUrl != null) { rooms[key] = existing; emit() }
            return
        }
        val msg = existing.messages[msgIndex]
        val current = msg.reactions[emoji] ?: emptyList()
        if (reactorPubkey in current) return
        val updatedMsg = msg.copy(reactions = msg.reactions + (emoji to (current + reactorPubkey)))
        val updatedMessages = existing.messages.toMutableList().also { it[msgIndex] = updatedMsg }
        rooms[key] = existing.copy(messages = updatedMessages)
        emit()
    }

    fun getRoom(relayUrl: String, groupId: String): GroupRoom? = rooms[roomKey(relayUrl, groupId)]

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
        rooms.clear()
        seenMessages.clear()
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
}
