package com.wisp.app.nostr

import android.util.Base64
import java.security.MessageDigest

object Blossom {
    const val KIND_SERVER_LIST = 10063
    const val KIND_AUTH = 24242
    const val DEFAULT_SERVER = "https://blossom.primal.net"

    fun parseServerList(event: NostrEvent): List<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && tag[0] == "server") tag[1] else null
        }
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
