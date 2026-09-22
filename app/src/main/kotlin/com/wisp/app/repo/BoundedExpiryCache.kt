package com.wisp.app.repo

/** Small insertion-ordered cooldown cache. Caller supplies synchronization and a monotonic clock. */
internal class BoundedExpiryCache<K>(private val capacity: Int, private val ttlMillis: Long) {
    private val entries = LinkedHashMap<K, Long>()

    fun contains(key: K, now: Long): Boolean {
        val expires = entries[key] ?: return false
        if (now < expires) return true
        entries.remove(key)
        return false
    }

    fun add(key: K, now: Long) {
        entries.remove(key)
        entries[key] = now + ttlMillis
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun remove(key: K) { entries.remove(key) }
    fun clear() { entries.clear() }
}
