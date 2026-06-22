package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.nostr.IngredientScaler
import cooking.zap.app.nostr.RecipeParser

/**
 * The shared recipe-body items — hero image, title, summary, prep/cook/
 * servings chips, serving scaler, chef's notes, ingredients, numbered
 * directions, additional resources. Emitted into a [LazyColumn] by both the
 * published [cooking.zap.app.ui.screen.RecipeDetailScreen] (which appends its
 * own ActionBar after) and the read-only Sous Chef import preview (concern
 * 2.1), so the two render identically.
 *
 * Detail-only pieces are passed as header slots so this stays
 * engagement-agnostic: [headerAuthorSlot] (byline row, between title and
 * summary) and [headerTrailingSlot] (e.g. a "Start cooking" button, after the
 * hashtags). Both default to empty — the import preview renders neither.
 */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.recipeBody(
    recipe: RecipeParser.Recipe,
    multiplier: Double,
    onMultiplierChange: (Double) -> Unit,
    onHashtagClick: ((String) -> Unit)? = null,
    headerAuthorSlot: @Composable ColumnScope.() -> Unit = {},
    headerTrailingSlot: @Composable ColumnScope.() -> Unit = {},
) {
    item(key = "header") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            recipe.image?.let { image ->
                AsyncImage(
                    model = image,
                    contentDescription = recipe.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = recipe.title ?: "Untitled",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            headerAuthorSlot()
            recipe.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MetaChips(recipe.content.details, multiplier)
            // The recipe's `#t` tags (zapcooking / zapcooking-<category>) are
            // intentionally NOT displayed — they're still written at publish and
            // power sorting/filtering + the category chips, just not shown here.
            headerTrailingSlot()
        }
    }

    recipe.content.chefNotes?.takeIf { it.isNotBlank() }?.let { notes ->
        item(key = "chef-notes") {
            Section(title = "Chef's notes") {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (recipe.content.ingredients.isNotEmpty()) {
        item(key = "ingredients") {
            Section(title = "Ingredients") {
                ScalerChips(multiplier, onMultiplierChange)
                Spacer(Modifier.height(8.dp))
                recipe.content.ingredients.forEach { line ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("•  ", color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = IngredientScaler.scaleLine(line, multiplier),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    if (recipe.content.directions.isNotEmpty()) {
        item(key = "directions") {
            Section(title = "Directions") {
                recipe.content.directions.forEachIndexed { index, step ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    recipe.content.additionalMarkdown?.takeIf { it.isNotBlank() }?.let { extra ->
        item(key = "additional") {
            Section(title = "Additional resources") {
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(details: RecipeParser.RecipeDetails, multiplier: Double) {
    val prep = details.prepTime
    val cook = details.cookTime
    // Servings scales with the multiplier (the only Details field that does —
    // prep/cook are free-text durations that don't).
    val servings = details.servings?.let { IngredientScaler.scaleLine(it, multiplier) }
    if (prep == null && cook == null && servings == null) return
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        prep?.let { AssistChip(onClick = {}, label = { Text("Prep $it") }) }
        cook?.let { AssistChip(onClick = {}, label = { Text("Cook $it") }) }
        servings?.let { AssistChip(onClick = {}, label = { Text("Serves $it") }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScalerChips(multiplier: Double, onSelect: (Double) -> Unit) {
    val options = listOf(0.5 to "½×", 1.0 to "1×", 2.0 to "2×", 3.0 to "3×")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = multiplier == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}
