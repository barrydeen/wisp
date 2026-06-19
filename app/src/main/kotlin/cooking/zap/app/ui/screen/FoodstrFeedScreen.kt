package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.NoteActions
import cooking.zap.app.ui.component.PostCard
import cooking.zap.app.ui.component.RecipeCard
import cooking.zap.app.viewmodel.FoodstrFeedViewModel
import cooking.zap.app.viewmodel.FoodstrFeedViewModel.FoodstrItem

/**
 * Home foodstr feed — recipes (as [RecipeCard]) and `#foodstr` notes (as the
 * shared [PostCard]) merged into one time-sorted list (concern 1.5). Recipe
 * taps open the recipe-detail route; notes keep full inline engagement via
 * [NoteActions]. Reachable from the home drawer; the post-login default swap
 * is a deferred follow-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodstrFeedScreen(
    viewModel: FoodstrFeedViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    onProfileClick: (String) -> Unit,
    onBack: () -> Unit,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
) {
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Foodstr") },
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
        when {
            items.isEmpty() && isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No recipes or #foodstr posts yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(items.size, key = { items[it].key }) { index ->
                        when (val item = items[index]) {
                            is FoodstrItem.Recipe -> {
                                val recipe = item.recipe
                                val profile = remember(recipe.author) { eventRepo.getProfileData(recipe.author) }
                                RecipeCard(
                                    recipe = recipe,
                                    authorName = profile?.displayString,
                                    authorPicture = profile?.picture,
                                    onClick = { onRecipeClick(recipe.author, recipe.dTag) },
                                    onProfileClick = { onProfileClick(recipe.author) },
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            is FoodstrItem.Note -> {
                                FoodstrNote(
                                    event = item.event,
                                    eventRepo = eventRepo,
                                    userPubkey = userPubkey,
                                    noteActions = noteActions,
                                    zapAnimatingIds = zapAnimatingIds,
                                    zapInProgressIds = zapInProgressIds,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodstrNote(
    event: NostrEvent,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    zapAnimatingIds: Set<String>,
    zapInProgressIds: Set<String>,
) {
    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()

    val profile = remember(profileVersion, event.pubkey) { eventRepo.getProfileData(event.pubkey) }
    val likeCount = remember(reactionVersion, event.id) { eventRepo.getReactionCount(event.id) }
    val replyCount = remember(replyCountVersion, event.id) { eventRepo.getReplyCount(event.id) }
    val zapSats = remember(zapVersion, event.id) { eventRepo.getZapSats(event.id) }
    val userEmojis = remember(reactionVersion, event.id, userPubkey) {
        userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
    }
    val repostCount = remember(repostVersion, event.id) { eventRepo.getRepostCount(event.id) }
    val hasUserReposted = remember(repostVersion, event.id) { eventRepo.hasUserReposted(event.id) }
    val hasUserZapped = remember(zapVersion, event.id) { eventRepo.hasUserZapped(event.id) }
    val reactionEmojiUrls = remember(reactionVersion, event.id) { eventRepo.getReactionEmojiUrls(event.id) }

    PostCard(
        event = event,
        profile = profile,
        onReply = { noteActions.onReply(event) },
        onProfileClick = { noteActions.onProfileClick(event.pubkey) },
        onNavigateToProfile = noteActions.onProfileClick,
        onNoteClick = { noteActions.onNoteClick(event.id) },
        onReact = { emoji -> noteActions.onReact(event, emoji) },
        userReactionEmojis = userEmojis,
        onRepost = { noteActions.onRepost(event) },
        onQuote = { noteActions.onQuote(event) },
        hasUserReposted = hasUserReposted,
        repostCount = repostCount,
        onZap = { noteActions.onZap(event) },
        hasUserZapped = hasUserZapped,
        likeCount = likeCount,
        replyCount = replyCount,
        zapSats = zapSats,
        isZapAnimating = event.id in zapAnimatingIds,
        isZapInProgress = event.id in zapInProgressIds,
        eventRepo = eventRepo,
        reactionEmojiUrls = reactionEmojiUrls,
        isOwnEvent = event.pubkey == userPubkey,
        onAddToList = { noteActions.onAddToList(event.id) },
        noteActions = noteActions,
    )
}
