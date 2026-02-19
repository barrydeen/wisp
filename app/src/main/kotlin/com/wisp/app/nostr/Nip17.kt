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
        val content: String,
        val tags: List<List<String>>
    )

    /**
     * Create a gift-wrapped DM (kind 1059) from sender to recipient.
     * Implements the 3-layer NIP-17 scheme: rumor(14) -> seal(13) -> gift wrap(1059).
     */
    fun createGiftWrap(
        senderPrivkey: ByteArray,
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        message: String,
        replyTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val senderPubkeyHex = senderPubkey.toHex()
        val recipientPubkeyHex = recipientPubkey.toHex()

        // Layer 1: Build unsigned kind 14 rumor (no id, no sig)
        val rumorTags = mutableListOf<List<String>>()
        rumorTags.add(listOf("p", recipientPubkeyHex))
        rumorTags.addAll(replyTags)

        val now = System.currentTimeMillis() / 1000
        val rumorJson = buildJsonObject {
            put("kind", JsonPrimitive(14))
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
        val giftWrap = NostrEvent.create(
            privkey = throwaway.privkey,
            pubkey = throwaway.pubkey,
            kind = 1059,
            content = encryptedSeal,
            tags = listOf(listOf("p", recipientPubkeyHex)),
            createdAt = wrapTimestamp
        )

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
            if (kind != 14) return null

            val tags = rumorObj["tags"]?.jsonArray?.map { tagArr ->
                tagArr.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            Rumor(
                pubkey = rumorObj["pubkey"]?.jsonPrimitive?.content ?: seal.pubkey,
                createdAt = rumorObj["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: seal.created_at,
                content = rumorObj["content"]?.jsonPrimitive?.content ?: "",
                tags = tags
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun randomizeTimestamp(base: Long): Long {
        // +-2 days in seconds
        val twoDays = 2 * 24 * 60 * 60
        return base + random.nextInt(twoDays * 2) - twoDays
    }
}
