package cooking.zap.app.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import cooking.zap.app.nostr.RecipeParser

private val POSTER_SHAPE = RoundedCornerShape(16.dp)

/** Fixed title block height (~2 lines of titleSmall) so grid rows stay aligned. */
private val TITLE_MIN_HEIGHT = 40.dp

/**
 * Poster-style recipe tile for the Recipes grid: a 2:3 portrait image with the
 * title below it (mirrors the web RecipeCard). Title-only — the full
 * engagement bar (zap/react/repost) lives on the detail screen.
 *
 * Missing or broken images fall back to a deterministic, offline placeholder
 * seeded by the recipe id, so a recipe always shows the same tile and the grid
 * never has holes.
 */
@Composable
fun RecipeCard(
    recipe: RecipeParser.Recipe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(POSTER_SHAPE),
        ) {
            // Pre-validate the URL rather than relying on Coil's error slot for a
            // null/blank model (a null model can stay in the empty state and
            // leave a hole). Missing → placeholder directly; real URL → load it,
            // falling back to the placeholder only on an actual load failure.
            val imageUrl = recipe.image?.takeIf { it.isNotBlank() }
            if (imageUrl == null) {
                RecipePlaceholderTile(seed = recipe.id, modifier = Modifier.fillMaxSize())
            } else {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { RecipePosterSkeleton(Modifier.fillMaxSize()) },
                    error = { RecipePlaceholderTile(seed = recipe.id, modifier = Modifier.fillMaxSize()) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = recipe.title?.takeIf { it.isNotBlank() } ?: recipe.dTag,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TITLE_MIN_HEIGHT),
        )
    }
}

/**
 * A neutral, gently pulsing 2:3 tile used while an image loads and for the
 * grid's initial loading state. Apply the aspect ratio at the call site for
 * standalone (grid skeleton) use.
 */
@Composable
fun RecipePosterSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recipeSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recipeSkeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(POSTER_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}

/**
 * Deterministic food-toned placeholder (tint + glyph) for recipes with no
 * usable image. The tint is picked from the recipe id, so it's stable per
 * recipe and needs no network.
 */
@Composable
private fun RecipePlaceholderTile(seed: String, modifier: Modifier = Modifier) {
    val n = PLACEHOLDER_TINTS.size
    val tint = PLACEHOLDER_TINTS[((seed.hashCode() % n) + n) % n]
    // Blend toward the surface so it reads well in both light and dark themes.
    val bg = tint.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        modifier = modifier
            .clip(POSTER_SHAPE)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Restaurant,
            contentDescription = null,
            tint = tint.copy(alpha = 0.55f),
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Food-toned palette for the deterministic placeholder tiles. */
private val PLACEHOLDER_TINTS = listOf(
    Color(0xFFE2552E), // tomato
    Color(0xFF6FA03C), // herb
    Color(0xFFE0A52B), // mustard
    Color(0xFF3E8E9E), // teal
    Color(0xFFB5572E), // paprika
    Color(0xFF8E6DB0), // plum
)
