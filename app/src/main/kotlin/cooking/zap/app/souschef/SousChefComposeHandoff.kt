package cooking.zap.app.souschef

import cooking.zap.app.nostr.IngredientScaler
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.RecipeSerializer
import kotlin.math.abs

/**
 * Build the markdown payload for the Cheffy-style composer hand-off
 * (`pendingComposeMarkdown` → [cooking.zap.app.viewmodel.RecipeComposeViewModel.prefillFromMarkdown]).
 *
 * Pure: applies the preview [multiplier] to ingredient quantities and servings
 * (same scaler the read-only preview uses), prefixes a `# Title` heading so
 * prefill can extract it, then serializes via [RecipeSerializer.toContent].
 * No signing, no relays, no persistence — the compose route consumes the
 * string once.
 */
fun RecipeParser.Recipe.toComposeHandoffMarkdown(multiplier: Double = 1.0): String {
    val scaled = if (abs(multiplier - 1.0) < 1e-6) {
        this
    } else {
        copy(
            content = content.copy(
                details = content.details.copy(
                    servings = content.details.servings?.let {
                        IngredientScaler.scaleLine(it, multiplier)
                    },
                ),
                ingredients = content.ingredients.map {
                    IngredientScaler.scaleLine(it, multiplier)
                },
            ),
        )
    }
    val heading = "# ${scaled.title?.takeIf { it.isNotBlank() } ?: "Untitled"}\n"
    return heading + RecipeSerializer.toContent(scaled)
}
