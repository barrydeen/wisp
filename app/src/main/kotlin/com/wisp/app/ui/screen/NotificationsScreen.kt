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
import com.wisp.app.ui.component.QuotedNote
import com.wisp.app.ui.component.RichContent
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items = notifications, key = { it.groupId }) { group ->
                    when (group) {
                        is NotificationGroup.ReactionGroup -> ReactionGroupRow(
                            group = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            isFollowing = { viewModel.isFollowing(it) },
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.ZapGroup -> ZapGroupRow(
                            group = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            isFollowing = { viewModel.isFollowing(it) },
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.ReplyNotification -> ReplyPostCard(
                            item = group,
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
                            nip05Repo = nip05Repo,
                            isZapAnimating = isZapAnimating,
                            isZapInProgress = isZapInProgress,
                            isBookmarked = isBookmarked
                        )
                        is NotificationGroup.QuoteNotification -> QuoteNotificationRow(
                            item = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            isFollowing = viewModel.isFollowing(group.senderPubkey),
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.MentionNotification -> MentionNotificationRow(
                            item = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            isFollowing = viewModel.isFollowing(group.senderPubkey),
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ── Reaction Group ──────────────────────────────────────────────────────
// Each emoji on its own row: <emoji> <stacked avatars of that emoji's reactors>
// Then the referenced note rendered inline.

@Composable
private fun ReactionGroupRow(
    group: NotificationGroup.ReactionGroup,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: (String) -> Boolean,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNoteClick(group.referencedEventId) }
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
        // Each emoji row: <emoji> <avatars>
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
                    pubkeys = pubkeys,
                    resolveProfile = resolveProfile,
                    isFollowing = isFollowing,
                    onProfileClick = onProfileClick
                )
            }
        }
        // Inline referenced note
        if (eventRepo != null) {
            QuotedNote(
                eventId = group.referencedEventId,
                eventRepo = eventRepo,
                onNoteClick = onNoteClick
            )
        }
    }
}

// ── Zap Group ───────────────────────────────────────────────────────────
// Each zap on its own row (most recent first): <zap icon> <amount> <avatar> <message>
// Then the referenced note rendered inline.

@Composable
private fun ZapGroupRow(
    group: NotificationGroup.ZapGroup,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: (String) -> Boolean,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    val sortedZaps = remember(group.zaps) { group.zaps.sortedByDescending { it.createdAt } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNoteClick(group.referencedEventId) }
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
        sortedZaps.forEach { zap ->
            ZapEntryRow(
                zap = zap,
                profile = resolveProfile(zap.pubkey),
                showFollowBadge = isFollowing(zap.pubkey),
                onProfileClick = onProfileClick
            )
        }
        // Inline referenced note
        if (eventRepo != null) {
            QuotedNote(
                eventId = group.referencedEventId,
                eventRepo = eventRepo,
                onNoteClick = onNoteClick
            )
        }
    }
}

@Composable
private fun ZapEntryRow(
    zap: ZapEntry,
    profile: ProfileData?,
    showFollowBadge: Boolean,
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
    eventRepo: EventRepository?,
    userPubkey: String?,
    profileVersion: Int,
    reactionVersion: Int,
    replyCountVersion: Int,
    zapVersion: Int,
    repostVersion: Int,
    isFollowing: (String) -> Boolean,
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
    nip05Repo: Nip05Repository?,
    isZapAnimating: (String) -> Boolean,
    isZapInProgress: (String) -> Boolean,
    isBookmarked: (String) -> Boolean
) {
    if (eventRepo == null) return

    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(item.replyEventId, version) { eventRepo.getEvent(item.replyEventId) }

    // Request the reply event on-demand if not yet cached
    LaunchedEffect(item.replyEventId) {
        if (eventRepo.getEvent(item.replyEventId) == null) {
            eventRepo.requestQuotedEvent(item.replyEventId)
        }
    }

    if (event == null) return

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
    val hasUserReposted = remember(repostVersion, event.id) {
        eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(zapVersion, event.id) {
        eventRepo.hasUserZapped(event.id)
    }
    val followingAuthor = remember(event.pubkey) {
        isFollowing(event.pubkey)
    }

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
                modifier = Modifier.clickable { onNoteClick(item.referencedEventId) }
            )
        }
    }

    PostCard(
        event = event,
        profile = profile,
        onReply = { onReply(event) },
        onProfileClick = { onProfileClick(event.pubkey) },
        onNavigateToProfile = onProfileClick,
        onNoteClick = { onNoteClick(event.id) },
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
        isZapAnimating = isZapAnimating(event.id),
        isZapInProgress = isZapInProgress(event.id),
        eventRepo = eventRepo,
        reactionDetails = reactionDetails,
        zapDetails = zapDetails,
        onNavigateToProfileFromDetails = onProfileClick,
        onFollowAuthor = { onFollowToggle(event.pubkey) },
        onBlockAuthor = { onBlockUser(event.pubkey) },
        isFollowingAuthor = followingAuthor,
        isOwnEvent = event.pubkey == userPubkey,
        nip05Repo = nip05Repo,
        onBookmark = { onBookmark(event.id) },
        isBookmarked = isBookmarked(event.id),
        onQuotedNoteClick = onNoteClick,
        showDivider = false
    )
}

// ── Quote ───────────────────────────────────────────────────────────────

@Composable
private fun QuoteNotificationRow(
    item: NotificationGroup.QuoteNotification,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: Boolean,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    val profile = resolveProfile(item.senderPubkey)
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNoteClick(item.quoteEventId) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        if (eventRepo != null) {
            QuotedNote(
                eventId = item.quoteEventId,
                eventRepo = eventRepo,
                onNoteClick = onNoteClick
            )
        }
    }
}

// ── Mention ─────────────────────────────────────────────────────────────

@Composable
private fun MentionNotificationRow(
    item: NotificationGroup.MentionNotification,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
    isFollowing: Boolean,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    val profile = resolveProfile(item.senderPubkey)
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNoteClick(item.eventId) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        if (eventRepo != null) {
            QuotedNote(
                eventId = item.eventId,
                eventRepo = eventRepo,
                onNoteClick = onNoteClick
            )
        }
    }
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
