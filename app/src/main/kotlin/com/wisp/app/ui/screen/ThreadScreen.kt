package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.wisp.app.nostr.Nip69
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.CollapsedRepliesRow
import com.wisp.app.ui.component.GalleryCard
import com.wisp.app.ui.component.isGalleryEvent
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.threadConnector
import com.wisp.app.ui.component.threadIndentDp
import com.wisp.app.viewmodel.ThreadViewModel
import com.wisp.app.viewmodel.thread.ThreadItem
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
    onHashtagClick: ((String) -> Unit)? = null,
    onRelayClick: ((String) -> Unit)? = null,
    onArticleClick: ((Int, String, String) -> Unit)? = null,
    onPayInvoice: (suspend (String) -> Boolean)? = null,
    translationRepo: TranslationRepository? = null,
    autoTranslate: Boolean = false,
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
    isEmojiSetAdded: ((String, String) -> Boolean)? = null,
    /** Whether the user can private-zap [event]'s author (local keypair + DM relays on both sides). */
    canPrivateZapFor: (NostrEvent) -> Boolean = { false }
) {
    val flatThread by viewModel.flatThread.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scrollToIndex by viewModel.scrollToIndex.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showRootButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }
    // When expanding a folded branch, hold the viewport steady on the note above the button:
    // capture the top visible item + its scroll offset, then snap back to it once the subtree is
    // inserted so the screen keeps its exact position.
    var restoreAnchor by remember { mutableStateOf<Pair<Any, Int>?>(null) }

    // The sticky reply bar targets whichever note is centered in the viewport, not always the root.
    var focusedReplyEvent by remember { mutableStateOf<NostrEvent?>(null) }
    val threadState = rememberUpdatedState(flatThread)

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
            // Wait for list to have enough items, then scroll
            val target = scrollToIndex
            for (attempt in 0 until 10) {
                if (listState.layoutInfo.totalItemsCount > target) {
                    listState.animateScrollToItem(target)
                    break
                }
                kotlinx.coroutines.delay(150)
            }
            viewModel.clearScrollTarget()
        }
    }

    // After an inline expand inserts a subtree, restore the captured viewport position so the
    // note above the button stays exactly where it was.
    LaunchedEffect(restoreAnchor, flatThread) {
        val (anchorKey, anchorOffset) = restoreAnchor ?: return@LaunchedEffect
        val index = flatThread.indexOfFirst { it.key == anchorKey }
        if (index >= 0) listState.scrollToItem(index, anchorOffset)
        restoreAnchor = null
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            focusedReplyEvent = (threadState.value.getOrNull(idx) as? ThreadItem.Post)?.event
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

    val zapDisabledContext = LocalContext.current
    val zapDisabledMessage = stringResource(R.string.zap_private_requires_dm_relays)
    val onZapDisabledTap: () -> Unit = {
        Toast.makeText(zapDisabledContext, zapDisabledMessage, Toast.LENGTH_SHORT).show()
    }

    val expandBranch: (String) -> Unit = { anchorId ->
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
            restoreAnchor = first.key to listState.firstVisibleItemScrollOffset
        }
        viewModel.expandBranch(anchorId)
    }

    val focalEvent = (flatThread.firstOrNull() as? ThreadItem.Post)?.event
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
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            val replyTarget = focusedReplyEvent ?: focalEvent
            ThreadReplyBar(
                enabled = replyTarget != null,
                onClick = { replyTarget?.let { onReply(it) } }
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
            val spamThread by viewModel.spamThread.collectAsState()
            val spamExpanded by viewModel.spamExpanded.collectAsState()
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = flatThread, key = { it.key }, contentType = { it.contentType }) { item ->
                        if (item !is ThreadItem.Post) {
                            // Folded subtree — expand inline, anchoring the note above the button.
                            if (item is ThreadItem.CollapsedReplies) {
                                CollapsedRepliesRow(
                                    item = item,
                                    onExpand = { expandBranch(item.anchor.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        } else {
                            val event = item.event
                            val depth = item.depth
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
                            val indentPadding = threadIndentDp(depth)
                            val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            val showConnector = depth > 0
                            Box(
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .threadConnector(
                                        show = showConnector,
                                        indent = indentPadding,
                                        lineColor = lineColor,
                                        dashedTop = item.connectorStartsMidAir
                                    )
                            ) {
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
                                        showDivider = !showConnector,
                                        modifier = Modifier.padding(start = indentPadding)
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
                                        isPrivate = eventRepo.isPrivate(event.id),
                                        zapEnabled = !eventRepo.isPrivate(event.id) || canPrivateZapFor(event),
                                        onZapDisabledTap = onZapDisabledTap,
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
                                        autoTranslate = autoTranslate,
                                        pollVoteCounts = pollVoteCounts,
                                        pollTotalVotes = pollTotalVotes,
                                        userPollVotes = userPollVotes,
                                        onPollVote = { optionIds -> onPollVote(event.id, optionIds) },
                                        zapPollSatsCounts = zapPollSatsCounts,
                                        zapPollTotalSats = zapPollTotalSats,
                                        userZapPollVote = userZapPollVote,
                                        onZapPollVote = { idx -> onZapPollVote(event.id, idx) },
                                        showDivider = !showConnector,
                                        modifier = Modifier.padding(start = indentPadding)
                                    )
                                }
                            }
                        }
                    }

                    // Show "no replies" state when loading is done and only the root exists
                    val hasReplies = flatThread.any { it is ThreadItem.Post && it.depth > 0 }
                    if (!isLoading && flatThread.isNotEmpty() && !hasReplies && spamThread.isEmpty()) {
                        item(key = "no_replies") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.ic_no_replies),
                                    contentDescription = null,
                                    modifier = androidx.compose.ui.Modifier.size(72.dp),
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                                )
                                Text(
                                    stringResource(R.string.thread_no_replies),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }

                    if (spamThread.isNotEmpty()) {
                        item(key = "spam_toggle") {
                            SpamToggle(
                                count = spamThread.size,
                                expanded = spamExpanded,
                                onToggle = { viewModel.toggleSpamExpanded() }
                            )
                        }
                        if (spamExpanded) {
                            items(items = spamThread, key = { "spam_${it.first.id}" }, contentType = { "post" }) { (event, _) ->
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
                                        isPrivate = eventRepo.isPrivate(event.id),
                                        zapEnabled = !eventRepo.isPrivate(event.id) || canPrivateZapFor(event),
                                        onZapDisabledTap = onZapDisabledTap,
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
                                        autoTranslate = autoTranslate,
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

/** Sticky bottom "Reply…" bar — taps through to the reply flow for the currently focused note. */
@Composable
private fun ThreadReplyBar(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onClick)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.reply_bar_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.cd_reply),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

