package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object Nip37 {
    const val KIND_DRAFT = 31234
    private val json = Json { ignoreUnknownKeys = true }

    data class Draft(
        val dTag: String,
        val kind: Int,
        val content: String,
        val tags: List<List<String>>,
        val createdAt: Long,
        val wrapperEventId: String = ""
    )

    fun newDraftId(): String = UUID.randomUUID().toString()

    fun buildDraftTags(dTag: String, innerKind: Int): List<List<String>> {
        return listOf(
            listOf("d", dTag),
            listOf("k", innerKind.toString())
        )
    }

    fun serializeDraftContent(
        pubkeyHex: String,
        innerKind: Int,
        content: String,
        tags: List<List<String>>
    ): String {
        return buildJsonObject {
            put("kind", JsonPrimitive(innerKind))
            put("pubkey", JsonPrimitive(pubkeyHex))
            put("created_at", JsonPrimitive(System.currentTimeMillis() / 1000))
            put("tags", buildJsonArray {
                for (tag in tags) {
                    add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
                }
            })
            put("content", JsonPrimitive(content))
        }.toString()
    }

    fun parseDraft(wrapperEvent: NostrEvent, decryptedJson: String): Draft? {
        return try {
            val obj = json.parseToJsonElement(decryptedJson).jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val content = obj["content"]?.jsonPrimitive?.content ?: ""
            val tags = obj["tags"]?.jsonArray?.map { tagArr ->
                tagArr.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()
            val createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: wrapperEvent.created_at

            val dTag = wrapperEvent.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                ?: return null

            Draft(
                dTag = dTag,
                kind = kind,
                content = content,
                tags = tags,
                createdAt = createdAt,
                wrapperEventId = wrapperEvent.id
            )
        } catch (_: Exception) {
            null
        }
    }
}
