package cooking.zap.app.mealplan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * ISO-week identifiers for meal plans — the d-tag clock.
 *
 * A week id is `{isoWeekYear}-W{ww}` (e.g. "2026-W29"): ISO 8601 weeks,
 * Monday start, week-numbering year (NOT calendar year — Jan 1–3 2027
 * fall in 2026-W53), zero-padded week. Resolved from the device's local
 * wall-clock date per docs/mealplan-contract.md (frontend, frozen).
 *
 * Uses [IsoFields] only — never [java.util.Calendar] or
 * [java.time.temporal.WeekFields.of] with a locale. Locale-sensitive week
 * fields silently break parity on Sunday-start locales (e.g. `Locale.US`).
 *
 * Port of frontend `src/lib/mealplan/week.ts`.
 */
object Week {
    const val MEALPLAN_D_TAG_PREFIX = "mealplan-"

    private val WEEK_ID_RE = Regex("""^(\d{4})-W(\d{2})$""")
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val DAY_ONLY = DateTimeFormatter.ofPattern("d", Locale.US)

    data class ParsedWeekId(val year: Int, val week: Int)

    /** Week id for an arbitrary local date. */
    fun weekIdForDate(date: LocalDate): String {
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return "%d-W%02d".format(year, week)
    }

    /** Week id for "now" in the device's local timezone (wall-clock). */
    fun currentWeekId(): String = weekIdForDate(LocalDate.now())

    /** Number of ISO weeks (52 or 53) in an ISO week-numbering year. */
    fun weeksInISOYear(isoYear: Int): Int {
        // Jan 4 is always inside week 1 of its ISO year; Dec 28 is always
        // in the last ISO week of that week-numbering year.
        return LocalDate.of(isoYear, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    }

    /** Parse a week id into its parts, or null if malformed/out of range. */
    fun parseWeekId(weekId: String): ParsedWeekId? {
        val m = WEEK_ID_RE.matchEntire(weekId) ?: return null
        val year = m.groupValues[1].toInt()
        val week = m.groupValues[2].toInt()
        if (week < 1 || week > weeksInISOYear(year)) return null
        return ParsedWeekId(year, week)
    }

    fun isValidWeekId(weekId: String): Boolean = parseWeekId(weekId) != null

    /** The Monday (local calendar date) that starts the given week. */
    fun mondayOfWeek(weekId: String): LocalDate {
        val parsed = parseWeekId(weekId)
            ?: throw IllegalArgumentException("Invalid week id: $weekId")
        // Jan 4 is always in W01 of its ISO year; walk forward from W01's Monday.
        val w1Monday = LocalDate.of(parsed.year, 1, 4)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return w1Monday.plusWeeks((parsed.week - 1).toLong())
    }

    fun nextWeekId(weekId: String): String =
        weekIdForDate(mondayOfWeek(weekId).plusWeeks(1))

    fun prevWeekId(weekId: String): String =
        weekIdForDate(mondayOfWeek(weekId).minusWeeks(1))

    fun dTagForWeek(weekId: String): String = "$MEALPLAN_D_TAG_PREFIX$weekId"

    /** Inverse of [dTagForWeek]; null when the d-tag is not a valid mealplan tag. */
    fun weekIdFromDTag(dTag: String): String? {
        if (!dTag.startsWith(MEALPLAN_D_TAG_PREFIX)) return null
        val weekId = dTag.removePrefix(MEALPLAN_D_TAG_PREFIX)
        return if (isValidWeekId(weekId)) weekId else null
    }

    /**
     * Human display range, e.g. "Week 29 (Jul 13–19)" or, across a month
     * boundary, "Week 27 (Jun 29–Jul 5)". En-dash matches web `week.ts`.
     */
    fun weekDisplayRange(weekId: String): String {
        val parsed = parseWeekId(weekId)
            ?: throw IllegalArgumentException("Invalid week id: $weekId")
        val monday = mondayOfWeek(weekId)
        val sunday = monday.plusDays(6)
        val sameMonth = monday.month == sunday.month
        val range = if (sameMonth) {
            "${monday.format(MONTH_DAY)}–${sunday.format(DAY_ONLY)}"
        } else {
            "${monday.format(MONTH_DAY)}–${sunday.format(MONTH_DAY)}"
        }
        return "Week ${parsed.week} ($range)"
    }
}
