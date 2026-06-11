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
    /**
     * CLINK offer (`noffer1…`) advertised in kind-0. Read tolerantly from
     * `clink_offer`, `noffer`, or `offer` for cross-client compatibility.
     * Spec: https://github.com/shocknet/CLINK/blob/main/specs/clink-offers.md
     */
    val clinkOffer: String? = null,
    val updatedAt: Long
) {
    val displayString: String
        get() = displayName.takeUnlessBlank()
            ?: name.takeUnlessBlank()
            ?: pubkey.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" }

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
                    clinkOffer = (
                        obj["clink_offer"]?.jsonPrimitive?.content
                            ?: obj["noffer"]?.jsonPrimitive?.content
                            ?: obj["offer"]?.jsonPrimitive?.content
                        ).takeUnlessBlank(),
                    updatedAt = event.created_at
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
