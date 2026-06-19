package cooking.zap.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.nostr.IngredientScaler
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.ActionBar
import cooking.zap.app.ui.component.ProfilePicture

/**
 * Recipe reading view — branched from [ArticleScreen] (a recipe IS a kind
 * 30023 event), but laid out from the structured [RecipeParser.Recipe]:
 * hero image, summary, prep/cook/servings, a serving scaler, chef's notes,
 * ingredients, and numbered directions. The engagement bar ([ActionBar]) is
 * reused unchanged — zapping a recipe is the core on-brand action.
 *
 * Scope (concern 1.3): the comment THREAD is deferred (the heavy part);
 * cook mode is wired in 1.4 via [onStartCooking] (when null, no button
 * renders, so no dead UI ships).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    viewModel: cooking.zap.app.viewmodel.RecipeDetailViewModel,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null,
    onReply: (cooking.zap.app.nostr.NostrEvent) -> Unit = {},
    onReact: (cooking.zap.app.nostr.NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (cooking.zap.app.nostr.NostrEvent) -> Unit = {},
    onQuote: (cooking.zap.app.nostr.NostrEvent) -> Unit = {},
    onZap: (cooking.zap.app.nostr.NostrEvent) -> Unit = {},
    onAddToList: (String) -> Unit = {},
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
    listedIds: Set<String> = emptySet(),
    userPubkey: String? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onStartCooking: ((RecipeParser.Recipe) -> Unit)? = null,
) {
    val recipe by viewModel.recipe.collectAsState()
    val event by viewModel.event.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()

    val authorPubkey = recipe?.author
    val profile = remember(authorPubkey, profileVersion) { authorPubkey?.let { eventRepo.getProfileData(it) } }

    var multiplier by remember { mutableStateOf(1.0) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recipe?.title ?: "Recipe",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val current = recipe
        when {
            isLoading && current == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            current == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Recipe not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item(key = "header") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            current.image?.let { image ->
                                AsyncImage(
                                    model = image,
                                    contentDescription = current.title,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .padding(top = 8.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                            Text(
                                text = current.title ?: "Untitled",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (authorPubkey != null) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onProfileClick(authorPubkey) },
                                ) {
                                    ProfilePicture(url = profile?.picture, size = 32)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = profile?.displayString
                                            ?: authorPubkey.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            current.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MetaChips(current.content.details, multiplier)
                            if (current.hashtags.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                FlowRow {
                                    current.hashtags.forEach { tag ->
                                        SuggestionChip(
                                            onClick = { onHashtagClick?.invoke(tag) },
                                            label = { Text(tag) },
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                    }
                                }
                            }
                            if (onStartCooking != null && current.content.directions.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                androidx.compose.material3.Button(onClick = { onStartCooking(current) }) {
                                    Text("Start cooking")
                                }
                            }
                        }
                    }

                    current.content.chefNotes?.takeIf { it.isNotBlank() }?.let { notes ->
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

                    if (current.content.ingredients.isNotEmpty()) {
                        item(key = "ingredients") {
                            Section(title = "Ingredients") {
                                ScalerChips(multiplier) { multiplier = it }
                                Spacer(Modifier.height(8.dp))
                                current.content.ingredients.forEach { line ->
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

                    if (current.content.directions.isNotEmpty()) {
                        item(key = "directions") {
                            Section(title = "Directions") {
                                current.content.directions.forEachIndexed { index, step ->
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

                    current.content.additionalMarkdown?.takeIf { it.isNotBlank() }?.let { extra ->
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

                    val recipeEvent = event
                    if (recipeEvent != null) {
                        item(key = "action-bar") {
                            val likeCount = remember(reactionVersion, recipeEvent.id) { eventRepo.getReactionCount(recipeEvent.id) }
                            val zapSats = remember(zapVersion, recipeEvent.id) { eventRepo.getZapSats(recipeEvent.id) }
                            val userEmojis = remember(reactionVersion, recipeEvent.id, userPubkey) {
                                userPubkey?.let { eventRepo.getUserReactionEmojis(recipeEvent.id, it) } ?: emptySet()
                            }
                            val repostCount = remember(repostVersion, recipeEvent.id) { eventRepo.getRepostCount(recipeEvent.id) }
                            val hasUserReposted = remember(repostVersion, recipeEvent.id) { eventRepo.hasUserReposted(recipeEvent.id) }
                            val hasUserZapped = remember(zapVersion, recipeEvent.id) { eventRepo.hasUserZapped(recipeEvent.id) }
                            val reactionEmojiUrls = remember(reactionVersion, recipeEvent.id) { eventRepo.getReactionEmojiUrls(recipeEvent.id) }

                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            ActionBar(
                                onReply = { onReply(recipeEvent) },
                                onReact = { emoji -> onReact(recipeEvent, emoji) },
                                userReactionEmojis = userEmojis,
                                onRepost = { onRepost(recipeEvent) },
                                onQuote = { onQuote(recipeEvent) },
                                hasUserReposted = hasUserReposted,
                                onZap = { onZap(recipeEvent) },
                                hasUserZapped = hasUserZapped,
                                onAddToList = { onAddToList(recipeEvent.id) },
                                isInList = recipeEvent.id in listedIds,
                                likeCount = likeCount,
                                repostCount = repostCount,
                                zapSats = zapSats,
                                isZapAnimating = recipeEvent.id in zapAnimatingIds,
                                isZapInProgress = recipeEvent.id in zapInProgressIds,
                                reactionEmojiUrls = reactionEmojiUrls,
                                resolvedEmojis = resolvedEmojis,
                                unicodeEmojis = unicodeEmojis,
                                onOpenEmojiLibrary = onOpenEmojiLibrary,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }

                    item(key = "footer") { Spacer(Modifier.height(32.dp)) }
                }
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
