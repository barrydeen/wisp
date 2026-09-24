package com.wisp.app.repo

import android.content.SharedPreferences
import com.wisp.app.db.BatchWriter

/** A bounded decoded cache with write-behind records kept separately until committed. */
internal class PersistedMetadataCache<T>(
    private val prefs: SharedPreferences,
    private val prefix: String,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
    private val onFailure: (Throwable) -> Unit,
    private val capacity: Int = 2000
) : AutoCloseable {
    private data class Record<T>(val value: T, val timestamp: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Record<T>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Record<T>>): Boolean =
            size > capacity
    }
    private val dirty = LinkedHashMap<String, Record<T>>()
    private var closed = false
    private val timestampPrefix = "${prefix}ts_"
    private val writer = BatchWriter<Unit>(onFailure = onFailure) {
        val snapshot = synchronized(lock) { dirty.toMap() }
        if (snapshot.isNotEmpty()) {
            val editor = prefs.edit()
            for ((key, record) in snapshot) {
                editor.putString("$prefix$key", encode(record.value))
                editor.putLong("$timestampPrefix$key", record.timestamp)
            }
            check(editor.commit()) { "Failed to persist metadata for $prefix" }
            synchronized(lock) {
                for ((key, record) in snapshot) {
                    if (dirty[key] === record) dirty.remove(key)
                }
            }
        }
    }

    init {
        // SharedPreferences exposes only an all-keys snapshot, but decode at most the cache budget.
        prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) && !it.startsWith(timestampPrefix) }
            .take(capacity)
            .forEach { get(it.removePrefix(prefix)) }
    }

    fun get(key: String): T? = synchronized(lock) {
        dirty[key]?.let { return@synchronized it.value }
        cache[key]?.let { return@synchronized it.value }
        val encoded = prefs.getString("$prefix$key", null) ?: return@synchronized null
        try {
            val value = decode(encoded)
            cache[key] = Record(value, prefs.getLong("$timestampPrefix$key", 0))
            value
        } catch (_: Exception) {
            null
        }
    }

    /** Consult disk metadata even after LRU eviction; pending records take precedence over disk. */
    fun update(key: String, value: T, timestamp: Long): Boolean = synchronized(lock) {
        check(!closed) { "Metadata cache is closed" }
        val existing = dirty[key]?.timestamp ?: cache[key]?.timestamp
            ?: if (prefs.contains("$timestampPrefix$key")) prefs.getLong("$timestampPrefix$key", 0) else null
        if (existing != null && timestamp <= existing) return@synchronized false
        val record = Record(value, timestamp)
        cache[key] = record
        dirty[key] = record
        writer.enqueue(Unit)
        true
    }

    fun values(): List<T> = synchronized(lock) { cache.values.map { it.value } }

    fun clearMemory() = synchronized(lock) { cache.clear() }

    suspend fun flush() = writer.flush()

    override fun close() = synchronized(lock) {
        closed = true
        writer.close()
    }

    suspend fun shutdown() {
        close()
        writer.shutdown()
    }
}
