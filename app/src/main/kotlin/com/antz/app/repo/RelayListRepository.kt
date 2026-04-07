package com.antz.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.antz.app.nostr.Nip51
import com.antz.app.nostr.Nip65
import com.antz.app.nostr.NostrEvent
import com.antz.app.relay.RelayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RelayListRepository(context: Context) {
    companion object {
        /** Re-fetch all relay lists from the network after this much time has passed. */
        const val FRESHNESS_MS = 6L * 60 * 60 * 1000  // 6 hours
        private const val SYNC_TIME_KEY = "sync_time"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("antz_relay_lists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // pubkey -> parsed relay list
    private val cache = LruCache<String, List<RelayConfig>>(5000)
    // pubkey -> event timestamp
    private val timestamps = LruCache<String, Long>(5000)

    // DM relay cache (kind 10050): pubkey -> list of relay URLs
    private val dmRelayCache = LruCache<String, List<String>>(5000)
    private val dmTimestamps = LruCache<String, Long>(5000)

    init {
        loadFromPrefs()
        loadDmRelaysFromPrefs()
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

    fun updateDmRelaysFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_DM_RELAYS) return
        val existing = dmTimestamps.get(event.pubkey)
        if (existing != null && event.created_at <= existing) return

        val relays = Nip51.parseRelaySet(event)
        if (relays.isEmpty()) return

        dmRelayCache.put(event.pubkey, relays)
        dmTimestamps.put(event.pubkey, event.created_at)
        saveDmRelaysToPrefs(event.pubkey, relays, event.created_at)
    }

    fun getDmRelays(pubkey: String): List<String>? =
        dmRelayCache.get(pubkey)?.ifEmpty { null }

    fun hasDmRelays(pubkey: String): Boolean = dmRelayCache.get(pubkey) != null

    fun getMissingPubkeys(pubkeys: List<String>): List<String> =
        pubkeys.filter { cache.get(it) == null }

    /** True if a full relay-list sync completed within [FRESHNESS_MS]. */
    fun isSyncFresh(): Boolean {
        val last = prefs.getLong(SYNC_TIME_KEY, 0L)
        return last > 0L && System.currentTimeMillis() - last < FRESHNESS_MS
    }

    /** Record that a full relay-list network fetch just completed. */
    fun markSyncComplete() {
        prefs.edit().putLong(SYNC_TIME_KEY, System.currentTimeMillis()).apply()
    }

    fun clear() {
        cache.evictAll()
        timestamps.evictAll()
        dmRelayCache.evictAll()
        dmTimestamps.evictAll()
        prefs.edit().clear().apply()
    }

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

    private fun saveDmRelaysToPrefs(pubkey: String, relays: List<String>, timestamp: Long) {
        prefs.edit()
            .putString("dm_$pubkey", json.encodeToString(relays))
            .putLong("dm_ts_$pubkey", timestamp)
            .apply()
    }

    private fun loadDmRelaysFromPrefs() {
        val allKeys = prefs.all.keys
        val pubkeys = allKeys
            .filter { it.startsWith("dm_") && !it.startsWith("dm_ts_") }
            .map { it.removePrefix("dm_") }

        for (pubkey in pubkeys) {
            try {
                val str = prefs.getString("dm_$pubkey", null) ?: continue
                val ts = prefs.getLong("dm_ts_$pubkey", 0)
                val relays = json.decodeFromString<List<String>>(str)
                dmRelayCache.put(pubkey, relays)
                dmTimestamps.put(pubkey, ts)
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
