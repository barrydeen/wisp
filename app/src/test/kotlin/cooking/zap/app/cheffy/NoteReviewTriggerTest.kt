package cooking.zap.app.cheffy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NoteReviewTrigger] — the inline-slot decision matrix from issue
 * #150's Phase A findings (option 1). Production wiring splits this
 * conjunction across where the data lives — [NoteReviewTrigger.isEligible]
 * is PostCard's SINGLE eligibility source feeding both placements,
 * [NoteReviewTrigger.meetsInlineWidth] is ActionBar's measured gate, and
 * the quoted-context exclusion is PostCard's — [NoteReviewTrigger.showInline]
 * composes them so the whole matrix is pinned here on the JVM.
 *
 * Non-note surfaces are pinned at the compile level: the new ActionBar
 * parameter defaults to null, and the four non-note call sites
 * (GalleryCard, ArticleScreen, RecipeDetailScreen, FeedScreen's article
 * card) are untouched by the PR — a null trigger renders the pre-change
 * five-slot bar with no measurement pass at all.
 */
class NoteReviewTriggerTest {

    private val imageNote = "made this tonight https://image.nostr.build/dish.jpg"
    private val imagelessNote = "no photo, just vibes and a recipe link https://zap.cooking/recipe/1"

    // --- Threshold boundary (recalibrated: T = 324dp measured bar width,
    // issue #150 follow-up — six-slot gaps compress 8dp→2dp) ---

    @Test
    fun thresholdBoundary_323Absent_324Present() {
        assertFalse(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 323f, isQuoted = false, flagEnabled = true),
        )
        assertTrue(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 324f, isQuoted = false, flagEnabled = true),
        )
        // The width half in isolation — ActionBar's exact gate.
        assertFalse(NoteReviewTrigger.meetsInlineWidth(323.99f))
        assertTrue(NoteReviewTrigger.meetsInlineWidth(324f))
        assertEquals(324, NoteReviewTrigger.INLINE_MIN_BAR_WIDTH_DP)
    }

    @Test
    fun fieldDevice_384dpPortrait_showsTheSlot() {
        // 1080×2408 @ 450dpi → 384dp portrait window, ~332dp bar (window −
        // 52dp inset). The primary developer's phone: overflow-only under
        // the old 348 threshold, now clears with an 8dp margin. This is the
        // regression this PR exists to fix.
        assertTrue(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 332f, isQuoted = false, flagEnabled = true),
        )
        // 393-class (bar 341dp) clears too.
        assertTrue(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 341f, isQuoted = false, flagEnabled = true),
        )
    }

    // --- Eligibility (the single source: flag ∧ image detected) ---

    @Test
    fun imagelessNote_neverShowsTheSlot_atAnyWidth() {
        assertFalse(NoteReviewTrigger.isEligible(imagelessNote, flagEnabled = true))
        for (width in listOf(323f, 324f, 720f, 10_000f)) {
            assertFalse(
                NoteReviewTrigger.showInline(imagelessNote, width, isQuoted = false, flagEnabled = true),
            )
        }
    }

    @Test
    fun flagOff_neverShowsTheSlot_atAnyWidth() {
        // The kill switch is a compile-time const in production; the
        // parameterized flag pins the logic without weakening it.
        assertFalse(NoteReviewTrigger.isEligible(imageNote, flagEnabled = false))
        for (width in listOf(348f, 10_000f)) {
            assertFalse(
                NoteReviewTrigger.showInline(imageNote, width, isQuoted = false, flagEnabled = false),
            )
        }
    }

    @Test
    fun eligibility_isImageUrlsParity() {
        // Same detector as the menu entry and the sheet — one source.
        assertTrue(NoteReviewTrigger.isEligible(imageNote, flagEnabled = true))
        assertFalse(NoteReviewTrigger.isEligible("", flagEnabled = true))
        // Blossom bare-hash URL: not an image per finding 0.5 parity, so
        // no trigger in EITHER placement.
        assertFalse(
            NoteReviewTrigger.isEligible("https://blossom.example.com/${"a".repeat(64)}", flagEnabled = true),
        )
    }

    // --- Quoted-context exclusion ---

    @Test
    fun quotedRender_neverShowsTheSlot_evenWide() {
        assertFalse(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 800f, isQuoted = true, flagEnabled = true),
        )
    }

    // --- 320dp regression guard (pre-existing clipping is out of scope) ---

    @Test
    fun narrowWidths_renderTheIdenticalFiveSlotBar() {
        // Five-slot byte-identical pins: below the 324dp threshold the slot
        // is absent, so no six-slot gap compression applies and the bar
        // renders exactly what main renders today.
        //
        // A 320dp window gives the bar 268dp (window − 52). The slot must
        // be absent there, so this change contributes nothing to the
        // pre-existing 320dp five-slot clipping (tracked separately).
        assertFalse(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 268f, isQuoted = false, flagEnabled = true),
        )
        // And the modal 360dp phone (bar 308dp) stays overflow-only: only
        // 12dp count headroom over the six-slot hard minimum, so a wide
        // count would clip — not forced (issue #150 zero-margin finding).
        assertFalse(
            NoteReviewTrigger.showInline(imageNote, availableBarWidthDp = 308f, isQuoted = false, flagEnabled = true),
        )
    }

    // --- The composition IS the production conjunction ---

    @Test
    fun showInline_isExactlyTheConjunctionOfItsThreeGates() {
        // Guards drift between the tested composition and the split
        // production wiring: showInline must equal
        // isEligible ∧ !quoted ∧ meetsInlineWidth for every combination.
        for (content in listOf(imageNote, imagelessNote)) {
            for (flag in listOf(true, false)) {
                for (quoted in listOf(true, false)) {
                    for (width in listOf(200f, 323f, 324f, 332f, 800f)) {
                        val expected = NoteReviewTrigger.isEligible(content, flag) &&
                            !quoted &&
                            NoteReviewTrigger.meetsInlineWidth(width)
                        assertEquals(
                            "content=$content flag=$flag quoted=$quoted width=$width",
                            expected,
                            NoteReviewTrigger.showInline(content, width, quoted, flag),
                        )
                    }
                }
            }
        }
    }
}
