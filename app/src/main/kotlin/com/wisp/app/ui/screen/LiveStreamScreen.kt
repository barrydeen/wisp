package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.LiveChatMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.EmojiReactionPopup
import com.wisp.app.ui.component.InlineVideoPlayerWithFullscreen
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.viewmodel.LiveStreamViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveStreamScreen(
    viewModel: LiveStreamViewModel,
    eventRepo: EventRepository,
    signer: NostrSigner?,
    myPubkey: String?,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
    onFullScreenVideo: ((String, Long) -> Unit)? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onFollowAuthor: ((String) -> Unit)? = null,
    onBlockAuthor: ((String) -> Unit)? = null,
    isFollowing: ((String) -> Boolean)? = null,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)? = null,
    onZapStream: (() -> Unit)? = null,
    streamZapTotal: Long = 0,
    streamActivityEventId: String? = null,
    zapVersion: Int = 0,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet()
) {
    val activity by viewModel.activity.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        TopAppBar(
            title = {
                Text(
                    text = activity?.title ?: "Live Stream",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Video player or thumbnail
        val streamUrl = activity?.streamingUrl
        val imageUrl = activity?.image
        if (streamUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                InlineVideoPlayerWithFullscreen(
                    url = streamUrl,
                    onFullScreen = { posMs ->
                        onFullScreenVideo?.invoke(streamUrl, posMs)
                    }
                )
            }
        } else if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = activity?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }

        // Stream info bar
        val isStreamZapInProgress = streamActivityEventId != null && streamActivityEventId in zapInProgressIds
        val isStreamZapAnimating = streamActivityEventId != null && streamActivityEventId in zapAnimatingIds
        StreamInfoBar(
            hostPubkey = activity?.streamerPubkey ?: activity?.hostPubkey ?: viewModel.hostPubkey,
            title = activity?.title,
            status = activity?.status,
            eventRepo = eventRepo,
            onProfileClick = onProfileClick,
            onZapStream = onZapStream,
            streamZapTotal = streamZapTotal,
            isZapInProgress = isStreamZapInProgress,
            isZapAnimating = isStreamZapAnimating
        )

        HorizontalDivider()

        // Chat messages
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Text(
                    text = "No chat messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize()
            ) {
                items(messages.asReversed(), key = { it.id }) { msg ->
                    if (msg.isZapAnnouncement) {
                        ZapAnnouncementBubble(
                            message = msg,
                            eventRepo = eventRepo,
                            onProfileClick = onProfileClick
                        )
                    } else {
                        LiveChatBubble(
                            message = msg,
                            allMessages = messages,
                            eventRepo = eventRepo,
                            myPubkey = myPubkey,
                            resolvedEmojis = resolvedEmojis,
                            unicodeEmojis = unicodeEmojis,
                            onProfileClick = onProfileClick,
                            onReply = { viewModel.setReplyTarget(it) },
                            onReact = { messageId, senderPubkey, emoji ->
                                viewModel.sendReaction(messageId, senderPubkey, emoji, signer, resolvedEmojis)
                            },
                            onFollowAuthor = onFollowAuthor,
                            onBlockAuthor = onBlockAuthor,
                            isFollowing = isFollowing,
                            onZap = onZap,
                            zapVersion = zapVersion,
                            isZapAnimating = msg.id in zapAnimatingIds,
                            isZapInProgress = msg.id in zapInProgressIds
                        )
                    }
                }
            }
        }

        // Reply quote bar
        AnimatedVisibility(visible = replyTarget != null) {
            replyTarget?.let { reply ->
                val replyProfile = remember(reply.senderPubkey) { eventRepo.getProfileData(reply.senderPubkey) }
                val replyName = replyProfile?.displayString ?: (reply.senderPubkey.take(8) + "…")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Replying to $replyName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = { viewModel.clearReplyTarget() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Input bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                value = messageText,
                onValueChange = { viewModel.updateText(it) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { viewModel.sendMessage(signer) }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (messageText.isEmpty()) {
                            Text(
                                text = "Chat...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { viewModel.sendMessage(signer) },
                enabled = messageText.isNotBlank() && !sending
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (messageText.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamInfoBar(
    hostPubkey: String,
    title: String?,
    status: String?,
    eventRepo: EventRepository,
    onProfileClick: (String) -> Unit,
    onZapStream: (() -> Unit)? = null,
    streamZapTotal: Long = 0,
    isZapInProgress: Boolean = false,
    isZapAnimating: Boolean = false
) {
    val profile = remember(hostPubkey) { eventRepo.getProfileData(hostPubkey) }
    val displayName = profile?.displayString ?: (hostPubkey.take(8) + "…")
    val zapColor = com.wisp.app.ui.theme.WispThemeColors.zapColor

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (status == "live") {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFE53935),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        ProfilePicture(
            url = profile?.picture,
            size = 28,
            modifier = Modifier.clickable { onProfileClick(hostPubkey) }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onProfileClick(hostPubkey) }
        )
        if (streamZapTotal > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = zapColor
                )
                Text(
                    text = formatZapSats(streamZapTotal),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = zapColor
                )
            }
        }
        if (onZapStream != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Surface(
                    onClick = onZapStream,
                    enabled = !isZapInProgress,
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        isZapInProgress -> zapColor.copy(alpha = 0.5f)
                        isZapAnimating -> Color(0xFF4CAF50)
                        else -> zapColor
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isZapInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = "Zap this stream",
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when {
                                isZapAnimating -> "Sent!"
                                isZapInProgress -> "Zapping..."
                                else -> "Zap"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .wrapContentSize(unbounded = true, align = Alignment.Center)
                ) {
                    com.wisp.app.ui.component.ZapBurstEffect(
                        isActive = isZapAnimating,
                        modifier = Modifier.size(80.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ZapAnnouncementBubble(
    message: LiveChatMessage,
    eventRepo: EventRepository,
    onProfileClick: (String) -> Unit
) {
    val profile = remember(message.senderPubkey) { eventRepo.getProfileData(message.senderPubkey) }
    val displayName = profile?.displayString ?: (message.senderPubkey.take(8) + "…")
    val zapColor = com.wisp.app.ui.theme.WispThemeColors.zapColor

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = zapColor.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, zapColor.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = zapColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            ProfilePicture(
                url = profile?.picture,
                size = 28,
                onClick = { onProfileClick(message.senderPubkey) }
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " zapped ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatZapSats(message.zapAmountSats),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = zapColor
                    )
                    Text(
                        text = " sats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveChatBubble(
    message: LiveChatMessage,
    allMessages: List<LiveChatMessage>,
    eventRepo: EventRepository,
    myPubkey: String?,
    resolvedEmojis: Map<String, String>,
    unicodeEmojis: List<String>,
    onProfileClick: (String) -> Unit,
    onReply: (LiveChatMessage) -> Unit,
    onReact: (messageId: String, senderPubkey: String, emoji: String) -> Unit,
    onFollowAuthor: ((String) -> Unit)?,
    onBlockAuthor: ((String) -> Unit)?,
    isFollowing: ((String) -> Boolean)?,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)?,
    zapVersion: Int,
    isZapAnimating: Boolean,
    isZapInProgress: Boolean
) {
    val profile = remember(message.senderPubkey) { eventRepo.getProfileData(message.senderPubkey) }
    val displayName = profile?.displayString ?: (message.senderPubkey.take(8) + "…")

    val replyToMessage = remember(message.replyToId, allMessages) {
        message.replyToId?.let { id -> allMessages.firstOrNull { it.id == id } }
    }

    val myReactions = remember(message.reactions, myPubkey) {
        if (myPubkey == null) emptySet()
        else message.reactions.filter { (_, reactors) -> myPubkey in reactors }.keys.toSet()
    }

    val zapSats = remember(message.id, zapVersion) { eventRepo.getZapSats(message.id) }
    val hasZaps = zapSats > 0

    var showEmojiPicker by remember(message.id) { mutableStateOf(false) }

    val swipeOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThreshold = remember(density) { with(density) { 80.dp.toPx() } }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply icon revealed behind the message as you swipe
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(20.dp)
                .alpha((swipeOffset.value / swipeThreshold).coerceIn(0f, 1f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .offset { IntOffset(swipeOffset.value.toInt(), 0) }
                .pointerInput(message.id) {
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = { triggered = false },
                        onDragEnd = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                            triggered = false
                        },
                        onDragCancel = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                            triggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount > 0 && !triggered) {
                                val next = (swipeOffset.value + dragAmount).coerceIn(0f, swipeThreshold * 1.3f)
                                scope.launch { swipeOffset.snapTo(next) }
                                if (next >= swipeThreshold) {
                                    triggered = true
                                    onReply(message)
                                    scope.launch { swipeOffset.animateTo(0f, spring()) }
                                }
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.Top
        ) {
            // Avatar
            ProfilePicture(
                url = profile?.picture,
                size = 36,
                onClick = { onProfileClick(message.senderPubkey) }
            )
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header: name + time
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatLiveChatTimestamp(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                // Reply quote block
                if (replyToMessage != null) {
                    val replyProfile = remember(replyToMessage.senderPubkey) {
                        eventRepo.getProfileData(replyToMessage.senderPubkey)
                    }
                    val replyName = replyProfile?.displayString ?: (replyToMessage.senderPubkey.take(8) + "…")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
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
                                text = replyName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = replyToMessage.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Content
                RichContent(
                    content = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    emojiMap = message.emojiTags,
                    eventRepo = eventRepo,
                    onProfileClick = onProfileClick
                )

                // Per-emoji reaction badges with avatars
                if (message.reactions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        message.reactions.forEach { (emoji, reactors) ->
                            val isMe = myPubkey != null && myPubkey in reactors
                            val shortcode = if (emoji.startsWith(":") && emoji.endsWith(":")) emoji.removeSurrounding(":") else null
                            val emojiUrl = shortcode?.let { resolvedEmojis[it] }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { onReact(message.id, message.senderPubkey, emoji) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (emojiUrl != null) {
                                        AsyncImage(
                                            model = emojiUrl,
                                            contentDescription = emoji,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(text = emoji, fontSize = 16.sp)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    // Stacked reactor avatars
                                    val displayReactors = reactors.take(3)
                                    displayReactors.forEachIndexed { i, pubkey ->
                                        val reactorProfile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
                                        ProfilePicture(
                                            url = reactorProfile?.picture,
                                            size = 16,
                                            modifier = if (i > 0) Modifier.offset(x = (i * -4).dp) else Modifier
                                        )
                                    }
                                    if (reactors.size > 3) {
                                        Text(
                                            text = "+${reactors.size - 3}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            modifier = Modifier.offset(x = (displayReactors.size * -4).dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action row: reply, react, zap
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        IconButton(onClick = { onReply(message) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "Reply",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showEmojiPicker = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.FavoriteBorder,
                                contentDescription = "React",
                                modifier = Modifier.size(18.dp),
                                tint = if (myReactions.isNotEmpty()) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onZap != null) {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable(enabled = !isZapInProgress) {
                                        onZap(message.id, message.senderPubkey)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .wrapContentSize(unbounded = true, align = Alignment.Center)
                                    ) {
                                        Icon(
                                            Icons.Filled.Bolt,
                                            contentDescription = "Zap",
                                            modifier = Modifier.size(18.dp),
                                            tint = when {
                                                isZapInProgress -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                hasZaps || isZapAnimating -> com.wisp.app.ui.theme.WispThemeColors.zapColor
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .wrapContentSize(unbounded = true, align = Alignment.Center)
                                        ) {
                                            com.wisp.app.ui.component.ZapBurstEffect(
                                                isActive = isZapAnimating,
                                                modifier = Modifier.size(80.dp)
                                            )
                                        }
                                    }
                                    if (hasZaps) {
                                        Text(
                                            text = formatZapSats(zapSats),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = com.wisp.app.ui.theme.WispThemeColors.zapColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showEmojiPicker) {
                        EmojiReactionPopup(
                            onSelect = { emoji -> onReact(message.id, message.senderPubkey, emoji) },
                            onDismiss = { showEmojiPicker = false },
                            selectedEmojis = myReactions,
                            resolvedEmojis = resolvedEmojis,
                            unicodeEmojis = unicodeEmojis
                        )
                    }
                }
            }

            // 3-dot overflow menu
            Box(modifier = Modifier.align(Alignment.Top)) {
                var menuExpanded by remember { mutableStateOf(false) }
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current
                val isOwnMessage = message.senderPubkey == myPubkey
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (!isOwnMessage && onFollowAuthor != null) {
                        val following = isFollowing?.invoke(message.senderPubkey) ?: false
                        DropdownMenuItem(
                            text = { Text(if (following) stringResource(R.string.btn_unfollow) else stringResource(R.string.btn_follow)) },
                            onClick = {
                                menuExpanded = false
                                onFollowAuthor(message.senderPubkey)
                            }
                        )
                    }
                    if (!isOwnMessage && onBlockAuthor != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_block)) },
                            onClick = {
                                menuExpanded = false
                                onBlockAuthor(message.senderPubkey)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_share)) },
                        onClick = {
                            menuExpanded = false
                            try {
                                val nevent = Nip19.neventEncode(message.id.hexToByteArray())
                                val url = "https://njump.me/$nevent"
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, null))
                            } catch (_: Exception) {}
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_copy_note_id)) },
                        onClick = {
                            menuExpanded = false
                            try {
                                val relays = eventRepo.getEventRelays(message.id).take(3).toList()
                                val neventId = Nip19.neventEncode(
                                    eventId = message.id.hexToByteArray(),
                                    relays = relays,
                                    author = message.senderPubkey.hexToByteArray()
                                )
                                clipboardManager.setText(AnnotatedString(neventId))
                            } catch (_: Exception) {}
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_copy_note_json)) },
                        onClick = {
                            menuExpanded = false
                            val event = eventRepo.getEvent(message.id)
                            if (event != null) {
                                clipboardManager.setText(AnnotatedString(event.toJson()))
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_copy_text)) },
                        onClick = {
                            menuExpanded = false
                            clipboardManager.setText(AnnotatedString(message.content))
                        }
                    )
                }
            }
        }
    }
}

private val liveChatTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
private val liveChatDateTimeFormat = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)

private fun formatLiveChatTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = java.util.Date(epoch * 1000)
    val now = System.currentTimeMillis()
    return if (now - epoch * 1000 < 86400_000) liveChatTimeFormat.format(date)
           else liveChatDateTimeFormat.format(date)
}

private fun formatZapSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${"%.1f".format(sats / 1_000_000.0)}M"
    sats >= 1_000 -> "${"%.1f".format(sats / 1_000.0)}k"
    else -> sats.toString()
}
