package com.wisp.app.viewmodel

/** Fetch history is not subscription liveness: closed events must be subscribed again on return. */
internal class FeedEngagementState {
    private val members = mutableMapOf<String, Set<String>>()
    private val fetched = linkedSetOf<String>()

    fun isSubscribed(eventId: String): Boolean = members.values.any { eventId in it }

    fun register(subscriptionId: String, eventIds: Set<String>) {
        members[subscriptionId] = eventIds
    }

    fun markFetched(subscriptionId: String) {
        fetched.addAll(members[subscriptionId].orEmpty())
        while (fetched.size > 5_000) fetched.remove(fetched.first())
    }

    fun wasFetched(eventId: String): Boolean = eventId in fetched

    fun distantSubscriptions(nearbyIds: Set<String>): List<String> =
        members.filterValues { ids -> ids.none { it in nearbyIds } }.keys.toList()

    fun remove(subscriptionId: String) { members.remove(subscriptionId) }

    fun clear(clearHistory: Boolean = false) {
        members.clear()
        if (clearHistory) fetched.clear()
    }
}

/** Resolve stable keys against the current feed, never against a pre-debounce positional snapshot. */
internal fun viewportRange(feedIds: List<String>, visibleIds: Set<String>, before: Int, after: Int): IntRange? {
    val first = feedIds.indexOfFirst { it in visibleIds }
    if (first < 0) return null
    val last = feedIds.indexOfLast { it in visibleIds }
    val start = (first.toLong() - before.coerceAtLeast(0)).coerceAtLeast(0).toInt()
    val end = (last.toLong() + after.coerceAtLeast(0)).coerceAtMost(feedIds.lastIndex.toLong()).toInt()
    return start..end
}
