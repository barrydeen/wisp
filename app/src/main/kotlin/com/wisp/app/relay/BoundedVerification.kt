package com.wisp.app.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Equal payloads always use one worker, so verification is single-flight even across relays.
 * Queues and per-worker LRU caches are bounded; admission suspends rather than skipping checks.
 * Keys must be immutable and include every field being verified, including the signature. */
internal class BoundedVerification<K : Any>(
    scope: CoroutineScope,
    workerCount: Int = 4,
    queueCapacity: Int = 32,
    cacheSize: Int = 256,
    verify: (K) -> Boolean
) {
    private data class Request<K>(val key: K, val result: CompletableDeferred<Boolean>)

    init {
        require(workerCount > 0 && queueCapacity >= 0 && cacheSize > 0)
    }

    private val queues = List(workerCount) {
        Channel<Request<K>>(queueCapacity).also { queue ->
            scope.launch(Dispatchers.Default) {
                val cache = object : LinkedHashMap<K, Boolean>(cacheSize, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Boolean>): Boolean =
                        size > cacheSize
                }
                for (request in queue) {
                    val valid = cache[request.key] ?: try {
                        verify(request.key)
                    } catch (_: Exception) {
                        false
                    }.also { cache[request.key] = it }
                    request.result.complete(valid)
                }
            }
        }
    }

    suspend fun verify(key: K): Boolean {
        val result = CompletableDeferred<Boolean>()
        queues[Math.floorMod(key.hashCode(), queues.size)].send(Request(key, result))
        return result.await()
    }
}
