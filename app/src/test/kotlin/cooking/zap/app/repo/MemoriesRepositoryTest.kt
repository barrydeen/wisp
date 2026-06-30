package cooking.zap.app.repo

import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-helper gate for [MemoriesRepository], ported from the web's
 * memories.test.ts. Window math, the NIP-10 reply predicate, and the cache
 * gating are pure and tested here; the relay fetch + SharedPreferences cache
 * need Android and are exercised on-device.
 *
 * A fixed UTC zone makes the window assertions deterministic regardless of the
 * machine's local zone (the production helper uses the now-Calendar's own zone).
 */
class MemoriesRepositoryTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun cal(year: Int, monthIndex: Int, day: Int, hour: Int = 12, minute: Int = 0): Calendar =
        Calendar.getInstance(utc).apply { clear(); set(year, monthIndex, day, hour, minute, 0) }

    /** Assert a window spans exactly the given UTC calendar day, 00:00:00 → 23:59:59. */
    private fun expectDaySpan(w: MemoryWindow, year: Int, monthIndex: Int, day: Int) {
        val start = Calendar.getInstance(utc).apply { timeInMillis = w.since * 1000 }
        assertEquals(year, start.get(Calendar.YEAR))
        assertEquals(monthIndex, start.get(Calendar.MONTH))
        assertEquals(day, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
        assertEquals(0, start.get(Calendar.SECOND))

        val end = Calendar.getInstance(utc).apply { timeInMillis = w.until * 1000 }
        assertEquals(year, end.get(Calendar.YEAR))
        assertEquals(monthIndex, end.get(Calendar.MONTH))
        assertEquals(day, end.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, end.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, end.get(Calendar.MINUTE))
        assertEquals(59, end.get(Calendar.SECOND))
    }

    // ── getMemoryWindows ──────────────────────────────────────────────────

    @Test
    fun windows_forOneTwoThreeYearsAgo_inOrder() {
        val w = getMemoryWindows(cal(2026, Calendar.JUNE, 10, 14, 30))
        assertEquals(listOf(1, 2, 3), w.map { it.yearsAgo })
        expectDaySpan(w[0], 2025, Calendar.JUNE, 10)
        expectDaySpan(w[1], 2024, Calendar.JUNE, 10)
        expectDaySpan(w[2], 2023, Calendar.JUNE, 10)
    }

    @Test
    fun windows_sincePrecedesUntil() {
        for (win in getMemoryWindows(cal(2026, Calendar.JUNE, 10))) {
            assertTrue(win.since < win.until)
        }
    }

    @Test
    fun windows_handleJan1_startOfYear() {
        val w = getMemoryWindows(cal(2026, Calendar.JANUARY, 1, 0, 5))
        expectDaySpan(w[0], 2025, Calendar.JANUARY, 1)
        expectDaySpan(w[1], 2024, Calendar.JANUARY, 1)
        expectDaySpan(w[2], 2023, Calendar.JANUARY, 1)
    }

    @Test
    fun windows_handleDec31_endOfYear() {
        val w = getMemoryWindows(cal(2025, Calendar.DECEMBER, 31, 23, 55))
        expectDaySpan(w[0], 2024, Calendar.DECEMBER, 31)
        expectDaySpan(w[1], 2023, Calendar.DECEMBER, 31)
        expectDaySpan(w[2], 2022, Calendar.DECEMBER, 31)
    }

    @Test
    fun windows_fallBackToFeb28_whenTargetYearHasNoFeb29() {
        // Feb 29 2024 (leap); 2023/2022/2021 are non-leap.
        val w = getMemoryWindows(cal(2024, Calendar.FEBRUARY, 29))
        expectDaySpan(w[0], 2023, Calendar.FEBRUARY, 28)
        expectDaySpan(w[1], 2022, Calendar.FEBRUARY, 28)
        expectDaySpan(w[2], 2021, Calendar.FEBRUARY, 28)
    }

    @Test
    fun windows_keepFeb28AsFeb28_regardlessOfLeapStatus() {
        val w = getMemoryWindows(cal(2025, Calendar.FEBRUARY, 28))
        expectDaySpan(w[0], 2024, Calendar.FEBRUARY, 28) // 2024 leap, but source is the 28th
        expectDaySpan(w[1], 2023, Calendar.FEBRUARY, 28)
        expectDaySpan(w[2], 2022, Calendar.FEBRUARY, 28)
    }

    // ── isReplyNote ───────────────────────────────────────────────────────

    private val eid = "e".repeat(64)
    private val fid = "f".repeat(64)
    private val pk = "a".repeat(64)

    private fun ev(tags: List<List<String>>) =
        NostrEvent(id = "i".repeat(64), pubkey = pk, created_at = 1, kind = 1, tags = tags, content = "x", sig = "")

    @Test
    fun reply_keepsTopLevelNotes() {
        assertFalse(isReplyNote(ev(emptyList())))
        assertFalse(isReplyNote(ev(listOf(listOf("p", pk), listOf("t", "zapcooking")))))
    }

    @Test
    fun reply_dropsRootReplyAndUnmarked() {
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid, "", "root")))))
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid, "", "reply")))))
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid)))))                          // unmarked positional
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid, "wss://relay.example")))))   // relay hint, no marker
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid, "", "fork")))))              // unknown marker
    }

    @Test
    fun reply_keepsMentionsAndQuotes() {
        assertFalse(isReplyNote(ev(listOf(listOf("e", eid, "", "mention")))))
        assertFalse(isReplyNote(ev(listOf(listOf("e", eid, "", "Mention")))))          // case-insensitive
        assertFalse(isReplyNote(ev(listOf(listOf("q", eid)))))
        assertFalse(isReplyNote(ev(listOf(listOf("q", eid), listOf("p", pk)))))
    }

    @Test
    fun reply_mixedMentionPlusReply_isReply() {
        assertTrue(isReplyNote(ev(listOf(listOf("e", eid, "", "mention"), listOf("e", fid, "", "reply")))))
    }

    @Test
    fun reply_ignoresETagsWithNoEventId() {
        assertFalse(isReplyNote(ev(listOf(listOf("e")))))   // no id at all
        assertFalse(isReplyNote(ev(listOf(listOf("e", ""))))) // empty id
    }

    // ── shouldCacheMemories ───────────────────────────────────────────────

    private fun group(resolvedVia: MemoryResolved, withEvent: Boolean = false, yearsAgo: Int = 1) =
        MemoryGroup(yearsAgo, 0L, if (withEvent) listOf(ev(emptyList())) else emptyList(), resolvedVia)

    @Test
    fun cache_cachesOnlyWhenEveryWindowEosed() {
        // Production always fetches the 1/2/3-year windows, so use three groups.
        // All windows EOSE'd (even empty ones) → complete → cacheable ("nothing that day").
        assertTrue(shouldCacheMemories(listOf(group(MemoryResolved.EOSE), group(MemoryResolved.EOSE), group(MemoryResolved.EOSE))))
        // Same, but the 1-year window has notes — still all-EOSE → cacheable.
        assertTrue(shouldCacheMemories(listOf(group(MemoryResolved.EOSE, withEvent = true), group(MemoryResolved.EOSE), group(MemoryResolved.EOSE))))
    }

    @Test
    fun cache_doesNotCacheAnyTimeoutWindow() {
        // Any TIMEOUT window = incomplete → NOT cacheable, so a transient miss re-fetches.
        assertFalse(shouldCacheMemories(listOf(group(MemoryResolved.TIMEOUT), group(MemoryResolved.TIMEOUT), group(MemoryResolved.TIMEOUT))))
        // The frozen-3-year case: 1-year had notes + EOSE, 3-year timed out → not cached.
        assertFalse(shouldCacheMemories(listOf(group(MemoryResolved.EOSE, withEvent = true), group(MemoryResolved.EOSE), group(MemoryResolved.TIMEOUT))))
        // A TIMEOUT window with events is still incomplete → not durably cached.
        assertFalse(shouldCacheMemories(listOf(group(MemoryResolved.TIMEOUT, withEvent = true), group(MemoryResolved.EOSE))))
    }

    @Test
    fun cache_doesNotCacheEmptyGroupList() {
        assertFalse(shouldCacheMemories(emptyList()))
    }
}
