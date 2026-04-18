package com.wisp.app.db

import android.util.Log
import com.wisp.app.nostr.Nip29
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupRoom
import io.objectbox.Box
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GroupPersistence {
    private val metaBox: Box<GroupMetaEntity> = WispObjectBox.store.boxFor(GroupMetaEntity::class.java)
    private val msgBox: Box<GroupMessageEntity> = WispObjectBox.store.boxFor(GroupMessageEntity::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val msgChannel = Channel<GroupMessageEntity>(Channel.BUFFERED)

    init {
        // Batched write-behind for messages
        scope.launch {
            val batch = mutableListOf<GroupMessageEntity>()
            for (entity in msgChannel) {
                batch.add(entity)
                while (true) { val next = msgChannel.tryReceive().getOrNull() ?: break; batch.add(next) }
                if (batch.size < 50) {
                    delay(200)
                    while (true) { val next = msgChannel.tryReceive().getOrNull() ?: break; batch.add(next) }
                }
                try { msgBox.put(batch) } catch (e: Exception) {
                    Log.w("GroupPersistence", "msg batch write failed: ${e.message}")
                }
                batch.clear()
            }
        }
    }

    data class StoredRoom(
        val relayUrl: String,
        val groupId: String,
        val name: String?,
        val picture: String?,
        val about: String?,
        val isPrivate: Boolean,
        val isClosed: Boolean,
        val isRestricted: Boolean,
        val isHidden: Boolean,
        val admins: List<String>,
        val members: List<String>,
        val lastMessageAt: Long,
        val messages: List<GroupMessage>
    )

    /** Load all rooms (with recent messages) for a given account pubkey. */
    fun loadRooms(ownerPubkey: String): List<StoredRoom> {
        return try {
            val metaEntities = metaBox.query(GroupMetaEntity_.ownerPubkey.equal(ownerPubkey))
                .build().use { it.find() }
            metaEntities.map { meta ->
                val msgEntities = msgBox.query(GroupMessageEntity_.roomKey.equal(meta.roomKey))
                    .order(GroupMessageEntity_.createdAt)
                    .build().use { it.find(0, 200L) }
                val messages = msgEntities.map { msg ->
                    GroupMessage(
                        id = msg.eventId,
                        senderPubkey = msg.senderPubkey,
                        content = msg.content,
                        createdAt = msg.createdAt,
                        replyToId = msg.replyToId
                    )
                }
                val admins: List<String> = try { json.decodeFromString(meta.adminsJson) } catch (_: Exception) { emptyList() }
                val members: List<String> = try { json.decodeFromString(meta.membersJson) } catch (_: Exception) { emptyList() }
                StoredRoom(
                    relayUrl = meta.relayUrl,
                    groupId = meta.groupId,
                    name = meta.name,
                    picture = meta.picture,
                    about = meta.about,
                    isPrivate = meta.isPrivate,
                    isClosed = meta.isClosed,
                    isRestricted = meta.isRestricted,
                    isHidden = meta.isHidden,
                    admins = admins,
                    members = members,
                    // Derive lastMessageAt from newest stored message; fall back to stored value
                    lastMessageAt = maxOf(meta.lastMessageAt, messages.lastOrNull()?.createdAt ?: 0L),
                    messages = messages
                )
            }
        } catch (e: Exception) {
            Log.w("GroupPersistence", "loadRooms failed: ${e.message}")
            emptyList()
        }
    }

    /** Upsert room metadata, admins, and members. Called whenever any of these change. */
    fun upsertRoomMeta(ownerPubkey: String, room: GroupRoom) {
        scope.launch {
            try {
                val key = "$ownerPubkey|${room.relayUrl}|${room.groupId}"
                // Query for existing dbId so ObjectBox treats this as an update, not a new insert
                val existingId = metaBox.query(GroupMetaEntity_.roomKey.equal(key))
                    .build().use { it.findFirst()?.dbId ?: 0L }
                metaBox.put(GroupMetaEntity(
                    dbId = existingId,
                    roomKey = key,
                    ownerPubkey = ownerPubkey,
                    relayUrl = room.relayUrl,
                    groupId = room.groupId,
                    name = room.metadata?.name,
                    picture = room.metadata?.picture,
                    about = room.metadata?.about,
                    isPrivate = room.metadata?.isPrivate ?: false,
                    isClosed = room.metadata?.isClosed ?: false,
                    isRestricted = room.metadata?.isRestricted ?: false,
                    isHidden = room.metadata?.isHidden ?: false,
                    adminsJson = json.encodeToString(room.admins),
                    membersJson = json.encodeToString(room.members),
                    lastMessageAt = room.lastMessageAt
                ))
            } catch (e: Exception) {
                Log.w("GroupPersistence", "upsertRoomMeta failed: ${e.message}")
            }
        }
    }

    /** Queue a message for batched write. */
    fun queueMessage(ownerPubkey: String, relayUrl: String, groupId: String, message: GroupMessage) {
        msgChannel.trySend(GroupMessageEntity(
            eventId = message.id,
            roomKey = "$ownerPubkey|$relayUrl|$groupId",
            senderPubkey = message.senderPubkey,
            content = message.content,
            createdAt = message.createdAt,
            replyToId = message.replyToId
        ))
    }

    /** Delete all stored data for a room (on leave/delete). */
    fun deleteRoom(ownerPubkey: String, relayUrl: String, groupId: String) {
        scope.launch {
            try {
                val key = "$ownerPubkey|$relayUrl|$groupId"
                metaBox.query(GroupMetaEntity_.roomKey.equal(key)).build().use { it.remove() }
                msgBox.query(GroupMessageEntity_.roomKey.equal(key)).build().use { it.remove() }
            } catch (e: Exception) {
                Log.w("GroupPersistence", "deleteRoom failed: ${e.message}")
            }
        }
    }
}
