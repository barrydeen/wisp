package com.wisp.app.db

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BatchWriterTest {
    @Test
    fun flushAwaitsDurabilityOfAcceptedWrites() = runBlocking {
        val written = CopyOnWriteArrayList<List<Int>>()
        val writer = BatchWriter<Int>(settleMillis = 5, onFailure = {}) { written.add(it.toList()) }
        writer.enqueue(1)
        writer.enqueue(2)
        writer.enqueue(3)
        withTimeout(2_000) { writer.flush() }
        assertEquals(1, written.size)
        assertEquals(setOf(1, 2, 3), written[0].toSet())
        writer.shutdown()
    }

    @Test
    fun failureIsReportedOnceAndNeverPoisonsLaterFlushes() = runBlocking {
        val failNext = AtomicBoolean(true)
        val failures = AtomicInteger()
        val writer = BatchWriter<Int>(settleMillis = 1, onFailure = { failures.incrementAndGet() }) {
            if (failNext.getAndSet(false)) throw IllegalStateException("disk full")
        }
        writer.enqueue(1)
        assertTrue(withTimeout(2_000) { runCatching { writer.flush() }.isFailure })
        writer.enqueue(2)
        withTimeout(2_000) { writer.flush() }
        writer.enqueue(3)
        withTimeout(2_000) { writer.flush() }
        writer.shutdown()
        assertEquals(1, failures.get())
    }

    @Test
    fun shutdownThrowsWhenFailureWasNeverFlushed() = runBlocking {
        val writer = BatchWriter<Int>(settleMillis = 1, onFailure = {}) {
            throw IllegalStateException("disk full")
        }
        writer.enqueue(1)
        delay(100)
        assertTrue(withTimeout(2_000) { runCatching { writer.shutdown() }.isFailure })
    }

    @Test
    fun closeDrainsAcceptedWritesAndDropsEnqueuesWithoutThrowing() = runBlocking {
        val written = CopyOnWriteArrayList<Int>()
        val dropped = AtomicInteger()
        val writer = BatchWriter<Int>(settleMillis = 1, onFailure = { dropped.incrementAndGet() }) { written.addAll(it) }
        writer.enqueue(1)
        writer.enqueue(2)
        writer.close()
        withTimeout(2_000) { while (written.size < 2) delay(10) }
        writer.enqueue(3)
        assertEquals(1, dropped.get())
        assertFalse(3 in written)
    }
}
