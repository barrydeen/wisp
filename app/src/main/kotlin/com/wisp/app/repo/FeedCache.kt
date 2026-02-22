package com.wisp.app.repo

import android.content.Context
import com.wisp.app.nostr.NostrEvent
import java.io.File

class FeedCache(private val context: Context, private val pubkeyHex: String?) {
    private val cacheFile: File
        get() = File(context.filesDir, "feed_cache_${pubkeyHex ?: "anon"}.jsonl")

    fun load(): List<NostrEvent> {
        val file = cacheFile
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { NostrEvent.fromJson(line) } catch (_: Exception) { null }
                }
                .sortedByDescending { it.created_at }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(events: List<NostrEvent>) {
        val file = cacheFile
        val tmp = File(file.parent, "${file.name}.tmp")
        try {
            val top = events.sortedByDescending { it.created_at }.take(200)
            tmp.bufferedWriter().use { writer ->
                for (event in top) {
                    writer.write(event.toJson())
                    writer.newLine()
                }
            }
            tmp.renameTo(file)
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    fun clear() {
        cacheFile.delete()
    }

    fun exists(): Boolean = cacheFile.exists() && cacheFile.length() > 0
}
