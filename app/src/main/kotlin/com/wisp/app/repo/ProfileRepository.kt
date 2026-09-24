package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileRepository internal constructor(prefs: SharedPreferences, val avatarDir: File) : AutoCloseable {
    constructor(context: Context) : this(
        context.getSharedPreferences("wisp_profiles", Context.MODE_PRIVATE),
        File(context.filesDir, "avatars").also { it.mkdirs() }
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = PersistedMetadataCache<ProfileData>(
        prefs, "p_",
        encode = { json.encodeToString(it) },
        decode = { json.decodeFromString<ProfileData>(it) },
        onFailure = { Log.w("ProfileRepository", "Metadata write failed", it) }
    )

    fun updateFromEvent(event: NostrEvent): ProfileData? {
        if (event.kind != 0) return null
        val profile = ProfileData.fromEvent(event) ?: return null
        return if (cache.update(event.pubkey, profile, event.created_at)) profile else null
    }

    fun get(pubkey: String): ProfileData? = cache.get(pubkey)

    fun has(pubkey: String): Boolean = get(pubkey) != null

    fun search(query: String, limit: Int = 10): List<ProfileData> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return cache.values().filter { profile ->
            profile.name?.lowercase()?.contains(lowerQuery) == true ||
            profile.displayName?.lowercase()?.contains(lowerQuery) == true ||
            profile.nip05?.lowercase()?.contains(lowerQuery) == true
        }.take(limit)
    }

    suspend fun flush() = cache.flush()

    override fun close() = cache.close()

    suspend fun shutdown() = cache.shutdown()

    /**
     * Returns a local File for the user's cached avatar, or null if not cached.
     */
    fun getLocalAvatar(pubkey: String): File? {
        val file = File(avatarDir, pubkey)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Downloads the avatar from [url] and saves it to local storage.
     * Call from a coroutine — runs on IO dispatcher.
     */
    suspend fun cacheAvatar(pubkey: String, url: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(avatarDir, pubkey)
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.getInputStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("ProfileRepository", "Cached avatar for ${pubkey.take(8)} (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.e("ProfileRepository", "Failed to cache avatar for ${pubkey.take(8)}", e)
            }
        }
    }

}
