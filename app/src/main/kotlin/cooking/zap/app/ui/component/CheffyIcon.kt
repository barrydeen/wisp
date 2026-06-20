package cooking.zap.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cooking.zap.app.cheffy.Cheffy

/**
 * Cheffy — Zap Cooking's kitchen-companion mascot (concern 2.3). A **compact**
 * port of the web `CheffyIcon.svelte` color variant: a rounded face under an
 * oversized, slightly tilted chef toque whose right fold is a **Zap lightning
 * accent** (the signature). The toque uses the app theme's primary so it tints
 * with the "zapcooking" palette; face/ink/bolt are the web's fixed brand
 * colors. The `character`/avatar variant is deferred to 2.3b.
 *
 * Built from the web's exact SVG path data (viewBox 0 0 64 64) via [PathParser]
 * so the silhouette matches the web at any size.
 */
@Composable
fun CheffyIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    expression: Cheffy.Expression = Cheffy.Expression.NEUTRAL,
) {
    val hat = MaterialTheme.colorScheme.primary
    val face = Color(0xFFF6DCA6)
    val ink = Color(0xFF3A2415)
    val bolt = Color(0xFFFFC83A)
    val boltEdge = Color(0xFFC23A00)

    val g = GEOMETRY.getValue(expression)

    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 64f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            // Face.
            drawPath(FACE, color = face, style = Fill)

            // Toque (band + 3 puffs + Zap), tilted -4° about (32,18) like the web.
            withTransform({ rotate(-4f, pivot = Offset(32f, 18f)) }) {
                drawCircle(hat, radius = 9f, center = Offset(20.5f, 12.5f))
                drawCircle(hat, radius = 10f, center = Offset(32f, 8.5f))
                drawCircle(hat, radius = 8f, center = Offset(43.5f, 13f))
                drawPath(BAND, color = hat, style = Fill)
                // Zap fold accent — the signature feature.
                drawPath(ZAP, color = bolt, style = Fill)
                drawPath(ZAP, color = boltEdge, style = Stroke(width = 0.8f))
            }

            // Eyes.
            if (g.eyeStyle == EyeStyle.HAPPY) {
                drawPath(HAPPY_LEFT_EYE, color = ink, style = Stroke(width = 2.5f))
                drawPath(HAPPY_RIGHT_EYE, color = ink, style = Stroke(width = 2.5f))
            } else {
                val r = when (g.eyeStyle) {
                    EyeStyle.WIDE -> 3.9f
                    EyeStyle.SMALL -> 2.8f
                    else -> 3.3f // ROUND, UP
                }
                val dy = if (g.eyeStyle == EyeStyle.UP) -0.8f else 0f
                for (cx in listOf(LEFT_EYE, RIGHT_EYE)) {
                    drawCircle(ink, radius = r, center = Offset(cx, EYE_Y + dy))
                    drawCircle(Color.White, radius = r * 0.32f, center = Offset(cx + 1.1f, EYE_Y + dy - 1.1f))
                }
            }

            // Brow + mouth.
            drawPath(g.brow, color = ink, style = Stroke(width = 1.9f))
            drawPath(g.mouth, color = ink, style = if (g.mouthFilled) Fill else Stroke(width = 2f))
        }
    }
}

private enum class EyeStyle { ROUND, WIDE, SMALL, HAPPY, UP }

private class Geometry(
    val eyeStyle: EyeStyle,
    val mouth: Path,
    val mouthFilled: Boolean,
    val brow: Path,
)

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private const val LEFT_EYE = 25.6f
private const val RIGHT_EYE = 38.6f
private const val EYE_Y = 39f

// All paths are parsed ONCE at class-load (viewBox 0 0 64 64). CheffyIcon can
// render in lists (drawer, pending bubble), so we never re-allocate a
// PathParser per draw. The parsed Paths are only read, never mutated.
private val FACE = parse(
    "M32 22.5 C19.8 22.5 13 30.5 13 39.5 C13 50.2 21.2 56.5 32 56.5 C42.8 56.5 51 50.2 51 39.5 C51 30.5 44.2 22.5 32 22.5 Z",
)
private val BAND = parse(
    "M16.5 18 L47.5 18 Q49 18 49 20.5 L49 23.5 Q49 25.5 47 25.5 L17 25.5 Q15 25.5 15 23.5 L15 20.5 Q15 18 16.5 18 Z",
)
private val ZAP = parse("M46 6 L40.4 14.5 L45 14.5 L39.4 23.5 L52 11.5 L46.4 11.5 L50.4 6 Z")
private val HAPPY_LEFT_EYE = parse("M22.6 40 Q25.6 36.4 28.6 40")
private val HAPPY_RIGHT_EYE = parse("M35.6 40 Q38.6 36.4 41.6 40")

// Mirrors the web CheffyIcon expression switch (eyes/brow/mouth per mood),
// parsed once per expression.
private val GEOMETRY: Map<Cheffy.Expression, Geometry> = mapOf(
    Cheffy.Expression.HAPPY to Geometry(
        EyeStyle.HAPPY, parse("M26.8 45.6 Q32.7 51.8 38.4 45.8"), false, parse("M35.4 32.6 Q38.6 30.8 41.8 32.2"),
    ),
    Cheffy.Expression.THINKING to Geometry(
        EyeStyle.UP, parse("M30.4 48.4 Q33.2 47.2 35.8 48.8"), false, parse("M35.2 31.2 Q38.8 29.5 42.2 31.0"),
    ),
    Cheffy.Expression.EXCITED to Geometry(
        EyeStyle.WIDE, parse("M27.4 45.2 Q32.5 53.0 37.6 45.2 Z"), true, parse("M35.2 31.4 Q38.6 29.6 42.0 31.2"),
    ),
    Cheffy.Expression.CONCERNED to Geometry(
        EyeStyle.SMALL, parse("M28.8 49.0 Q32.5 46.4 36.2 49.0"), false, parse("M35.4 31.0 Q38.4 32.2 41.8 30.6"),
    ),
    Cheffy.Expression.COOKING to Geometry(
        EyeStyle.SMALL, parse("M28.0 46.0 Q32.6 51.2 37.0 46.0"), false, parse("M35.4 32.2 Q38.6 30.5 41.8 31.8"),
    ),
    Cheffy.Expression.NEUTRAL to Geometry(
        EyeStyle.ROUND, parse("M28.6 46.4 Q33.0 49.8 36.8 46.4"), false, parse("M35.6 33.0 Q38.6 31.3 41.6 32.6"),
    ),
)
