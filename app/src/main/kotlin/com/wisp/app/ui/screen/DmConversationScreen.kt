package com.wisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wisp.app.nostr.DmMessage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.ui.component.DmBubble
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.R
import com.wisp.app.viewmodel.DeliveryRelaySource
import com.wisp.app.viewmodel.DmConversationViewModel
import com.wisp.app.viewmodel.PowStatus
import com.wisp.app.viewmodel.SocialActionManager

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
    onNoteClick: ((String) -> Unit)? = null,
    peerPubkey: String? = null,
    participants: List<String> = emptyList(),
    signer: NostrSigner? = null,
    socialActionManager: SocialActionManager? = null,
    isWalletConnected: Boolean = false,
    onGoToWallet: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val peerDelivery by viewModel.peerDeliveryRelays.collectAsState()
    val allParticipantRelays by viewModel.allParticipantRelays.collectAsState()
    val userDmRelays by viewModel.userDmRelays.collectAsState()
    val miningStatus by viewModel.miningStatus.collectAsState()
    val replyingTo by viewModel.replyingToMessage.collectAsState()
    val selectedMessageId by viewModel.selectedMessageId.collectAsState()
    val isGroup = participants.size > 1
    val listState = rememberLazyListState()
    var showRelayInfo by remember { mutableStateOf(false) }
    var debugMessage by remember { mutableStateOf<DmMessage?>(null) }
    var zapTargetMessage by remember { mutableStateOf<DmMessage?>(null) }
    var zapPendingSats by remember { mutableLongStateOf(0L) }
    var lastZappedMessage by remember { mutableStateOf<DmMessage?>(null) }
    val zapSatsMap = remember { mutableStateMapOf<String, Long>() }
    val zapInProgress by (socialActionManager?.zapInProgress ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())).collectAsState()

    // Record sats on the bubble once zap completes (keyed by senderPubkey)
    LaunchedEffect(socialActionManager) {
        socialActionManager?.zapSuccess?.collect { key ->
            val msg = lastZappedMessage
            if (msg != null && key == msg.senderPubkey) {
                zapSatsMap[msg.id] = (zapSatsMap[msg.id] ?: 0L) + zapPendingSats / 1000
                lastZappedMessage = null
                zapPendingSats = 0L
            }
        }
    }
    val totalRelayCount = (allParticipantRelays.values.sumOf { it.urls.size } + userDmRelays.size)
        .coerceAtLeast(peerDelivery.urls.size + userDmRelays.size)
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadMedia(uris, context.contentResolver, signer)
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (isGroup) {
                            // Group DM header: stacked avatars + participant names
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    participants.take(3).forEachIndexed { i, pubkey ->
                                        val profile = remember(pubkey) { eventRepo?.getProfileData(pubkey) }
                                        ProfilePicture(
                                            url = profile?.picture,
                                            size = 28,
                                            modifier = Modifier.offset(x = (i * 12).dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width((participants.take(3).size * 12 + 4).dp))
                                Column {
                                    val names = participants.take(3).joinToString(", ") { pk ->
                                        eventRepo?.getProfileData(pk)?.displayString
                                            ?: pk.take(8) + "…"
                                    }
                                    val suffix = if (participants.size > 3) " +${participants.size - 3}" else ""
                                    Text(
                                        names + suffix,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${participants.size + 1} people",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = peerPubkey != null && onProfileClick != null) {
                                    peerPubkey?.let { onProfileClick?.invoke(it) }
                                }
                            ) {
                                ProfilePicture(url = peerProfile?.picture, size = 32)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    peerProfile?.displayString ?: stringResource(R.string.title_chat),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
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
                            // Show relay info for each participant (works for 1:1 and group)
                            val displayRelays = allParticipantRelays.ifEmpty {
                                if (peerDelivery.urls.isNotEmpty()) mapOf(peerPubkey.orEmpty() to peerDelivery) else emptyMap()
                            }
                            displayRelays.entries.forEachIndexed { index, (pubkey, delivery) ->
                                if (index > 0) Spacer(Modifier.height(6.dp))
                                val participantProfile = eventRepo?.getProfileData(pubkey)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfilePicture(url = participantProfile?.picture, size = 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = participantProfile?.displayString ?: pubkey.take(8) + "…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sourceLabel = when (delivery.source) {
                                        DeliveryRelaySource.DM_RELAYS -> null
                                        DeliveryRelaySource.READ_RELAYS -> "inbox"
                                        DeliveryRelaySource.WRITE_RELAYS -> "write"
                                        DeliveryRelaySource.OWN_RELAYS -> "your relays"
                                    }
                                    if (sourceLabel != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "($sourceLabel)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                for (url in delivery.urls) {
                                    Text(
                                        text = url.removePrefix("wss://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                    )
                                }
                            }
                            if (displayRelays.isNotEmpty() && userDmRelays.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                            }
                            if (userDmRelays.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfilePicture(url = userProfile?.picture, size = 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.dm_you),
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
                .navigationBarsPadding()
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        viewModel.selectMessage(null)
                    },
                reverseLayout = true
            ) {
                var lastDateKey = ""
                for (msg in messages.reversed()) {
                    item(key = msg.id) {
                        val icons = msg.relayUrls.map { url ->
                            url to relayInfoRepo?.getIconUrl(url)
                        }
                        DmBubble(
                            message = msg,
                            isSent = msg.senderPubkey == userPubkey,
                            isSelected = selectedMessageId == msg.id,
                            conversationMessages = messages,
                            eventRepo = eventRepo,
                            relayIcons = icons,
                            onSelect = { viewModel.selectMessage(msg.id) },
                            onReply = { viewModel.setReplyingTo(it) },
                            onReact = { m, emoji -> viewModel.sendReaction(m.rumorId, emoji, relayPool, signer) },
                            onZap = { m -> zapTargetMessage = m },
                            isZapInProgress = msg.senderPubkey in zapInProgress,
                            zapSats = msg.zaps.sumOf { it.sats }.coerceAtLeast(zapSatsMap[msg.id] ?: 0L),
                            onProfileClick = onProfileClick,
                            onNoteClick = onNoteClick,
                            onDebugTap = { debugMessage = it }
                        )
                    }
                    val dateKey = dayKey(msg.createdAt)
                    if (dateKey != lastDateKey) {
                        lastDateKey = dateKey
                        item(key = "date-$dateKey") {
                            DateHeader(formatDateHeader(msg.createdAt))
                        }
                    }
                }
            }

            // Dismiss selection on scroll
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) viewModel.selectMessage(null)
            }

            // Upload progress banner
            AnimatedVisibility(visible = uploadProgress != null) {
                Text(
                    text = uploadProgress ?: "",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Error banner for signing/upload failures
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

            // Reply preview strip
            AnimatedVisibility(visible = replyingTo != null) {
                val msg = replyingTo
                if (msg != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val senderName = remember(msg.senderPubkey) {
                                if (msg.senderPubkey == userPubkey) "You"
                                else eventRepo?.getProfileData(msg.senderPubkey)?.displayString
                                    ?: msg.senderPubkey.take(8) + "…"
                            }
                            Text(
                                "Replying to $senderName",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                msg.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { viewModel.clearReply() }) {
                            Icon(Icons.Outlined.Close, "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Message input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    enabled = uploadProgress == null && !sending
                ) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = stringResource(R.string.cd_attach_media),
                        tint = if (uploadProgress == null && !sending) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { viewModel.updateMessageText(it) },
                    placeholder = { Text(stringResource(R.string.placeholder_message)) },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (sending && miningStatus is PowStatus.Mining) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Mining...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (sending) {
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
                            contentDescription = stringResource(R.string.cd_send),
                            tint = if (messageText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    debugMessage?.let { msg ->
        DmDebugDialog(message = msg, onDismiss = { debugMessage = null })
    }

    if (zapTargetMessage != null) {
        ZapDialog(
            isWalletConnected = isWalletConnected,
            onDismiss = { zapTargetMessage = null },
            onZap = { amountMsats, message, isAnonymous, _ ->
                val target = zapTargetMessage ?: return@ZapDialog
                zapPendingSats = amountMsats
                lastZappedMessage = target
                zapTargetMessage = null
                socialActionManager?.sendZapToPubkey(target.senderPubkey, amountMsats, message, isAnonymous, rumorId = target.rumorId.ifEmpty { null })
            },
            onGoToWallet = {
                zapTargetMessage = null
                onGoToWallet()
            }
        )
    }
}

@Composable
private fun DmDebugDialog(message: DmMessage, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val json = if (tab == 0) message.debugGiftWrapJson ?: "(not available)" else message.debugRumorJson ?: "(not available)"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Gift Wrap") })
                androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Rumor") })
            }
        },
        text = {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dm_debug", json))
            }) { Text("Copy") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DateHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

private val dateHeaderFormat = SimpleDateFormat("EEEE, MMMM d", Locale.US)
private val dateHeaderYearFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)

private fun dayKey(epoch: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = epoch * 1000
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
}

private fun formatDateHeader(epoch: Long): String {
    val msgDate = Date(epoch * 1000)
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { time = msgDate }

    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        msg.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                msg.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        msg.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                msg.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        msg.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> dateHeaderYearFormat.format(msgDate)
        else -> dateHeaderFormat.format(msgDate)
    }
}
