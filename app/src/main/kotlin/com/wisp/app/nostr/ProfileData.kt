package com.wisp.app.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun String?.takeUnlessBlank(): String? = this?.ifBlank { null }

@Serializable
data class ProfileData(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,
    val banner: String?,
    val nip05: String?,
    val lud16: String?,
    val updatedAt: Long
) {
    val displayString: String
        get() = displayName.takeUnlessBlank()
            ?: name.takeUnlessBlank()
            ?: "${pubkey.take(8)}...${pubkey.takeLast(4)}"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromEvent(event: NostrEvent): ProfileData? {
            if (event.kind != 0) return null
            return try {
                val obj = json.parseToJsonElement(event.content).jsonObject
                ProfileData(
                    pubkey = event.pubkey,
                    name = obj["name"]?.jsonPrimitive?.content,
                    displayName = obj["display_name"]?.jsonPrimitive?.content,
                    about = obj["about"]?.jsonPrimitive?.content,
                    picture = obj["picture"]?.jsonPrimitive?.content,
                    banner = obj["banner"]?.jsonPrimitive?.content,
                    nip05 = obj["nip05"]?.jsonPrimitive?.content,
                    lud16 = obj["lud16"]?.jsonPrimitive?.content,
                    updatedAt = event.created_at
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
