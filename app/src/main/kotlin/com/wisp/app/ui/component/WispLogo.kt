package com.wisp.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Wisp app logo — a glowing core with flowing wisp trails.
 *
 * @param size The overall size of the logo mark (icon only)
 * @param showText Whether to show the "wisp" wordmark below the icon
 */
@Composable
fun WispLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    showText: Boolean = true
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.size(size)) {
            drawWispMark(primary, secondary)
        }

        if (showText) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "wisp",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = primary
            )
        }
    }
}

private fun DrawScope.drawWispMark(primary: Color, secondary: Color) {
    val w = size.width
    val h = size.height

    // Center-bottom is the origin point of the wisp
    val coreX = w * 0.5f
    val coreY = h * 0.72f

    // --- Main wisp trail (center, flowing upward with an elegant S-curve) ---
    val mainPath = Path().apply {
        moveTo(coreX, coreY)
        cubicTo(
            coreX - w * 0.05f, coreY - h * 0.2f,   // cp1: slight left
            coreX + w * 0.15f, coreY - h * 0.4f,    // cp2: swing right
            coreX + w * 0.02f, coreY - h * 0.62f     // end: drift back center-ish
        )
    }
    drawPath(
        path = mainPath,
        brush = Brush.verticalGradient(
            colors = listOf(primary.copy(alpha = 0.0f), primary),
            startY = coreY - h * 0.62f,
            endY = coreY
        ),
        style = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
    )

    // --- Left wisp trail (shorter, curves left) ---
    val leftPath = Path().apply {
        moveTo(coreX, coreY)
        cubicTo(
            coreX - w * 0.1f, coreY - h * 0.15f,
            coreX - w * 0.25f, coreY - h * 0.25f,
            coreX - w * 0.22f, coreY - h * 0.42f
        )
    }
    drawPath(
        path = leftPath,
        brush = Brush.verticalGradient(
            colors = listOf(secondary.copy(alpha = 0.0f), secondary.copy(alpha = 0.7f)),
            startY = coreY - h * 0.42f,
            endY = coreY
        ),
        style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
    )

    // --- Right wisp trail (shortest, quick flick right) ---
    val rightPath = Path().apply {
        moveTo(coreX, coreY)
        cubicTo(
            coreX + w * 0.08f, coreY - h * 0.1f,
            coreX + w * 0.22f, coreY - h * 0.18f,
            coreX + w * 0.25f, coreY - h * 0.32f
        )
    }
    drawPath(
        path = rightPath,
        brush = Brush.verticalGradient(
            colors = listOf(primary.copy(alpha = 0.0f), primary.copy(alpha = 0.5f)),
            startY = coreY - h * 0.32f,
            endY = coreY
        ),
        style = Stroke(width = w * 0.025f, cap = StrokeCap.Round)
    )

    // --- Glowing core dot ---
    // Outer glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = 0.3f), Color.Transparent),
            center = Offset(coreX, coreY),
            radius = w * 0.12f
        ),
        radius = w * 0.12f,
        center = Offset(coreX, coreY)
    )
    // Inner bright dot
    drawCircle(
        color = primary,
        radius = w * 0.04f,
        center = Offset(coreX, coreY)
    )
    // Hot center
    drawCircle(
        color = Color.White,
        radius = w * 0.018f,
        center = Offset(coreX, coreY)
    )
}
