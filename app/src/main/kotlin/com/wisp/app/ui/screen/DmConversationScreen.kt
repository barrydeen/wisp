package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.ui.component.DmBubble
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.viewmodel.DmConversationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmConversationScreen(
    viewModel: DmConversationViewModel,
    relayPool: RelayPool,
    peerProfile: ProfileData?,
    userProfile: ProfileData? = null,
    userPubkey: String?,
    eventRepo: EventRepository? = null,
    relayInfoRepo: RelayInfoRepository? = null,
    onBack: () -> Unit,
    onProfileClick: ((String) -> Unit)? = null,
    peerPubkey: String? = null,
    signer: NostrSigner? = null
) {
    val messages by viewModel.messages.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val peerDmRelays by viewModel.peerDmRelays.collectAsState()
    val userDmRelays by viewModel.userDmRelays.collectAsState()
    val listState = rememberLazyListState()
    var showRelayInfo by remember { mutableStateOf(false) }
    val totalRelayCount = (peerDmRelays.size + userDmRelays.size)

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = peerPubkey != null && onProfileClick != null) {
                                peerPubkey?.let { onProfileClick?.invoke(it) }
                            }
                        ) {
                            ProfilePicture(
                                url = peerProfile?.picture,
                                size = 32
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                peerProfile?.displayString ?: "Chat",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (totalRelayCount > 0) {
                            IconButton(onClick = { showRelayInfo = !showRelayInfo }) {
                                Box {
                                    Icon(
                                        Icons.Outlined.Cloud,
                                        contentDescription = "DM relays",
                                        tint = if (showRelayInfo) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Badge with relay count
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$totalRelayCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Expandable relay info panel
                AnimatedVisibility(visible = showRelayInfo) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (peerDmRelays.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfilePicture(url = peerProfile?.picture, size = 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = peerProfile?.displayString ?: "Peer",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                for (url in peerDmRelays) {
                                    Text(
                                        text = url.removePrefix("wss://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                    )
                                }
                            }
                            if (peerDmRelays.isNotEmpty() && userDmRelays.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                            }
                            if (userDmRelays.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfilePicture(url = userProfile?.picture, size = 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "You",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                for (url in userDmRelays) {
                                    Text(
                                        text = url.removePrefix("wss://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = false
            ) {
                items(items = messages, key = { it.id }) { msg ->
                    val icons = msg.relayUrls.map { url ->
                        url to relayInfoRepo?.getIconUrl(url)
                    }
                    DmBubble(
                        content = msg.content,
                        timestamp = msg.createdAt,
                        isSent = msg.senderPubkey == userPubkey,
                        eventRepo = eventRepo,
                        relayIcons = icons
                    )
                }
            }

            // Error banner for signing failures
            AnimatedVisibility(visible = sendError != null) {
                Text(
                    text = sendError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Clear error when user starts typing again
            LaunchedEffect(messageText) {
                if (messageText.isNotBlank()) viewModel.clearSendError()
            }

            // Message input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { viewModel.updateMessageText(it) },
                    placeholder = { Text("Message...") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = { viewModel.sendMessage(relayPool, signer) },
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (messageText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
