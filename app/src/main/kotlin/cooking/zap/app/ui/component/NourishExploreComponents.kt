package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
