package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

object Nip17 {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    data class Rumor(
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val content: String,
        val tags: List<List<String>>
    )

    /**
     * Create a gift-wrapped DM (kind 1059) from sender to recipient.
     * Implements the 3-layer NIP-17 scheme: rumor(14) -> seal(13) -> gift wrap(1059).
     */
    suspend fun createGiftWrap(
        senderPrivkey: ByteArray,
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        message: String,
        replyTags: List<List<String>> = emptyList(),
        rumorPTag: String? = null,
        rumorKind: Int = 14,
        targetDifficulty: Int = 0,
        onProgress: ((Long) -> Unit)? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val senderPubkeyHex = senderPubkey.toHex()
        val recipientPubkeyHex = recipientPubkey.toHex()

        // Layer 1: Build unsigned rumor (no id, no sig)
        val rumorTags = mutableListOf<List<String>>()
        if (rumorKind == 14) rumorTags.add(listOf("p", rumorPTag ?: recipientPubkeyHex))
        rumorTags.addAll(replyTags)

        val now = createdAt
        val rumorJson = buildJsonObject {
            put("kind", JsonPrimitive(rumorKind))
            put("pubkey", JsonPrimitive(senderPubkeyHex))
            put("created_at", JsonPrimitive(now))
            put("tags", buildJsonArray {
                for (tag in rumorTags) {
                    add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
                }
            })
            put("content", JsonPrimitive(message))
        }.toString()

        // Layer 2: Seal (kind 13) — encrypt rumor with sender→recipient conversation key
        val senderRecipientKey = Nip44.getConversationKey(senderPrivkey, recipientPubkey)
        val encryptedRumor = Nip44.encrypt(rumorJson, senderRecipientKey)
        senderRecipientKey.wipe()
        val sealTimestamp = randomizeTimestamp(now)
        val seal = NostrEvent.create(
            privkey = senderPrivkey,
            pubkey = senderPubkey,
            kind = 13,
            content = encryptedRumor,
            tags = emptyList(),
            createdAt = sealTimestamp
        )

        // Layer 3: Gift wrap (kind 1059) — encrypt seal with throwaway→recipient key
        val throwaway = Keys.generate()
        val throwawayRecipientKey = Nip44.getConversationKey(throwaway.privkey, recipientPubkey)
        val encryptedSeal = Nip44.encrypt(seal.toJson(), throwawayRecipientKey)
        val wrapTimestamp = randomizeTimestamp(now)
        val baseTags = listOf(listOf("p", recipientPubkeyHex))

        val finalTags: List<List<String>>
        val finalCreatedAt: Long
        if (targetDifficulty > 0) {
            val result = Nip13.mine(
                pubkeyHex = throwaway.pubkey.toHex(),
                kind = 1059,
                content = encryptedSeal,
                tags = baseTags,
                targetDifficulty = targetDifficulty,
                createdAt = wrapTimestamp,
                onProgress = onProgress
            )
            finalTags = result.tags
            finalCreatedAt = result.createdAt
        } else {
            finalTags = baseTags
            finalCreatedAt = wrapTimestamp
        }

        val giftWrap = NostrEvent.create(
            privkey = throwaway.privkey,
            pubkey = throwaway.pubkey,
            kind = 1059,
            content = encryptedSeal,
            tags = finalTags,
            createdAt = finalCreatedAt
        )

        // Wipe throwaway private key — no reason for it to persist
        throwaway.wipe()
        throwawayRecipientKey.wipe()

        return giftWrap
    }

    /**
     * Unwrap a received gift wrap (kind 1059) to extract the inner rumor.
     */
    fun unwrapGiftWrap(recipientPrivkey: ByteArray, giftWrap: NostrEvent): Rumor? {
        if (giftWrap.kind != 1059) return null

        return try {
            // Decrypt gift wrap → seal JSON
            val throwawayPubkey = giftWrap.pubkey.hexToByteArray()
            val throwawayKey = Nip44.getConversationKey(recipientPrivkey, throwawayPubkey)
            val sealJson = Nip44.decrypt(giftWrap.content, throwawayKey)

            // Parse seal
            val seal = NostrEvent.fromJson(sealJson)
            if (seal.kind != 13) return null

            // Decrypt seal → rumor JSON
            val sealPubkey = seal.pubkey.hexToByteArray()
            val sealKey = Nip44.getConversationKey(recipientPrivkey, sealPubkey)
            val rumorJson = Nip44.decrypt(seal.content, sealKey)

            // Parse rumor
            val rumorObj = json.parseToJsonElement(rumorJson).jsonObject
            val kind = rumorObj["kind"]?.jsonPrimitive?.content?.toIntOrNull()
            if (kind != 14 && kind != 7) return null

            val tags = rumorObj["tags"]?.jsonArray?.map { tagArr ->
                tagArr.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            Rumor(
                pubkey = rumorObj["pubkey"]?.jsonPrimitive?.content ?: seal.pubkey,
                createdAt = rumorObj["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: seal.created_at,
                kind = kind,
                content = rumorObj["content"]?.jsonPrimitive?.content ?: "",
                tags = tags
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a gift-wrapped DM using a NostrSigner (for remote signer support).
     * The seal is encrypted and signed via the signer. The gift wrap layer
     * still uses a local throwaway key (no reason to involve the signer).
     */
    suspend fun createGiftWrapRemote(
        signer: NostrSigner,
        recipientPubkeyHex: String,
        message: String,
        replyTags: List<List<String>> = emptyList(),
        rumorPTag: String? = null,
        rumorKind: Int = 14,
        targetDifficulty: Int = 0,
        onProgress: ((Long) -> Unit)? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val senderPubkeyHex = signer.pubkeyHex

        // Layer 1: Build unsigned rumor (no id, no sig)
        val rumorTags = mutableListOf<List<String>>()
        if (rumorKind == 14) rumorTags.add(listOf("p", rumorPTag ?: recipientPubkeyHex))
        rumorTags.addAll(replyTags)

        val now = createdAt
        val rumorJson = buildJsonObject {
            put("kind", JsonPrimitive(rumorKind))
            put("pubkey", JsonPrimitive(senderPubkeyHex))
            put("created_at", JsonPrimitive(now))
            put("tags", buildJsonArray {
                for (tag in rumorTags) {
                    add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
                }
            })
            put("content", JsonPrimitive(message))
        }.toString()

        // Layer 2: Seal (kind 13) — encrypt rumor with signer, sign seal with signer
        val encryptedRumor = signer.nip44Encrypt(rumorJson, recipientPubkeyHex)
        val sealTimestamp = randomizeTimestamp(now)
        val seal = signer.signEvent(
            kind = 13,
            content = encryptedRumor,
            tags = emptyList(),
            createdAt = sealTimestamp
        )

        // Layer 3: Gift wrap (kind 1059) — local throwaway key (no signer needed)
        val throwaway = Keys.generate()
        val throwawayRecipientKey = Nip44.getConversationKey(throwaway.privkey, recipientPubkeyHex.hexToByteArray())
        val encryptedSeal = Nip44.encrypt(seal.toJson(), throwawayRecipientKey)
        val wrapTimestamp = randomizeTimestamp(now)
        val baseTags = listOf(listOf("p", recipientPubkeyHex))

        val finalTags: List<List<String>>
        val finalCreatedAt: Long
        if (targetDifficulty > 0) {
            val result = Nip13.mine(
                pubkeyHex = throwaway.pubkey.toHex(),
                kind = 1059,
                content = encryptedSeal,
                tags = baseTags,
                targetDifficulty = targetDifficulty,
                createdAt = wrapTimestamp,
                onProgress = onProgress
            )
            finalTags = result.tags
            finalCreatedAt = result.createdAt
        } else {
            finalTags = baseTags
            finalCreatedAt = wrapTimestamp
        }

        val giftWrap = NostrEvent.create(
            privkey = throwaway.privkey,
            pubkey = throwaway.pubkey,
            kind = 1059,
            content = encryptedSeal,
            tags = finalTags,
            createdAt = finalCreatedAt
        )

        throwaway.wipe()
        throwawayRecipientKey.wipe()

        return giftWrap
    }

    /**
     * Unwrap a received gift wrap using a NostrSigner (for remote signer support).
     * Uses signer.nip44Decrypt for both decrypt layers.
     */
    suspend fun unwrapGiftWrapRemote(signer: NostrSigner, giftWrap: NostrEvent): Rumor? {
        if (giftWrap.kind != 1059) return null

        return try {
            // Decrypt gift wrap → seal JSON
            val sealJson = signer.nip44Decrypt(giftWrap.content, giftWrap.pubkey)

            // Parse seal
            val seal = NostrEvent.fromJson(sealJson)
            if (seal.kind != 13) return null

            // Decrypt seal → rumor JSON
            val rumorJson = signer.nip44Decrypt(seal.content, seal.pubkey)

            // Parse rumor
            val rumorObj = json.parseToJsonElement(rumorJson).jsonObject
            val kind = rumorObj["kind"]?.jsonPrimitive?.content?.toIntOrNull()
            if (kind != 14 && kind != 7) return null

            val tags = rumorObj["tags"]?.jsonArray?.map { tagArr ->
                tagArr.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            Rumor(
                pubkey = rumorObj["pubkey"]?.jsonPrimitive?.content ?: seal.pubkey,
                createdAt = rumorObj["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: seal.created_at,
                kind = kind,
                content = rumorObj["content"]?.jsonPrimitive?.content ?: "",
                tags = tags
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compute the deterministic event ID for a rumor (kind 14), using the same
     * SHA-256 serialization as regular Nostr events. This ID is used as the e-tag
     * target for replies and private reactions.
     */
    fun computeRumorId(rumor: Rumor): String =
        NostrEvent.computeId(rumor.pubkey, rumor.createdAt, rumor.kind, rumor.tags, rumor.content)

    /** Serialize a Rumor to a JSON string (for debug inspection). */
    fun rumorToJson(rumor: Rumor): String = buildJsonObject {
        put("id", JsonPrimitive(computeRumorId(rumor)))
        put("pubkey", JsonPrimitive(rumor.pubkey))
        put("created_at", JsonPrimitive(rumor.createdAt))
        put("kind", JsonPrimitive(rumor.kind))
        put("tags", buildJsonArray {
            for (tag in rumor.tags) {
                add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
            }
        })
        put("content", JsonPrimitive(rumor.content))
    }.toString()

    /**
     * Build the same Rumor structure that createGiftWrap/createGiftWrapRemote uses internally.
     * Pass the same [createdAt] you'll give to createGiftWrap to get the matching rumorId.
     */
    fun buildRumor(
        senderPubkeyHex: String,
        message: String,
        pTag: String? = null,
        replyTags: List<List<String>> = emptyList(),
        kind: Int = 14,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): Rumor {
        val tags = mutableListOf<List<String>>()
        if (kind == 14 && pTag != null) tags.add(listOf("p", pTag))
        tags.addAll(replyTags)
        return Rumor(senderPubkeyHex, createdAt, kind, message, tags)
    }

    /**
     * Returns true if a decrypted rumor is a private DM reaction rather than a message.
     * A reaction has an e-tag pointing to the target message and short emoji content.
     */
    fun isReaction(rumor: Rumor): Boolean = rumor.kind == 7

    /**
     * Extract all conversation participants from a rumor, excluding [myPubkey].
     * Returns a stable sorted list of pubkeys for use as a conversation key.
     */
    fun getConversationParticipants(rumor: Rumor, myPubkey: String): List<String> {
        val all = mutableSetOf<String>()
        all.add(rumor.pubkey)
        rumor.tags.filter { it.size >= 2 && it[0] == "p" }.forEach { all.add(it[1]) }
        all.remove(myPubkey)
        return all.toSortedSet().toList()
    }

    /**
     * Create a single gift-wrapped private DM reaction for [recipientPubkey].
     * Call in a loop over all conversation participants to broadcast the reaction.
     * The inner rumor carries the emoji as content and an e-tag pointing to
     * [targetRumorId] so recipients can associate the reaction with the right message.
     */
    suspend fun createDmReaction(
        senderPrivkey: ByteArray,
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        targetRumorId: String,
        originalSenderPubkey: String,
        emoji: String,
        emojiUrl: String? = null
    ): NostrEvent {
        val tags = mutableListOf(
            listOf("e", targetRumorId),
            listOf("p", originalSenderPubkey),
            listOf("k", "14")
        )
        if (emojiUrl != null) {
            tags.add(listOf("emoji", emoji.removeSurrounding(":"), emojiUrl))
        }
        return createGiftWrap(
            senderPrivkey = senderPrivkey,
            senderPubkey = senderPubkey,
            recipientPubkey = recipientPubkey,
            message = emoji,
            rumorKind = 7,
            replyTags = tags
        )
    }

    /** Remote-signer variant of [createDmReaction]. */
    suspend fun createDmReactionRemote(
        signer: NostrSigner,
        recipientPubkeyHex: String,
        targetRumorId: String,
        originalSenderPubkey: String,
        emoji: String,
        emojiUrl: String? = null
    ): NostrEvent {
        val tags = mutableListOf(
            listOf("e", targetRumorId),
            listOf("p", originalSenderPubkey),
            listOf("k", "14")
        )
        if (emojiUrl != null) {
            tags.add(listOf("emoji", emoji.removeSurrounding(":"), emojiUrl))
        }
        return createGiftWrapRemote(
            signer = signer,
            recipientPubkeyHex = recipientPubkeyHex,
            message = emoji,
            rumorKind = 7,
            replyTags = tags
        )
    }

    private fun randomizeTimestamp(base: Long): Long {
        // 0 to 1 day in the past — NIP-17 spec allows up to 2 days, but keeping it to 1 day
        // ensures interop with clients (e.g. Amethyst) whose kind-1059 subscription may use
        // a `since` filter tighter than 2 days, which would silently drop our gift wraps.
        val oneDay = 24 * 60 * 60
        return base - random.nextInt(oneDay)
    }
}
