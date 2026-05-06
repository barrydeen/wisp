package com.wisp.app.db

import android.util.Log
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.DmReaction
import com.wisp.app.nostr.DmZap
import com.wisp.app.nostr.EncryptedMedia
import io.objectbox.Box
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists decrypted NIP-17 DMs so we don't re-decrypt every gift wrap on each cold start.
 * Critical for remote signer (Amber) mode where each unwrap requires two IPC round-trips.
 */
class DmPersistence {
    private val box: Box<DmMessageEntity> = WispObjectBox.store.boxFor(DmMessageEntity::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel = Channel<Pair<String, DmMessage>>(Channel.BUFFERED)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class ReactionDto(val authorPubkey: String, val emoji: String, val timestamp: Long, val emojiUrl: String? = null)

    @Serializable
    private data class ZapDto(val zapperPubkey: String, val sats: Long, val timestamp: Long)

    @Serializable
    private data class FileMetadataDto(
        val fileUrl: String,
        val mimeType: String,
        val algorithm: String,
        val keyHex: String,
        val nonceHex: String,
        val encryptedHash: String,
        val originalHash: String,
        val size: Long?,
        val dimensions: String?,
        val thumbhash: String?,
        val blurhash: String?
    )

    init {
        // Batched write-behind: collect over a short window then bulk-put
        scope.launch {
            val batch = mutableListOf<DmMessageEntity>()
            for (item in writeChannel) {
                batch.add(toEntity(item.first, item.second))
                while (true) {
                    val next = writeChannel.tryReceive().getOrNull() ?: break
                    batch.add(toEntity(next.first, next.second))
                }
                if (batch.size < 50) {
                    delay(200)
                    while (true) {
                        val next = writeChannel.tryReceive().getOrNull() ?: break
                        batch.add(toEntity(next.first, next.second))
                    }
                }
                try {
                    // Resolve existing dbIds so puts are upserts (preserve identity per ownerPlusGiftWrap)
                    val resolved = batch.map { entity ->
                        val existingId = box.query(DmMessageEntity_.ownerPlusGiftWrap.equal(entity.ownerPlusGiftWrap))
                            .build().use { it.findFirst()?.dbId ?: 0L }
                        entity.copy(dbId = existingId)
                    }
                    box.put(resolved)
                } catch (e: Exception) {
                    Log.w("DmPersistence", "Batch write failed: ${e.message}")
                }
                batch.clear()
            }
        }
    }

    /** Queue an upsert. Safe to call repeatedly — coalesced under a 200ms window. */
    fun queueMessage(ownerPubkey: String, msg: DmMessage) {
        if (ownerPubkey.isBlank() || msg.giftWrapId.isBlank()) return
        writeChannel.trySend(ownerPubkey to msg)
    }

    /** Load every persisted DM for an account. Caller groups by conversation. */
    fun loadAll(ownerPubkey: String): List<Pair<String, DmMessage>> {
        if (ownerPubkey.isBlank()) return emptyList()
        return try {
            val entities = box.query(DmMessageEntity_.ownerPubkey.equal(ownerPubkey))
                .build().use { it.find() }
            entities.mapNotNull { it.toDmMessage()?.let { msg -> it.conversationKey to msg } }
        } catch (e: Exception) {
            Log.w("DmPersistence", "loadAll failed: ${e.message}")
            emptyList()
        }
    }

    /** Wipe a single account's DMs (e.g. on logout / [DmRepository.clear]). */
    fun deleteAllForOwner(ownerPubkey: String) {
        if (ownerPubkey.isBlank()) return
        scope.launch {
            try {
                box.query(DmMessageEntity_.ownerPubkey.equal(ownerPubkey))
                    .build().use { it.remove() }
            } catch (e: Exception) {
                Log.w("DmPersistence", "deleteAllForOwner failed: ${e.message}")
            }
        }
    }

    /** Remove every conversation involving [pubkey] (mute / block flow). */
    fun deleteConversationsWithPubkey(ownerPubkey: String, pubkey: String) {
        if (ownerPubkey.isBlank() || pubkey.isBlank()) return
        scope.launch {
            try {
                val entities = box.query(DmMessageEntity_.ownerPubkey.equal(ownerPubkey))
                    .build().use { it.find() }
                val toRemove = entities.filter { it.conversationKey.split(",").contains(pubkey) }
                if (toRemove.isNotEmpty()) box.remove(toRemove)
            } catch (e: Exception) {
                Log.w("DmPersistence", "deleteConversationsWithPubkey failed: ${e.message}")
            }
        }
    }

    private fun toEntity(ownerPubkey: String, msg: DmMessage): DmMessageEntity {
        val reactionsJson = json.encodeToString(
            ListSerializer(ReactionDto.serializer()),
            msg.reactions.map { ReactionDto(it.authorPubkey, it.emoji, it.timestamp, it.emojiUrl) }
        )
        val zapsJson = json.encodeToString(
            ListSerializer(ZapDto.serializer()),
            msg.zaps.map { ZapDto(it.zapperPubkey, it.sats, it.timestamp) }
        )
        val fileJson = msg.encryptedFileMetadata?.let {
            json.encodeToString(FileMetadataDto.serializer(), FileMetadataDto(
                it.fileUrl, it.mimeType, it.algorithm, it.keyHex, it.nonceHex,
                it.encryptedHash, it.originalHash, it.size, it.dimensions, it.thumbhash, it.blurhash
            ))
        }
        return DmMessageEntity(
            ownerPubkey = ownerPubkey,
            msgId = msg.id,
            giftWrapId = msg.giftWrapId,
            ownerPlusGiftWrap = "$ownerPubkey|${msg.giftWrapId}",
            conversationKey = inferConversationKey(ownerPubkey, msg),
            senderPubkey = msg.senderPubkey,
            content = msg.content,
            createdAt = msg.createdAt,
            rumorId = msg.rumorId,
            replyToId = msg.replyToId,
            participantsJson = json.encodeToString(ListSerializer(String.serializer()), msg.participants),
            relayUrlsJson = json.encodeToString(ListSerializer(String.serializer()), msg.relayUrls.toList()),
            reactionsJson = reactionsJson,
            zapsJson = zapsJson,
            emojiMapJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), msg.emojiMap),
            encryptedFileMetadataJson = fileJson,
            debugGiftWrapJson = msg.debugGiftWrapJson,
            debugRumorJson = msg.debugRumorJson
        )
    }

    private fun inferConversationKey(ownerPubkey: String, msg: DmMessage): String =
        (msg.participants + ownerPubkey).toSortedSet().joinToString(",")

    private fun DmMessageEntity.toDmMessage(): DmMessage? {
        return try {
            val participants: List<String> = json.decodeFromString(ListSerializer(String.serializer()), participantsJson)
            val relayUrls: List<String> = json.decodeFromString(ListSerializer(String.serializer()), relayUrlsJson)
            val reactions: List<ReactionDto> = json.decodeFromString(ListSerializer(ReactionDto.serializer()), reactionsJson)
            val zaps: List<ZapDto> = json.decodeFromString(ListSerializer(ZapDto.serializer()), zapsJson)
            val emojiMap: Map<String, String> = json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()), emojiMapJson)
            val fileMeta = encryptedFileMetadataJson?.let {
                val dto: FileMetadataDto = json.decodeFromString(FileMetadataDto.serializer(), it)
                EncryptedMedia.EncryptedFileMetadata(
                    dto.fileUrl, dto.mimeType, dto.algorithm, dto.keyHex, dto.nonceHex,
                    dto.encryptedHash, dto.originalHash, dto.size, dto.dimensions, dto.thumbhash, dto.blurhash
                )
            }
            DmMessage(
                id = msgId,
                senderPubkey = senderPubkey,
                content = content,
                createdAt = createdAt,
                giftWrapId = giftWrapId,
                relayUrls = relayUrls.toSet(),
                rumorId = rumorId,
                replyToId = replyToId,
                participants = participants,
                reactions = reactions.map { DmReaction(it.authorPubkey, it.emoji, it.timestamp, it.emojiUrl) },
                zaps = zaps.map { DmZap(it.zapperPubkey, it.sats, it.timestamp) },
                emojiMap = emojiMap,
                encryptedFileMetadata = fileMeta,
                debugGiftWrapJson = debugGiftWrapJson,
                debugRumorJson = debugRumorJson
            )
        } catch (e: Exception) {
            Log.w("DmPersistence", "Failed to deserialize DM $giftWrapId: ${e.message}")
            null
        }
    }
}
