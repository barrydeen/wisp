package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.GalleryCard
import com.wisp.app.ui.component.isGalleryEvent
import com.wisp.app.ui.component.PostCard
import com.wisp.app.viewmodel.ThreadViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val absoluteTimestampFormat = SimpleDateFormat("MMM d, yyyy \u00B7 h:mm a", Locale.US)

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
    onHashtagClick: ((String) -> Unit)? = null,
    onRelayClick: ((String) -> Unit)? = null,
    onArticleClick: ((Int, String, String) -> Unit)? = null,
    onPayInvoice: (suspend (String) -> Boolean)? = null,
    translationRepo: TranslationRepository? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onPollVote: (String, List<String>) -> Unit = { _, _ -> },
    onZapPollVote: (String, Int) -> Unit = { _, _ -> },
    onGroupRoom: ((String, String) -> Unit)? = null,
    onLiveStreamClick: ((String, String, String?) -> Unit)? = null,
    fetchGroupPreview: (suspend (String, String) -> com.wisp.app.repo.GroupPreview?)? = null,
    onAddEmojiSet: ((String, String) -> Unit)? = null,
    onRemoveEmojiSet: ((String, String) -> Unit)? = null,
    isEmojiSetAdded: ((String, String) -> Boolean)? = null
) {
    val focal by viewModel.focal.collectAsState()
    val ancestors by viewModel.ancestors.collectAsState()
    val replies by viewModel.replies.collectAsState()
    val childCounts by viewModel.childCounts.collectAsState()
    val blockedReplyIds by viewModel.blockedReplyIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val listState = rememberLazyListState()


    // Scroll focal to top once after the thread settles. Fires once per ThreadScreen lifetime
    // so the user can scroll up freely afterward.
    var didScrollToFocal by remember { mutableStateOf(false) }
    LaunchedEffect(ancestors, focal) {
        if (didScrollToFocal) return@LaunchedEffect
        val currentFocal = focal ?: return@LaunchedEffect
        // Focal is its own root → it's already at index 0, mark done without scrolling.
        // Without this, root-as-focal posts never auto-scroll because ancestors stays empty.
        if (ancestors.isEmpty() && Nip10.getReplyTarget(currentFocal) == null) {
            didScrollToFocal = true
            return@LaunchedEffect
        }
        // Otherwise wait for ancestors to render before scrolling, so focal lands at the top
        // instead of in the middle of an empty view.
        if (ancestors.isEmpty()) return@LaunchedEffect
        val focalIndex = ancestors.size // focal is right after ancestors
        for (attempt in 0 until 10) {
            if (listState.layoutInfo.totalItemsCount > focalIndex) {
                listState.animateScrollToItem(focalIndex)
                didScrollToFocal = true
                break
            }
            kotlinx.coroutines.delay(150)
        }
    }

    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val relaySourceVersion by eventRepo.relaySourceVersion.collectAsState()
    val nip05Version by nip05Repo?.version?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val translationVersion by translationRepo?.version?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val pollVoteVersion by eventRepo.pollVoteVersion.collectAsState()
    val followList by contactRepo.followList.collectAsState()

    val resolvedEmojisState = rememberUpdatedState(resolvedEmojis)
    val unicodeEmojisState = rememberUpdatedState(unicodeEmojis)
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
            onHashtagClick = onHashtagClick,
            onRelayClick = onRelayClick,
            onArticleClick = onArticleClick,
            onPayInvoice = onPayInvoice,
            onGroupRoom = onGroupRoom,
            onLiveStreamClick = onLiveStreamClick,
            fetchGroupPreview = fetchGroupPreview,
            onAddEmojiSet = onAddEmojiSet,
            onRemoveEmojiSet = onRemoveEmojiSet,
            isEmojiSetAdded = isEmojiSetAdded,
            onPollVote = onPollVote,
            resolvedEmojisProvider = { resolvedEmojisState.value },
            unicodeEmojisProvider = { unicodeEmojisState.value },
            onOpenEmojiLibrary = onOpenEmojiLibrary
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
        if (isLoading && focal == null && replies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val spamThread by viewModel.spamThread.collectAsState()
            val spamExpanded by viewModel.spamExpanded.collectAsState()
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // === Section 1: Ancestors (compact) ===
                    items(
                        items = ancestors,
                        key = { "ancestor_${it.id}" },
                        contentType = { "ancestor" }
                    ) { event ->
                        val profileData = eventRepo.getProfileData(event.pubkey)
                        PostCard(
                            event = event,
                            profile = profileData,
                            onReply = {},
                            onNoteClick = { onNoteClick(event) },
                            onProfileClick = {},
                            eventRepo = eventRepo,
                            resolvedEmojis = resolvedEmojis,
                            noteActions = noteActions,
                            onQuotedNoteClick = onQuotedNoteClick,
                            showDivider = false,
                            ancestorCompact = true
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            thickness = 0.5.dp
                        )
                    }

                    // === Section 2: Focal post (highlighted) ===
                    focal?.let { focalEvent ->
                        item(key = "focal_${focalEvent.id}", contentType = "focal") {
                            val profileData = eventRepo.getProfileData(focalEvent.pubkey)
                            val likeCount = reactionVersion.let { eventRepo.getReactionCount(focalEvent.id) }
                            val replyCount = replyCountVersion.let { eventRepo.getReplyCount(focalEvent.id) }
                            val zapSats = zapVersion.let { eventRepo.getZapSats(focalEvent.id) }
                            val userEmojis = reactionVersion.let { userPubkey?.let { eventRepo.getUserReactionEmojis(focalEvent.id, it) } ?: emptySet() }
                            val reactionDetails = reactionVersion.let { eventRepo.getReactionDetails(focalEvent.id) }
                            val zapDetailsList = zapVersion.let { eventRepo.getZapDetails(focalEvent.id) }
                            val repostCount = repostVersion.let { eventRepo.getRepostCount(focalEvent.id) }
                            val repostPubkeys = repostVersion.let { eventRepo.getReposterPubkeys(focalEvent.id) }
                            val hasUserReposted = repostVersion.let { eventRepo.hasUserReposted(focalEvent.id) }
                            val hasUserZapped = zapVersion.let { eventRepo.hasUserZapped(focalEvent.id) }
                            val eventReactionEmojiUrls = reactionVersion.let { eventRepo.getReactionEmojiUrls(focalEvent.id) }
                            val relayIcons = remember(relaySourceVersion, focalEvent.id) {
                                eventRepo.getEventRelays(focalEvent.id).map { url ->
                                    url to relayInfoRepo?.getIconUrl(url)
                                }
                            }
                            val translationState = remember(translationVersion, focalEvent.id) {
                                translationRepo?.getState(focalEvent.id) ?: com.wisp.app.repo.TranslationState()
                            }
                            val pollVoteCounts = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 1068) eventRepo.getPollVoteCounts(focalEvent.id) else emptyMap()
                            }
                            val pollTotalVotes = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 1068) eventRepo.getPollTotalVotes(focalEvent.id) else 0
                            }
                            val userPollVotes = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 1068) eventRepo.getUserPollVotes(focalEvent.id) else emptyList()
                            }
                            val zapPollSatsCounts = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 6969) eventRepo.getZapPollSatsCounts(focalEvent.id) else emptyMap()
                            }
                            val zapPollTotalSats = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 6969) eventRepo.getZapPollTotalSats(focalEvent.id) else 0L
                            }
                            val userZapPollVote = remember(pollVoteVersion, focalEvent.id) {
                                if (focalEvent.kind == 6969) eventRepo.getUserZapPollVote(focalEvent.id) else null
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                            ) {
                                PostCard(
                                    event = focalEvent,
                                    profile = profileData,
                                    onReply = { onReply(focalEvent) },
                                    onProfileClick = { onProfileClick(focalEvent.pubkey) },
                                    onNavigateToProfile = onProfileClick,
                                    onNoteClick = { },
                                    onReact = { emoji -> onReact(focalEvent, emoji) },
                                    userReactionEmojis = userEmojis,
                                    onRepost = { onRepost(focalEvent) },
                                    onQuote = { onQuote(focalEvent) },
                                    hasUserReposted = hasUserReposted,
                                    repostCount = repostCount,
                                    onZap = { onZap(focalEvent) },
                                    hasUserZapped = hasUserZapped,
                                    likeCount = likeCount,
                                    replyCount = replyCount,
                                    zapSats = zapSats,
                                    isZapAnimating = focalEvent.id in zapAnimatingIds,
                                    isZapInProgress = focalEvent.id in zapInProgressIds,
                                    eventRepo = eventRepo,
                                    reactionDetails = reactionDetails,
                                    zapDetails = zapDetailsList,
                                    repostDetails = repostPubkeys,
                                    reactionEmojiUrls = eventReactionEmojiUrls,
                                    resolvedEmojis = resolvedEmojis,
                                    unicodeEmojis = unicodeEmojis,
                                    onOpenEmojiLibrary = onOpenEmojiLibrary,
                                    relayIcons = relayIcons,
                                    onNavigateToProfileFromDetails = onProfileClick,
                                    onFollowAuthor = { onToggleFollow(focalEvent.pubkey) },
                                    onBlockAuthor = { onBlockUser(focalEvent.pubkey) },
                                    isFollowingAuthor = followList.let { contactRepo.isFollowing(focalEvent.pubkey) },
                                    isOwnEvent = focalEvent.pubkey == userPubkey,
                                    onAddToList = { onAddToList(focalEvent.id) },
                                    isInList = focalEvent.id in listedIds,
                                    onPin = { onTogglePin(focalEvent.id) },
                                    isPinned = focalEvent.id in pinnedIds,
                                    onDelete = { onDeleteEvent(focalEvent.id, focalEvent.kind) },
                                    nip05Repo = nip05Repo,
                                    onQuotedNoteClick = onQuotedNoteClick,
                                    noteActions = noteActions,
                                    translationState = translationState,
                                    onTranslate = { translationRepo?.translate(focalEvent.id, focalEvent.content) },
                                    pollVoteCounts = pollVoteCounts,
                                    pollTotalVotes = pollTotalVotes,
                                    userPollVotes = userPollVotes,
                                    onPollVote = { optionIds -> onPollVote(focalEvent.id, optionIds) },
                                    zapPollSatsCounts = zapPollSatsCounts,
                                    zapPollTotalSats = zapPollTotalSats,
                                    userZapPollVote = userZapPollVote,
                                    onZapPollVote = { idx -> onZapPollVote(focalEvent.id, idx) },
                                    showDivider = false
                                )
                                // Absolute timestamp + reply count meta line
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = absoluteTimestampFormat.format(Date(focalEvent.created_at * 1000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    val directReplyCount = replies.size
                                    if (directReplyCount > 0) {
                                        Text(
                                            text = " \u00B7 $directReplyCount ${if (directReplyCount == 1) "reply" else "replies"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline,
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }

                    // === Section 3: Empty state ===
                    if (!isLoading && focal != null && replies.isEmpty()) {
                        item(key = "empty_replies") {
                            Text(
                                text = "No replies yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 32.dp)
                            )
                        }
                    }

                    // === Section 3: Replies (tappable, with child count hints) ===
                    items(
                        items = replies,
                        key = { "reply_${it.id}" },
                        contentType = { "reply" }
                    ) { event ->
                        if (event.id in blockedReplyIds) {
                            BlockedPlaceholder()
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline,
                                thickness = 0.5.dp
                            )
                            return@items
                        }

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
                        val zapPollSatsCounts = remember(pollVoteVersion, event.id) {
                            if (event.kind == 6969) eventRepo.getZapPollSatsCounts(event.id) else emptyMap()
                        }
                        val zapPollTotalSats = remember(pollVoteVersion, event.id) {
                            if (event.kind == 6969) eventRepo.getZapPollTotalSats(event.id) else 0L
                        }
                        val userZapPollVote = remember(pollVoteVersion, event.id) {
                            if (event.kind == 6969) eventRepo.getUserZapPollVote(event.id) else null
                        }

                        Column {
                            if (isGalleryEvent(event)) {
                                GalleryCard(
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
                                    resolvedEmojis = resolvedEmojis,
                                    unicodeEmojis = unicodeEmojis,
                                    onOpenEmojiLibrary = onOpenEmojiLibrary,
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
                                    showDivider = false
                                )
                            } else {
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
                                    resolvedEmojis = resolvedEmojis,
                                    unicodeEmojis = unicodeEmojis,
                                    onOpenEmojiLibrary = onOpenEmojiLibrary,
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
                                    translationState = translationState,
                                    onTranslate = { translationRepo?.translate(event.id, event.content) },
                                    pollVoteCounts = pollVoteCounts,
                                    pollTotalVotes = pollTotalVotes,
                                    userPollVotes = userPollVotes,
                                    onPollVote = { optionIds -> onPollVote(event.id, optionIds) },
                                    zapPollSatsCounts = zapPollSatsCounts,
                                    zapPollTotalSats = zapPollTotalSats,
                                    userZapPollVote = userZapPollVote,
                                    onZapPollVote = { idx -> onZapPollVote(event.id, idx) },
                                    showDivider = false
                                )
                            }

                            // "View N replies" hint for replies that have children
                            val replyChildCount = childCounts[event.id] ?: 0
                            val engagementReplyCount = replyCountVersion.let { eventRepo.getReplyCount(event.id) }
                            val hintCount = maxOf(replyChildCount, engagementReplyCount)
                            if (hintCount > 0) {
                                Text(
                                    text = "$hintCount ${if (hintCount == 1) "reply" else "replies"} \u203A",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 10.dp)
                                )
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline,
                                thickness = 0.5.dp
                            )
                        }
                    }

                    // === Spam section ===
                    if (spamThread.isNotEmpty()) {
                        item(key = "spam_toggle") {
                            SpamToggle(
                                count = spamThread.size,
                                expanded = spamExpanded,
                                onToggle = { viewModel.toggleSpamExpanded() }
                            )
                        }
                        if (spamExpanded) {
                            items(items = spamThread, key = { "spam_${it.id}" }, contentType = { "post" }) { event ->
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
                                    eventRepo.getEventRelays(event.id).map { url -> url to relayInfoRepo?.getIconUrl(url) }
                                }
                                val translationState = remember(translationVersion, event.id) {
                                    translationRepo?.getState(event.id) ?: com.wisp.app.repo.TranslationState()
                                }
                                Column {
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
                                        resolvedEmojis = resolvedEmojis,
                                        unicodeEmojis = unicodeEmojis,
                                        onOpenEmojiLibrary = onOpenEmojiLibrary,
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
                                        translationState = translationState,
                                        onTranslate = { translationRepo?.translate(event.id, event.content) },
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = 16.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { viewModel.markNotSpam(event.pubkey) }) {
                                            Text(
                                                stringResource(R.string.thread_not_spam),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
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
private fun BlockedPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Block,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Post from blocked user",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun SpamToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.thread_hidden_spam, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) stringResource(R.string.thread_tap_to_hide)
                else stringResource(R.string.thread_tap_to_show),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
        }
    }
}
