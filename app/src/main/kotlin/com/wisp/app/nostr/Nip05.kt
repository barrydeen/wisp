package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object Nip05 {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify a NIP-05 identifier against a pubkey.
     * Parses "local@domain", fetches https://domain/.well-known/nostr.json?name=local,
     * and checks that names[local] matches the given pubkey hex.
     */
    suspend fun verify(identifier: String, pubkeyHex: String, httpClient: OkHttpClient): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val parts = identifier.split("@", limit = 2)
                if (parts.size != 2) return@withContext false

                val local = parts[0].ifEmpty { return@withContext false }
                val domain = parts[1].ifEmpty { return@withContext false }

                val url = "https://$domain/.well-known/nostr.json?name=$local"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) return@withContext false

                val body = response.body?.string() ?: return@withContext false
                val root = json.parseToJsonElement(body).jsonObject
                val names = root["names"]?.jsonObject ?: return@withContext false
                val registeredPubkey = names[local]?.jsonPrimitive?.content

                registeredPubkey.equals(pubkeyHex, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
}
