package com.wisp.app.relay

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionManager(private val relayPool: RelayPool) {

    /**
     * Await a specific EOSE signal by subscription ID. One-shot, no lingering collector.
     */
    suspend fun awaitEose(targetSubId: String) {
        relayPool.eoseSignals.first { it == targetSubId }
    }

    /**
     * Await EOSE with a timeout. Returns true if EOSE was received, false on timeout.
     */
    suspend fun awaitEoseWithTimeout(targetSubId: String, timeoutMs: Long = 15_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals.first { it == targetSubId }
            true
        } ?: false
    }

    /**
     * Await count-based EOSE: wait until [expectedCount] EOSE signals arrive for [targetSubId],
     * or until [timeoutMs] elapses.
     * Returns the number of EOSE signals actually received.
     */
    suspend fun awaitEoseCount(
        targetSubId: String,
        expectedCount: Int,
        timeoutMs: Long = 15_000
    ): Int {
        if (expectedCount <= 0) return 0
        var eoseCount = 0
        withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals.collect { subId ->
                if (subId == targetSubId && ++eoseCount >= expectedCount) {
                    return@collect
                }
            }
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
