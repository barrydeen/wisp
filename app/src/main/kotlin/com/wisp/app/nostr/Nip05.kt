package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

enum class Nip05Result {
    /** Pubkey matches the NIP-05 record. */
    VERIFIED,
    /** Server responded but pubkey does not match — possible impersonation. */
    MISMATCH,
    /** Could not reach server or parse response — temporary/unknown failure. */
    ERROR
}

object Nip05 {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify a NIP-05 identifier against a pubkey.
     * Parses "local@domain", fetches https://domain/.well-known/nostr.json?name=local,
     * and checks that names[local] matches the given pubkey hex.
     *
     * Returns [Nip05Result.VERIFIED] on match, [Nip05Result.MISMATCH] when the server
     * responds with a different pubkey, or [Nip05Result.ERROR] on network/parse failures.
     */
    suspend fun verify(identifier: String, pubkeyHex: String, httpClient: OkHttpClient): Nip05Result =
        withContext(Dispatchers.IO) {
            try {
                val parts = identifier.split("@", limit = 2)
                if (parts.size != 2) return@withContext Nip05Result.ERROR

                val local = parts[0].ifEmpty { return@withContext Nip05Result.ERROR }
                val domain = parts[1].ifEmpty { return@withContext Nip05Result.ERROR }

                val url = "https://$domain/.well-known/nostr.json?name=$local"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) return@withContext Nip05Result.ERROR

                val body = response.body?.string() ?: return@withContext Nip05Result.ERROR
                val root = json.parseToJsonElement(body).jsonObject
                val names = root["names"]?.jsonObject ?: return@withContext Nip05Result.MISMATCH
                val registeredPubkey = names[local]?.jsonPrimitive?.content
                    ?: return@withContext Nip05Result.MISMATCH

                if (registeredPubkey.equals(pubkeyHex, ignoreCase = true))
                    Nip05Result.VERIFIED
                else
                    Nip05Result.MISMATCH
            } catch (_: Exception) {
                Nip05Result.ERROR
            }
        }
}
