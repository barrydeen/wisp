package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RelayListRepository internal constructor(private val prefs: SharedPreferences) : AutoCloseable {
    constructor(context: Context) : this(context.getSharedPreferences("wisp_relay_lists", Context.MODE_PRIVATE))

    companion object {
        /** Re-fetch all relay lists from the network after this much time has passed. */
        const val FRESHNESS_MS = 6L * 60 * 60 * 1000  // 6 hours
        private const val SYNC_TIME_KEY = "sync_time"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // pubkey -> parsed relay list
    private val cache = PersistedMetadataCache<List<RelayConfig>>(
        prefs, "rl_",
        encode = { relays -> json.encodeToString(relays.map { SerializableRelay(it.url, it.read, it.write) }) },
        decode = { encoded -> json.decodeFromString<List<SerializableRelay>>(encoded).map { RelayConfig(it.url, it.read, it.write) } },
        onFailure = { Log.w("RelayListRepository", "Relay metadata write failed", it) }
    )

    // DM relay cache (kind 10050): pubkey -> list of relay URLs
    private val dmRelayCache = PersistedMetadataCache<List<String>>(
        prefs, "dm_",
        encode = { json.encodeToString(it) },
        decode = { json.decodeFromString<List<String>>(it) },
        onFailure = { Log.w("RelayListRepository", "DM relay metadata write failed", it) }
    )

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != 10002) return
        val relays = Nip65.parseRelayList(event)
        if (relays.isEmpty()) return

        cache.update(event.pubkey, relays, event.created_at)
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
        val relays = Nip51.parseRelaySet(event)
        if (relays.isEmpty()) return

        dmRelayCache.update(event.pubkey, relays, event.created_at)
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

    /** Account switches evict decoded values, not the shared public relay directory. */
    fun clear() = clearMemory()

    fun clearMemory() {
        cache.clearMemory()
        dmRelayCache.clearMemory()
        // Sync freshness describes the previous account's follow set, unlike relay metadata.
        prefs.edit().remove(SYNC_TIME_KEY).apply()
    }

    suspend fun flush() {
        cache.flush()
        dmRelayCache.flush()
    }

    override fun close() {
        cache.close()
        dmRelayCache.close()
    }

    suspend fun shutdown() {
        close()
        try {
            cache.shutdown()
        } finally {
            dmRelayCache.shutdown()
        }
    }

    @Serializable
    private data class SerializableRelay(
        val url: String,
        val read: Boolean = true,
        val write: Boolean = true
    )
}
