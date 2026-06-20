package cooking.zap.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.ActionBar
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.ui.component.recipeBody

/**
 * Recipe reading view — branched from [ArticleScreen] (a recipe IS a kind
 * 30023 event), laid out from the structured [RecipeParser.Recipe] via the
 * shared [recipeBody] (hero, summary, prep/cook/servings, serving scaler,
 * chef's notes, ingredients, directions). The engagement bar ([ActionBar]) is
 * appended below the body — zapping a recipe is the core on-brand action. The
 * byline and "Start cooking" button are passed as [recipeBody] header slots.
 *
 * Scope (concern 1.3): the comment THREAD is deferred; cook mode is wired in
 * 1.4 via [onStartCooking] (when null, no button renders).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val nourish by viewModel.nourish.collectAsState()

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
                    recipeBody(
                        recipe = current,
                        multiplier = multiplier,
                        onMultiplierChange = { multiplier = it },
                        onHashtagClick = onHashtagClick,
                        headerAuthorSlot = {
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
                        },
                        headerTrailingSlot = {
                            if (onStartCooking != null && current.content.directions.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                androidx.compose.material3.Button(onClick = { onStartCooking(current) }) {
                                    Text("Start cooking")
                                }
                            }
                        },
                    )

                    // Nourish health score (concern 2.4a) — outside recipeBody so the
                    // Sous Chef preview stays score-free. Renders only when present.
                    nourish?.let { score ->
                        item(key = "nourish") { NourishSection(score) }
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

/**
 * Nourish health-score card (concern 2.4a): overall + label, the 8 dimensions
 * as score bars, and top suggestions. Shown only when a score was read from
 * Pantry. (Pre-ship: port the real Nourish visual for parity with the web.)
 */
@Composable
private fun NourishSection(score: NourishScore) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Nourish",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${score.overall}/10 · ${score.overallLabel}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        score.dimensions.forEach { dim ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Text(
                    text = dim.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(124.dp),
                )
                LinearProgressIndicator(
                    progress = { dim.score / 10f },
                    modifier = Modifier.weight(1f).height(6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${dim.score}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
            }
        }
        if (score.improvements.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            score.improvements.forEach { tip ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•  ", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
