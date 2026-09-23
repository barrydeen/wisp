package com.wisp.app.db

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchWriterTest {
    @Test
    fun laterFlushSucceedsAfterReportedWriteFailure() = runBlocking {
        var failNext = true
        val written = mutableListOf<String>()
        val writer = BatchWriter<String>(settleMillis = 5, maxBatchSize = 10, onFailure = {}) { batch ->
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk")
            }
            written.addAll(batch)
        }
        try {
            writer.enqueue("lost")
            val first = runCatching { writer.flush() }
            assertTrue(first.exceptionOrNull()?.message?.contains("disk") == true)

            writer.enqueue("kept")
            writer.flush()
            assertEquals(listOf("kept"), written)
            writer.shutdown()
        } finally {
            writer.close()
        }
    }

    @Test
    fun shutdownThrowsWhenWriteFailureWasNeverFlushed() = runBlocking {
        val failed = CompletableDeferred<Throwable>()
        val writer = BatchWriter<String>(settleMillis = 5, maxBatchSize = 10, onFailure = { failed.complete(it) }) {
            throw IllegalStateException("disk")
        }
        writer.enqueue("lost")
        assertTrue(failed.await().message?.contains("disk") == true)
        val shutdown = runCatching { writer.shutdown() }
        assertTrue(shutdown.exceptionOrNull()?.message?.contains("disk") == true)
    }
}
