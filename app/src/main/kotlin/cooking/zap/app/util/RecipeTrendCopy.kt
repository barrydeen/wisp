package cooking.zap.app.util

/**
 * Copy for the Recipes header trend pill. Pure strings, assembled here rather
 * than in the composable so the wording is unit-testable.
 *
 * Two things this wording is carefully doing:
 *
 * 1. **It never says "in this feed."** The counted set is not the feed's set —
 *    the server counts kinds 30023 + 35000 on the Pantry members relay, while
 *    the feed reads kind 30023 across a relay union that excludes Pantry. The
 *    two overlap and neither contains the other, so a user cannot reconcile
 *    this number by scrolling. A platform-wide phrasing is true; anything
 *    implying the feed would not be.
 * 2. **It states the window inline.** On web the three-week window lives in a
 *    `title=` tooltip. There is no hover on Android, so the window has to be
 *    visible text or the number is unexplained — which is why the window
 *    clause must never be the part that truncates at narrow widths.
 */
object RecipeTrendCopy {

    /** The hero number with its up-arrow. The only number in the header. */
    fun count(count: Int): String = "↑ $count"

    /** The muted label after the number, stating the window. */
    val label: String = "new recipes · last ${RecipeTrend.WINDOW_WEEKS} weeks"

    /**
     * TalkBack reads the number and window as one phrase, so the pill is a
     * single merged semantics node rather than a number floating beside an
     * unattached label. Spelled-out window: screen readers handle "3 weeks"
     * unevenly, and this string is short enough to write out.
     */
    fun contentDescription(count: Int): String =
        "$count new recipes in the last ${spelled(RecipeTrend.WINDOW_WEEKS)} weeks"

    private fun spelled(n: Int): String = when (n) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        else -> n.toString()
    }
}
