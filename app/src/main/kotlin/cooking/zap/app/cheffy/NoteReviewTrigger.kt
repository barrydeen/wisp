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
     * Minimum measured bar width for the inline (six-slot) layout.
     *
     * Recalibrated in the issue #150 follow-up: the original 348 excluded
     * a large share of real phones. The field device (1080×2408 @ 450dpi →
     * 384dp portrait window, ~332dp bar) fell below 348 and saw the slot
     * only in landscape, yet portrait is the dominant feed orientation.
     *
     * Derived, not hand-tuned. In six-slot mode the four inter-slot gaps
     * compress 8dp→2dp (ActionBar `slotGap`; the five-slot bar keeps 8dp
     * and is byte-identical), so:
     *
     * ```
     *   6 × 48dp touch targets ............... 288dp  a11y floor (decision 2,
     *                                                 never moves)
     *   4 × 2dp inter-slot gaps ..............   8dp  six-slot mode only
     *   ------------------------------------------
     *   six-slot hard minimum ............... 296dp  weight(1f) separation
     *                                                 gap collapses to 0 here
     *   + separation / populated-count budget  28dp  ml-auto gap + room for
     *                                                 3-digit counts before
     *                                                 the weight spacer hits 0
     *   ==========================================
     *   INLINE_MIN_BAR_WIDTH_DP ............. 324dp
     * ```
     *
     * The 28dp budget preserves the count headroom the shipped 348 gave at
     * its own threshold (348 − 320 hard-min = 28), so counts are no more
     * likely to clip than today. The bar is inset ~52dp from the window, so
     * a 324dp bar ⇒ ~376dp window. Portrait windows that clear it:
     *
     * - 384dp-class (332dp bar, the field device) — 8dp margin, 36dp count
     *   headroom. **Clears.**
     * - 393dp-class (341dp bar) / 411dp-class (359dp bar). **Clear.**
     * - 360dp-class (308dp bar) stays overflow-only: only 12dp count
     *   headroom, so a wide reaction/zap count would clip the same way the
     *   #150 investigation found at 320dp with five slots. Not forced.
     *
     * Below the threshold the slot is ABSENT — never shrunk below 48dp
     * (the a11y ruling) and never left to Row squeeze semantics (which
     * would clip the trigger and counts unpredictably).
     */
    const val INLINE_MIN_BAR_WIDTH_DP = 324

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
