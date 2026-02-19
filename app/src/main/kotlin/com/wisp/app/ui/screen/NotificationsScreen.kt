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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.ZapEntry
import com.wisp.app.repo.EventRepository
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
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    val notifications by viewModel.notifications.collectAsState()
    val eventRepo = viewModel.eventRepository

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
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.ZapGroup -> ZapGroupRow(
                            group = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.ReplyNotification -> ReplyNotificationRow(
                            item = group,
                            eventRepo = eventRepo,
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.QuoteNotification -> QuoteNotificationRow(
                            item = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
                            onNoteClick = onNoteClick,
                            onProfileClick = onProfileClick
                        )
                        is NotificationGroup.MentionNotification -> MentionNotificationRow(
                            item = group,
                            eventRepo = eventRepo,
                            resolveProfile = { viewModel.getProfileData(it) },
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
// Render the reply event directly inline (like a feed note) — no QuotedNote container.

@Composable
private fun ReplyNotificationRow(
    item: NotificationGroup.ReplyNotification,
    eventRepo: EventRepository?,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    if (eventRepo == null) return

    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(item.replyEventId, version) { eventRepo.getEvent(item.replyEventId) }
    val profile = remember(event, version) { event?.let { eventRepo.getProfileData(it.pubkey) } }
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNoteClick(item.replyEventId) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Author row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                url = profile?.picture,
                size = 34,
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
                text = "replied",
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
        // Render reply content directly inline
        if (event != null) {
            Spacer(Modifier.height(6.dp))
            RichContent(
                content = event.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                eventRepo = eventRepo,
                onProfileClick = onProfileClick,
                onNoteClick = onNoteClick
            )
        }
    }
}

// ── Quote ───────────────────────────────────────────────────────────────

@Composable
private fun QuoteNotificationRow(
    item: NotificationGroup.QuoteNotification,
    eventRepo: EventRepository?,
    resolveProfile: (String) -> ProfileData?,
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
