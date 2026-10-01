package com.wisp.app.repo

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class KeyedObservationTest {
    private val lock = Any()

    private suspend fun awaitCollector(obs: KeyedObservation<String, Int>, key: String) {
        withTimeout(2_000) { while (!obs.hasCollectors(key)) delay(10) }
    }

    @Test
    fun collectorReceivesSnapshotThenPublishesAndCleansUp() = runBlocking {
        val store = mutableMapOf<String, Int>()
        val obs = KeyedObservation<String, Int>(lock) { store[it] ?: -1 }
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) { obs.observe("a").toList(seen) }
        awaitCollector(obs, "a")
        assertTrue(obs.hasCollectors("a"))
        synchronized(lock) { store["a"] = 7 }
        obs.publish("a")
        withTimeout(2_000) { while (seen.size < 2) delay(10) }
        assertEquals(listOf(-1, 7), seen)
        job.cancelAndJoin()
        assertEquals(0, obs.collectorCount())
    }

    @Test
    fun closeCompletesCollectorsAndReplaysInitialSnapshot() = runBlocking {
        val store = mutableMapOf("a" to 1)
        val obs = KeyedObservation<String, Int>(lock) { store[it] ?: -1 }
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) { obs.observe("a").toList(seen) }
        awaitCollector(obs, "a")
        obs.close()
        withTimeout(2_000) { job.join() }
        assertEquals(listOf(1), seen)
        assertFalse(obs.hasCollectors("a"))
        // Observing after close terminates immediately with no emissions.
        assertEquals(emptyList<Int>(), obs.observe("a").toList())
        // Publishes after close are harmless no-ops.
        obs.publish("a")
        obs.publishAll()
    }

    @Test
    fun publishOnlyReachesCollectorsOfThatKey() = runBlocking {
        val store = mutableMapOf("a" to 0, "b" to 0)
        val obs = KeyedObservation<String, Int>(lock) { store[it] ?: -1 }
        val seenA = CopyOnWriteArrayList<Int>()
        val seenB = CopyOnWriteArrayList<Int>()
        val jobs = listOf(
            launch(Dispatchers.Default) { obs.observe("a").toList(seenA) },
            launch(Dispatchers.Default) { obs.observe("b").toList(seenB) }
        )
        awaitCollector(obs, "a")
        awaitCollector(obs, "b")
        synchronized(lock) { store["b"] = 5 }
        obs.publish("b")
        withTimeout(2_000) { while (seenB.size < 2) delay(10) }
        assertEquals(listOf(0), seenA)
        assertEquals(listOf(0, 5), seenB)
        obs.close()
        jobs.forEach { it.join() }
    }
}
