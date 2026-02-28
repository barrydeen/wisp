package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BlossomRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val keyRepo = KeyRepository(context)

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<String>> = _servers

    private val httpClient
        get() = com.wisp.app.relay.HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 60
        )

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
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        _servers.value = loadServers()
    }

    fun refreshFromPrefs() {
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
        ext: String,
        signer: NostrSigner? = null
    ): String = withContext(Dispatchers.IO) {
        val sha256Hex = Blossom.sha256Hex(fileBytes)
        val authHeader = if (signer != null) {
            Blossom.createUploadAuth(signer, sha256Hex)
        } else {
            val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
            Blossom.createUploadAuth(keypair.privkey, keypair.pubkey, sha256Hex)
        }
        val mediaType = mimeType.toMediaType()
        val body = fileBytes.toRequestBody(mediaType)

        // Build ordered server list with Primal as final fallback
        val serverList = loadServers()
        val candidates = (serverList + Blossom.DEFAULT_SERVER).distinct()

        var lastException: Exception? = null
        for (server in candidates) {
            try {
                return@withContext uploadToServer(server, body, authHeader, mimeType)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Upload failed: no servers available")
    }

    private fun uploadToServer(
        server: String,
        body: okhttp3.RequestBody,
        authHeader: String,
        mimeType: String
    ): String {
        // Try BUD-05 /media first (strips EXIF), fall back to /upload on 404
        for (path in listOf("/media", "/upload")) {
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
                return urlField
            }
            if (response.code == 404 && path == "/media") continue
            throw Exception("Upload failed: ${response.code} ${response.message}")
        }
        throw Exception("Upload failed")
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_prefs_$pubkeyHex" else "wisp_prefs"
    }
}
