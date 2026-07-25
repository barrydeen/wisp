package cooking.zap.app.ui.component

import androidx.compose.ui.unit.dp
import cooking.zap.app.util.RecipeTrend
import cooking.zap.app.util.RecipeTrendCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things about the pill that are checkable without a device: its
 * sizing contract with the header's reserved slot, and its copy.
 *
 * The visual behaviour (no reflow at first paint, the collapse animation,
 * TalkBack) is verified on device — Compose tests asserting it would be
 * brittle theatre.
 */
class RecipeTrendPillTest {

    // --- Sizing ---

    @Test
    fun declaredHeightAccommodatesTheSparkline() {
        // The header reserves RecipeTrendPillDefaults.Height while loading and
        // the pill fills it, so the pill's tallest child must fit inside it.
        // Bump the sparkline past the pill height and the reserved slot silently
        // stops matching what lands in it — which is the reflow the reservation
        // exists to prevent.
        assertTrue(
            "pill height ${RecipeTrendPillDefaults.Height} must accommodate " +
                "sparkline ${RecipeTrendPillDefaults.SparkHeight}",
            RecipeTrendPillDefaults.Height >= RecipeTrendPillDefaults.SparkHeight,
        )
    }

    @Test
    fun sparklinePaddingFitsInsideTheSparklineBox() {
        // sparkPoints maps the max-count bucket to `pad` and a zero bucket to
        // `height - pad`; padding at or past half the height would invert that.
        assertTrue(
            RecipeTrendPillDefaults.SparkPad * 2 < RecipeTrendPillDefaults.SparkHeight,
        )
    }

    @Test
    fun minSparkWidthIsNarrowerThanTheFullSparkline() {
        // Otherwise the drop threshold would fire even at full width.
        assertTrue(
            RecipeTrendPillDefaults.MinSparkWidth < RecipeTrendPillDefaults.SparkWidth,
        )
        assertTrue(RecipeTrendPillDefaults.MinSparkWidth > 0.dp)
    }

    // --- Copy ---

    @Test
    fun copy_assemblesTheApprovedString() {
        assertEquals("↑ 21", RecipeTrendCopy.count(21))
        assertEquals("new recipes · last 3 weeks", RecipeTrendCopy.label)
    }

    @Test
    fun copy_labelStatesTheWindowInline() {
        // On web the window lives in a title= tooltip. There is no hover here,
        // so if the window ever leaves the visible label the number becomes
        // unexplained.
        assertTrue(
            "the visible label must state the window",
            RecipeTrendCopy.label.contains("${RecipeTrend.WINDOW_WEEKS} weeks"),
        )
    }

    @Test
    fun copy_neverImpliesTheFeed() {
        // The counted set overlaps the feed's set but neither contains the
        // other, so the number cannot be reconciled by scrolling.
        val all = (RecipeTrendCopy.count(21) + RecipeTrendCopy.label +
            RecipeTrendCopy.contentDescription(21)).lowercase()
        listOf("feed", "here", "below", "pantry").forEach {
            assertTrue("copy must not contain '$it'", !all.contains(it))
        }
    }

    @Test
    fun copy_handlesSingleDigitAndThreeDigitCounts() {
        assertEquals("↑ 7", RecipeTrendCopy.count(7))
        assertEquals("↑ 128", RecipeTrendCopy.count(128))
        assertEquals(
            "128 new recipes in the last three weeks",
            RecipeTrendCopy.contentDescription(128),
        )
    }

    @Test
    fun contentDescription_readsAsOnePhrase() {
        // One merged semantics node: number and window spoken together, with
        // the window spelled out rather than left as a bare digit.
        assertEquals(
            "21 new recipes in the last three weeks",
            RecipeTrendCopy.contentDescription(21),
        )
    }
}
