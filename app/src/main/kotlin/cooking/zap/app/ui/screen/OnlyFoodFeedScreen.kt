package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.NoteActions
import cooking.zap.app.ui.component.PostCard
import cooking.zap.app.viewmodel.OnlyFoodFeedViewModel
import cooking.zap.app.viewmodel.OnlyFoodFeedViewModel.Mode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Item runway before the viewport bottom that triggers the next backward page. */
private const val PAGE_PREFETCH_DISTANCE = 6

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
    // Engagement "version" signals collected once and shared by every visible note (see
    // EngagementVersions). OnlyFood doesn't surface translation state, so that field stays inert.
    val engagementVersions = rememberEngagementVersions(eventRepo)
    val notes by viewModel.notes.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isPaging by viewModel.isPaging.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val emptyFollows by viewModel.emptyFollows.collectAsState()

    val listState = rememberLazyListState()
    // Stick-to-top landing: re-pin to the newest note while the user is following the
    // head of the feed, so newer posts streaming in (or merged by refresh) don't insert
    // above the anchored top and force a manual scroll up. Cleared once the user scrolls
    // down to page older notes. Saved across navigation.
    var autoFollowTop by rememberSaveable { mutableStateOf(true) }
    // Infinite scroll: page older when the last item nears the viewport. Keyed on
    // totalItemsCount (not a bare boolean) so a page that appends WHILE the user is
    // parked at the bottom re-triggers the next page — a boolean stuck `true` would
    // stall. distinctUntilChanged suppresses re-fires when nothing changed; the VM's
    // own isPaging/endReached guards are the backstop. PostCards measure slowly, so
    // a 6-item runway prefetches earlier than the old 3.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (total > 0 && lastVisible >= total - PAGE_PREFETCH_DISTANCE) total else -1
        }
            .distinctUntilChanged()
            .filter { it >= 0 }
            .collect { viewModel.loadMore() }
    }

    // --- Stick-to-top landing (parity with FeedScreen) ---
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    // A user drag means they're taking control (e.g. scrolling down to page older).
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) autoFollowTop = false
    }
    // Resume following only when scrolling settles back at the very top.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { if (isAtTop) autoFollowTop = true }
    }
    // Re-pin to the newest note when the head of the feed changes while following.
    val topNoteId = notes.firstOrNull()?.id
    LaunchedEffect(topNoteId, autoFollowTop) {
        if (autoFollowTop && topNoteId != null && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }
    // Switching Global/Following swaps the cache — land at the top of the new mode. Track the
    // previous mode (mirrors FeedScreen's prev* guards) so we only re-pin on an actual user
    // toggle, not on first composition or nav re-entry — those must keep the restored
    // LazyListState instead of being yanked to the top.
    var prevMode by rememberSaveable { mutableStateOf(mode.name) }
    LaunchedEffect(mode) {
        if (mode.name != prevMode) {
            prevMode = mode.name
            autoFollowTop = true
            listState.scrollToItem(0)
        }
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
                                    versions = engagementVersions,
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
    versions: EngagementVersions,
) {
    // Per-event reactive engagement (mirrors FeedItem): each lookup runs through derivedStateOf
    // reading the shared [versions] signals, so a global bump only recomposes the note whose
    // value actually changed instead of every visible card.
    val profile by remember(event.pubkey) { derivedStateOf { versions.profile.value.let { eventRepo.getProfileData(event.pubkey) } } }
    val likeCount by remember(event.id) { derivedStateOf { versions.reaction.value.let { eventRepo.getReactionCount(event.id) } } }
    val replyCount by remember(event.id) { derivedStateOf { versions.reply.value.let { eventRepo.getReplyCount(event.id) } } }
    val zapSats by remember(event.id) { derivedStateOf { versions.zap.value.let { eventRepo.getZapSats(event.id) } } }
    val userEmojis by remember(event.id, userPubkey) {
        derivedStateOf { versions.reaction.value.let { userPubkey?.let { pk -> eventRepo.getUserReactionEmojis(event.id, pk) } ?: emptySet() } }
    }
    val repostCount by remember(event.id) { derivedStateOf { versions.repost.value.let { eventRepo.getRepostCount(event.id) } } }
    val hasUserReposted by remember(event.id) { derivedStateOf { versions.repost.value.let { eventRepo.hasUserReposted(event.id) } } }
    val hasUserZapped by remember(event.id) { derivedStateOf { versions.zap.value.let { eventRepo.hasUserZapped(event.id) } } }
    val reactionEmojiUrls by remember(event.id) { derivedStateOf { versions.reaction.value.let { eventRepo.getReactionEmojiUrls(event.id) } } }
    val relayIcons by remember(event.id) { derivedStateOf { versions.relaySource.value.let { eventRepo.getEventRelays(event.id).map { url -> url to null } } } }

    PostCard(
        event = event,
        profile = profile,
        relayIcons = relayIcons,
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
