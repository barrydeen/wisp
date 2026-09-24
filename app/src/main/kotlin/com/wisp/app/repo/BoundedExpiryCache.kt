package com.wisp.app.repo

/** Bounded insertion-ordered expiry cache. Caller supplies synchronization and a wall clock. */
internal class BoundedExpiryCache<K : Any, V : Any>(private val capacity: Int, private val ttlMillis: Long) {
    private class Entry<V>(val value: V, val expiresAt: Long)

    private val entries = LinkedHashMap<K, Entry<V>>()

    fun contains(key: K, now: Long): Boolean {
        val entry = entries[key] ?: return false
        if (now < entry.expiresAt) return true
        entries.remove(key)
        return false
    }

    fun get(key: K, now: Long): V? = if (contains(key, now)) entries.getValue(key).value else null

    /** Insertion time of a tracked key, even if already expired. */
    fun markedAt(key: K): Long? = entries[key]?.let { it.expiresAt - ttlMillis }

    fun add(key: K, value: V, now: Long) {
        entries.remove(key)
        entries[key] = Entry(value, now + ttlMillis)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun expiredKeys(now: Long): Set<K> = entries.filterValues { now >= it.expiresAt }.keys.toSet()

    fun keys(now: Long): Set<K> = entries.filterValues { now < it.expiresAt }.keys.toSet()

    /** Snapshot of all keys with their insertion times (expired included), for persistence. */
    fun snapshot(): List<Pair<K, Long>> = entries.entries.map { (key, entry) -> key to entry.expiresAt - ttlMillis }

    fun remove(key: K): Boolean = entries.remove(key) != null

    fun size(): Int = entries.size

    fun clear() { entries.clear() }
}
