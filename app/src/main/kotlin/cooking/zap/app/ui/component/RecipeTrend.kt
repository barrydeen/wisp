package cooking.zap.app.ui.component

import cooking.zap.app.api.RecipeWeek

/**
 * Geometry + arithmetic for the "new recipes" trend indicator, ported from the
 * web `/recipes` page (frontend `src/routes/recipes/+page.svelte`).
 *
 * Deliberately **pure Kotlin** — no Android, no Compose, no `dp`. Everything
 * here is deterministic and covered by JVM unit tests; the drawing code that
 * consumes it (and the one shared height constant it will need) lands with the
 * pill composable, not here.
 *
 * The one intentional divergence from web: the web helper rounds every emitted
 * coordinate to one decimal because it serializes an SVG `d` attribute. Compose
 * draws from floats directly, so no rounding is applied and the tests pin the
 * un-rounded values.
 */
object RecipeTrend {

    /**
     * Weeks summed into the hero number. A one-week count reads as empty early
     * in the week (few recipes published yet); a three-week rolling sum stays
     * meaningful on a Monday morning. Any UI showing this number must state the
     * window in visible copy — on Android there is no hover to explain it.
     */
    const val WINDOW_WEEKS = 3

    /** A point in the sparkline's local coordinate space (y grows downward). */
    data class Point(val x: Float, val y: Float)

    /**
     * One cubic Bézier segment: two control points and an endpoint. The curve
     * starts at the previous point, so a caller draws `moveTo(points.first())`
     * then one `cubicTo` per segment.
     */
    data class CubicSegment(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    )

    /**
     * Sum of the last [window] buckets — the hero number. Mirrors the web
     * `weeks.slice(-3).reduce(...)`: a series shorter than the window simply
     * sums everything it has, and an empty series is 0. A non-positive window
     * is 0 rather than an exception — this feeds a decorative pill, and no
     * arithmetic edge case is worth crashing the Recipes tab for.
     */
    fun recentCount(weeks: List<RecipeWeek>, window: Int = WINDOW_WEEKS): Int {
        if (window <= 0) return 0
        return weeks.takeLast(window).sumOf { it.count }
    }

    /**
     * Map the full series onto sparkline coordinates, y-scaled to the series
     * max.
     *
     * Two guards ported from web, both load-bearing:
     * - the divisor is `max(seriesMax, 1)`, so an all-zero series flattens to
     *   the baseline instead of dividing by zero;
     * - a single-point series is centered horizontally (`width / 2`) rather
     *   than dividing by `size - 1 == 0`.
     *
     * The max-count bucket sits exactly at [pad] from the top; a zero bucket
     * sits at `height - pad`.
     */
    fun sparkPoints(
        weeks: List<RecipeWeek>,
        width: Float,
        height: Float,
        pad: Float,
    ): List<Point> {
        if (weeks.isEmpty()) return emptyList()
        val max = weeks.maxOf { it.count }.coerceAtLeast(1).toFloat()
        val lastIndex = weeks.size - 1
        return weeks.mapIndexed { i, week ->
            val x = if (lastIndex == 0) {
                width / 2f
            } else {
                pad + (i.toFloat() / lastIndex) * (width - pad * 2f)
            }
            val y = pad + (1f - week.count / max) * (height - pad * 2f)
            Point(x, y)
        }
    }

    /**
     * Convert [points] into cubic Bézier segments along a Catmull-Rom spline,
     * so the line curves instead of showing polyline corners.
     *
     * Ported verbatim from the web `smoothPath`: control points are offset by a
     * sixth of the surrounding points' span, and both ends duplicate their
     * neighbour (`pts[i - 1] ?: pts[i]`, `pts[i + 2] ?: p2`) so the first and
     * last segments stay anchored. Fewer than two points yields no segments —
     * there is nothing to draw between.
     */
    fun smoothSegments(points: List<Point>): List<CubicSegment> {
        if (points.size < 2) return emptyList()
        return (0 until points.size - 1).map { i ->
            val p0 = points.getOrElse(i - 1) { points[i] }
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points.getOrElse(i + 2) { p2 }
            CubicSegment(
                c1x = p1.x + (p2.x - p0.x) / 6f,
                c1y = p1.y + (p2.y - p0.y) / 6f,
                c2x = p2.x - (p3.x - p1.x) / 6f,
                c2y = p2.y - (p3.y - p1.y) / 6f,
                x = p2.x,
                y = p2.y,
            )
        }
    }
}
