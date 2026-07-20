package com.wisp.app.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wisp.app.viewmodel.thread.ThreadFlattener
import kotlin.math.min

/** Per-level indent step for threaded replies. */
private val INDENT_STEP: Dp = 16.dp

/**
 * Start-padding indent for a reply at [depth], clamped to the thread depth cap so the rail
 * never runs out of horizontal space.
 */
fun threadIndentDp(depth: Int, cap: Int = ThreadFlattener.DEPTH_CAP, step: Dp = INDENT_STEP): Dp =
    step * min(depth, cap)

/**
 * Depth connector: a single vertical rail into a rounded corner and a short horizontal run to
 * the reply — instead of one straight line per ancestor level. Drawn behind a row when [show]
 * is true (depth > 0). [indent] is the row's start padding (from [threadIndentDp]); the rail
 * lands one corner radius left of the card's padded edge so the arc's horizontal run meets the
 * card edge exactly.
 *
 * When [dashedTop] is true the top of the rail is drawn dashed, signalling that the guide line
 * continues upward to a parent that isn't the row directly above (e.g. the first reply of a
 * branch, or a reply revealed by expanding a folded subtree) — so the rail doesn't look like it
 * starts "in mid-air."
 *
 * Shared by ThreadScreen and ArticleScreen so the two indent paths can't drift.
 */
fun Modifier.threadConnector(
    show: Boolean,
    indent: Dp,
    lineColor: Color,
    cornerRadius: Dp = 8.dp,
    stroke: Dp = 1.dp,
    dashedTop: Boolean = false,
    dashLength: Dp = 14.dp
): Modifier = this.drawBehind {
    if (!show) return@drawBehind
    val r = cornerRadius.toPx()
    val strokePx = stroke.toPx()
    val lineX = indent.toPx() - r + strokePx
    val railBottom = size.height - r

    if (dashedTop && railBottom > 0f) {
        val dashEnd = min(railBottom, dashLength.toPx())
        val dashOn = (stroke * 3).toPx()
        val dashOff = (stroke * 3).toPx()
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = lineColor
                strokeWidth = strokePx
                style = PaintingStyle.Stroke
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
                isAntiAlias = true
            }
            canvas.drawLine(Offset(lineX, 0f), Offset(lineX, dashEnd), paint)
        }
        if (dashEnd < railBottom) {
            drawLine(
                color = lineColor,
                start = Offset(lineX, dashEnd),
                end = Offset(lineX, railBottom),
                strokeWidth = strokePx
            )
        }
    } else {
        drawLine(
            color = lineColor,
            start = Offset(lineX, 0f),
            end = Offset(lineX, railBottom),
            strokeWidth = strokePx
        )
    }
    drawArc(
        color = lineColor,
        startAngle = 90f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(lineX, size.height - 2f * r),
        size = Size(2f * r, 2f * r),
        style = Stroke(width = strokePx, cap = StrokeCap.Round)
    )
    drawLine(
        color = lineColor,
        start = Offset(lineX + r, size.height),
        end = Offset(size.width, size.height),
        strokeWidth = strokePx
    )
}
