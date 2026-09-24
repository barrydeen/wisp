package com.wisp.app.repo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Collector-owned registrations: obtaining a Flow does not allocate or pin a cache entry. */
internal class KeyedObservation<K, V>(
    private val lock: Any,
    private val snapshot: (K) -> V
) {
    private val collectors = mutableMapOf<K, MutableSet<Channel<V>>>()
    private var closed = false

    fun observe(key: K): Flow<V> = flow {
        val channel = Channel<V>(Channel.CONFLATED)
        synchronized(lock) {
            if (closed) {
                channel.close()
            } else {
                collectors.getOrPut(key) { mutableSetOf() }.add(channel)
                // Registration and initial lookup must be atomic with publication.
                channel.trySend(snapshot(key))
            }
        }
        try {
            for (value in channel) emit(value)
        } finally {
            synchronized(lock) {
                collectors[key]?.let {
                    it.remove(channel)
                    if (it.isEmpty()) collectors.remove(key)
                }
                channel.cancel()
            }
        }
    }

    fun publish(key: K) = synchronized(lock) {
        collectors[key]?.let { channels ->
            val value = snapshot(key)
            channels.forEach { it.trySend(value) }
        }
        Unit
    }

    fun publishAll() = synchronized(lock) {
        collectors.keys.toList().forEach(::publish)
    }

    fun close() = synchronized(lock) {
        closed = true
        collectors.values.forEach { channels -> channels.forEach { it.close() } }
        collectors.clear()
    }

    fun hasCollectors(key: K): Boolean = synchronized(lock) { collectors.containsKey(key) }

    internal fun collectorCount(): Int = synchronized(lock) { collectors.values.sumOf { it.size } }
}
