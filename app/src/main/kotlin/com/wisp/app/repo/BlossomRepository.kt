package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class BlossomRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val keyRepo = KeyRepository(context)

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<String>> = _servers

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != Blossom.KIND_SERVER_LIST) return
        val urls = Blossom.parseServerList(event)
        saveBlossomServers(urls)
    }

    fun saveBlossomServers(urls: List<String>) {
        val list = urls.ifEmpty { listOf(Blossom.DEFAULT_SERVER) }
        prefs.edit().putString("blossom_servers", json.encodeToString(list)).apply()
        _servers.value = list
    }

    fun clear() {
        _servers.value = listOf(Blossom.DEFAULT_SERVER)
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        _servers.value = loadServers()
    }

    private fun loadServers(): List<String> {
        val str = prefs.getString("blossom_servers", null) ?: return listOf(Blossom.DEFAULT_SERVER)
        return try {
            val list = json.decodeFromString<List<String>>(str)
            list.ifEmpty { listOf(Blossom.DEFAULT_SERVER) }
        } catch (_: Exception) {
            listOf(Blossom.DEFAULT_SERVER)
        }
    }

    suspend fun uploadMedia(
        fileBytes: ByteArray,
        mimeType: String,
        ext: String
    ): String = withContext(Dispatchers.IO) {
        val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
        val sha256Hex = Blossom.sha256Hex(fileBytes)
        val authHeader = Blossom.createUploadAuth(keypair.privkey, keypair.pubkey, sha256Hex)
        val mediaType = mimeType.toMediaType()
        val body = fileBytes.toRequestBody(mediaType)

        val serverList = _servers.value
        var lastException: Exception? = null

        for (server in serverList) {
            // Try BUD-05 /media first (strips EXIF), fall back to /upload
            for (path in listOf("/media", "/upload")) {
                try {
                    val url = server.trimEnd('/') + path
                    val request = Request.Builder()
                        .url(url)
                        .put(body)
                        .header("Authorization", authHeader)
                        .header("Content-Type", mimeType)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                            ?: throw Exception("Empty response")
                        val responseJson = json.parseToJsonElement(responseBody)
                        val urlField = (responseJson as? kotlinx.serialization.json.JsonObject)
                            ?.get("url")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            ?: throw Exception("No url in response")
                        return@withContext urlField
                    }
                    // If /media returns 404, try /upload
                    if (response.code == 404 && path == "/media") continue
                    lastException = Exception("Upload failed: ${response.code} ${response.message}")
                } catch (e: Exception) {
                    lastException = e
                    if (path == "/media") continue
                }
            }
        }
        throw lastException ?: Exception("Upload failed")
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_prefs_$pubkeyHex" else "wisp_prefs"
    }
}
