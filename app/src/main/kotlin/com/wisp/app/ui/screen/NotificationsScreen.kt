package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.ZapEntry
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.StackedAvatarRow
import com.wisp.app.viewmodel.NotificationsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    scrollToTopTrigger: Int = 0,
    userPubkey: String? = null,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (NostrEvent) -> Unit = {},
    onQuote: (NostrEvent) -> Unit = {},
    onZap: (NostrEvent) -> Unit = {},
    onFollowToggle: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    onBookmark: (String) -> Unit = {},
    onAddToList: (String) -> Unit = {},
    nip05Repo: Nip05Repository? = null,
    isZapAnimating: (String) -> Boolean = { false },
    isZapInProgress: (String) -> Boolean = { false },
    isBookmarked: (String) -> Boolean = { false }
) {
    val notifications by viewModel.notifications.collectAsState()
    val eventRepo = viewModel.eventRepository
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    // Version flows for cache invalidation on reply PostCards
    val reactionVersion = eventRepo?.reactionVersion?.collectAsState()?.value ?: 0
    val zapVersion = eventRepo?.zapVersion?.collectAsState()?.value ?: 0
    val replyCountVersion = eventRepo?.replyCountVersion?.collectAsState()?.value ?: 0
    val repostVersion = eventRepo?.repostVersion?.collectAsState()?.value ?: 0
    val profileVersion = eventRepo?.profileVersion?.collectAsState()?.value ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (notifications.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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
            val recentCutoff = System.currentTimeMillis() / 1000 - 600
            val (recentNotifs, olderNotifs) = remember(notifications, recentCutoff / 60) {
                notifications.partition { it.groupId.endsWith(":recent") || it.latestTimestamp >= recentCutoff }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (recentNotifs.isNotEmpty()) {
                    item(key = "header_recent") {
                        SectionHeader("Recent")
                    }
                    items(items = recentNotifs, key = { it.groupId }) { group ->
                        NotificationItem(
                            group = group,
                            viewModel = viewModel,
                            eventRepo = eventRepo,
                            userPubkey = userPubkey,
                            profileVersion = profileVersion,
                            reactionVersion = reactionVersion,
                            replyCountVersion = replyCountVersion,
                            zapVersion = zapVersion,
                            repostVersion = repostVersion,
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick,
                            onReply = onReply,
                            onReact = onReact,
                            onRepost = onRepost,
                            onQuote = onQuote,
                            onZap = onZap,
                            onFollowToggle = onFollowToggle,
                            onBlockUser = onBlockUser,
                            onBookmark = onBookmark,
                            onAddToList = onAddToList,
                            nip05Repo = nip05Repo,
                            isZapAnimating = isZapAnimating,
                            isZapInProgress = isZapInProgress,
                            isBookmarked = isBookmarked
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    }
                }
                if (olderNotifs.isNotEmpty()) {
                    item(key = "header_earlier") {
                        SectionHeader("Earlier")
                    }
                    items(items = olderNotifs, key = { it.groupId }) { group ->
                        NotificationItem(
                            group = group,
                            viewModel = viewModel,
                            eventRepo = eventRepo,
                            userPubkey = userPubkey,
                            profileVersion = profileVersion,
                            reactionVersion = reactionVersion,
                            replyCountVersion = replyCountVersion,
                            zapVersion = zapVersion,
                            repostVersion = repostVersion,
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick,
                            onReply = onReply,
                            onReact = onReact,
                            onRepost = onRepost,
                            onQuote = onQuote,
                            onZap = onZap,
                            onFollowToggle = onFollowToggle,
                            onBlockUser = onBlockUser,
                            onBookmark = onBookmark,
                            onAddToList = onAddToList,
                            nip05Repo = nip05Repo,
                            isZapAnimating = isZapAnimating,
                            isZapInProgress = isZapInProgress,
                            isBookmarked = isBookmarked
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ── Section Header ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

// ── Notification Item Router ────────────────────────────────────────────

@Composable
private fun NotificationItem(
    group: NotificationGroup,
    viewModel: NotificationsViewModel,
    eventRepo: EventRepository?,
    userPubkey: String?,
    profileVersion: Int,
    reactionVersion: Int,
    replyCountVersion: Int,
    zapVersion: Int,
    repostVersion: Int,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onReply: (NostrEvent) -> Unit,
    onReact: (NostrEvent, String) -> Unit,
    onRepost: (NostrEvent) -> Unit,
    onQuote: (NostrEvent) -> Unit,
    onZap: (NostrEvent) -> Unit,
    onFollowToggle: (String) -> Unit,
    onBlockUser: (String) -> Unit,
    onBookmark: (String) -> Unit,
    onAddToList: (String) -> Unit = {},
    nip05Repo: Nip05Repository?,
    isZapAnimating: (String) -> Boolean,
    isZapInProgress: (String) -> Boolean,
    isBookmarked: (String) -> Boolean
) {
    // Shared PostCard params for rendering referenced notes with full action bar
    val postCardParams = NotifPostCardParams(
        eventRepo = eventRepo,
        userPubkey = userPubkey,
        profileVersion = profileVersion,
        reactionVersion = reactionVersion,
        replyCountVersion = replyCountVersion,
        zapVersion = zapVersion,
        repostVersion = repostVersion,
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
        onBookmark = onBookmark,
        onAddToList = onAddToList,
        nip05Repo = nip05Repo,
        isZapAnimating = isZapAnimating,
        isZapInProgress = isZapInProgress,
        isBookmarked = isBookmarked
    )

    when (group) {
        is NotificationGroup.ReactionGroup -> ReactionGroupRow(
            group = group,
            resolveProfile = { viewModel.getProfileData(it) },
            isFollowing = { viewModel.isFollowing(it) },
            onProfileClick = onProfileClick,
            postCardParams = postCardParams
        )
        is NotificationGroup.ZapGroup -> ZapGroupRow(
            group = group,
            resolveProfile = { viewModel.getProfileData(it) },
            isFollowing = { viewModel.isFollowing(it) },
            onProfileClick = onProfileClick,
            postCardParams = postCardParams
        )
        is NotificationGroup.ReplyNotification -> ReplyPostCard(
            item = group,
            postCardParams = postCardParams
        )
        is NotificationGroup.QuoteNotification -> QuoteNotificationRow(
            item = group,
            resolveProfile = { viewModel.getProfileData(it) },
            isFollowing = viewModel.isFollowing(group.senderPubkey),
            onProfileClick = onProfileClick,
            postCardParams = postCardParams
        )
        is NotificationGroup.MentionNotification -> MentionNotificationRow(
            item = group,
            resolveProfile = { viewModel.getProfileData(it) },
            isFollowing = viewModel.isFollowing(group.senderPubkey),
            onProfileClick = onProfileClick,
            postCardParams = postCardParams
        )
    }
}

// ── Reaction Group ──────────────────────────────────────────────────────
// Each emoji on its own row: <emoji> <stacked avatars of that emoji's reactors>
// Then the referenced note rendered as a full PostCard with action bar.

@Composable
private fun ReactionGroupRow(
    group: NotificationGroup.ReactionGroup,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: (String) -> Boolean,
    onProfileClick: (String) -> Unit,
    postCardParams: NotifPostCardParams
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Emoji summary header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Timestamp on top-right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatNotifTimestamp(group.latestTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Each emoji row: <emoji> <avatars> (newest reactor first)
            group.reactions.forEach { (emoji, pubkeys) ->
                val displayEmoji = if (emoji == "+") "\u2764\uFE0F" else emoji
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayEmoji,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    StackedAvatarRow(
                        pubkeys = pubkeys.reversed(),
                        resolveProfile = resolveProfile,
                        isFollowing = isFollowing,
                        onProfileClick = onProfileClick,
                        highlightFirst = pubkeys.size > 1
                    )
                }
            }
        }
        // Referenced note as full PostCard
        ReferencedNotePostCard(
            eventId = group.referencedEventId,
            params = postCardParams
        )
    }
}

// ── Zap Group ───────────────────────────────────────────────────────────
// Each zap on its own row (most recent first): <zap icon> <amount> <avatar> <message>
// Then the referenced note rendered as a full PostCard with action bar.

@Composable
private fun ZapGroupRow(
    group: NotificationGroup.ZapGroup,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: (String) -> Boolean,
    onProfileClick: (String) -> Unit,
    postCardParams: NotifPostCardParams
) {
    val sortedZaps = remember(group.zaps) { group.zaps.sortedByDescending { it.createdAt } }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Zap summary header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header with total + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\u26A1 ${formatSats(group.totalSats)} total",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatNotifTimestamp(group.latestTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            // Each zap row: <zap icon> <amount> <avatar> <name> <message>
            sortedZaps.forEachIndexed { index, zap ->
                ZapEntryRow(
                    zap = zap,
                    profile = resolveProfile(zap.pubkey),
                    showFollowBadge = isFollowing(zap.pubkey),
                    highlighted = index == 0 && sortedZaps.size > 1,
                    onProfileClick = onProfileClick
                )
            }
        }
        // Referenced note as full PostCard
        ReferencedNotePostCard(
            eventId = group.referencedEventId,
            params = postCardParams
        )
    }
}

@Composable
private fun ZapEntryRow(
    zap: ZapEntry,
    profile: ProfileData?,
    showFollowBadge: Boolean,
    highlighted: Boolean = false,
    onProfileClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = formatSats(zap.sats),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.width(8.dp))
        ProfilePicture(
            url = profile?.picture,
            size = 24,
            showFollowBadge = showFollowBadge,
            highlighted = highlighted,
            modifier = Modifier.clickable { onProfileClick(zap.pubkey) }
        )
        Spacer(Modifier.width(6.dp))
        if (zap.message.isNotBlank()) {
            Text(
                text = zap.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            val name = profile?.displayString
                ?: zap.pubkey.take(8) + "..."
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Reply ───────────────────────────────────────────────────────────────
// Full PostCard for reply notifications — same rendering as feed items.

@Composable
private fun ReplyPostCard(
    item: NotificationGroup.ReplyNotification,
    postCardParams: NotifPostCardParams
) {
    val eventRepo = postCardParams.eventRepo ?: return

    // "reply to #note1abc..." label
    if (item.referencedEventId != null) {
        val truncatedNoteId = remember(item.referencedEventId) {
            try {
                val noteId = Nip19.noteEncode(item.referencedEventId.hexToByteArray())
                "#" + noteId.take(10)
            } catch (_: Exception) {
                "#" + item.referencedEventId.take(8)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "reply to $truncatedNoteId",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = androidx.compose.ui.graphics.Color(0xFFFF9800),
                modifier = Modifier.clickable { postCardParams.onNoteClick(item.referencedEventId) }
            )
        }
    }

    ReferencedNotePostCard(
        eventId = item.replyEventId,
        params = postCardParams
    )
}

// ── Quote ───────────────────────────────────────────────────────────────

@Composable
private fun QuoteNotificationRow(
    item: NotificationGroup.QuoteNotification,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: Boolean,
    onProfileClick: (String) -> Unit,
    postCardParams: NotifPostCardParams
) {
    val profile = resolveProfile(item.senderPubkey)
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Quote header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                url = profile?.picture,
                size = 34,
                showFollowBadge = isFollowing,
                modifier = Modifier.clickable { onProfileClick(item.senderPubkey) }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "quoted your note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatNotifTimestamp(item.latestTimestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Quote event as full PostCard
        ReferencedNotePostCard(
            eventId = item.quoteEventId,
            params = postCardParams
        )
    }
}

// ── Mention ─────────────────────────────────────────────────────────────

@Composable
private fun MentionNotificationRow(
    item: NotificationGroup.MentionNotification,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: Boolean,
    onProfileClick: (String) -> Unit,
    postCardParams: NotifPostCardParams
) {
    val profile = resolveProfile(item.senderPubkey)
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Mention header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                url = profile?.picture,
                size = 34,
                showFollowBadge = isFollowing,
                modifier = Modifier.clickable { onProfileClick(item.senderPubkey) }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "mentioned you",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatNotifTimestamp(item.latestTimestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Mention event as full PostCard
        ReferencedNotePostCard(
            eventId = item.eventId,
            params = postCardParams
        )
    }
}

// ── Shared PostCard params ───────────────────────────────────────────────

private data class NotifPostCardParams(
    val eventRepo: EventRepository?,
    val userPubkey: String?,
    val profileVersion: Int,
    val reactionVersion: Int,
    val replyCountVersion: Int,
    val zapVersion: Int,
    val repostVersion: Int,
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
    val onBookmark: (String) -> Unit,
    val onAddToList: (String) -> Unit,
    val nip05Repo: Nip05Repository?,
    val isZapAnimating: (String) -> Boolean,
    val isZapInProgress: (String) -> Boolean,
    val isBookmarked: (String) -> Boolean
)

// ── Referenced Note PostCard ────────────────────────────────────────────
// Renders any event ID as a full PostCard with action bar (reactions, zaps, etc.)

@Composable
private fun ReferencedNotePostCard(
    eventId: String,
    params: NotifPostCardParams
) {
    val eventRepo = params.eventRepo ?: return

    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(eventId, version) { eventRepo.getEvent(eventId) }

    LaunchedEffect(eventId) {
        if (eventRepo.getEvent(eventId) == null) {
            eventRepo.requestQuotedEvent(eventId)
        }
    }

    if (event == null) return

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
    val hasUserReposted = remember(params.repostVersion, event.id) {
        eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(params.zapVersion, event.id) {
        eventRepo.hasUserZapped(event.id)
    }
    val followingAuthor = remember(event.pubkey) {
        params.isFollowing(event.pubkey)
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
        onNavigateToProfileFromDetails = params.onProfileClick,
        onFollowAuthor = { params.onFollowToggle(event.pubkey) },
        onBlockAuthor = { params.onBlockUser(event.pubkey) },
        isFollowingAuthor = followingAuthor,
        isOwnEvent = event.pubkey == params.userPubkey,
        nip05Repo = params.nip05Repo,
        onBookmark = { params.onBookmark(event.id) },
        isBookmarked = params.isBookmarked(event.id),
        onAddToList = { params.onAddToList(event.id) },
        onQuotedNoteClick = params.onNoteClick,
        showDivider = false
    )
}

private val notifTimeFormat = SimpleDateFormat("MMM d", Locale.US)

private fun formatNotifTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    return notifTimeFormat.format(Date(epoch * 1000))
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M sats"
    sats >= 1_000 -> "${sats / 1_000}K sats"
    else -> "$sats sats"
}
