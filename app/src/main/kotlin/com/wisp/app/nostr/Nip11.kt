package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class RelayInfo(
    val name: String?,
    val icon: String?,
    val description: String?,
    val pubkey: String? = null,
    val contact: String? = null,
    val software: String? = null,
    val version: String? = null,
    val supportedNips: List<Int> = emptyList(),
    val paymentRequired: Boolean = false,
    val authRequired: Boolean = false,
    val restrictedWrites: Boolean = false,
    val maxMessageLength: Int? = null,
    val maxSubscriptions: Int? = null,
    val maxContentLength: Int? = null,
    val createdAtLowerLimit: Long? = null,
    val createdAtUpperLimit: Long? = null
) {
    fun isOpenPublicRelay(): Boolean =
        !paymentRequired && !authRequired && !restrictedWrites
}

object Nip11 {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient
        get() = com.wisp.app.relay.HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 10
        )

    suspend fun fetchRelayInfo(url: String, httpClient: OkHttpClient? = null): RelayInfo? {
        val client = httpClient ?: this.httpClient
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = url
                    .replace("wss://", "https://")
                    .replace("ws://", "http://")
                    .trimEnd('/')
                val request = Request.Builder()
                    .url(httpUrl)
                    .addHeader("Accept", "application/nostr+json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                val limitation = try { obj["limitation"]?.jsonObject } catch (_: Exception) { null }
                val nips = try {
                    obj["supported_nips"]?.jsonArray?.mapNotNull {
                        try { it.jsonPrimitive.int } catch (_: Exception) { null }
                    } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                RelayInfo(
                    name = obj["name"]?.jsonPrimitive?.content,
                    icon = obj["icon"]?.jsonPrimitive?.content,
                    description = obj["description"]?.jsonPrimitive?.content,
                    pubkey = try { obj["pubkey"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                    contact = try { obj["contact"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                    software = try { obj["software"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                    version = try { obj["version"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                    supportedNips = nips,
                    paymentRequired = try { limitation?.get("payment_required")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false,
                    authRequired = try { limitation?.get("auth_required")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false,
                    restrictedWrites = try { limitation?.get("restricted_writes")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false,
                    maxMessageLength = try { limitation?.get("max_message_length")?.jsonPrimitive?.int } catch (_: Exception) { null },
                    maxSubscriptions = try { limitation?.get("max_subscriptions")?.jsonPrimitive?.int } catch (_: Exception) { null },
                    maxContentLength = try { limitation?.get("max_content_length")?.jsonPrimitive?.int } catch (_: Exception) { null },
                    createdAtLowerLimit = try { limitation?.get("created_at_lower_limit")?.jsonPrimitive?.content?.toLong() } catch (_: Exception) { null },
                    createdAtUpperLimit = try { limitation?.get("created_at_upper_limit")?.jsonPrimitive?.content?.toLong() } catch (_: Exception) { null }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
