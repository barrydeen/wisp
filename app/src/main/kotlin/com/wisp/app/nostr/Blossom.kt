package com.wisp.app.nostr

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

object Blossom {
    const val KIND_SERVER_LIST = 10063
    const val KIND_AUTH = 24242
    const val DEFAULT_SERVER = "https://blossom.primal.net"

    /**
     * Extract Blossom server URLs from the events tags and returns them as a list.
     *
     * The event must be a Blossom server list event (BUD-03)(10063). Tags that are not defined by
     * BUD-03 or are invalid (i.e. invalid URL) are ignored. The returned list is deduplicated and
     * ordered by first occurrence in the event.
     *
     * @throws IllegalArgumentException if [event] is not kind [KIND_SERVER_LIST]
     */
    fun parseServerList(event: NostrEvent): List<String> {
        require(event.kind == KIND_SERVER_LIST) {
            "Expected kind $KIND_SERVER_LIST event, got kind ${event.kind}"
        }
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && tag[0] == "server") {
                val url = tag[1].toHttpUrlOrNull()
                url?.toString()
            }
            else {
                null
            }
        }.distinct()
    }

    fun buildServerListTags(urls: List<String>): List<List<String>> {
        return urls.map { listOf("server", it) }
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHex()
    }

    fun createUploadAuth(
        privkey: ByteArray,
        pubkey: ByteArray,
        sha256Hex: String
    ): String {
        val tags = uploadAuthTags(sha256Hex)
        val event = NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_AUTH,
            content = "Upload",
            tags = tags
        )
        return encodeAuthHeader(event)
    }

    suspend fun createUploadAuth(
        signer: NostrSigner,
        sha256Hex: String
    ): String {
        val tags = uploadAuthTags(sha256Hex)
        val event = signer.signEvent(kind = KIND_AUTH, content = "Upload", tags = tags)
        return encodeAuthHeader(event)
    }

    private fun uploadAuthTags(sha256Hex: String): List<List<String>> {
        val expiration = (System.currentTimeMillis() / 1000 + 300).toString()
        return listOf(
            listOf("t", "upload"),
            listOf("x", sha256Hex),
            listOf("expiration", expiration)
        )
    }

    private fun encodeAuthHeader(event: NostrEvent): String {
        val json = event.toJson()
        val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Nostr $base64"
    }
}
