package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the OnlyFood backward-paging rule ([pageBoundsBehind] +
 * [pageEndReached]). The live `submit()` path needs Android; the cursor math and
 * the exhaustion decision — the part the premature-termination bug lived in — are
 * pure and tested here.
 *
 * The bug was never "zero events ends paging" (that is correct). It was "an empty
 * intermediate time window ends paging while older events still exist." So the
 * load-bearing test feeds the bounds helper through a quiet stretch and asserts
 * the page that lands an older event does NOT end-reach.
 */
class OnlyFoodPagingTest {

    @Test
    fun pageBounds_haveNoSinceFloor() {
        // The fix: no `since` window floor — `until` + limit walk backwards.
        assertNull(pageBoundsBehind(1_000).since)
    }

    @Test
    fun pageBounds_untilExcludesBoundarySecond() {
        // until = oldest - 1 strictly excludes the boundary second, so the next
        // page can't collide with the one that established `oldest`.
        assertEquals(999L, pageBoundsBehind(1_000).until)
    }

    @Test
    fun paging_walksAcrossAQuietStretch_withoutEndReaching() {
        // Start with the oldest loaded post at t=1000. The OLD logic floored the
        // page at `until - windowSeconds`, so an empty [floor, 999] stretch set
        // endReached even though an older post sits at t=100. The NEW logic has no
        // floor: the page query runs unbounded-below, the t=100 post lands
        // (receivedNew > 0), so paging does NOT end and the cursor advances past
        // the gap to its next strictly-decreasing `until`.
        var oldest = 1_000L
        val firstPage = pageBoundsBehind(oldest)
        assertNull(firstPage.since)
        assertEquals(999L, firstPage.until)

        // A single older event arrives across the gap (nothing in between).
        val receivedNewAcrossGap = 1
        assertFalse(
            "an older event across a quiet stretch must not end paging",
            pageEndReached(receivedNewAcrossGap),
        )

        // Cursor advances to the new oldest; next `until` strictly decreases.
        oldest = 100L
        val secondPage = pageBoundsBehind(oldest)
        assertNull(secondPage.since)
        assertEquals(99L, secondPage.until)
        assertTrue("until must strictly decrease across pages", secondPage.until < firstPage.until)
    }

    @Test
    fun paging_endsOnlyOnGenuineZeroOlderEvents() {
        // The relay returned nothing strictly older than `until` → real floor.
        assertTrue(pageEndReached(0))
        // Any new older event (even one) keeps paging alive.
        assertFalse(pageEndReached(1))
        assertFalse(pageEndReached(42))
    }

    // ---- cursor isolation: keyword-only firehose events must not move `oldest` ----

    /** [createdAt] with an optional food `#t` tag (tagged = hashtag-reachable). */
    private fun ev(createdAt: Long, foodTag: Boolean) = NostrEvent(
        id = "e$createdAt-$foodTag",
        pubkey = "pk",
        created_at = createdAt,
        kind = 1,
        tags = if (foodTag) listOf(listOf("t", "foodstr")) else emptyList(),
        content = "",
        sig = "",
    )

    @Test
    fun cursor_ignoresOlderKeywordOnlyFirehoseEvent_theDeepPagingHole() {
        // Hashtag notes bottom out at t=1000. A keyword-only firehose note (no `#t`)
        // sits OLDER at t=500. The old `min(seen)` cursor would jump `until` to 499,
        // skipping every hashtag note in (500, 1000] on the next PAGE — the hole.
        // The fix pins the cursor to the oldest hashtag-reachable note (1000).
        val seen = listOf(
            ev(3_000, foodTag = true),
            ev(1_000, foodTag = true),
            ev(500, foodTag = false), // keyword-only firehose candidate, older
        )
        assertEquals(1_000L, oldestPageableCreatedAt(seen))
    }

    @Test
    fun cursor_countsFirehoseGrabbedTaggedEvent_noPrematureEndReach() {
        // A firehose note that ALSO carries a food tag IS hashtag-reachable, so it must
        // stay counted (min over ALL tagged events). Because the cursor is then at or
        // below it, a PAGE queries strictly below and can't re-receive it → no
        // `received` undercount. An id-set excluding all firehose events would wrongly
        // drop it and reopen that gap.
        val seen = listOf(
            ev(3_000, foodTag = true),
            ev(800, foodTag = true), // "grabbed by firehose" but tagged → still the floor
        )
        assertEquals(800L, oldestPageableCreatedAt(seen))
    }

    @Test
    fun cursor_isNullWhenNoHashtagReachableEvent() {
        // All keyword-only (no `#t`): the hashtag PAGE has nothing to page → no-op.
        assertNull(oldestPageableCreatedAt(listOf(ev(900, foodTag = false), ev(400, foodTag = false))))
        assertNull(oldestPageableCreatedAt(emptyList()))
    }

    @Test
    fun pageUntil_isMonotonicallyDecreasing_acrossSuccessivePages() {
        // Throttle-safety: feed each page's landed-oldest back in; `until` must
        // strictly decrease every time so no two page queries are identical.
        val oldests = listOf(5_000L, 4_900L, 4_899L, 1L)
        val untils = oldests.map { pageBoundsBehind(it).until }
        assertEquals(listOf(4_999L, 4_899L, 4_898L, 0L), untils)
        for (i in 1 until untils.size) {
            assertTrue("page $i until must decrease", untils[i] < untils[i - 1])
        }
    }
}
