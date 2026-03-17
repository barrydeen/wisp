package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.InterestSet
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.PostCard
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.viewmodel.HashtagFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashtagFeedScreen(
    viewModel: HashtagFeedViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    interestSets: List<InterestSet> = emptyList(),
    interestSetsLoaded: Boolean = true,
    onFollowHashtag: (dTag: String) -> Unit = {},
    onUnfollowHashtag: (dTag: String) -> Unit = {},
    onCreateDefaultSet: () -> Unit = {},
    nip05Repo: Nip05Repository? = null,
    translationRepo: TranslationRepository? = null,
    onHashtagPicker: () -> Unit = {},
    onBack: () -> Unit,
    onPollVote: (String, List<String>) -> Unit = { _, _ -> }
) {
    val hashtag by viewModel.hashtag.collectAsState()
    val setName by viewModel.setName.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val profileVersion by eventRepo.profileVersion.collectAsState()
    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val pollVoteVersion by eventRepo.pollVoteVersion.collectAsState()

    val isFollowing = remember(interestSets, hashtag) {
        interestSets.any { hashtag.lowercase() in it.hashtags }
    }
    var showSetPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = setName ?: "#$hashtag",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onHashtagPicker) {
                        Icon(
                            Icons.Outlined.Tag,
                            contentDescription = "Hashtag picker"
                        )
                    }
                    if (userPubkey != null) {
                        if (!interestSetsLoaded && interestSets.isEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            IconButton(onClick = {
                                val containingSets = interestSets.filter { hashtag.lowercase() in it.hashtags }
                                if (containingSets.isNotEmpty()) {
                                    containingSets.forEach { onUnfollowHashtag(it.dTag) }
                                } else if (interestSets.size == 1) {
                                    onFollowHashtag(interestSets.first().dTag)
                                } else if (interestSets.isEmpty()) {
                                    onCreateDefaultSet()
                                } else {
                                    showSetPicker = true
                                }
                            }) {
                                Icon(
                                    if (isFollowing) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = if (isFollowing) "Unfollow hashtag" else "Follow hashtag",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
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
            val listState = remember(hashtag) { LazyListState() }
            LaunchedEffect(isLoading) {
                if (!isLoading) {
                    listState.scrollToItem(0)
                }
            }
            LazyColumn(
                state = listState,
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
                        nip05Repo = nip05Repo,
                        translationRepo = translationRepo,
                        pollVoteVersion = pollVoteVersion,
                        onPollVote = onPollVote
                    )
                }
            }
        }
    }

    if (showSetPicker) {
        InterestSetPickerDialog(
            sets = interestSets,
            onSelect = { dTag ->
                onFollowHashtag(dTag)
                showSetPicker = false
            },
            onDismiss = { showSetPicker = false }
        )
    }
}

@Composable
private fun InterestSetPickerDialog(
    sets: List<InterestSet>,
    onSelect: (dTag: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Set") },
        text = {
            Column {
                sets.forEach { set ->
                    Surface(
                        onClick = { onSelect(set.dTag) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                set.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${set.hashtags.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
    nip05Repo: Nip05Repository? = null,
    translationRepo: TranslationRepository? = null,
    pollVoteVersion: Int = 0,
    onPollVote: (String, List<String>) -> Unit = { _, _ -> }
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
    val translationVersion by translationRepo?.version?.collectAsState() ?: remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val translationState = remember(translationVersion, event.id) {
        translationRepo?.getState(event.id) ?: com.wisp.app.repo.TranslationState()
    }
    val pollVoteCounts = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getPollVoteCounts(event.id) else emptyMap()
    }
    val pollTotalVotes = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getPollTotalVotes(event.id) else 0
    }
    val userPollVotes = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getUserPollVotes(event.id) else emptyList()
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
        noteActions = noteActions,
        translationState = translationState,
        onTranslate = { translationRepo?.translate(event.id, event.content) },
        pollVoteCounts = pollVoteCounts,
        pollTotalVotes = pollTotalVotes,
        userPollVotes = userPollVotes,
        onPollVote = { optionIds -> onPollVote(event.id, optionIds) }
    )
}
