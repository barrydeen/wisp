package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.PostCard
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.viewmodel.HashtagFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashtagFeedScreen(
    viewModel: HashtagFeedViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    nip05Repo: Nip05Repository? = null,
    onBack: () -> Unit
) {
    val hashtag by viewModel.hashtag.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val profileVersion by eventRepo.profileVersion.collectAsState()
    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "#$hashtag",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
    ) { innerPadding ->
        if (isLoading && notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(items = notes, key = { it.id }) { event ->
                    HashtagFeedItem(
                        event = event,
                        eventRepo = eventRepo,
                        userPubkey = userPubkey,
                        profileVersion = profileVersion,
                        reactionVersion = reactionVersion,
                        replyCountVersion = replyCountVersion,
                        zapVersion = zapVersion,
                        repostVersion = repostVersion,
                        noteActions = noteActions,
                        nip05Repo = nip05Repo
                    )
                }
            }
        }
    }
}

@Composable
private fun HashtagFeedItem(
    event: NostrEvent,
    eventRepo: EventRepository,
    userPubkey: String?,
    profileVersion: Int,
    reactionVersion: Int,
    replyCountVersion: Int,
    zapVersion: Int,
    repostVersion: Int,
    noteActions: NoteActions,
    nip05Repo: Nip05Repository? = null
) {
    val profile = remember(profileVersion, event.pubkey) {
        eventRepo.getProfileData(event.pubkey)
    }
    val likeCount = remember(reactionVersion, event.id) {
        eventRepo.getReactionCount(event.id)
    }
    val replyCount = remember(replyCountVersion, event.id) {
        eventRepo.getReplyCount(event.id)
    }
    val zapSats = remember(zapVersion, event.id) {
        eventRepo.getZapSats(event.id)
    }
    val userEmojis = remember(reactionVersion, event.id, userPubkey) {
        userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
    }
    val reactionDetails = remember(reactionVersion, event.id) {
        eventRepo.getReactionDetails(event.id)
    }
    val zapDetails = remember(zapVersion, event.id) {
        eventRepo.getZapDetails(event.id)
    }
    val repostCount = remember(repostVersion, event.id) {
        eventRepo.getRepostCount(event.id)
    }
    val repostPubkeys = remember(repostVersion, event.id) {
        eventRepo.getReposterPubkeys(event.id)
    }
    val hasUserReposted = remember(repostVersion, event.id) {
        eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(zapVersion, event.id) {
        eventRepo.hasUserZapped(event.id)
    }
    val reactionEmojiUrls = remember(reactionVersion, event.id) {
        eventRepo.getReactionEmojiUrls(event.id)
    }

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
        eventRepo = eventRepo,
        reactionDetails = reactionDetails,
        zapDetails = zapDetails,
        repostDetails = repostPubkeys,
        reactionEmojiUrls = reactionEmojiUrls,
        onNavigateToProfileFromDetails = noteActions.onProfileClick,
        onFollowAuthor = { noteActions.onFollowAuthor(event.pubkey) },
        onBlockAuthor = { noteActions.onBlockAuthor(event.pubkey) },
        isFollowingAuthor = noteActions.isFollowing(event.pubkey),
        isOwnEvent = event.pubkey == userPubkey,
        nip05Repo = nip05Repo,
        onAddToList = { noteActions.onAddToList(event.id) },
        onPin = { noteActions.onPin(event.id) },
        onDelete = { noteActions.onDelete(event.id, event.kind) },
        onQuotedNoteClick = noteActions.onNoteClick,
        noteActions = noteActions
    )
}
