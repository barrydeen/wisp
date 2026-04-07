package com.antz.app.relay

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionManager(private val relayPool: RelayPool) {

    /**
     * Await a specific EOSE signal by subscription ID. One-shot, no lingering collector.
     * Returns immediately if a reconnect has occurred since [generation] was captured.
     */
    suspend fun awaitEose(
        targetSubId: String,
        generation: Long = relayPool.reconnectGeneration
    ) {
        if (relayPool.reconnectGeneration != generation) return
        relayPool.eoseSignals.first {
            relayPool.reconnectGeneration != generation || it == targetSubId
        }
    }

    /**
     * Await EOSE with a timeout. Returns true if EOSE was received, false on timeout.
     * Returns false immediately if a reconnect has occurred since [generation] was captured.
     */
    suspend fun awaitEoseWithTimeout(
        targetSubId: String,
        timeoutMs: Long = 15_000,
        generation: Long = relayPool.reconnectGeneration
    ): Boolean {
        if (relayPool.reconnectGeneration != generation) return false
        return withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals.first {
                relayPool.reconnectGeneration != generation || it == targetSubId
            }
            relayPool.reconnectGeneration == generation
        } ?: false
    }

    /**
     * Await count-based EOSE: wait until [expectedCount] EOSE signals arrive for [targetSubId],
     * or until [timeoutMs] elapses.
     * Returns the number of EOSE signals actually received.
     * Returns 0 immediately if a reconnect has occurred since [generation] was captured.
     */
    suspend fun awaitEoseCount(
        targetSubId: String,
        expectedCount: Int,
        timeoutMs: Long = 15_000,
        generation: Long = relayPool.reconnectGeneration
    ): Int {
        if (expectedCount <= 0) return 0
        if (relayPool.reconnectGeneration != generation) return 0
        var eoseCount = 0
        withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals
                .filter { it == targetSubId }
                .takeWhile { relayPool.reconnectGeneration == generation }
                .take(expectedCount)
                .collect { eoseCount++ }
        }
        return eoseCount
    }

    /**
     * Close a subscription on all relays (including ephemeral).
     */
    fun closeSubscription(subscriptionId: String) {
        relayPool.closeOnAllRelays(subscriptionId)
    }
}
