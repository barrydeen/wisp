package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig

/**
 * Lightweight persistent store of relay hints for any pubkey.
 * Accumulates hints from p-tag relay URLs and author provenance (which relay
 * delivered an event). Used as a fallback tier when no kind 10002 relay list
 * is available for a given pubkey.
 */
class RelayHintStore(context: Context) {
    companion object {
        private const val TAG = "RelayHintStore"
        private const val PREFS_NAME = "wisp_relay_hints"
        private const val MAX_HINTS_PER_PUBKEY = 5
        private const val MAX_PERSISTED = 2000
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = LruCache<String, MutableSet<String>>(5000)
    @Volatile
    private var dirty = false

    init {
        loadFromPrefs()
    }

    /** Add a single relay hint for a pubkey. */
    fun addHint(pubkey: String, relayUrl: String) {
        if (relayUrl.isBlank() || !RelayConfig.isAcceptableUrl(relayUrl)) return
        @Suppress("NAME_SHADOWING")
        val relayUrl = relayUrl.trimEnd('/')
        val hints = cache.get(pubkey)
            ?: mutableSetOf<String>().also { cache.put(pubkey, it) }
        if (hints.size >= MAX_HINTS_PER_PUBKEY && relayUrl !in hints) return
        if (hints.add(relayUrl)) dirty = true
    }

    /** Record that an author publishes on this relay (event was delivered from it). */
    fun addAuthorRelay(pubkey: String, relayUrl: String) = addHint(pubkey, relayUrl)

    /** Scan p-tags for relay URL hints at index 2. */
    fun extractHintsFromTags(event: NostrEvent) {
        for (tag in event.tags) {
            if (tag.size >= 3 && tag[0] == "p") {
                val url = tag[2]
                if (RelayConfig.isAcceptableUrl(url)) {
                    addHint(tag[1], url)
                }
            }
        }
    }

    /** Return accumulated hints for a pubkey. */
    fun getHints(pubkey: String): Set<String> =
        cache.get(pubkey)?.toSet() ?: emptySet()

    /** Batch-persist dirty hints to SharedPreferences. Call from periodic cleanup loop. */
    fun flush() {
        if (!dirty) return
        dirty = false
        val snapshot = cache.snapshot()
        // Cap persisted entries to avoid unbounded growth
        val entries = if (snapshot.size > MAX_PERSISTED) {
            snapshot.entries.take(MAX_PERSISTED)
        } else {
            snapshot.entries
        }
        val serialized = entries.joinToString("\n") { (pubkey, urls) ->
            "$pubkey\t${urls.joinToString(",")}"
        }
        prefs.edit().putString("hints", serialized).apply()
        Log.d(TAG, "flush(): persisted ${entries.size} entries")
    }

    /** Clear all hints (e.g. on logout). */
    fun clear() {
        cache.evictAll()
        dirty = false
        prefs.edit().clear().apply()
    }

    private fun loadFromPrefs() {
        val data = prefs.getString("hints", null) ?: return
        if (data.isBlank()) return
        var count = 0
        for (line in data.split("\n")) {
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) continue
            val pubkey = parts[0]
            val urls = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
            if (urls.isEmpty()) continue
            cache.put(pubkey, urls)
            count++
        }
        if (count > 0) Log.d(TAG, "Loaded $count hint entries from cache")
    }
}
