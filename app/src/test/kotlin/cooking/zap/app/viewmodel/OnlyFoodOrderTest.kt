package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the OnlyFood emission-coalescing + stable-order machinery
 * ([mergeFeedOrder] + [launchFeedCoalescer]). The live `submit()` path needs
 * Android; the two extracted helpers carry the load-bearing behavior and are
 * tested here on the JVM.
 *
 * - [mergeFeedOrder] is the single "compute the display list" path: the unsettled
 *   rebuild (mid-build-reconnect safety, Correction 1) and the settled append
 *   (no straggler reshuffle), selected purely by the `settled` flag (Correction 3).
 * - [launchFeedCoalescer] is the burst→bounded-emission win; the test asserts the
 *   emission COUNT under virtual time, not the final list (Correction 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlyFoodOrderTest {

    private fun ev(id: String, createdAt: Long) = NostrEvent(
        id = id,
        pubkey = "pk",
        created_at = createdAt,
        kind = 1,
        tags = emptyList(),
        content = "",
        sig = "",
    )

    // ---- mergeFeedOrder: unsettled rebuild (Correction 1) --------------------

    @Test
    fun unsettled_rebuildsFromSeen_discardingStaleCache() {
        // Mid-build-reconnect: a dropped load left stale entries in the display
        // cache; the reconnect re-submit runs on the SAME state. An unsettled flush
        // must rebuild from `seen` from scratch, NOT append onto the stale cache.
        val ordered = arrayListOf(ev("stale", 999))
        val placed = hashSetOf("stale")
        val seen = linkedMapOf(
            "a" to ev("a", 100),
            "b" to ev("b", 300),
            "c" to ev("c", 200),
        )

        val out = mergeFeedOrder(ordered, placed, seen.values, settled = false)

        // From-scratch, sorted desc by created_at. "stale" is gone — proof it was a
        // rebuild, not an append onto pre-drop state.
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
        assertEquals(listOf("b", "c", "a"), ordered.map { it.id })
        assertEquals(setOf("a", "b", "c"), placed)
    }

    // ---- mergeFeedOrder: settled append (no reshuffle, AC3) ------------------

    @Test
    fun settled_appendsNewerStragglerAtTail_withoutReorderingRenderedRows() {
        // Already-rendered, settled (newest-first) order.
        val ordered = arrayListOf(ev("b", 300), ev("c", 200), ev("a", 100))
        val placed = hashSetOf("a", "b", "c")
        // A late straggler arrives that is NEWER than everything on screen.
        val seen = linkedMapOf(
            "b" to ev("b", 300),
            "c" to ev("c", 200),
            "a" to ev("a", 100),
            "straggler" to ev("straggler", 999),
        )

        val out = mergeFeedOrder(ordered, placed, seen.values, settled = true)

        // Rendered rows keep their positions; the newer straggler appends at the
        // BOTTOM rather than jumping to the top.
        assertEquals(listOf("b", "c", "a", "straggler"), out.map { it.id })
    }

    @Test
    fun settled_appendsBatch_sortedWithinTheBatch() {
        // A backward page: two older events arriving out of relay order.
        val ordered = arrayListOf(ev("b", 300), ev("a", 100))
        val placed = hashSetOf("a", "b")
        val seen = linkedMapOf(
            "b" to ev("b", 300),
            "a" to ev("a", 100),
            "old1" to ev("old1", 50),
            "old2" to ev("old2", 70),
        )

        val out = mergeFeedOrder(ordered, placed, seen.values, settled = true)

        // Existing prefix untouched; the new batch is sorted desc among itself and
        // appended at the tail.
        assertEquals(listOf("b", "a", "old2", "old1"), out.map { it.id })
    }

    @Test
    fun settled_appendDoesNotDuplicateAlreadyPlacedEvents() {
        val ordered = arrayListOf(ev("b", 300), ev("a", 100))
        val placed = hashSetOf("a", "b")
        // `seen` repeats the placed events plus one new — append must add only "new".
        val seen = linkedMapOf(
            "b" to ev("b", 300),
            "a" to ev("a", 100),
            "new" to ev("new", 50),
        )

        val out = mergeFeedOrder(ordered, placed, seen.values, settled = true)

        assertEquals(listOf("b", "a", "new"), out.map { it.id })
    }

    // ---- mergeFeedOrder: flag selects path, mode-independent (Correction 3) --

    @Test
    fun settledFlag_alone_selectsPath_independentOfVisibleMode() {
        // The helper has no notion of "current mode" — the `settled` flag alone
        // picks rebuild vs append, so a background mode that already settled appends
        // while a still-loading one rebuilds, regardless of which mode is visible.
        val seen = linkedMapOf("x" to ev("x", 100), "y" to ev("y", 200))

        val o1 = arrayListOf(ev("stale", 999))
        val p1 = hashSetOf("stale")
        val rebuilt = mergeFeedOrder(o1, p1, seen.values, settled = false)
        assertEquals("unsettled → full rebuild from seen", listOf("y", "x"), rebuilt.map { it.id })

        val o2 = arrayListOf(ev("kept", 999))
        val p2 = hashSetOf("kept")
        val appended = mergeFeedOrder(o2, p2, seen.values, settled = true)
        assertEquals("settled → append keeps prefix", listOf("kept", "y", "x"), appended.map { it.id })
    }

    @Test
    fun settled_appendFromEmptyCache_isAFullSort() {
        // Toggling to a mode that settled in the background (ordered never built
        // while it wasn't visible): append-from-empty must yield a full sorted list.
        val ordered = arrayListOf<NostrEvent>()
        val placed = hashSetOf<String>()
        val seen = linkedMapOf(
            "a" to ev("a", 100),
            "b" to ev("b", 300),
            "c" to ev("c", 200),
        )

        val out = mergeFeedOrder(ordered, placed, seen.values, settled = true)

        assertEquals(listOf("b", "c", "a"), out.map { it.id })
    }

    // ---- launchFeedCoalescer: burst → bounded emission COUNT (Correction 4) --

    @Test
    fun coalescer_collapsesABurstIntoOneEmission() = runTest {
        val signal = Channel<Unit>(Channel.CONFLATED)
        var emissions = 0
        val job = launchFeedCoalescer(signal, settleMs = 50) { emissions++ }

        // A firehose of 100 rapid inserts in a single window.
        repeat(100) { signal.trySend(Unit) }
        advanceUntilIdle()

        // The whole burst collapses to a single settle-window emission — NOT 100.
        assertTrue("expected ~1 emission for a 100-event burst, got $emissions", emissions in 1..2)

        job.cancel()
    }

    @Test
    fun coalescer_tracksWindows_andStaysBounded_notPerEvent() = runTest {
        val signal = Channel<Unit>(Channel.CONFLATED)
        var emissions = 0
        val job = launchFeedCoalescer(signal, settleMs = 50) { emissions++ }

        repeat(50) { signal.trySend(Unit) }
        advanceUntilIdle() // window 1
        val afterFirst = emissions
        repeat(50) { signal.trySend(Unit) }
        advanceUntilIdle() // window 2
        val afterSecond = emissions

        // First burst coalesces 50 inserts into ~one emission (real batching).
        assertTrue("first burst should coalesce, got $afterFirst", afterFirst in 1..2)
        // The second burst adds emissions → the collector tracks windows and isn't
        // frozen after the first one. (A burst landing on a parked collector yields
        // up to 2 — the conflated channel delivers one item directly and buffers the
        // conflated remainder — still bounded, never per-event.)
        assertTrue("second burst must add emissions, got $afterSecond", afterSecond > afterFirst)
        // 100 total inserts → a tiny bounded emission count, not 100.
        assertTrue("emissions must stay bounded, got $afterSecond", afterSecond <= 4)

        job.cancel()
    }
}
