package com.wisp.app.relay

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks active subscription IDs per relay URL to prevent subscription explosion.
 * Enforces a soft cap per relay; priority subscriptions bypass the cap.
 */
class SubscriptionTracker {
    private val relaySubs = ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        const val SOFT_CAP = 20
        private val PRIORITY_PREFIXES = listOf(
            "dms", "notif", "feed", "self-data", "thread-", "engage", "user-engage"
        )
    }

    fun track(relayUrl: String, subId: String) {
        relaySubs.getOrPut(relayUrl) { ConcurrentHashMap.newKeySet() }.add(subId)
    }

    fun untrackAll(subId: String) {
        for (subs in relaySubs.values) {
            subs.remove(subId)
        }
    }

    fun untrackRelay(relayUrl: String) {
        relaySubs.remove(relayUrl)
    }

    fun hasCapacity(relayUrl: String, subId: String): Boolean {
        if (PRIORITY_PREFIXES.any { subId.startsWith(it) }) return true
        val count = relaySubs[relayUrl]?.size ?: 0
        return count < SOFT_CAP
    }

    fun getCount(relayUrl: String): Int = relaySubs[relayUrl]?.size ?: 0

    fun clear() {
        relaySubs.clear()
    }
}
