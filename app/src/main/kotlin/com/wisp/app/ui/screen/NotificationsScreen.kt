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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.wisp.app.ui.component.EmojiReactionPopup
import com.wisp.app.ui.component.EmojiShortcodePopup
import com.wisp.app.ui.component.detectEmojiAutocomplete
import com.wisp.app.ui.component.insertEmojiShortcode
import com.wisp.app.ui.component.LightningAnimation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip69
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.NotificationType
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.GalleryCard
import com.wisp.app.ui.component.isGalleryEvent
import com.wisp.app.ui.component.PostCard
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.heightIn
import com.wisp.app.nostr.Nip19
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.viewmodel.NotificationFilter
import com.wisp.app.viewmodel.NotificationsViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.wisp.app.R
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
    onZapPollVote: (String, Int) -> Unit = { _, _ -> },
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onSendDm: (peerPubkey: String, content: String) -> Unit = { _, _ -> },
    onDmReact: (peerPubkey: String, rumorId: String, senderPubkey: String, emoji: String) -> Unit = { _, _, _, _ -> },
    onDmZap: (peerPubkey: String, rumorId: String, senderPubkey: String) -> Unit = { _, _, _ -> },
    dmZapSats: (senderPubkey: String) -> Long = { 0L },
    onDmConversationClick: (conversationKey: String) -> Unit = {},
    onPayInvoice: (suspend (String) -> Boolean)? = null,
    onGroupRoom: ((String, String) -> Unit)? = null,
    /** Navigate to a group room and scroll to a specific message (from group notification click). */
    onGroupNotificationClick: ((groupChatId: String, messageId: String) -> Unit)? = null,
    /** Look up group message content, group name, and emoji tags. */
    resolveGroupMessage: ((groupChatId: String, messageId: String) -> Triple<String?, String?, Map<String, String>>)? = null,
    fetchGroupPreview: (suspend (String, String) -> com.wisp.app.repo.GroupPreview?)? = null,
    onAddEmojiSet: ((String, String) -> Unit)? = null,
    onRemoveEmojiSet: ((String, String) -> Unit)? = null,
    isEmojiSetAdded: ((String, String) -> Boolean)? = null,
) {
    val notifications by viewModel.filteredFlatNotifications.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val enabledTypes by viewModel.enabledTypes.collectAsState()
    val chatRoomsEnabled by viewModel.chatRoomsEnabled.collectAsState()
    val summary by viewModel.summary24h.collectAsState()
    val eventRepo = viewModel.eventRepository
    val listState = rememberLazyListState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val profileVersion = eventRepo?.profileVersion?.collectAsState()?.value ?: 0

    // Track which notification is expanded (only one at a time)
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Track inline replies sent by user: replyEventId -> list of sent content strings
    var inlineReplies by remember { mutableStateOf(mapOf<String, List<String>>()) }

    // Track inline DM replies sent by user: peerPubkey -> list of sent content strings
    var inlineDmReplies by remember { mutableStateOf(mapOf<String, List<String>>()) }

    // Mention autocomplete state
    val mentionQuery by viewModel.mentionQuery.collectAsState()
    val mentionCandidates by viewModel.mentionCandidates.collectAsState()
    val resolveDisplayName: (String) -> String? = remember(eventRepo) {
        { bech32 ->
            try {
                val data = Nip19.decodeNostrUri("nostr:$bech32")
                if (data is com.wisp.app.nostr.NostrUriData.ProfileRef) {
                    eventRepo?.getProfileData(data.pubkey)?.displayString
                } else null
            } catch (_: Exception) { null }
        }
    }

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
            onPollVote = onPollVote,
            onZapPollVote = onZapPollVote,
            onPayInvoice = onPayInvoice,
            onGroupRoom = onGroupRoom,
            fetchGroupPreview = fetchGroupPreview,
            onAddEmojiSet = onAddEmojiSet,
            onRemoveEmojiSet = onRemoveEmojiSet,
            isEmojiSetAdded = isEmojiSetAdded
        )
    }

    var zapErrorMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        zapError?.collect { error -> zapErrorMessage = error }
    }
    if (zapErrorMessage != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { zapErrorMessage = null },
            title = { Text(stringResource(R.string.zap_failed)) },
            text = { Text(zapErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { zapErrorMessage = null }) { Text(stringResource(R.string.btn_ok)) }
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
                    Text(
                        stringResource(R.string.nav_notifications),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    val allEnabled = enabledTypes.size == NotificationFilter.entries.size && chatRoomsEnabled
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.cd_filter_notifications),
                            tint = if (allEnabled) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onToggleNotifSound) {
                        Icon(
                            if (notifSoundEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = if (notifSoundEnabled) stringResource(R.string.cd_mute_notifications)
                                                 else stringResource(R.string.cd_unmute_notifications)
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
                    stringResource(R.string.error_no_notifications),
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
                        enabledTypes = enabledTypes,
                        onFilterSelect = { filter ->
                            if (enabledTypes == setOf(filter)) {
                                viewModel.enableAll()
                            } else {
                                viewModel.isolateType(filter)
                            }
                        }
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
                        resolvedEmojis = resolvedEmojis,
                        onClick = {
                            if (item.type == NotificationType.DM_REACTION && item.dmPeerPubkey != null) {
                                onDmConversationClick(item.dmPeerPubkey)
                            } else {
                                expandedId = if (isExpanded) null else item.id
                            }
                        },
                        onProfileClick = onProfileClick,
                        onSendReply = { replyToEvent, content ->
                            val key = item.replyEventId ?: ""
                            val existing = inlineReplies[key] ?: emptyList()
                            inlineReplies = inlineReplies + (key to (existing + content))
                            onSendReply(replyToEvent, content)
                        },
                        onSendDm = { peerPubkey, content ->
                            onSendDm(peerPubkey, content)
                            val existing = inlineDmReplies[peerPubkey] ?: emptyList()
                            inlineDmReplies = inlineDmReplies + (peerPubkey to (existing + content))
                        },
                        onDmReact = onDmReact,
                        onDmZap = onDmZap,
                        dmZapSats = dmZapSats,
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
                        },
                        mentionQuery = mentionQuery,
                        mentionCandidates = mentionCandidates,
                        onMentionDetect = { tfv -> viewModel.detectMentionQuery(tfv) },
                        onMentionSelect = { candidate, text, cursor -> viewModel.selectMention(candidate, text, cursor) },
                        onMentionClear = { viewModel.clearMentionState() },
                        resolveDisplayName = resolveDisplayName,
                        onGroupNotificationClick = onGroupNotificationClick,
                        resolveGroupMessage = resolveGroupMessage
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
        } // PullToRefreshBox
    }

    if (showFilterSheet) {
        NotificationFilterSheet(
            enabledTypes = enabledTypes,
            chatRoomsEnabled = chatRoomsEnabled,
            onToggleType = { viewModel.toggleType(it) },
            onToggleChatRooms = { viewModel.toggleChatRooms() },
            onEnableAll = { viewModel.enableAll() },
            onDisableAll = { viewModel.disableAll() },
            onDismiss = { showFilterSheet = false }
        )
    }
}

// ── Filter Bottom Sheet ─────────────────────────────────────────────────

private fun NotificationFilter.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    NotificationFilter.REPLIES -> Icons.Outlined.ChatBubbleOutline
    NotificationFilter.REACTIONS -> Icons.Outlined.FavoriteBorder
    NotificationFilter.ZAPS -> Icons.Outlined.CurrencyBitcoin
    NotificationFilter.REPOSTS -> Icons.Outlined.Repeat
    NotificationFilter.MENTIONS -> Icons.Outlined.AlternateEmail
    NotificationFilter.VOTES -> Icons.Outlined.BarChart
    NotificationFilter.DMS -> Icons.Outlined.MailOutline
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationFilterSheet(
    enabledTypes: Set<NotificationFilter>,
    chatRoomsEnabled: Boolean,
    onToggleType: (NotificationFilter) -> Unit,
    onToggleChatRooms: () -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                stringResource(R.string.notif_filter_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            NotificationFilter.entries.forEach { filter ->
                val enabled = filter in enabledTypes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleType(filter) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        filter.icon(),
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(filter.labelResId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { onToggleType(filter) }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Chat rooms toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleChatRooms() }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = if (chatRoomsEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.notif_chat_rooms),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (chatRoomsEnabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = chatRoomsEnabled,
                    onCheckedChange = { onToggleChatRooms() }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Enable All / Disable All
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = onEnableAll) {
                    Text(stringResource(R.string.notif_enable_all))
                }
                TextButton(onClick = onDisableAll) {
                    Text(stringResource(R.string.notif_disable_all))
                }
            }
        }
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
    resolvedEmojis: Map<String, String> = emptyMap(),
    onClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    onSendReply: (NostrEvent, String) -> Unit = { _, _ -> },
    onSendDm: (String, String) -> Unit = { _, _ -> },
    onDmReact: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onDmZap: (String, String, String) -> Unit = { _, _, _ -> },
    dmZapSats: (String) -> Long = { 0L },
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onReplyFocused: () -> Unit = {},
    mentionQuery: String? = null,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    onMentionDetect: ((TextFieldValue) -> Unit)? = null,
    onMentionSelect: ((MentionCandidate, String, Int) -> TextFieldValue)? = null,
    onMentionClear: (() -> Unit)? = null,
    resolveDisplayName: ((String) -> String?)? = null,
    onGroupNotificationClick: ((groupChatId: String, messageId: String) -> Unit)? = null,
    resolveGroupMessage: ((groupChatId: String, messageId: String) -> Triple<String?, String?, Map<String, String>>)? = null
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
                // Show voted option labels for NIP-88 polls
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
                // Show voted option label for zap poll votes (kind 6969)
                if (item.type == NotificationType.ZAP && item.zapPollOptionIndex != null) {
                    val optionLabel = remember(item.referencedEventId, item.zapPollOptionIndex) {
                        val pollEvent = eventRepo?.getEvent(item.referencedEventId)
                        if (pollEvent != null) {
                            Nip69.parseZapPollOptions(pollEvent)
                                .firstOrNull { it.index == item.zapPollOptionIndex }?.label
                        } else null
                    }
                    if (optionLabel != null) {
                        Text(
                            text = "voted: $optionLabel",
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
                    eventRepo = eventRepo,
                    inlineDmReplies = inlineDmReplies,
                    userPubkey = userPubkey,
                    onSendDm = onSendDm,
                    onUploadMedia = onUploadMedia,
                    onProfileClick = onProfileClick,
                    onFocused = onReplyFocused,
                    resolvedEmojis = resolvedEmojis,
                    onDmReact = onDmReact,
                    onDmZap = onDmZap,
                    isDmZapInProgress = postCardParams?.isZapInProgress?.invoke(item.actorPubkey) ?: false,
                    isDmZapAnimating = postCardParams?.isZapAnimating?.invoke(item.actorPubkey) ?: false,
                    dmZapSats = dmZapSats(item.actorPubkey),
                    mentionQuery = mentionQuery,
                    mentionCandidates = mentionCandidates,
                    onMentionDetect = onMentionDetect,
                    onMentionSelect = onMentionSelect,
                    onMentionClear = onMentionClear,
                    resolveDisplayName = resolveDisplayName
                )
            } else if (item.groupChatId != null && onGroupNotificationClick != null) {
                // Group chat notifications (reactions, zaps, replies) — shown with group badge
                val resolveId = if (item.type == NotificationType.REPLY) item.replyEventId ?: item.referencedEventId
                    else item.referencedEventId
                val (msgContent, grpName, emojiTags) = remember(item.groupChatId, resolveId) {
                    resolveGroupMessage?.invoke(item.groupChatId, resolveId)
                        ?: Triple(null, null, emptyMap())
                }
                // For replies, also resolve the quoted original message (our message being replied to)
                val quotedContent = if (item.type == NotificationType.REPLY && item.replyEventId != null) {
                    remember(item.groupChatId, item.referencedEventId) {
                        resolveGroupMessage?.invoke(item.groupChatId, item.referencedEventId)?.first
                    }
                } else null
                val quotedAuthorName = if (quotedContent != null) {
                    remember(userPubkey) {
                        userPubkey?.let { eventRepo?.getProfileData(it)?.displayString }
                    }
                } else null
                GroupChatExpansion(
                    item = item,
                    eventRepo = eventRepo,
                    groupName = grpName,
                    messageContent = msgContent,
                    quotedContent = quotedContent,
                    quotedAuthorName = quotedAuthorName,
                    emojiMap = resolvedEmojis + emojiTags,
                    onProfileClick = onProfileClick,
                    onClick = {
                        val scrollTarget = item.replyEventId ?: item.referencedEventId
                        onGroupNotificationClick(item.groupChatId, scrollTarget)
                    }
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
                    onReplyFocused = onReplyFocused,
                    resolvedEmojis = resolvedEmojis,
                    mentionQuery = mentionQuery,
                    mentionCandidates = mentionCandidates,
                    onMentionDetect = onMentionDetect,
                    onMentionSelect = onMentionSelect,
                    onMentionClear = onMentionClear,
                    resolveDisplayName = resolveDisplayName
                )
            } else if (item.type == NotificationType.DM_ZAP || item.type == NotificationType.PROFILE_ZAP) {
                ZapMessageExpansion(item = item)
            } else if (postCardParams != null && item.type != NotificationType.DM_REACTION) {
                NoteExpansion(
                    item = item,
                    params = postCardParams
                )
            }
        }
    }
}

// ── Zap Message Expansion (DM_ZAP, PROFILE_ZAP) ─────────────────────────

@Composable
private fun ZapMessageExpansion(item: FlatNotificationItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
    ) {
        val msg = item.zapMessage.trim()
        Text(
            text = if (msg.isNotEmpty()) "\u201C$msg\u201D" else "This zap doesn\u2019t contain a message.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (msg.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = if (msg.isEmpty()) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
        )
    }
}

// ── Group Chat Expansion ────────────────────────────────────────────────

@Composable
private fun GroupChatExpansion(
    item: FlatNotificationItem,
    eventRepo: EventRepository?,
    groupName: String?,
    messageContent: String?,
    quotedContent: String? = null,
    quotedAuthorName: String? = null,
    emojiMap: Map<String, String> = emptyMap(),
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
            .clickable(onClick = onClick)
    ) {
        // Group badge
        if (groupName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Message content
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // For replies: show the quoted original message (our message) first
                if (quotedContent != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = quotedAuthorName ?: "You",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = quotedContent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (messageContent != null) {
                    com.wisp.app.ui.component.RichContent(
                        content = messageContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        emojiMap = emojiMap,
                        eventRepo = eventRepo,
                        onProfileClick = onProfileClick,
                        onNoteClick = {}
                    )
                } else {
                    Text(
                        text = "Message not available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
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
    eventRepo: EventRepository? = null,
    inlineDmReplies: List<String> = emptyList(),
    userPubkey: String? = null,
    onSendDm: (String, String) -> Unit = { _, _ -> },
    onUploadMedia: (List<Uri>, onUrl: (String) -> Unit) -> Unit = { _, _ -> },
    onProfileClick: (String) -> Unit = {},
    onFocused: () -> Unit = {},
    resolvedEmojis: Map<String, String> = emptyMap(),
    onDmReact: (peerPubkey: String, rumorId: String, senderPubkey: String, emoji: String) -> Unit = { _, _, _, _ -> },
    onDmZap: (peerPubkey: String, rumorId: String, senderPubkey: String) -> Unit = { _, _, _ -> },
    isDmZapInProgress: Boolean = false,
    isDmZapAnimating: Boolean = false,
    dmZapSats: Long = 0L,
    mentionQuery: String? = null,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    onMentionDetect: ((TextFieldValue) -> Unit)? = null,
    onMentionSelect: ((MentionCandidate, String, Int) -> TextFieldValue)? = null,
    onMentionClear: (() -> Unit)? = null,
    resolveDisplayName: ((String) -> String?)? = null
) {
    val peerPubkey = item.dmPeerPubkey ?: return
    val rumorId = item.dmRumorId ?: ""
    var showEmojiPicker by remember { mutableStateOf(false) }
    var sentReactionEmoji by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // Their message bubble — uses RichContent to render image/video URLs
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 68.dp, end = 16.dp)
        ) {
            com.wisp.app.ui.component.RichContent(
                content = item.dmContent ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                eventRepo = eventRepo,
                onProfileClick = onProfileClick,
                onNoteClick = {},
                modifier = Modifier.padding(12.dp)
            )
        }

        // DM action bar — react and zap
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp, top = 2.dp)
        ) {
            IconButton(
                onClick = { showEmojiPicker = true },
                modifier = Modifier.size(36.dp)
            ) {
                if (sentReactionEmoji != null) {
                    Text(text = sentReactionEmoji!!, fontSize = 16.sp)
                } else {
                    Icon(
                        Icons.Outlined.AddReaction,
                        contentDescription = "React",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { if (!isDmZapInProgress) onDmZap(peerPubkey, rumorId, item.actorPubkey) },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isDmZapInProgress) {
                        LightningAnimation(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.wisp.app.R.drawable.ic_bolt),
                            contentDescription = "Zap",
                            tint = if (dmZapSats > 0) WispThemeColors.zapColor
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .wrapContentSize(unbounded = true, align = Alignment.Center)
                ) {
                    com.wisp.app.ui.component.ZapBurstEffect(
                        isActive = isDmZapAnimating,
                        modifier = Modifier.size(100.dp)
                    )
                }
            }
            if (dmZapSats > 0 && !isDmZapInProgress) {
                Text(
                    text = formatSatsCompact(dmZapSats),
                    style = MaterialTheme.typography.labelSmall,
                    color = WispThemeColors.zapColor
                )
            }
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
                resolvedEmojis = resolvedEmojis,
                modifier = Modifier.padding(start = 48.dp, top = 4.dp, end = 16.dp)
            )
        }

        // Inline DM composer — with media upload and GIF keyboard support
        InlineReplyComposer(
            onSend = { content -> onSendDm(peerPubkey, content) },
            onUploadMedia = onUploadMedia,
            onFocused = onFocused,
            placeholder = stringResource(R.string.placeholder_message),
            resolvedEmojis = resolvedEmojis,
            mentionQuery = mentionQuery,
            mentionCandidates = mentionCandidates,
            onMentionDetect = onMentionDetect,
            onMentionSelect = onMentionSelect,
            onMentionClear = onMentionClear,
            resolveDisplayName = resolveDisplayName,
            modifier = Modifier.padding(start = 48.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
        )

        // Emoji reaction popup
        if (showEmojiPicker) {
            EmojiReactionPopup(
                onSelect = { emoji ->
                    onDmReact(peerPubkey, rumorId, item.actorPubkey, emoji)
                    sentReactionEmoji = emoji
                    showEmojiPicker = false
                },
                onDismiss = { showEmojiPicker = false }
            )
        }
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
    onReplyFocused: () -> Unit = {},
    resolvedEmojis: Map<String, String> = emptyMap(),
    mentionQuery: String? = null,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    onMentionDetect: ((TextFieldValue) -> Unit)? = null,
    onMentionSelect: ((MentionCandidate, String, Int) -> TextFieldValue)? = null,
    onMentionClear: (() -> Unit)? = null,
    resolveDisplayName: ((String) -> String?)? = null
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

        // User's inline replies — rendered instantly from content, no event lookup needed
        val userProfile = remember(profileVersion, userPubkey) {
            userPubkey?.let { resolveProfile(it) }
        }
        inlineReplies.forEach { content ->
            SentNoteCard(
                content = content,
                profile = userProfile,
                eventRepo = postCardParams?.eventRepo,
                onProfileClick = onProfileClick,
                onNoteClick = postCardParams?.onNoteClick ?: {},
                resolvedEmojis = resolvedEmojis
            )
        }

        // Inline reply composer
        if (replyEvent != null) {
            InlineReplyComposer(
                onSend = { content -> onSendReply(replyEvent, content) },
                onUploadMedia = onUploadMedia,
                onFocused = onReplyFocused,
                placeholder = stringResource(R.string.reply_placeholder),
                resolvedEmojis = resolvedEmojis,
                mentionQuery = mentionQuery,
                mentionCandidates = mentionCandidates,
                onMentionDetect = onMentionDetect,
                onMentionSelect = onMentionSelect,
                onMentionClear = onMentionClear,
                resolveDisplayName = resolveDisplayName,
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
    val zapPollSatsCounts = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 6969) eventRepo.getZapPollSatsCounts(event.id) else emptyMap()
    }
    val zapPollTotalSats = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 6969) eventRepo.getZapPollTotalSats(event.id) else 0L
    }
    val userZapPollVote = remember(params.pollVoteVersion, event.id) {
        if (event.kind == 6969) eventRepo.getUserZapPollVote(event.id) else null
    }

    if (isGalleryEvent(event)) {
        GalleryCard(
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
            isFollowingAuthor = followingAuthor,
            isOwnEvent = event.pubkey == params.userPubkey,
            nip05Repo = params.nip05Repo,
            onAddToList = { params.onAddToList(event.id) },
            isInList = params.isInList(event.id),
            onQuotedNoteClick = params.onNoteClick,
            noteActions = run {
                val p = params
                if (p.onPayInvoice != null || p.onGroupRoom != null || p.fetchGroupPreview != null || p.onAddEmojiSet != null) {
                    com.wisp.app.ui.component.NoteActions(
                        onPayInvoice = p.onPayInvoice,
                        onGroupRoom = p.onGroupRoom,
                        fetchGroupPreview = p.fetchGroupPreview,
                        onAddEmojiSet = p.onAddEmojiSet,
                        onRemoveEmojiSet = p.onRemoveEmojiSet,
                        isEmojiSetAdded = p.isEmojiSetAdded,
                        onPollVote = p.onPollVote,
                        nip05Repo = p.nip05Repo
                    )
                } else null
            },
            showDivider = false
        )
    } else {
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
                val rootId = when (event.kind) {
                    1 -> Nip10.getRootId(event) ?: Nip10.getReplyTarget(event) ?: event.id
                    7, 6 -> {
                        val refId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (refId != null) {
                            val ref = params.eventRepo?.getEvent(refId)
                            if (ref != null) Nip10.getRootId(ref) ?: refId else refId
                        } else event.id
                    }
                    9735 -> {
                        val refId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (refId != null) {
                            val ref = params.eventRepo?.getEvent(refId)
                            if (ref != null) Nip10.getRootId(ref) ?: refId else refId
                        } else event.id
                    }
                    else -> Nip10.getRootId(event) ?: event.id
                }
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
            zapPollSatsCounts = zapPollSatsCounts,
            zapPollTotalSats = zapPollTotalSats,
            userZapPollVote = userZapPollVote,
            onZapPollVote = { idx -> params.onZapPollVote(event.id, idx) },
            noteActions = run {
                val p = params
                if (p.onPayInvoice != null || p.onGroupRoom != null || p.fetchGroupPreview != null || p.onAddEmojiSet != null) {
                    com.wisp.app.ui.component.NoteActions(
                        onPayInvoice = p.onPayInvoice,
                        onGroupRoom = p.onGroupRoom,
                        fetchGroupPreview = p.fetchGroupPreview,
                        onAddEmojiSet = p.onAddEmojiSet,
                        onRemoveEmojiSet = p.onRemoveEmojiSet,
                        isEmojiSetAdded = p.isEmojiSetAdded,
                        onPollVote = p.onPollVote,
                        nip05Repo = p.nip05Repo
                    )
                } else null
            },
            showDivider = false
        )
    }
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
    val onPollVote: (String, List<String>) -> Unit = { _, _ -> },
    val onZapPollVote: (String, Int) -> Unit = { _, _ -> },
    val onPayInvoice: (suspend (String) -> Boolean)? = null,
    val onGroupRoom: ((String, String) -> Unit)? = null,
    val fetchGroupPreview: (suspend (String, String) -> com.wisp.app.repo.GroupPreview?)? = null,
    val onAddEmojiSet: ((String, String) -> Unit)? = null,
    val onRemoveEmojiSet: ((String, String) -> Unit)? = null,
    val isEmojiSetAdded: ((String, String) -> Boolean)? = null
)

// ── Inline Sent Reply ──────────────────────────────────────────────────

@Composable
private fun InlineSentReply(
    content: String,
    profile: ProfileData?,
    eventRepo: EventRepository? = null,
    onProfileClick: (String) -> Unit,
    onNoteClick: (String) -> Unit,
    resolvedEmojis: Map<String, String> = emptyMap(),
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
            emojiMap = resolvedEmojis,
            eventRepo = eventRepo,
            onProfileClick = onProfileClick,
            onNoteClick = onNoteClick,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Sent Note Card ─────────────────────────────────────────────────────

@Composable
private fun SentNoteCard(
    content: String,
    profile: ProfileData?,
    eventRepo: EventRepository? = null,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (String) -> Unit = {},
    resolvedEmojis: Map<String, String> = emptyMap()
) {
    val displayName = profile?.displayString ?: "You"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(url = profile?.picture)
            Spacer(Modifier.width(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        com.wisp.app.ui.component.RichContent(
            content = content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            emojiMap = resolvedEmojis,
            eventRepo = eventRepo,
            onProfileClick = onProfileClick,
            onNoteClick = onNoteClick
        )
    }
}

// ── Inline Reply Composer ──────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InlineReplyComposer(
    onSend: (String) -> Unit,
    onUploadMedia: ((List<Uri>, onUrl: (String) -> Unit) -> Unit)? = null,
    onFocused: () -> Unit = {},
    placeholder: String = "Reply...",
    resolvedEmojis: Map<String, String> = emptyMap(),
    mentionQuery: String? = null,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    onMentionDetect: ((TextFieldValue) -> Unit)? = null,
    onMentionSelect: ((MentionCandidate, String, Int) -> TextFieldValue)? = null,
    onMentionClear: (() -> Unit)? = null,
    resolveDisplayName: ((String) -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val textFieldState = remember { TextFieldState() }

    // TextFieldValue mirror for cursor-aware emoji/mention autocomplete
    var replyTfv by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(textFieldState) {
        snapshotFlow {
            textFieldState.text.toString() to textFieldState.selection
        }.collect { (text, selection) ->
            val tfv = TextFieldValue(text, selection)
            replyTfv = tfv
            onMentionDetect?.invoke(tfv)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty() && onUploadMedia != null) {
            onUploadMedia(uris) { url ->
                textFieldState.edit {
                    val current = toString()
                    val newText = if (current.isBlank()) url else "$current\n$url"
                    replace(0, length, newText)
                }
            }
        }
    }

    Column(modifier = modifier) {
        // Mention autocomplete dropdown
        if (onMentionSelect != null) {
            AnimatedVisibility(
                visible = mentionQuery != null && mentionCandidates.isNotEmpty(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(bottom = 4.dp)
                ) {
                    LazyColumn {
                        items(mentionCandidates, key = { it.profile.pubkey }) { candidate ->
                            MentionCandidateRow(
                                candidate = candidate,
                                onClick = {
                                    val result = onMentionSelect(
                                        candidate,
                                        textFieldState.text.toString(),
                                        textFieldState.selection.start
                                    )
                                    textFieldState.edit {
                                        replace(0, length, result.text)
                                        selection = result.selection
                                    }
                                    replyTfv = result
                                }
                            )
                        }
                    }
                }
            }
        }

        // Emoji shortcode autocomplete
        val replyEmojiState = remember(replyTfv) { detectEmojiAutocomplete(replyTfv) }
        if (replyEmojiState != null && mentionQuery == null) {
            EmojiShortcodePopup(
                query = replyEmojiState.query,
                resolvedEmojis = resolvedEmojis,
                onSelect = { shortcode ->
                    val newTfv = insertEmojiShortcode(replyTfv, replyEmojiState.triggerIndex, shortcode)
                    textFieldState.edit {
                        replace(0, length, newTfv.text)
                        selection = newTfv.selection
                    }
                    replyTfv = newTfv
                }
            )
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onUploadMedia != null) {
            IconButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = "Add media",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        var fieldModifier = Modifier
            .weight(1f)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
        if (onUploadMedia != null) {
            fieldModifier = fieldModifier.contentReceiver(object : ReceiveContentListener {
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
            })
        }
        val replyOutputTransformation = remember(resolvedEmojis, resolveDisplayName) {
            com.wisp.app.ui.component.MentionOutputTransformation(
                resolveDisplayName = resolveDisplayName ?: { null },
                resolvedEmojis = resolvedEmojis
            )
        }
        BasicTextField(
            state = textFieldState,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = fieldModifier,
            outputTransformation = replyOutputTransformation,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences
            ),
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
                        onMentionClear?.invoke()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send_reply),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
    }
}

@Composable
private fun MentionCandidateRow(
    candidate: MentionCandidate,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(
            url = candidate.profile.picture,
            size = 32,
            showFollowBadge = candidate.isContact
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.profile.displayString,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1
            )
            val subtitle = candidate.profile.name?.let { "@$it" }
                ?: candidate.profile.nip05
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun NotificationTypeIcon(item: FlatNotificationItem, showSats: Boolean = false) {
    val iconSize = 28.dp
    if (item.type == NotificationType.ZAP || item.type == NotificationType.DM_ZAP || item.type == NotificationType.PROFILE_ZAP) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.wisp.app.R.drawable.ic_bolt),
                contentDescription = stringResource(R.string.cd_send_zap),
                modifier = Modifier.size(iconSize - 4.dp),
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
                            contentDescription = stringResource(R.string.cd_reaction),
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
                contentDescription = stringResource(R.string.cd_repost),
                modifier = Modifier.size(iconSize),
                tint = WispThemeColors.repostColor
            )
        }
        NotificationType.REPLY -> {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = stringResource(R.string.cd_reply),
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.QUOTE -> {
            Icon(
                Icons.Outlined.FormatQuote,
                contentDescription = "Quoted",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.MENTION -> {
            Icon(
                Icons.Outlined.AlternateEmail,
                contentDescription = stringResource(R.string.cd_mention),
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
        NotificationType.DM_REACTION -> {
            // Show the emoji directly if short enough, otherwise use a heart icon
            val emoji = item.emoji
            if (!emoji.isNullOrBlank() && emoji.length <= 4) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(iconSize),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Icon(
                    Icons.Outlined.Favorite,
                    contentDescription = "DM Reaction",
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        NotificationType.VOTE -> {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = stringResource(R.string.cd_vote),
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationType.DM_ZAP -> {} // handled by early-return above
        NotificationType.PROFILE_ZAP -> {} // handled by early-return above
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
    NotificationType.DM_REACTION -> "reacted to your message"
    NotificationType.DM_ZAP -> "zapped your message"
    NotificationType.PROFILE_ZAP -> "zapped your profile"
}

// ── Daily Summary Bar ──────────────────────────────────────────────────

@Composable
private fun DailySummaryBar(
    summary: NotificationSummary,
    enabledTypes: Set<NotificationFilter> = emptySet(),
    onFilterSelect: (NotificationFilter) -> Unit = {}
) {
    val isFiltered = enabledTypes.size == 1
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
            SummaryStat(Icons.Outlined.ChatBubbleOutline, summary.replyCount.toString(),
                active = isFiltered && NotificationFilter.REPLIES in enabledTypes,
                onClick = { onFilterSelect(NotificationFilter.REPLIES) })
            SummaryStat(Icons.Outlined.FavoriteBorder, summary.reactionCount.toString(),
                active = isFiltered && NotificationFilter.REACTIONS in enabledTypes,
                onClick = { onFilterSelect(NotificationFilter.REACTIONS) })
            run {
                val zapContext = LocalContext.current
                val useZapBolt = remember {
                    zapContext.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE)
                        .getBoolean("zap_bolt_icon", false)
                }
                val zapActive = isFiltered && NotificationFilter.ZAPS in enabledTypes
                if (useZapBolt) {
                    SummaryStatPainter(painterResource(R.drawable.ic_bolt), formatSatsCompact(summary.zapSats),
                        active = zapActive, onClick = { onFilterSelect(NotificationFilter.ZAPS) })
                } else {
                    SummaryStat(Icons.Outlined.CurrencyBitcoin, formatSatsCompact(summary.zapSats),
                        active = zapActive, onClick = { onFilterSelect(NotificationFilter.ZAPS) })
                }
            }
            SummaryStat(Icons.Outlined.Repeat, summary.repostCount.toString(),
                active = isFiltered && NotificationFilter.REPOSTS in enabledTypes,
                onClick = { onFilterSelect(NotificationFilter.REPOSTS) })
            SummaryStat(Icons.Outlined.AlternateEmail, (summary.mentionCount + summary.quoteCount).toString(),
                active = isFiltered && NotificationFilter.MENTIONS in enabledTypes,
                onClick = { onFilterSelect(NotificationFilter.MENTIONS) })
            SummaryStat(Icons.Outlined.MailOutline, summary.dmCount.toString(),
                active = isFiltered && NotificationFilter.DMS in enabledTypes,
                onClick = { onFilterSelect(NotificationFilter.DMS) })
        }
    }
}

@Composable
private fun SummaryStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    active: Boolean = false,
    onClick: () -> Unit = {}
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(
                if (active) Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun SummaryStatPainter(
    icon: androidx.compose.ui.graphics.painter.Painter,
    value: String,
    active: Boolean = false,
    onClick: () -> Unit = {}
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(
                if (active) Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = tint)
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
