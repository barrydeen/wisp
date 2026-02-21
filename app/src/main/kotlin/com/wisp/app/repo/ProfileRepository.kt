package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val cache = LruCache<String, ProfileData>(2000)
    private val timestamps = LruCache<String, Long>(2000)

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent): ProfileData? {
        if (event.kind != 0) return null
        val existing = timestamps.get(event.pubkey)
        if (existing != null && event.created_at <= existing) return cache.get(event.pubkey)

        val profile = ProfileData.fromEvent(event) ?: return null
        cache.put(event.pubkey, profile)
        timestamps.put(event.pubkey, event.created_at)
        saveToPrefs(event.pubkey, profile, event.created_at)
        return profile
    }

    fun get(pubkey: String): ProfileData? {
        cache.get(pubkey)?.let { return it }
        return loadOneFromPrefs(pubkey)
    }

    fun has(pubkey: String): Boolean = get(pubkey) != null

    fun search(query: String, limit: Int = 10): List<ProfileData> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        val snapshot = cache.snapshot()
        return snapshot.values.filter { profile ->
            profile.name?.lowercase()?.contains(lowerQuery) == true ||
            profile.displayName?.lowercase()?.contains(lowerQuery) == true ||
            profile.nip05?.lowercase()?.contains(lowerQuery) == true
        }.take(limit)
    }

    private fun loadOneFromPrefs(pubkey: String): ProfileData? {
        val str = prefs.getString("p_$pubkey", null) ?: return null
        return try {
            val profile = json.decodeFromString<ProfileData>(str)
            val ts = prefs.getLong("p_ts_$pubkey", 0)
            cache.put(pubkey, profile)
            timestamps.put(pubkey, ts)
            profile
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToPrefs(pubkey: String, profile: ProfileData, timestamp: Long) {
        prefs.edit()
            .putString("p_$pubkey", json.encodeToString(profile))
            .putLong("p_ts_$pubkey", timestamp)
            .apply()
    }

    private fun loadFromPrefs() {
        val allKeys = prefs.all.keys
        val pubkeys = allKeys
            .filter { it.startsWith("p_") && !it.startsWith("p_ts_") }
            .map { it.removePrefix("p_") }

        for (pubkey in pubkeys) {
            try {
                val str = prefs.getString("p_$pubkey", null) ?: continue
                val ts = prefs.getLong("p_ts_$pubkey", 0)
                val profile = json.decodeFromString<ProfileData>(str)
                cache.put(pubkey, profile)
                timestamps.put(pubkey, ts)
            } catch (_: Exception) {}
        }
    }
}
