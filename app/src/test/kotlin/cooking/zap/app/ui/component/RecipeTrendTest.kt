package cooking.zap.app.ui.component

import cooking.zap.app.api.RecipeWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry/arithmetic for the "new recipes" trend pill.
 *
 * The live-series fixture is the real 12-bucket response captured from
 * `GET https://zap.cooking/api/pantry/recipes-by-week` on 2026-07-24, so the
 * pinned hero number is checkable against production rather than invented.
 */
class RecipeTrendTest {

    private fun weeks(vararg counts: Int): List<RecipeWeek> =
        counts.mapIndexed { i, c -> RecipeWeek(weekStart = "2026-01-%02d".format(i + 1), count = c) }

    /** Live series, 2026-05-04 … 2026-07-20. Max = 8, last three = 8 + 8 + 5. */
    private val liveSeries = weeks(1, 2, 2, 0, 1, 1, 2, 6, 4, 8, 8, 5)

    // --- recentCount ---

    @Test
    fun recentCount_emptySeries_isZero() {
        assertEquals(0, RecipeTrend.recentCount(emptyList()))
    }

    @Test
    fun recentCount_shorterThanWindow_sumsWhatExists() {
        assertEquals(7, RecipeTrend.recentCount(weeks(7)))
        assertEquals(9, RecipeTrend.recentCount(weeks(7, 2)))
    }

    @Test
    fun recentCount_exactlyWindowLength_sumsAll() {
        assertEquals(12, RecipeTrend.recentCount(weeks(3, 4, 5)))
    }

    @Test
    fun recentCount_longSeries_sumsOnlyLastThree() {
        // 8 + 8 + 5 — NOT the 40 of the full series.
        assertEquals(21, RecipeTrend.recentCount(liveSeries))
        assertEquals(40, liveSeries.sumOf { it.count })
    }

    @Test
    fun recentCount_allZero_isZero() {
        assertEquals(0, RecipeTrend.recentCount(weeks(0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun recentCount_gapWeeksInsideWindow_countAsZero() {
        // Zero-filled buckets must contribute 0, not be skipped over in favour
        // of older non-zero weeks.
        assertEquals(4, RecipeTrend.recentCount(weeks(9, 9, 4, 0, 0)))
    }

    @Test
    fun recentCount_customWindow() {
        assertEquals(5, RecipeTrend.recentCount(liveSeries, window = 1))
        assertEquals(40, RecipeTrend.recentCount(liveSeries, window = 12))
        assertEquals(40, RecipeTrend.recentCount(liveSeries, window = 99))
    }

    @Test
    fun recentCount_nonPositiveWindow_isZeroNotCrash() {
        assertEquals(0, RecipeTrend.recentCount(liveSeries, window = 0))
        assertEquals(0, RecipeTrend.recentCount(liveSeries, window = -3))
    }

    // --- sparkPoints ---

    @Test
    fun sparkPoints_emptySeries_isEmpty() {
        assertTrue(RecipeTrend.sparkPoints(emptyList(), 96f, 22f, 3f).isEmpty())
    }

    @Test
    fun sparkPoints_singlePoint_isHorizontallyCentered() {
        val pts = RecipeTrend.sparkPoints(weeks(4), 96f, 22f, 3f)
        assertEquals(1, pts.size)
        // width / 2 — never (i / 0).
        assertEquals(48f, pts[0].x, EPSILON)
        assertTrue(pts[0].x.isFinite() && pts[0].y.isFinite())
        // Its own max, so it sits at the top of the band.
        assertEquals(3f, pts[0].y, EPSILON)
    }

    @Test
    fun sparkPoints_allEqualCounts_isFlatLineAtTop() {
        val pts = RecipeTrend.sparkPoints(weeks(5, 5, 5, 5), 96f, 22f, 3f)
        assertEquals(4, pts.size)
        pts.forEach { assertEquals(3f, it.y, EPSILON) }
        // x still spans the full padded width.
        assertEquals(3f, pts.first().x, EPSILON)
        assertEquals(93f, pts.last().x, EPSILON)
    }

    @Test
    fun sparkPoints_allZero_flattensToBaselineWithoutNaN() {
        // max(0, 1) guard: no divide-by-zero, no NaN.
        val pts = RecipeTrend.sparkPoints(weeks(0, 0, 0), 96f, 22f, 3f)
        pts.forEach {
            assertTrue("expected finite, was ${it.y}", it.y.isFinite())
            assertEquals(19f, it.y, EPSILON) // height - pad
        }
    }

    @Test
    fun sparkPoints_maxCountSitsAtPad_zeroSitsAtHeightMinusPad() {
        val pts = RecipeTrend.sparkPoints(liveSeries, 96f, 22f, 3f)
        assertEquals(12, pts.size)
        // Indices 9 and 10 are the two count-8 peaks; index 3 is the zero week.
        assertEquals(3f, pts[9].y, EPSILON)
        assertEquals(3f, pts[10].y, EPSILON)
        assertEquals(19f, pts[3].y, EPSILON)
    }

    @Test
    fun sparkPoints_liveSeries_pinnedCoordinates() {
        val pts = RecipeTrend.sparkPoints(liveSeries, 96f, 22f, 3f)
        // x: pad + (i / 11) * (96 - 6); y: pad + (1 - count / 8) * (22 - 6)
        assertEquals(3f, pts[0].x, EPSILON)
        assertEquals(17f, pts[0].y, EPSILON) // count 1
        assertEquals(76.63636f, pts[9].x, EPSILON) // 3 + (9/11)*90
        assertEquals(93f, pts[11].x, EPSILON)
        assertEquals(9f, pts[11].y, EPSILON) // count 5
    }

    // --- smoothSegments (Catmull-Rom → cubic Bézier) ---

    @Test
    fun smoothSegments_fewerThanTwoPoints_isEmpty() {
        assertTrue(RecipeTrend.smoothSegments(emptyList()).isEmpty())
        assertTrue(RecipeTrend.smoothSegments(listOf(RecipeTrend.Point(1f, 2f))).isEmpty())
    }

    @Test
    fun smoothSegments_twoPoints_duplicatesBothEndpoints() {
        val segs = RecipeTrend.smoothSegments(
            listOf(RecipeTrend.Point(0f, 10f), RecipeTrend.Point(10f, 0f))
        )
        assertEquals(1, segs.size)
        val s = segs[0]
        assertEquals(1.6666666f, s.c1x, EPSILON)
        assertEquals(8.333333f, s.c1y, EPSILON)
        assertEquals(8.333333f, s.c2x, EPSILON)
        assertEquals(1.6666666f, s.c2y, EPSILON)
        assertEquals(10f, s.x, EPSILON)
        assertEquals(0f, s.y, EPSILON)
    }

    @Test
    fun smoothSegments_threePoints_matchesWebControlPointMath() {
        val segs = RecipeTrend.smoothSegments(
            listOf(
                RecipeTrend.Point(0f, 0f),
                RecipeTrend.Point(10f, 10f),
                RecipeTrend.Point(20f, 0f),
            )
        )
        assertEquals(2, segs.size)

        val first = segs[0]
        assertEquals(1.6666666f, first.c1x, EPSILON)
        assertEquals(1.6666666f, first.c1y, EPSILON)
        assertEquals(6.6666665f, first.c2x, EPSILON)
        assertEquals(10f, first.c2y, EPSILON)
        assertEquals(10f, first.x, EPSILON)
        assertEquals(10f, first.y, EPSILON)

        val second = segs[1]
        assertEquals(13.333333f, second.c1x, EPSILON)
        assertEquals(10f, second.c1y, EPSILON)
        assertEquals(18.333334f, second.c2x, EPSILON)
        assertEquals(1.6666666f, second.c2y, EPSILON)
        assertEquals(20f, second.x, EPSILON)
        assertEquals(0f, second.y, EPSILON)
    }

    @Test
    fun smoothSegments_endpointsAreTheDataPoints() {
        // The curve must pass through every sample: segment i ends on point i+1.
        val pts = RecipeTrend.sparkPoints(liveSeries, 96f, 22f, 3f)
        val segs = RecipeTrend.smoothSegments(pts)
        assertEquals(pts.size - 1, segs.size)
        segs.forEachIndexed { i, seg ->
            assertEquals(pts[i + 1].x, seg.x, EPSILON)
            assertEquals(pts[i + 1].y, seg.y, EPSILON)
        }
    }

    @Test
    fun smoothSegments_flatSeries_staysFlat() {
        // An all-equal series must not bow: every control point shares the y.
        val pts = RecipeTrend.sparkPoints(weeks(5, 5, 5, 5), 96f, 22f, 3f)
        RecipeTrend.smoothSegments(pts).forEach { seg ->
            assertEquals(3f, seg.c1y, EPSILON)
            assertEquals(3f, seg.c2y, EPSILON)
            assertEquals(3f, seg.y, EPSILON)
        }
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
