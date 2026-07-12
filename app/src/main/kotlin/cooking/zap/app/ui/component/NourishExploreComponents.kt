package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.nostr.NourishParser

private val NourishGreen = Color(0xFF22C55E)

/**
 * Explore tile: [RecipeCard] with a small leaf mark on the title (Nourish
 * presence only — no score number; overalls aren't well-differentiated yet).
 */
@Composable
fun NourishExploreRecipeCard(
    item: NourishDiscovery.RankedRecipe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val macrosView = NourishParser.macrosRowView(item.score.macros)

    Column(modifier = modifier.fillMaxWidth()) {
        RecipeCard(
            recipe = item.recipe,
            onClick = onClick,
            titleAccessory = {
                Icon(
                    Icons.Outlined.Eco,
                    contentDescription = stringResource(R.string.nourish_title),
                    tint = NourishGreen.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        if (macrosView != null) {
            Spacer(modifier.height(4.dp))
            Text(
                text = "${macrosView.kcal} kcal · ${macrosView.proteinG}g protein",
                style = MaterialTheme.typography.labelSmall,
                color = if (macrosView.tone == "rough") {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = macrosView.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}
