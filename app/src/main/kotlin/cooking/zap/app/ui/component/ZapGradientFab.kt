package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cooking.zap.app.R

// Zap Cooking web palette gradient: amber-500 (#F59E0B) → orange-500 (#F97316).
// Amber at the top-left fading into deeper orange at the bottom-right matches
// the brand's FAB style.
private val ZapAmber = Color(0xFFF59E0B)
private val ZapOrange = Color(0xFFF97316)
val ZapFabGradient = Brush.linearGradient(colors = listOf(ZapAmber, ZapOrange))

private val FAB_SIZE = 56.dp

/**
 * Circular FAB filled with the Zap Cooking brand gradient.
 *
 * Built from a [Box] rather than Material's [androidx.compose.material3.FloatingActionButton]
 * on purpose: the Material FAB draws its own Surface (tonal + shadow layers), and with a
 * transparent container that surface leaves a visible seam over the gradient. A single
 * clipped Box gives a clean circle with no artifacts.
 */
@Composable
fun ZapGradientFab(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
            .size(FAB_SIZE)
            .clip(CircleShape)
            .background(brush = ZapFabGradient)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            content != null -> content()
            // Default: the bold "+" tuned to the Edit pencil's stroke weight.
            icon == null -> Icon(
                painter = painterResource(R.drawable.ic_fab_plus),
                contentDescription = contentDescription,
                tint = Color.White
            )
            else -> Icon(icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}
