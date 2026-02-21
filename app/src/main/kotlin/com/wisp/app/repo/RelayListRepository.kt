package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RelayListRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_relay_lists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // pubkey -> parsed relay list
    private val cache = LruCache<String, List<RelayConfig>>(5000)
    // pubkey -> event timestamp
    private val timestamps = LruCache<String, Long>(5000)

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != 10002) return
        val existing = timestamps.get(event.pubkey)
        if (existing != null && event.created_at <= existing) return

        val relays = Nip65.parseRelayList(event)
        if (relays.isEmpty()) return

        cache.put(event.pubkey, relays)
        timestamps.put(event.pubkey, event.created_at)
        saveToPrefs(event.pubkey, relays, event.created_at)
    }

    fun getWriteRelays(pubkey: String): List<String>? {
        val relays = cache.get(pubkey) ?: return null
        return relays.filter { it.write }.map { it.url }.ifEmpty { null }
    }

    fun getReadRelays(pubkey: String): List<String>? {
        val relays = cache.get(pubkey) ?: return null
        return relays.filter { it.read }.map { it.url }.ifEmpty { null }
    }

    fun hasRelayList(pubkey: String): Boolean = cache.get(pubkey) != null

    fun getMissingPubkeys(pubkeys: List<String>): List<String> =
        pubkeys.filter { cache.get(it) == null }

    private fun saveToPrefs(pubkey: String, relays: List<RelayConfig>, timestamp: Long) {
        val serializable = relays.map { SerializableRelay(it.url, it.read, it.write) }
        prefs.edit()
            .putString("rl_$pubkey", json.encodeToString(serializable))
            .putLong("rl_ts_$pubkey", timestamp)
            .apply()
    }

    private fun loadFromPrefs() {
        val allKeys = prefs.all.keys
        val pubkeys = allKeys
            .filter { it.startsWith("rl_") && !it.startsWith("rl_ts_") }
            .map { it.removePrefix("rl_") }

        for (pubkey in pubkeys) {
            try {
                val str = prefs.getString("rl_$pubkey", null) ?: continue
                val ts = prefs.getLong("rl_ts_$pubkey", 0)
                val serializable = json.decodeFromString<List<SerializableRelay>>(str)
                val relays = serializable.map { RelayConfig(it.url, it.read, it.write) }
                cache.put(pubkey, relays)
                timestamps.put(pubkey, ts)
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class SerializableRelay(
        val url: String,
        val read: Boolean = true,
        val write: Boolean = true
    )
}
