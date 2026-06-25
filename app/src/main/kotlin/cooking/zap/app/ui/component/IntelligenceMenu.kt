package cooking.zap.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cooking.zap.app.R

/**
 * Intelligence menu — the top-bar AI entry, mirroring the web's
 * `IntelligenceMenu`: a single atom-glyph icon button that opens a compact
 * dropdown listing the AI tools in the web's order (Sous Chef → Cheffy →
 * Nourish), each routing to its existing destination.
 *
 * Self-contained so the Feed and Recipes top bars share one structure; the
 * caller only supplies the per-item navigation actions.
 */
@Composable
fun IntelligenceMenu(
    onSousChef: () -> Unit,
    onCheffy: () -> Unit,
    onNourish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.cd_intelligence_menu)

    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = menuLabel },
        ) {
            AtomIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_souschef)) },
                leadingIcon = {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = SousChefPurple)
                },
                onClick = {
                    expanded = false
                    onSousChef()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_cheffy)) },
                leadingIcon = { CheffyIcon(size = 20.dp) },
                onClick = {
                    expanded = false
                    onCheffy()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.title_nourish_hub)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Eco, contentDescription = null, tint = NourishGreen)
                },
                onClick = {
                    expanded = false
                    onNourish()
                },
            )
        }
    }
}

/** Nourish "green island" accent — matches the NourishCard strong green. */
private val NourishGreen = Color(0xFF22C55E)

/**
 * Atom glyph — a nucleus orbited by three elliptical electron paths at 0/60/120°,
 * mirroring the web's `IntelligenceIcon`. Drawn so it scales crisply at any size.
 */
@Composable
private fun AtomIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val stroke = Stroke(width = w * 0.06f)
        val rx = w * 0.46f
        val ry = h * 0.18f
        val orbitSize = Size(rx * 2f, ry * 2f)
        val orbitTopLeft = Offset(cx - rx, cy - ry)

        for (angle in listOf(0f, 60f, 120f)) {
            rotate(degrees = angle, pivot = Offset(cx, cy)) {
                drawOval(color = tint, topLeft = orbitTopLeft, size = orbitSize, style = stroke)
            }
        }
        drawCircle(color = tint, radius = w * 0.09f, center = Offset(cx, cy))
    }
}
