package cooking.zap.app.mealplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Port of frontend `src/lib/mealplan/week.test.ts`.
 *
 * Vectors are inline (matching web's current style). PR 9's web-side half
 * will extract these to shared JSON fixtures — do not invent a parallel
 * fixture format here ahead of that extraction.
 *
 * W53 coverage is release-blocking: 2026 is a 53-week ISO year and these
 * boundaries go live in production this December.
 */
class WeekTest {

    // ---- week ids across ISO year boundaries --------------------------------

    @Test
    fun `2026 is a 53-week ISO year`() {
        assertEquals(53, Week.weeksInISOYear(2026))
        assertEquals(52, Week.weeksInISOYear(2025))
    }

    @Test
    fun `Jan 1-3 2027 belong to 2026-W53`() {
        assertEquals("2026-W53", Week.weekIdForDate(LocalDate.of(2027, 1, 1)))
        assertEquals("2026-W53", Week.weekIdForDate(LocalDate.of(2027, 1, 2)))
        assertEquals("2026-W53", Week.weekIdForDate(LocalDate.of(2027, 1, 3)))
        // Jan 4 2027 is a Monday — first day of 2027-W01
        assertEquals("2027-W01", Week.weekIdForDate(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `Dec 29 2025 belongs to 2026-W01`() {
        assertEquals("2026-W01", Week.weekIdForDate(LocalDate.of(2025, 12, 29)))
        assertEquals("2025-W52", Week.weekIdForDate(LocalDate.of(2025, 12, 28)))
    }

    @Test
    fun `zero-pads single-digit weeks`() {
        assertEquals("2026-W06", Week.weekIdForDate(LocalDate.of(2026, 2, 2)))
    }

    // ---- week arithmetic ----------------------------------------------------

    @Test
    fun `crosses into and out of W53`() {
        assertEquals("2027-W01", Week.nextWeekId("2026-W53"))
        assertEquals("2026-W53", Week.prevWeekId("2027-W01"))
    }

    @Test
    fun `crosses a 52-week year boundary`() {
        assertEquals("2025-W52", Week.prevWeekId("2026-W01"))
        assertEquals("2026-W01", Week.nextWeekId("2025-W52"))
    }

    @Test
    fun `walks ordinary weeks`() {
        assertEquals("2026-W30", Week.nextWeekId("2026-W29"))
        assertEquals("2026-W28", Week.prevWeekId("2026-W29"))
    }

    @Test
    fun `mondayOfWeek returns the ISO Monday`() {
        val monday = Week.mondayOfWeek("2026-W29")
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals("2026-W29", Week.weekIdForDate(monday))
    }

    /**
     * Locale-sensitive [WeekFields.of] silently breaks Monday-start parity on
     * Sunday-start locales (e.g. Locale.US). IsoFields is the only allowed clock.
     */
    @Test
    fun `Monday-start semantics — IsoFields not locale WeekFields`() {
        val sunday = LocalDate.of(2027, 1, 3) // Sunday, still in ISO 2026-W53
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals("2026-W53", Week.weekIdForDate(sunday))
        assertEquals(DayOfWeek.MONDAY, Week.mondayOfWeek("2026-W53").dayOfWeek)

        // Prove Locale.US week numbering disagrees with ISO on this edge —
        // using WeekFields.of(locale) here would break cross-platform parity.
        val usWeekFields = WeekFields.of(Locale.US)
        val usWeekYear = sunday.get(usWeekFields.weekBasedYear())
        val usWeek = sunday.get(usWeekFields.weekOfWeekBasedYear())
        assertFalse(
            "Locale.US week fields must not match ISO for Sunday Jan 3 2027 " +
                "(got $usWeekYear-W$usWeek); IsoFields is required",
            usWeekYear == 2026 && usWeek == 53,
        )
    }

    // ---- d-tag round trip ---------------------------------------------------

    @Test
    fun `encodes and decodes d-tags`() {
        assertEquals("mealplan-2026-W29", Week.dTagForWeek("2026-W29"))
        assertEquals("2026-W29", Week.weekIdFromDTag("mealplan-2026-W29"))
    }

    @Test
    fun `rejects foreign or malformed d-tags`() {
        assertNull(Week.weekIdFromDTag("grocery-abc123"))
        assertNull(Week.weekIdFromDTag("mealplan-2026-29"))
        assertNull(Week.weekIdFromDTag("mealplan-2026-W99"))
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun `accepts real weeks and rejects out-of-range ones`() {
        assertTrue(Week.isValidWeekId("2026-W53")) // 53-week year
        assertFalse(Week.isValidWeekId("2025-W53")) // 52-week year — range-checked
        assertFalse(Week.isValidWeekId("2026-W00"))
        assertFalse(Week.isValidWeekId("2026-W54"))
        assertFalse(Week.isValidWeekId("26-W05"))
        assertFalse(Week.isValidWeekId("garbage"))
        assertEquals(Week.ParsedWeekId(2026, 29), Week.parseWeekId("2026-W29"))
    }

    // ---- display range ------------------------------------------------------

    @Test
    fun `formats a same-month week`() {
        // 2026-W29: Mon Jul 13 – Sun Jul 19
        assertEquals("Week 29 (Jul 13–19)", Week.weekDisplayRange("2026-W29"))
    }

    @Test
    fun `formats a cross-month week`() {
        // 2026-W27: Mon Jun 29 – Sun Jul 5
        assertEquals("Week 27 (Jun 29–Jul 5)", Week.weekDisplayRange("2026-W27"))
    }

    @Test
    fun `currentWeekId uses local wall-clock`() {
        // Smoke: must equal weekIdForDate(today) — contract local wall-clock rule.
        assertEquals(Week.weekIdForDate(LocalDate.now()), Week.currentWeekId())
        assertNotNull(Week.parseWeekId(Week.currentWeekId()))
    }
}
