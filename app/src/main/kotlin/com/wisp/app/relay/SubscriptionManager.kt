package com.wisp.app.relay

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionManager(private val relayPool: RelayPool) {

    /**
     * Await a specific EOSE signal by subscription ID. One-shot, no lingering collector.
     * Also matches chunk variants (e.g., "feed:c1", "feed:c2") for the given base subId.
     */
    suspend fun awaitEose(targetSubId: String) {
        relayPool.eoseSignals.first { it == targetSubId || it.startsWith("$targetSubId:c") }
    }

    /**
     * Await EOSE with a timeout. Returns true if EOSE was received, false on timeout.
     * Also matches chunk variants (e.g., "feed:c1", "feed:c2") for the given base subId.
     */
    suspend fun awaitEoseWithTimeout(targetSubId: String, timeoutMs: Long = 15_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals.first { it == targetSubId || it.startsWith("$targetSubId:c") }
            true
        } ?: false
    }

    /**
     * Await count-based EOSE: wait until [expectedCount] EOSE signals arrive for [targetSubId],
     * or until [timeoutMs] elapses. Counts EOSE from both the base subId and any chunk
     * variants (e.g., "feed:c1", "feed:c2").
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
            relayPool.eoseSignals
                .filter { it == targetSubId || it.startsWith("$targetSubId:c") }
                .take(expectedCount)
                .collect { eoseCount++ }
        }
        return eoseCount
    }

    /**
     * Close a subscription on all relays (including ephemeral and chunk variants).
     */
    fun closeSubscription(subscriptionId: String) {
        relayPool.closeOnAllRelays(subscriptionId)
    }
}
