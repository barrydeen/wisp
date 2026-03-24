package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.NotificationType
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.PostCard
import android.net.Uri
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.viewmodel.NotificationFilter
import com.wisp.app.viewmodel.NotificationsViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    scrollToTopTrigger: Int = 0,
    userPubkey: String? = null,
    notifSoundEnabled: Boolean = true,
    onToggleNotifSound: () -> Unit = {},
    onBack: () -> Unit = {},
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onSendReply: (NostrEvent, String) -> Unit = { _, _ -> },
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (NostrEvent) -> Unit = {},
    onQuote: (NostrEvent) -> Unit = {},
    onZap: (NostrEvent) -> Unit = {},
    onFollowToggle: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    onMuteThread: (String) -> Unit = {},
    onAddToList: (String) -> Unit = {},
    nip05Repo: Nip05Repository? = null,
    isZapAnimating: (String) -> Boolean = { false },
    isZapInProgress: (String) -> Boolean = { false },
    isInList: (String) -> Boolean = { false },
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    zapError: SharedFlow<String>? = null,
    translationRepo: TranslationRepository? = null,
    onPollVote: (String, List<String>) -> Unit = { _, _ -> },
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onSendDm: (peerPubkey: String, content: String) -> Unit = { _, _ -> },
) {
    val notifications by viewModel.filteredFlatNotifications.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val currentFilter by viewModel.filter.collectAsState()
    val summary by viewModel.summary24h.collectAsState()
    val eventRepo = viewModel.eventRepository
    val listState = rememberLazyListState()
    var showFilterDropdown by remember { mutableStateOf(false) }
    val profileVersion = eventRepo?.profileVersion?.collectAsState()?.value ?: 0

    // Track which notification is expanded (only one at a time)
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Track inline replies sent by user: replyEventId -> list of sent content strings
    var inlineReplies by remember { mutableStateOf(mapOf<String, List<String>>()) }

    // Track inline DM replies sent by user: peerPubkey -> list of sent content strings
    var inlineDmReplies by remember { mutableStateOf(mapOf<String, List<String>>()) }

    // Version flows for PostCard cache invalidation
    val reactionVersion = eventRepo?.reactionVersion?.collectAsState()?.value ?: 0
    val zapVersion = eventRepo?.zapVersion?.collectAsState()?.value ?: 0
    val replyCountVersion = eventRepo?.replyCountVersion?.collectAsState()?.value ?: 0
    val repostVersion = eventRepo?.repostVersion?.collectAsState()?.value ?: 0
    val pollVoteVersion = eventRepo?.pollVoteVersion?.collectAsState()?.value ?: 0
    val followListSize = viewModel.contactRepository?.followList?.collectAsState()?.value?.size ?: 0

    val postCardParams = remember(
        eventRepo, userPubkey, profileVersion, reactionVersion, zapVersion,
        replyCountVersion, repostVersion, followListSize, resolvedEmojis, unicodeEmojis, pollVoteVersion
    ) {
        NotifPostCardParams(
            eventRepo = eventRepo,
            userPubkey = userPubkey,
            profileVersion = profileVersion,
            reactionVersion = reactionVersion,
            replyCountVersion = replyCountVersion,
            zapVersion = zapVersion,
            repostVersion = repostVersion,
            followListSize = followListSize,
            resolvedEmojis = resolvedEmojis,
            unicodeEmojis = unicodeEmojis,
            onOpenEmojiLibrary = onOpenEmojiLibrary,
            isFollowing = { viewModel.isFollowing(it) },
            onNoteClick = onNoteClick,
            onProfileClick = onProfileClick,
            onReply = onReply,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = onZap,
            onFollowToggle = onFollowToggle,
            onBlockUser = onBlockUser,
            onMuteThread = onMuteThread,
            onAddToList = onAddToList,
            nip05Repo = nip05Repo,
            isZapAnimating = isZapAnimating,
            isZapInProgress = isZapInProgress,
            isInList = isInList,
            translationRepo = translationRepo,
            pollVoteVersion = pollVoteVersion,
            onPollVote = onPollVote
        )
    }

    var zapErrorMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        zapError?.collect { error -> zapErrorMessage = error }
    }
    if (zapErrorMessage != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { zapErrorMessage = null },
            title = { Text("Zap Failed") },
            text = { Text(zapErrorMessage ?: "") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { zapErrorMessage = null }) { Text("OK") }
            }
        )
    }

    var handledScrollTrigger by rememberSaveable { mutableStateOf(scrollToTopTrigger) }
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger != handledScrollTrigger) {
            handledScrollTrigger = scrollToTopTrigger
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box {
                            Surface(
                                onClick = { showFilterDropdown = true },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        currentFilter.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Filter notifications",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showFilterDropdown,
                                onDismissRequest = { showFilterDropdown = false }
                            ) {
                                NotificationFilter.entries.forEach { filterOption ->
                                    DropdownMenuItem(
                                        text = { Text(filterOption.label) },
                                        onClick = {
                                            showFilterDropdown = false
                                            viewModel.setFilter(filterOption)
                                        },
                                        trailingIcon = if (currentFilter == filterOption) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleNotifSound) {
                        Icon(
                            if (notifSoundEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = if (notifSoundEnabled) "Mute notifications" else "Unmute notifications"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh(onRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        if (notifications.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(64.dp))
                Text(
                    "No notifications yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "summary_24h", contentType = "summary") {
                    DailySummaryBar(
                        summary = summary,
                        onFilterSelect = { viewModel.setFilter(it) }
                    )
                }
                items(items = notifications, key = { it.id }, contentType = { "notification" }) { item ->
                    val isExpanded = expandedId == item.id
                    val itemIndex = notifications.indexOf(item) + 1 // +1 for summary header
                    val coroutineScope = rememberCoroutineScope()
                    ZenNotificationRow(
                        item = item,
                        resolveProfile = { viewModel.getProfileData(it) },
                        eventRepo = eventRepo,
                        profileVersion = profileVersion,
                        isExpanded = isExpanded,
                        inlineReplies = inlineReplies[item.replyEventId ?: ""] ?: emptyList(),
                        inlineDmReplies = inlineDmReplies[item.dmPeerPubkey ?: ""] ?: emptyList(),
                        userPubkey = userPubkey,
                        postCardParams = postCardParams,
                        onClick = {
                            expandedId = if (isExpanded) null else item.id
                        },
                        onProfileClick = onProfileClick,
                        onSendReply = { replyToEvent, content ->
                            onSendReply(replyToEvent, content)
                            val key = item.replyEventId ?: ""
                            val existing = inlineReplies[key] ?: emptyList()
                            inlineReplies = inlineReplies + (key to (existing + content))
                        },
                        onSendDm = { peerPubkey, content ->
                            onSendDm(peerPubkey, content)
                            val existing = inlineDmReplies[peerPubkey] ?: emptyList()
                            inlineDmReplies = inlineDmReplies + (peerPubkey to (existing + content))
                        },
                        onUploadMedia = onUploadMedia,
                        onReplyFocused = {
                            coroutineScope.launch {
                                // Wait for keyboard to appear and layout to settle
                                kotlinx.coroutines.delay(300)
                                // Find how tall this item is in the current layout
                                val itemInfo = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == item.id }
                                val itemHeight = itemInfo?.size ?: 0
                                // Visible height after keyboard takes ~half the screen
                                val visibleHeight = listState.layoutInfo.viewportSize.height
                                // Offset so the bottom of the item (where composer is)
                                // aligns with the bottom of the visible area
                                val offset = (itemHeight - visibleHeight * 3 / 5).coerceAtLeast(0)
                                listState.animateScrollToItem(index = itemIndex, scrollOffset = offset)
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
        } // PullToRefreshBox
    }
}

// ── Zen Notification Row ────────────────────────────────────────────────

@Composable
private fun ZenNotificationRow(
    item: FlatNotificationItem,
    resolveProfile: (String) -> ProfileData?,
    eventRepo: EventRepository?,
    profileVersion: Int,
    isExpanded: Boolean = false,
    inlineReplies: List<String> = emptyList(),
    inlineDmReplies: List<String> = emptyList(),
    userPubkey: String? = null,
    postCardParams: NotifPostCardParams? = null,
    onClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    onSendReply: (NostrEvent, String) -> Unit = { _, _ -> },
    onSendDm: (String, String) -> Unit = { _, _ -> },
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onReplyFocused: () -> Unit = {}
) {
    val profile = remember(profileVersion, item.actorPubkey) { resolveProfile(item.actorPubkey) }
    val displayName = profile?.displayString
        ?: item.actorPubkey.take(8) + "..." + item.actorPubkey.takeLast(4)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Compact row — always visible
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon / emoji on the left (with sats below for zaps)
            NotificationTypeIcon(item, showSats = true)
            Spacer(Modifier.width(8.dp))
            ProfilePicture(
                url = profile?.picture,
                size = 32,
                modifier = Modifier.clickable { onProfileClick(item.actorPubkey) }
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = actionText(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // Show voted option labels
                if (item.type == NotificationType.VOTE && item.voteOptionIds.isNotEmpty()) {
                    val optionLabels = remember(item.referencedEventId, item.voteOptionIds) {
                        val pollEvent = eventRepo?.getEvent(item.referencedEventId)
                        if (pollEvent != null) {
                            val options = Nip88.parsePollOptions(pollEvent)
                            item.voteOptionIds.mapNotNull { id -> options.firstOrNull { it.id == id }?.label }
                        } else emptyList()
                    }
                    if (optionLabels.isNotEmpty()) {
                        Text(
                            text = optionLabels.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatNotifTimestamp(item.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Expanded section
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            if (item.type == NotificationType.DM) {
                DmExpansion(
                    item = item,
                    resolveProfile = resolveProfile,
                    profileVersion = profileVersion,
                    inlineDmReplies = inlineDmReplies,
                    userPubkey = userPubkey,
                    onSendDm = onSendDm,
                    onProfileClick = onProfileClick,
                    onFocused = onReplyFocused
                )
            } else if (item.type == NotificationType.REPLY) {
                ReplyExpansion(
                    item = item,
                    eventRepo = eventRepo,
                    resolveProfile = resolveProfile,
                    profileVersion = profileVersion,
                    inlineReplies = inlineReplies,
                    userPubkey = userPubkey,
                    postCardParams = postCardParams,
                    onProfileClick = onProfileClick,
                    onSendReply = onSendReply,
                    onUploadMedia = onUploadMedia,
                    onReplyFocused = onReplyFocused
                )
            } else if (postCardParams != null) {
                NoteExpansion(
                    item = item,
                    params = postCardParams
                )
            }
        }
    }
}

// ── Note Expansion (non-reply types) ────────────────────────────────────

@Composable
private fun NoteExpansion(
    item: FlatNotificationItem,
    params: NotifPostCardParams
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // For QUOTE: show the quote event (which embeds the quoted note via RichContent)
        // For all others: show the referenced note
        val eventId = when (item.type) {
            NotificationType.QUOTE -> item.quoteEventId ?: item.referencedEventId
            else -> item.referencedEventId
        }
        ReferencedNotePostCard(
            eventId = eventId,
            params = params
        )
    }
}

// ── DM Expansion ──────────────────────────────────────────────────────

@Composable
private fun DmExpansion(
    item: FlatNotificationItem,
    resolveProfile: (String) -> ProfileData?,
    profileVersion: Int,
    inlineDmReplies: List<String> = emptyList(),
    userPubkey: String? = null,
    onSendDm: (String, String) -> Unit = { _, _ -> },
    onProfileClick: (String) -> Unit = {},
    onFocused: () -> Unit = {}
) {
    val peerPubkey = item.dmPeerPubkey ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // Their message
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 68.dp, end = 16.dp)
        ) {
            Text(
                text = item.dmContent ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }

        // User's inline DM replies
        val userProfile = remember(profileVersion, userPubkey) {
            userPubkey?.let { resolveProfile(it) }
        }
        inlineDmReplies.forEach { content ->
            InlineSentReply(
                content = content,
                profile = userProfile,
                onProfileClick = onProfileClick,
                onNoteClick = {},
                modifier = Modifier.padding(start = 48.dp, top = 4.dp, end = 16.dp)
            )
        }

        // Inline DM composer
        InlineReplyComposer(
            onSend = { content -> onSendDm(peerPubkey, content) },
            onFocused = onFocused,
            placeholder = "Message...",
            modifier = Modifier.padding(start = 48.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
        )
    }
}

// ── Reply Expansion ────────────────────────────────────────────────────

@Composable
private fun ReplyExpansion(
    item: FlatNotificationItem,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
    profileVersion: Int,
    inlineReplies: List<String>,
    userPubkey: String?,
    postCardParams: NotifPostCardParams?,
    onProfileClick: (String) -> Unit,
    onSendReply: (NostrEvent, String) -> Unit,
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onReplyFocused: () -> Unit = {}
) {
    val replyEvent = remember(item.replyEventId) { item.replyEventId?.let { eventRepo?.getEvent(it) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // Original note (the note being replied to) — compact bordered card
        if (item.referencedEventId.isNotBlank() && postCardParams != null) {
            Text(
                text = "replying to",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                ReferencedNotePostCard(
                    eventId = item.referencedEventId,
                    params = postCardParams
                )
            }
        }

        // The reply event — full PostCard with action bar
        if (replyEvent != null && postCardParams != null) {
            ReferencedNotePostCard(
                eventId = replyEvent.id,
                params = postCardParams
            )
        }

        // User's inline replies
        val userProfile = remember(profileVersion, userPubkey) {
            userPubkey?.let { resolveProfile(it) }
        }
        inlineReplies.forEach { content ->
            InlineSentReply(
                content = content,
                profile = userProfile,
                eventRepo = postCardParams?.eventRepo,
                onProfileClick = onProfileClick,
                onNoteClick = postCardParams?.onNoteClick ?: {},
                modifier = Modifier.padding(start = 48.dp, top = 4.dp, end = 16.dp)
            )
        }

        // Inline reply composer
        if (replyEvent != null) {
            InlineReplyComposer(
                onSend = { content -> onSendReply(replyEvent, content) },
                onUploadMedia = onUploadMedia,
                onFocused = onReplyFocused,
                modifier = Modifier.padding(start = 48.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
            )
        }
    }
}

// ── Referenced Note PostCard ────────────────────────────────────────────

@Composable
private fun ReferencedNotePostCard(
    eventId: String,
    params: NotifPostCardParams,
    relayHints: List<String> = emptyList()
) {
    val eventRepo = params.eventRepo ?: return

    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(eventId, version) { eventRepo.getEvent(eventId) }

    LaunchedEffect(eventId) {
        if (eventRepo.getEvent(eventId) == null) {
            eventRepo.requestQuotedEvent(eventId, relayHints)
        }
    }

    if (event == null) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }

    val profile = remember(params.profileVersion, event.pubkey) {
        eventRepo.getProfileData(event.pubkey)
    }
    val likeCount = remember(params.reactionVersion, event.id) {
        eventRepo.getReactionCount(event.id)
    }
    val replyCount = remember(params.replyCountVersion, event.id) {
        eventRepo.getReplyCount(event.id)
    }
    val zapSats = remember(params.zapVersion, event.id) {
        eventRepo.getZapSats(event.id)
    }
    val userEmojis = remember(params.reactionVersion, event.id, params.userPubkey) {
        params.userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
    }
    val reactionDetails = remember(params.reactionVersion, event.id) {
        eventRepo.getReactionDetails(event.id)
    }
    val zapDetails = remember(params.zapVersion, event.id) {
        eventRepo.getZapDetails(event.id)
    }
    val repostCount = remember(params.repostVersion, event.id) {
        eventRepo.getRepostCount(event.id)
    }
    val repostPubkeys = remember(params.repostVersion, event.id) {
        eventRepo.getReposterPubkeys(event.id)
    }
    val hasUserReposted = remember(params.repostVersion, event.id) {
        eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(params.zapVersion, event.id) {
        eventRepo.hasUserZapped(event.id)
    }
    val followingAuthor = remember(params.followListSize, event.pubkey) {
        params.isFollowing(event.pubkey)
    }
    val eventReactionEmojiUrls = remember(params.reactionVersion, event.id) {
        eventRepo.getReactionEmojiUrls(event.id)
    }
    val translationVersion by params.translationRepo?.version?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val translationState = remember(translationVersion, event.id) {
        params.translationRepo?.getState(event.id) ?: com.wisp.app.repo.TranslationState()
    }
    val pollVoteCounts = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getPollVoteCounts(event.id) else emptyMap()
    }
    val pollTotalVotes = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getPollTotalVotes(event.id) else 0
    }
    val userPollVotes = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 1068) eventRepo.getUserPollVotes(event.id) else emptyList()
    }

    PostCard(
        event = event,
        profile = profile,
        onReply = { params.onReply(event) },
        onProfileClick = { params.onProfileClick(event.pubkey) },
        onNavigateToProfile = params.onProfileClick,
        onNoteClick = { params.onNoteClick(event.id) },
        onReact = { emoji -> params.onReact(event, emoji) },
        userReactionEmojis = userEmojis,
        onRepost = { params.onRepost(event) },
        onQuote = { params.onQuote(event) },
        hasUserReposted = hasUserReposted,
        repostCount = repostCount,
        onZap = { params.onZap(event) },
        hasUserZapped = hasUserZapped,
        likeCount = likeCount,
        replyCount = replyCount,
        zapSats = zapSats,
        isZapAnimating = params.isZapAnimating(event.id),
        isZapInProgress = params.isZapInProgress(event.id),
        eventRepo = eventRepo,
        reactionDetails = reactionDetails,
        zapDetails = zapDetails,
        repostDetails = repostPubkeys,
        reactionEmojiUrls = eventReactionEmojiUrls,
        resolvedEmojis = params.resolvedEmojis,
        unicodeEmojis = params.unicodeEmojis,
        onOpenEmojiLibrary = params.onOpenEmojiLibrary,
        onNavigateToProfileFromDetails = params.onProfileClick,
        onFollowAuthor = { params.onFollowToggle(event.pubkey) },
        onBlockAuthor = { params.onBlockUser(event.pubkey) },
        onMuteThread = {
            val rootId = Nip10.getRootId(event) ?: event.id
            params.onMuteThread(rootId)
        },
        isFollowingAuthor = followingAuthor,
        isOwnEvent = event.pubkey == params.userPubkey,
        nip05Repo = params.nip05Repo,
        onAddToList = { params.onAddToList(event.id) },
        isInList = params.isInList(event.id),
        onQuotedNoteClick = params.onNoteClick,
        translationState = translationState,
        onTranslate = { params.translationRepo?.translate(event.id, event.content) },
        pollVoteCounts = pollVoteCounts,
        pollTotalVotes = pollTotalVotes,
        userPollVotes = userPollVotes,
        onPollVote = { optionIds -> params.onPollVote(event.id, optionIds) },
        showDivider = false
    )
}

// ── PostCard params holder ─────────────────────────────────────────────

private data class NotifPostCardParams(
    val eventRepo: EventRepository?,
    val userPubkey: String?,
    val profileVersion: Int,
    val reactionVersion: Int,
    val replyCountVersion: Int,
    val zapVersion: Int,
    val repostVersion: Int,
    val followListSize: Int = 0,
    val resolvedEmojis: Map<String, String> = emptyMap(),
    val unicodeEmojis: List<String> = emptyList(),
    val onOpenEmojiLibrary: (() -> Unit)? = null,
    val isFollowing: (String) -> Boolean,
    val onNoteClick: (String) -> Unit,
    val onProfileClick: (String) -> Unit,
    val onReply: (NostrEvent) -> Unit,
    val onReact: (NostrEvent, String) -> Unit,
    val onRepost: (NostrEvent) -> Unit,
    val onQuote: (NostrEvent) -> Unit,
    val onZap: (NostrEvent) -> Unit,
    val onFollowToggle: (String) -> Unit,
    val onBlockUser: (String) -> Unit,
    val onMuteThread: (String) -> Unit = {},
    val onAddToList: (String) -> Unit,
    val nip05Repo: Nip05Repository?,
    val isZapAnimating: (String) -> Boolean,
    val isZapInProgress: (String) -> Boolean,
    val isInList: (String) -> Boolean,
    val translationRepo: TranslationRepository? = null,
    val pollVoteVersion: Int = 0,
    val onPollVote: (String, List<String>) -> Unit = { _, _ -> }
)

// ── Inline Sent Reply ──────────────────────────────────────────────────

@Composable
private fun InlineSentReply(
    content: String,
    profile: ProfileData?,
    eventRepo: EventRepository? = null,
    onProfileClick: (String) -> Unit,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        ProfilePicture(
            url = profile?.picture,
            size = 28
        )
        Spacer(Modifier.width(6.dp))
        com.wisp.app.ui.component.RichContent(
            content = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            eventRepo = eventRepo,
            onProfileClick = onProfileClick,
            onNoteClick = onNoteClick,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Inline Reply Composer ──────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InlineReplyComposer(
    onSend: (String) -> Unit,
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onFocused: () -> Unit = {},
    placeholder: String = "Reply...",
    modifier: Modifier = Modifier
) {
    val textFieldState = remember { TextFieldState() }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            state = textFieldState,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .contentReceiver(object : ReceiveContentListener {
                    override fun onReceive(
                        transferableContent: TransferableContent
                    ): TransferableContent? {
                        if (!transferableContent.hasMediaType(MediaType.Image)) {
                            return transferableContent
                        }
                        val clipData = transferableContent.clipEntry.clipData
                        val uris = (0 until clipData.itemCount)
                            .mapNotNull { i -> clipData.getItemAt(i).uri }
                        if (uris.isNotEmpty()) {
                            onUploadMedia(uris) { url ->
                                textFieldState.edit {
                                    val current = toString()
                                    val newText = if (current.isBlank()) url else "$current\n$url"
                                    replace(0, length, newText)
                                }
                            }
                        }
                        return transferableContent.consume { item -> item.uri != null }
                    }
                }),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            decorator = { innerTextField ->
                Box {
                    if (textFieldState.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clickable {
                    val trimmed = textFieldState.text.toString().trim()
                    if (trimmed.isNotEmpty()) {
                        onSend(trimmed)
                        textFieldState.edit { replace(0, length, "") }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send reply",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun NotificationTypeIcon(item: FlatNotificationItem, showSats: Boolean = false) {
    val iconSize = 28.dp
    if (item.type == NotificationType.ZAP) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.CurrencyBitcoin,
                contentDescription = "Zap",
                modifier = Modifier.size(iconSize),
                tint = WispThemeColors.zapColor
            )
            if (showSats && item.zapSats > 0) {
                Text(
                    text = formatSatsCompact(item.zapSats),
                    style = MaterialTheme.typography.labelSmall,
                    color = WispThemeColors.zapColor,
                    maxLines = 1
                )
            }
        }
        return
    }
    when (item.type) {
        NotificationType.REACTION -> {
            if (item.emoji != null) {
                val shortcode = Nip30.shortcodeRegex.matchEntire(item.emoji)?.groupValues?.get(1)
                if (item.emojiUrl != null) {
                    AsyncImage(
                        model = item.emojiUrl,
                        contentDescription = shortcode ?: item.emoji,
                        modifier = Modifier.size(iconSize)
                    )
                } else {
                    val displayEmoji = if (item.emoji == "+") "\u2764\uFE0F" else item.emoji
                    if (shortcode == null) {
                        Text(
                            text = displayEmoji,
                            fontSize = 22.sp
                        )
                    } else {
                        Icon(
                            Icons.Outlined.FavoriteBorder,
                            contentDescription = "Reaction",
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Icon(
                    Icons.Outlined.FavoriteBorder,
                    contentDescription = "Reaction",
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        NotificationType.ZAP -> { /* handled above */ }
        NotificationType.REPOST -> {
            Icon(
                Icons.Outlined.Repeat,
                contentDescription = "Repost",
                modifier = Modifier.size(iconSize),
                tint = WispThemeColors.repostColor
            )
        }
        NotificationType.REPLY -> {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Reply",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.QUOTE -> {
            Text(
                text = "\u201C\u201D",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.MENTION -> {
            Icon(
                Icons.Outlined.AlternateEmail,
                contentDescription = "Mention",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.DM -> {
            Icon(
                Icons.Outlined.MailOutline,
                contentDescription = "DM",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.VOTE -> {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = "Vote",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun actionText(item: FlatNotificationItem): String = when (item.type) {
    NotificationType.REACTION -> "reacted"
    NotificationType.ZAP -> "zapped"
    NotificationType.REPOST -> "reposted"
    NotificationType.REPLY -> "replied"
    NotificationType.QUOTE -> "quoted"
    NotificationType.MENTION -> "mentioned you"
    NotificationType.VOTE -> "voted"
    NotificationType.DM -> "messaged you"
}

// ── Daily Summary Bar ──────────────────────────────────────────────────

@Composable
private fun DailySummaryBar(summary: NotificationSummary, onFilterSelect: (NotificationFilter) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "24h",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SummaryStat(Icons.Outlined.ChatBubbleOutline, summary.replyCount.toString()) { onFilterSelect(NotificationFilter.REPLIES) }
            SummaryStat(Icons.Outlined.FavoriteBorder, summary.reactionCount.toString()) { onFilterSelect(NotificationFilter.REACTIONS) }
            SummaryStat(Icons.Outlined.CurrencyBitcoin, formatSatsCompact(summary.zapSats)) { onFilterSelect(NotificationFilter.ZAPS) }
            SummaryStat(Icons.Outlined.Repeat, summary.repostCount.toString()) { onFilterSelect(NotificationFilter.REPOSTS) }
            SummaryStat(Icons.Outlined.AlternateEmail, (summary.mentionCount + summary.quoteCount).toString()) { onFilterSelect(NotificationFilter.MENTIONS) }
            SummaryStat(Icons.Outlined.MailOutline, summary.dmCount.toString()) { onFilterSelect(NotificationFilter.DMS) }
        }
    }
}

@Composable
private fun SummaryStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Formatters ─────────────────────────────────────────────────────────

private fun formatSatsCompact(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M"
    sats >= 1_000 -> "${sats / 1_000}K"
    else -> sats.toString()
}

private val notifDateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private val notifDateTimeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatNotifTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val now = System.currentTimeMillis()
    val millis = epoch * 1000
    val diff = now - millis

    if (diff < 0) return notifDateTimeFormat.format(Date(millis))

    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    if (seconds < 60) return "${seconds}s"
    if (minutes < 60) return "${minutes}m"
    if (hours < 24) return "${hours}h"

    val days = diff / (24 * 60 * 60 * 1000L)
    if (days == 1L) return "yesterday"

    val date = Date(millis)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    val dateYear = cal.get(java.util.Calendar.YEAR)

    return if (dateYear != currentYear) {
        notifDateTimeYearFormat.format(date)
    } else {
        notifDateTimeFormat.format(date)
    }
}
