package cooking.zap.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cooking.zap.app.api.RecipeWeek
import cooking.zap.app.ui.theme.ZapAmber
import cooking.zap.app.ui.theme.ZapOrange
import cooking.zap.app.util.RecipeTrend
import cooking.zap.app.util.RecipeTrendCopy

/** Sizing for the trend pill and for the slot the header reserves for it. */
object RecipeTrendPillDefaults {

    /**
     * The pill's height — and, because it is the same symbol at both call
     * sites, the height of the placeholder the header reserves while the
     * trend is still loading. Sharing the constant is what keeps the reserved
     * slot and the pill from drifting apart; [RecipeTrendPillTest] additionally
     * pins that it still accommodates [SparkHeight].
     */
    val Height: Dp = 24.dp

    /** Sparkline canvas, matching the web geometry (96×22 with 3px padding). */
    val SparkWidth: Dp = 96.dp
    val SparkHeight: Dp = 22.dp
    val SparkPad: Dp = 3.dp

    /**
     * Below this much leftover width the sparkline is dropped entirely rather
     * than squeezed into something unreadable. The copy is never what gives
     * way — the window clause ("last 3 weeks") is what makes the number
     * honest, so it must not be the thing that ellipsizes on a narrow screen.
     */
    val MinSparkWidth: Dp = 40.dp

    /** Gap between the number, the label, and the sparkline. */
    val Gap: Dp = 6.dp
}

/**
 * The Recipes header trend indicator: `↑ 21  new recipes · last 3 weeks` plus
 * a smooth sparkline of the weekly series.
 *
 * Layout priority is copy first. Both text runs are measured at their natural
 * width and the sparkline takes only what is left over, clamped to
 * [RecipeTrendPillDefaults.SparkWidth] and dropped below
 * [RecipeTrendPillDefaults.MinSparkWidth]. The alternative — letting the Row
 * squeeze the text — would truncate the window clause first and leave an
 * unexplained number on screen.
 *
 * The whole row is one merged semantics node, so TalkBack reads "21 new
 * recipes in the last three weeks" rather than announcing a bare number, an
 * unattached label, and a canvas.
 */
@Composable
fun RecipeTrendPill(
    count: Int,
    weeks: List<RecipeWeek>,
    modifier: Modifier = Modifier,
) {
    val defaults = RecipeTrendPillDefaults
    val countText = RecipeTrendCopy.count(count)
    val labelText = RecipeTrendCopy.label
    val countStyle = MaterialTheme.typography.labelLarge
    val labelStyle = MaterialTheme.typography.bodySmall
    val description = RecipeTrendCopy.contentDescription(count)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(defaults.Height)
            // One node for assistive tech: the number and its window are a
            // single fact, and the canvas has nothing to say on its own.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Measure the copy at its natural width so the sparkline can be given
        // exactly the remainder — never the other way round.
        val textWidth = remember(countText, labelText, countStyle, labelStyle, density) {
            with(density) {
                (measurer.measure(countText, countStyle).size.width +
                    measurer.measure(labelText, labelStyle).size.width).toDp()
            }
        }
        val leftover = maxWidth - textWidth - defaults.Gap * 2
        val sparkWidth = leftover.coerceAtMost(defaults.SparkWidth)
        val showSpark = sparkWidth >= defaults.MinSparkWidth

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(defaults.Gap),
        ) {
            Text(
                text = countText,
                style = countStyle,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            Text(
                text = labelText,
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            if (showSpark) {
                Sparkline(
                    weeks = weeks,
                    modifier = Modifier.size(width = sparkWidth, height = defaults.SparkHeight),
                )
            }
        }
    }
}

/**
 * The sparkline itself: a Catmull-Rom-smoothed line over the full weekly
 * series with a soft gradient area beneath it and a dot anchoring the latest
 * week.
 *
 * Geometry comes from [RecipeTrend], which is pure and unit-tested — this
 * draws the points, it does not compute them.
 */
@Composable
private fun Sparkline(
    weeks: List<RecipeWeek>,
    modifier: Modifier = Modifier,
) {
    val padPx = with(LocalDensity.current) { RecipeTrendPillDefaults.SparkPad.toPx() }
    Canvas(modifier = modifier) {
        val points = RecipeTrend.sparkPoints(weeks, size.width, size.height, padPx)
        if (points.isEmpty()) return@Canvas

        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            RecipeTrend.smoothSegments(points).forEach { s ->
                cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x, s.y)
            }
        }

        // Area fill first, so the stroke sits on top of it.
        val area = Path().apply {
            addPath(line)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(ZapOrange.copy(alpha = 0.20f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
        )

        // Horizontal sweep, orange into amber — a sized brush, since the
        // shared FAB gradient is diagonal with unspecified endpoints.
        drawPath(
            path = line,
            brush = Brush.linearGradient(
                colors = listOf(ZapOrange, ZapAmber),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
            ),
            style = Stroke(width = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )

        // Dot on the last point, anchoring "now".
        drawCircle(
            color = ZapAmber,
            radius = 2.dp.toPx(),
            center = Offset(points.last().x, points.last().y),
        )
    }
}

/**
 * The empty slot the header holds open while the trend is still resolving, so
 * the pill fades into pre-paid space instead of re-padding a live grid. Same
 * height constant as the pill by construction.
 */
@Composable
fun RecipeTrendPillPlaceholder(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(RecipeTrendPillDefaults.Height),
    )
}
