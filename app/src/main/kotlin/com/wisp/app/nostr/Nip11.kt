package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RelayInfo(
    val name: String?,
    val icon: String?,
    val description: String?
)

object Nip11 {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchRelayInfo(url: String): RelayInfo? {
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
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                RelayInfo(
                    name = obj["name"]?.jsonPrimitive?.content,
                    icon = obj["icon"]?.jsonPrimitive?.content,
                    description = obj["description"]?.jsonPrimitive?.content
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
