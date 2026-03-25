package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.DmConversation
import com.wisp.app.R
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.DmListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmListScreen(
    viewModel: DmListViewModel,
    eventRepo: EventRepository,
    userPubkey: String? = null,
    onBack: (() -> Unit)? = null,
    onConversation: (DmConversation) -> Unit,
    onNewGroupDm: () -> Unit = {}
) {
    val conversations by viewModel.conversationList.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_chat)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewGroupDm) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = "New group DM")
            }
        }
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(64.dp))
                Text(
                    stringResource(R.string.error_no_messages),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.error_send_message_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items = conversations, key = { it.conversationKey }, contentType = { "conversation" }) { convo ->
                    ConversationRow(
                        convo = convo,
                        eventRepo = eventRepo,
                        onClick = { onConversation(convo) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    convo: DmConversation,
    eventRepo: EventRepository,
    onClick: () -> Unit
) {
    val lastMsg = convo.messages.lastOrNull()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Avatar: stacked for group, single for 1:1
        if (convo.isGroup) {
            GroupAvatarCluster(participants = convo.participants, eventRepo = eventRepo)
        } else {
            val profile = remember(convo.peerPubkey) { eventRepo.getProfileData(convo.peerPubkey) }
            ProfilePicture(url = profile?.picture)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val displayName = if (convo.isGroup) {
                convo.participants.take(3).joinToString(", ") { pk ->
                    eventRepo.getProfileData(pk)?.displayString ?: pk.take(8) + "…"
                }.let { if (convo.participants.size > 3) "$it +${convo.participants.size - 3}" else it }
            } else {
                eventRepo.getProfileData(convo.peerPubkey)?.displayString
                    ?: convo.peerPubkey.take(8) + "..." + convo.peerPubkey.takeLast(4)
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            lastMsg?.let {
                Text(
                    text = it.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = formatTimestamp(convo.lastMessageAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupAvatarCluster(participants: List<String>, eventRepo: EventRepository) {
    Box(modifier = Modifier.size(48.dp)) {
        participants.take(3).forEachIndexed { i, pubkey ->
            val profile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
            ProfilePicture(
                url = profile?.picture,
                size = 28,
                modifier = Modifier
                    .align(
                        when (i) {
                            0 -> Alignment.TopStart
                            1 -> Alignment.TopEnd
                            else -> Alignment.BottomCenter
                        }
                    )
                    .offset(
                        x = when (i) { 1 -> 4.dp; 2 -> 2.dp; else -> 0.dp },
                        y = when (i) { 2 -> 4.dp; else -> 0.dp }
                    )
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MMM d", Locale.US)
private val timeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = Date(epoch * 1000)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    return if (cal.get(java.util.Calendar.YEAR) != currentYear) {
        timeYearFormat.format(date)
    } else {
        timeFormat.format(date)
    }
}
