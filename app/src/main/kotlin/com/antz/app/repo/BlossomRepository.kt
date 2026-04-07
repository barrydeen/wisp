package com.antz.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.antz.app.nostr.Blossom
import com.antz.app.nostr.EncryptedMedia
import com.antz.app.nostr.NostrEvent
import com.antz.app.nostr.NostrSigner
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

    // Strong reference to prevent GC — syncs flow when prefs change from any instance
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "blossom_servers") _servers.value = loadServers()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private val httpClient
        get() = com.antz.app.relay.HttpClientFactory.createHttpClient(
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
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
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
        // nostr.build uses blossom.band for their Blossom endpoint — substitute transparently
        val serverList = loadServers().map { url ->
            if (url.trimEnd('/').equals("https://nostr.build", ignoreCase = true)) {
                "https://blossom.band"
            } else url
        }
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

    data class EncryptedUploadResult(
        val url: String,
        val keyHex: String,
        val nonceHex: String,
        val originalSha256Hex: String,
        val encryptedSha256Hex: String,
        val originalSize: Long
    )

    /**
     * Encrypt a file with AES-256-GCM and upload the encrypted blob to Blossom.
     * Always uses /upload (not /media) since media processing would corrupt the ciphertext.
     */
    suspend fun uploadEncryptedMedia(
        fileBytes: ByteArray,
        mimeType: String,
        signer: NostrSigner? = null
    ): EncryptedUploadResult = withContext(Dispatchers.IO) {
        val encrypted = EncryptedMedia.encryptFile(fileBytes)
        val sha256Hex = encrypted.encryptedSha256Hex
        val authHeader = if (signer != null) {
            Blossom.createUploadAuth(signer, sha256Hex)
        } else {
            val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
            Blossom.createUploadAuth(keypair.privkey, keypair.pubkey, sha256Hex)
        }
        val body = encrypted.encryptedBytes.toRequestBody("application/octet-stream".toMediaType())

        val serverList = loadServers().map { url ->
            if (url.trimEnd('/').equals("https://nostr.build", ignoreCase = true)) {
                "https://blossom.band"
            } else url
        }
        val candidates = (serverList + Blossom.DEFAULT_SERVER).distinct()

        var lastException: Exception? = null
        for (server in candidates) {
            try {
                val url = uploadToServerRaw(server, body, authHeader)
                return@withContext EncryptedUploadResult(
                    url = url,
                    keyHex = encrypted.keyHex,
                    nonceHex = encrypted.nonceHex,
                    originalSha256Hex = encrypted.originalSha256Hex,
                    encryptedSha256Hex = encrypted.encryptedSha256Hex,
                    originalSize = fileBytes.size.toLong()
                )
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Upload failed: no servers available")
    }

    /**
     * Upload directly to /upload endpoint only (no /media fallback).
     * Used for encrypted blobs where media processing would corrupt the data.
     */
    private fun uploadToServerRaw(
        server: String,
        body: okhttp3.RequestBody,
        authHeader: String
    ): String {
        val url = server.trimEnd('/') + "/upload"
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/octet-stream")
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
        throw Exception("Upload failed: ${response.code} ${response.message}")
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "antz_prefs_$pubkeyHex" else "antz_prefs"
    }
}
