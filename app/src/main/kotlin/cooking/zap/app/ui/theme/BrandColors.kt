package cooking.zap.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Zap Cooking's brand accent pair, matching the web palette: amber-500 →
 * orange-500. Fixed rather than theme-derived — this is the brand mark, and it
 * reads the same in light and dark.
 */
val ZapAmber = Color(0xFFF59E0B)
val ZapOrange = Color(0xFFF97316)

/**
 * Diagonal sweep for filled surfaces (the create-recipe FAB): amber at the
 * top-left fading into deeper orange at the bottom-right. Endpoints are
 * unspecified, so the gradient spans whatever it is drawn into — which makes
 * it wrong for a horizontal stroke; build a sized brush for those.
 */
val ZapFabGradient = Brush.linearGradient(colors = listOf(ZapAmber, ZapOrange))
