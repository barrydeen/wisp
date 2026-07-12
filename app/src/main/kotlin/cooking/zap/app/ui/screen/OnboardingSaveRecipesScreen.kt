package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.ui.component.RecipeCard

/**
 * Onboarding "Save a few recipes" step. Doubles as the
 * save/bookmark tutorial: tapping a recipe's bookmark saves it to the canonical
 * kind-30001 "Saved" list (My Recipes → Saved). Additive/skippable — onboarding was already
 * completed at the Creators step.
 */
@Composable
fun OnboardingSaveRecipesScreen(
    recipes: List<RecipeParser.Recipe>,
    loading: Boolean,
    bookmarkedCoordinates: Set<String>,
    onToggleBookmark: (recipeId: String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Save a few recipes",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onSkip) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))

            // Coachmark — the tutorial. Tapping the bookmark saves to My Recipes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Tap the bookmark to save it to My Recipes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))

            if (loading && recipes.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            } else if (recipes.isEmpty()) {
                Text(
                    text = "Couldn't load recipes right now — you can save some from My Recipes later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Two-column grid via chunked rows (the outer Column already scrolls,
                // so a non-lazy grid avoids nested-scroll conflicts).
                recipes.chunked(2).forEach { rowRecipes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowRecipes.forEach { recipe ->
                            val coordinate = "${RecipeParser.RECIPE_KIND}:${recipe.author}:${recipe.dTag}"
                            SaveableRecipeTile(
                                recipe = recipe,
                                isSaved = coordinate in bookmarkedCoordinates,
                                onToggle = { onToggleBookmark(recipe.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Keep the last odd card half-width.
                        if (rowRecipes.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                val saved = recipes.count { "${RecipeParser.RECIPE_KIND}:${it.author}:${it.dTag}" in bookmarkedCoordinates }
                Text(
                    if (saved > 0) "Continue with $saved saved" else "Continue",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SaveableRecipeTile(
    recipe: RecipeParser.Recipe,
    isSaved: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Reuse the existing recipe card; tapping the tile also toggles the save.
        RecipeCard(
            recipe = recipe,
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth()
        )
        // Bookmark affordance overlaid on the poster's top-end corner.
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (isSaved) "Saved to My Recipes" else "Save to My Recipes",
                    tint = if (isSaved) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
