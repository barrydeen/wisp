package com.wisp.app.db

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Nonblocking producers; close drains accepted writes, shutdown also waits for durability. */
internal class BatchWriter<T>(
    private val settleMillis: Long = 200,
    private val maxBatchSize: Int = 500,
    private val onFailure: (Throwable) -> Unit,
    private val write: (List<T>) -> Unit
) : AutoCloseable {
    private sealed interface Command<out T> {
        data class Value<T>(val value: T) : Command<T>
        data class Flush(val result: CompletableDeferred<Unit>) : Command<Nothing>
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // An unbounded queue deliberately trades burst memory for no lost events and no UI blocking.
    // Database transactions are still bounded by maxBatchSize.
    private val queue = Channel<Command<T>>(Channel.UNLIMITED)
    private var failure: Throwable? = null
    private val worker = scope.launch {
        val batch = ArrayList<T>(maxBatchSize)
        fun drain() {
            if (batch.isEmpty()) return
            try {
                write(batch)
            } catch (e: Exception) {
                failure = failure ?: e
                onFailure(e)
            } finally {
                batch.clear()
            }
        }
        try {
            for (command in queue) {
                when (command) {
                    is Command.Value -> {
                        batch.add(command.value)
                        delay(settleMillis)
                    }
                    is Command.Flush -> {
                        failure?.let { command.result.completeExceptionally(it) }
                            ?: command.result.complete(Unit)
                        continue
                    }
                }
                while (batch.size < maxBatchSize) {
                    when (val next = queue.tryReceive().getOrNull() ?: break) {
                        is Command.Value -> batch.add(next.value)
                        is Command.Flush -> {
                            drain()
                            failure?.let { next.result.completeExceptionally(it) }
                                ?: next.result.complete(Unit)
                        }
                    }
                }
                drain()
            }
        } finally {
            scope.cancel()
        }
    }

    fun enqueue(value: T) {
        check(queue.trySend(Command.Value(value)).isSuccess) { "Persistence writer is closed" }
    }

    /** Waits for writes accepted before this call; failures are not silently acknowledged. */
    suspend fun flush() {
        val result = CompletableDeferred<Unit>()
        if (queue.trySend(Command.Flush(result)).isSuccess) result.await() else shutdown()
    }

    override fun close() {
        queue.close()
    }

    suspend fun shutdown() {
        close()
        worker.join()
        failure?.let { throw it }
    }
}
