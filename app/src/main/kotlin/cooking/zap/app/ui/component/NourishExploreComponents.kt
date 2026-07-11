package cooking.zap.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.nostr.NourishParser

private val NourishGreen = Color(0xFF22C55E)

/**
 * Explore tile: existing [RecipeCard] poster + Phase 4 macros strip when
 * present, plus a compact overall score. Tapping opens the recipe (Nourish
 * profile lives on the detail/hub path).
 */
@Composable
fun NourishExploreRecipeCard(
    item: NourishDiscovery.RankedRecipe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        RecipeCard(
            recipe = item.recipe,
            onClick = onClick,
        )
        Spacer(Modifier.height(6.dp))
        val macrosView = NourishParser.macrosRowView(item.score.macros)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nourish ${item.score.overall}/10",
                style = MaterialTheme.typography.labelMedium,
                color = NourishGreen,
                fontWeight = FontWeight.SemiBold,
            )
            if (macrosView != null) {
                Text(
                    text = "${macrosView.kcal} kcal · ${macrosView.proteinG}g protein",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (macrosView.tone == "rough") {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (macrosView != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = macrosView.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun NourishFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) {
        NourishGreen.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val border = if (selected) {
        NourishGreen.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val fg = if (selected) NourishGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun NourishSortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) {
        NourishGreen.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val border = if (selected) {
        NourishGreen.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val fg = if (selected) NourishGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
