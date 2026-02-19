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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NotificationItem
import com.wisp.app.nostr.ProfileData
import com.wisp.app.ui.component.ProfilePicture
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
                items(items = notifications, key = { it.id }) { item ->
                    val profile = viewModel.getProfileData(item.senderPubkey)
                    NotificationRow(
                        item = item,
                        profile = profile,
                        onNoteClick = onNoteClick,
                        onProfileClick = onProfileClick
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    profile: ProfileData?,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    val displayName = profile?.displayString
        ?: item.senderPubkey.take(8) + "..." + item.senderPubkey.takeLast(4)

    val description = when (item) {
        is NotificationItem.Reaction -> {
            val emoji = if (item.emoji == "+") "\u2764\uFE0F" else item.emoji
            "$emoji reacted to your note"
        }
        is NotificationItem.Reply -> "replied: ${item.contentPreview}"
        is NotificationItem.Zap -> "\u26A1 zapped ${item.amountSats} sats"
        is NotificationItem.Quote -> "quoted your note: ${item.contentPreview}"
        is NotificationItem.Mention -> "mentioned you: ${item.contentPreview}"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val eventId = when (item) {
                    is NotificationItem.Reply -> item.replyEventId
                    is NotificationItem.Quote -> item.id
                    is NotificationItem.Mention -> item.eventId
                    else -> item.referencedEventId
                }
                if (eventId != null) onNoteClick(eventId)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        ProfilePicture(
            url = profile?.picture,
            modifier = Modifier.clickable { onProfileClick(item.senderPubkey) }
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatNotifTimestamp(item.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val notifTimeFormat = SimpleDateFormat("MMM d", Locale.US)

private fun formatNotifTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    return notifTimeFormat.format(Date(epoch * 1000))
}
