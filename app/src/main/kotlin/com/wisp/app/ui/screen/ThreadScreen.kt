package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.PostCard
import com.wisp.app.viewmodel.ThreadViewModel
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    eventRepo: EventRepository,
    contactRepo: ContactRepository,
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
    onAddToList: (String) -> Unit = {}
) {
    val flatThread by viewModel.flatThread.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scrollToIndex by viewModel.scrollToIndex.collectAsState()
    val listState = rememberLazyListState()

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
            isFollowing = { pubkey -> contactRepo.isFollowing(pubkey) },
            userPubkey = userPubkey,
            nip05Repo = nip05Repo
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding)
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
                        onNavigateToProfileFromDetails = onProfileClick,
                        onFollowAuthor = { onToggleFollow(event.pubkey) },
                        onBlockAuthor = { onBlockUser(event.pubkey) },
                        isFollowingAuthor = followList.let { contactRepo.isFollowing(event.pubkey) },
                        isOwnEvent = event.pubkey == userPubkey,
                        onAddToList = { onAddToList(event.id) },
                        isInList = event.id in listedIds,
                        onPin = { onTogglePin(event.id) },
                        isPinned = event.id in pinnedIds,
                        nip05Repo = nip05Repo,
                        onQuotedNoteClick = onQuotedNoteClick,
                        noteActions = noteActions,
                        modifier = Modifier.padding(start = (min(depth, 4) * 24).dp)
                    )
                }
            }
        }
    }
}
