package com.wisp.app.viewmodel

import org.junit.Assert.*
import org.junit.Test

class FeedEngagementStateTest {
    @Test
    fun closedSubscriptionsCanBeSubscribedAgain() {
        val state = FeedEngagementState()
        state.register("one", setOf("a", "b"))
        assertTrue(state.isSubscribed("a"))
        assertEquals(listOf("one"), state.distantSubscriptions(setOf("c")))
        state.remove("one")
        assertFalse(state.isSubscribed("a"))
        state.register("two", setOf("a"))
        assertTrue(state.isSubscribed("a"))
        state.clear()
        assertFalse(state.isSubscribed("a"))
    }

    @Test
    fun retainBatchWhileAnyMemberIsNearby() {
        val state = FeedEngagementState()
        state.register("one", setOf("a", "b"))
        state.register("two", setOf("c"))
        assertEquals(listOf("two"), state.distantSubscriptions(setOf("b")))
        assertEquals(setOf("one", "two"), state.distantSubscriptions(emptySet()).toSet())
    }

    @Test
    fun stableKeysFollowPrependAndReorder() {
        assertEquals(1..2, viewportRange(listOf("a", "b", "c"), setOf("b", "c"), 0, 0))
        assertEquals(2..3, viewportRange(listOf("new", "a", "b", "c"), setOf("b", "c"), 0, 0))
        assertEquals(0..1, viewportRange(listOf("c", "b", "a"), setOf("b", "c"), 0, 0))
    }

    @Test
    fun replacementAndEmptyViewportHaveNoRange() {
        assertNull(viewportRange(listOf("new"), setOf("old"), 5, 10))
        assertNull(viewportRange(emptyList(), setOf("old"), 5, 10))
        assertNull(viewportRange(listOf("a"), emptySet(), 5, 10))
    }

    @Test
    fun clampsBuffersWithoutOverflow() {
        assertEquals(0..2, viewportRange(listOf("a", "b", "c"), setOf("b"), Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1..1, viewportRange(listOf("a", "b", "c"), setOf("b"), -5, -10))
    }
}
