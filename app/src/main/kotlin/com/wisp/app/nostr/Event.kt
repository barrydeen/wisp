package com.wisp.app.nostr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.Immutable
import java.security.MessageDigest

@Immutable
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @Serializable(with = LongAsStringSerializer::class)
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun create(
            privkey: ByteArray,
            pubkey: ByteArray,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Long = System.currentTimeMillis() / 1000
        ): NostrEvent {
            val pubkeyHex = pubkey.toHex()
            val id = computeId(pubkeyHex, createdAt, kind, tags, content)
            val idBytes = id.hexToByteArray()
            val sig = Keys.sign(privkey, idBytes).toHex()
            return NostrEvent(
                id = id,
                pubkey = pubkeyHex,
                created_at = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sig
            )
        }

        fun computeId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            val tagsJson = buildJsonArray {
                for (tag in tags) {
                    add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
                }
            }
            val serialized = buildString(512) {
                append("[0,\"")
                append(pubkey)
                append("\",")
                append(createdAt)
                append(',')
                append(kind)
                append(',')
                append(tagsJson)
                append(",")
                // JSON-escape the content string
                val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                append('"')
                append(escaped)
                append('"')
                append(']')
            }
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(serialized.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun createUnsigned(
            pubkeyHex: String,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Long = System.currentTimeMillis() / 1000
        ): NostrEvent {
            val id = computeId(pubkeyHex, createdAt, kind, tags, content)
            return NostrEvent(
                id = id,
                pubkey = pubkeyHex,
                created_at = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = ""
            )
        }

        fun fromJson(jsonStr: String): NostrEvent = json.decodeFromString(jsonStr)

        fun fromJsonArray(array: JsonArray): NostrEvent {
            return NostrEvent(
                id = array.jsonArray[0].let {
                    (it as? JsonPrimitive)?.content ?: throw IllegalArgumentException("Missing id")
                },
                pubkey = (array.jsonArray[1] as JsonPrimitive).content,
                created_at = (array.jsonArray[2] as JsonPrimitive).content.toLong(),
                kind = (array.jsonArray[3] as JsonPrimitive).content.toInt(),
                tags = array.jsonArray[4].jsonArray.map { tagArr ->
                    tagArr.jsonArray.map { it.jsonPrimitive.content }
                },
                content = (array.jsonArray[5] as JsonPrimitive).content,
                sig = (array.jsonArray[6] as JsonPrimitive).content
            )
        }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun withSignature(sig: String): NostrEvent = copy(sig = sig)
}

private object LongAsStringSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LongAsString", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long = decoder.decodeLong()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
