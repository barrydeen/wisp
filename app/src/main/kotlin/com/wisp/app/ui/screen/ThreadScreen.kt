package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.PostCard
import com.wisp.app.viewmodel.ThreadViewModel
import kotlin.math.min
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    eventRepo: EventRepository,
    contactRepo: ContactRepository,
    relayInfoRepo: RelayInfoRepository? = null,
    nip05Repo: Nip05Repository? = null,
    userPubkey: String?,
    onBack: () -> Unit,
    onReply: (NostrEvent) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (NostrEvent) -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (NostrEvent) -> Unit = {},
    onQuote: (NostrEvent) -> Unit = {},
    onToggleFollow: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    onZap: (NostrEvent) -> Unit = {},
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
    listedIds: Set<String> = emptySet(),
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
    onDeleteEvent: (String, Int) -> Unit = { _, _ -> },
    onAddToList: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null
) {
    val flatThread by viewModel.flatThread.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scrollToIndex by viewModel.scrollToIndex.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showRootButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            // Capture position when scroll starts
            previousIndex = listState.firstVisibleItemIndex
            previousOffset = listState.firstVisibleItemScrollOffset
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                val scrolledUp = index < previousIndex || (index == previousIndex && offset < previousOffset)
                val notAtTop = index > 0 || offset > 0
                showRootButton = scrolledUp && notAtTop
                previousIndex = index
                previousOffset = offset
            }
        }
    }

    LaunchedEffect(scrollToIndex) {
        if (scrollToIndex >= 0) {
            listState.animateScrollToItem(scrollToIndex)
            viewModel.clearScrollTarget()
        }
    }

    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val relaySourceVersion by eventRepo.relaySourceVersion.collectAsState()
    val nip05Version by nip05Repo?.version?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val followList by contactRepo.followList.collectAsState()

    val noteActions = remember(userPubkey) {
        NoteActions(
            onReply = onReply,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = onZap,
            onProfileClick = onProfileClick,
            onNoteClick = { eventId -> onQuotedNoteClick?.invoke(eventId) },
            onAddToList = onAddToList,
            onFollowAuthor = onToggleFollow,
            onBlockAuthor = onBlockUser,
            onPin = onTogglePin,
            onDelete = onDeleteEvent,
            isFollowing = { pubkey -> contactRepo.isFollowing(pubkey) },
            userPubkey = userPubkey,
            nip05Repo = nip05Repo,
            onHashtagClick = onHashtagClick
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thread") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading && flatThread.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = flatThread, key = { it.first.id }) { (event, depth) ->
                        val profileData = eventRepo.getProfileData(event.pubkey)
                        val likeCount = reactionVersion.let { eventRepo.getReactionCount(event.id) }
                        val replyCount = replyCountVersion.let { eventRepo.getReplyCount(event.id) }
                        val zapSats = zapVersion.let { eventRepo.getZapSats(event.id) }
                        val userEmojis = reactionVersion.let { userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet() }
                        val reactionDetails = reactionVersion.let { eventRepo.getReactionDetails(event.id) }
                        val zapDetailsList = zapVersion.let { eventRepo.getZapDetails(event.id) }
                        val repostCount = repostVersion.let { eventRepo.getRepostCount(event.id) }
                        val repostPubkeys = repostVersion.let { eventRepo.getReposterPubkeys(event.id) }
                        val hasUserReposted = repostVersion.let { eventRepo.hasUserReposted(event.id) }
                        val hasUserZapped = zapVersion.let { eventRepo.hasUserZapped(event.id) }
                        val eventReactionEmojiUrls = reactionVersion.let { eventRepo.getReactionEmojiUrls(event.id) }
                        val relayIcons = remember(relaySourceVersion, event.id) {
                            eventRepo.getEventRelays(event.id).map { url ->
                                url to relayInfoRepo?.getIconUrl(url)
                            }
                        }
                        PostCard(
                            event = event,
                            profile = profileData,
                            onReply = { onReply(event) },
                            onProfileClick = { onProfileClick(event.pubkey) },
                            onNavigateToProfile = onProfileClick,
                            onNoteClick = { onNoteClick(event) },
                            onReact = { emoji -> onReact(event, emoji) },
                            userReactionEmojis = userEmojis,
                            onRepost = { onRepost(event) },
                            onQuote = { onQuote(event) },
                            hasUserReposted = hasUserReposted,
                            repostCount = repostCount,
                            onZap = { onZap(event) },
                            hasUserZapped = hasUserZapped,
                            likeCount = likeCount,
                            replyCount = replyCount,
                            zapSats = zapSats,
                            isZapAnimating = event.id in zapAnimatingIds,
                            isZapInProgress = event.id in zapInProgressIds,
                            eventRepo = eventRepo,
                            reactionDetails = reactionDetails,
                            zapDetails = zapDetailsList,
                            repostDetails = repostPubkeys,
                            reactionEmojiUrls = eventReactionEmojiUrls,
                            relayIcons = relayIcons,
                            onNavigateToProfileFromDetails = onProfileClick,
                            onFollowAuthor = { onToggleFollow(event.pubkey) },
                            onBlockAuthor = { onBlockUser(event.pubkey) },
                            isFollowingAuthor = followList.let { contactRepo.isFollowing(event.pubkey) },
                            isOwnEvent = event.pubkey == userPubkey,
                            onAddToList = { onAddToList(event.id) },
                            isInList = event.id in listedIds,
                            onPin = { onTogglePin(event.id) },
                            isPinned = event.id in pinnedIds,
                            onDelete = { onDeleteEvent(event.id, event.kind) },
                            nip05Repo = nip05Repo,
                            onQuotedNoteClick = onQuotedNoteClick,
                            noteActions = noteActions,
                            modifier = Modifier.padding(start = (min(depth, 4) * 24).dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showRootButton,
                    enter = slideInVertically { -it },
                    exit = slideOutVertically { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Surface(
                        onClick = {
                            scope.launch {
                                listState.scrollToItem(0)
                                showRootButton = false
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Back to Top",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
