package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RelayInfo(
    val name: String?,
    val icon: String?,
    val description: String?,
    val paymentRequired: Boolean = false,
    val authRequired: Boolean = false,
    val restrictedWrites: Boolean = false
) {
    fun isOpenPublicRelay(): Boolean =
        !paymentRequired && !authRequired && !restrictedWrites
}

object Nip11 {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
                RelayInfo(
                    name = obj["name"]?.jsonPrimitive?.content,
                    icon = obj["icon"]?.jsonPrimitive?.content,
                    description = obj["description"]?.jsonPrimitive?.content,
                    paymentRequired = try { limitation?.get("payment_required")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false,
                    authRequired = try { limitation?.get("auth_required")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false,
                    restrictedWrites = try { limitation?.get("restricted_writes")?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
