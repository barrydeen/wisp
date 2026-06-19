package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.NoteActions
import cooking.zap.app.ui.component.PostCard
import cooking.zap.app.viewmodel.OnlyFoodFeedViewModel
import cooking.zap.app.viewmodel.OnlyFoodFeedViewModel.Mode

/**
 * OnlyFood 🍳 — the social food feed (concern 1.6). A Global/Following toggle
 * over the expanded food-hashtag set; notes render via the shared [PostCard]
 * with full inline engagement; infinite scroll pages older windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlyFoodFeedScreen(
    viewModel: OnlyFoodFeedViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    onBack: () -> Unit,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
) {
    val notes by viewModel.notes.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isPaging by viewModel.isPaging.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val emptyFollows by viewModel.emptyFollows.collectAsState()

    val listState = rememberLazyListState()
    // Infinite scroll: when the last item nears the viewport, page older.
    val shouldPage by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            notes.isNotEmpty() && last >= notes.size - 3
        }
    }
    LaunchedEffect(shouldPage) {
        if (shouldPage) viewModel.loadMore()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("OnlyFood 🍳") },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = mode == Mode.GLOBAL,
                    onClick = { viewModel.setMode(Mode.GLOBAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Global") }
                SegmentedButton(
                    selected = mode == Mode.FOLLOWING,
                    onClick = { viewModel.setMode(Mode.FOLLOWING) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Following") }
            }

            // Pull-to-refresh is the only path that re-queries a loaded mode
            // (toggling swaps caches without a relay query). Empty states live
            // inside the LazyColumn so the pull gesture works even when blank
            // — the recovery path for a mode the relay throttled to 0.
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    when {
                        emptyFollows -> item(key = "empty-follows") {
                            FullPageMessage(
                                "Follow some food people to see their posts here.",
                                Modifier.fillParentMaxSize(),
                            )
                        }
                        notes.isEmpty() && isLoading -> item(key = "loading") {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        notes.isEmpty() -> item(key = "empty") {
                            FullPageMessage(
                                "No food posts yet — pull down to refresh.",
                                Modifier.fillParentMaxSize(),
                            )
                        }
                        else -> {
                            items(notes.size, key = { notes[it].id }) { index ->
                                OnlyFoodNote(
                                    event = notes[index],
                                    eventRepo = eventRepo,
                                    userPubkey = userPubkey,
                                    noteActions = noteActions,
                                    zapAnimatingIds = zapAnimatingIds,
                                    zapInProgressIds = zapInProgressIds,
                                )
                                HorizontalDivider()
                            }
                            if (isPaging) {
                                item(key = "paging") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPageMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlyFoodNote(
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
