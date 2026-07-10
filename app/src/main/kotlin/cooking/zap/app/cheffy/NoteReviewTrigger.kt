package cooking.zap.app.cheffy

import cooking.zap.app.FeatureFlags

/**
 * Gates for the Cheffy Note Review trigger placements (issue #150,
 * Phase A findings — option 1 approved: adaptive right-aligned sixth
 * ActionBar slot, both placements retained on wide screens).
 *
 * Pure so the whole decision matrix is JVM-testable. Production splits
 * the conjunction across where the data lives — [isEligible] in PostCard
 * (the SINGLE eligibility source feeding both the menu entry and the
 * inline slot), the quoted-context exclusion in PostCard (quoted cards
 * render narrower than any window heuristic assumes), and
 * [meetsInlineWidth] in ActionBar against its measured constraints —
 * while [showInline] composes them for the tests.
 *
 * READ_ONLY is deliberately absent here: PostCard hides the entire
 * action row behind `LocalCanSign` (finding 0.4), and the note menu adds
 * its own `LocalCanSign` check — identity gating stays upstream.
 */
object NoteReviewTrigger {

    /**
     * Minimum measured bar width for the inline slot, from the Phase A
     * math: five 48dp slots + four 8dp gaps (272) + one 8dp gap + one
     * 48dp target (328) + a 20dp populated-count budget. Below this the
     * slot is ABSENT — never shrunk below 48dp (the issue's a11y ruling)
     * and never left to Row squeeze semantics (which would clip the
     * trigger and counts unpredictably).
     */
    const val INLINE_MIN_BAR_WIDTH_DP = 348

    /**
     * The single eligibility source: flag ∧ image detected. [flagEnabled]
     * is parameterized so the flag-off behavior stays testable while the
     * kill switch remains a compile-time const.
     */
    fun isEligible(
        noteContent: String,
        flagEnabled: Boolean = FeatureFlags.NOTE_REVIEW_ENABLED,
    ): Boolean = flagEnabled && ImageUrls.extractImageUrls(noteContent).isNotEmpty()

    /** The width half of the inline gate — ActionBar's measured constraint. */
    fun meetsInlineWidth(availableBarWidthDp: Float): Boolean =
        availableBarWidthDp >= INLINE_MIN_BAR_WIDTH_DP

    /**
     * The full inline-slot decision, composed for tests: eligible, not a
     * quoted (reduced-width) render, and the bar measured wide enough.
     */
    fun showInline(
        noteContent: String,
        availableBarWidthDp: Float,
        isQuoted: Boolean,
        flagEnabled: Boolean = FeatureFlags.NOTE_REVIEW_ENABLED,
    ): Boolean = isEligible(noteContent, flagEnabled) &&
        !isQuoted &&
        meetsInlineWidth(availableBarWidthDp)
}
