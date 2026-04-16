package com.wisp.app.repo

import android.util.LruCache

class SpamAuthorCache(maxEntries: Int = 2000) {
    private val cache = LruCache<String, CacheEntry>(maxEntries)

    data class CacheEntry(val score: Float, val noteCount: Int)

    fun get(pubkey: String, currentNoteCount: Int = 0): Float? {
        val entry = cache.get(pubkey) ?: return null
        if (entry.noteCount < RESCORE_THRESHOLD && currentNoteCount >= RESCORE_THRESHOLD) {
            return null
        }
        return entry.score
    }

    fun put(pubkey: String, score: Float, noteCount: Int) {
        cache.put(pubkey, CacheEntry(score, noteCount))
    }

    companion object {
        private const val RESCORE_THRESHOLD = 5
    }
}
