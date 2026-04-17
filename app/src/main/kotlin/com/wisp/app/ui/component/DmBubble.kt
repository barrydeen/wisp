package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.ui.draw.scale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.wisp.app.R
import com.wisp.app.nostr.DmMessage
import com.wisp.app.ui.screen.GroupChatHorizontalChipStrip
import com.wisp.app.nostr.DmReaction
import com.wisp.app.nostr.EncryptedMedia
import com.wisp.app.ui.component.FullScreenEncryptedImageViewer
import com.wisp.app.repo.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.wisp.app.ui.theme.WispThemeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmBubble(
    message: DmMessage,
    isSent: Boolean,
    isSelected: Boolean,
    conversationMessages: List<DmMessage>,
    eventRepo: EventRepository? = null,
    relayIcons: List<Pair<String, String?>> = emptyList(),
    onSelect: () -> Unit,
    onReply: (DmMessage) -> Unit,
    onReact: (DmMessage, String) -> Unit,
    onZap: (DmMessage) -> Unit,
    isZapInProgress: Boolean = false,
    zapSats: Long = 0,
    onProfileClick: ((String) -> Unit)? = null,
    onNoteClick: ((String) -> Unit)? = null,
    onDebugTap: ((DmMessage) -> Unit)? = null,
    noteActions: NoteActions? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var swipeOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }
    var swipeTriggered by remember(message.id) { mutableStateOf(false) }
    var showActionsSheet by remember(message.id) { mutableStateOf(false) }
    val actionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val senderProfile = remember(message.senderPubkey) { eventRepo?.getProfileData(message.senderPubkey) }
    val senderName = senderProfile?.displayString ?: (message.senderPubkey.take(8) + "…")

    val animatedSwipe by animateFloatAsState(
        targetValue = swipeOffsetPx,
        animationSpec = if (swipeOffsetPx == 0f) spring(dampingRatio = Spring.DampingRatioMediumBouncy) else spring(stiffness = Spring.StiffnessHigh),
        label = "swipe"
    )

    val bubbleColor = if (isSent) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)

    val quotedMessage = remember(message.replyToId, conversationMessages.size) {
        message.replyToId?.let { rid -> conversationMessages.firstOrNull { it.rumorId == rid || it.id == rid } }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // Swipe hint icon
        Icon(
            Icons.AutoMirrored.Outlined.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(22.dp)
                .scale(scaleX = -1f, scaleY = -1f)
                .alpha((animatedSwipe / swipeThresholdPx).coerceIn(0f, 1f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isSent) 24.dp else 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 4.dp
                )
                .offset { IntOffset(animatedSwipe.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val newOffset = (swipeOffsetPx + delta).coerceIn(0f, swipeThresholdPx * 1.3f)
                        swipeOffsetPx = newOffset
                        if (!swipeTriggered && newOffset >= swipeThresholdPx) {
                            swipeTriggered = true
                            onReply(message)
                        }
                    },
                    onDragStopped = {
                        swipeTriggered = false
                        swipeOffsetPx = 0f
                    }
                ),
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isSent) {
                ProfilePicture(
                    url = senderProfile?.picture,
                    size = 36,
                    onClick = { onProfileClick?.invoke(message.senderPubkey) }
                )
                Spacer(Modifier.width(6.dp))
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f, fill = isSent),
                contentAlignment = if (isSent) Alignment.BottomEnd else Alignment.BottomStart
            ) {
                @OptIn(ExperimentalFoundationApi::class)
                Box(
                    modifier = Modifier
                        .widthIn(min = 80.dp, max = maxWidth - 28.dp)
                        .wrapContentWidth(if (isSent) Alignment.End else Alignment.Start)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isSent) 16.dp else 4.dp,
                                bottomEnd = if (isSent) 4.dp else 16.dp
                            )
                        )
                        .background(bubbleColor)
                        .combinedClickable(
                            onClick = { showActionsSheet = true },
                            onDoubleClick = { onDebugTap?.invoke(message) }
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Header row: sender name + timestamp (received messages only)
                        if (!isSent) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = senderName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        if (quotedMessage != null) {
                            QuotedDmPreview(
                                message = quotedMessage,
                                isSentByMe = quotedMessage.senderPubkey == message.senderPubkey,
                                eventRepo = eventRepo,
                                parentIsSent = isSent
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (message.encryptedFileMetadata != null) {
                            EncryptedMediaContent(
                                metadata = message.encryptedFileMetadata,
                                messageId = message.rumorId.ifEmpty { message.id },
                                tintColor = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            RichContent(
                                content = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                linkColor = MaterialTheme.colorScheme.primary,
                                emojiMap = resolvedEmojis + message.emojiMap,
                                eventRepo = eventRepo,
                                onProfileClick = onProfileClick,
                                onNoteClick = onNoteClick,
                                noteActions = noteActions
                            )
                        }
                        // Timestamp at bottom only for sent messages (received shows it in header)
                        if (isSent) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = formatTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (message.reactions.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            ReactionChips(
                                reactions = message.reactions,
                                eventRepo = eventRepo,
                                isSent = isSent,
                                onToggle = { emoji -> onReact(message, emoji) },
                                resolvedEmojis = resolvedEmojis
                            )
                        }
                    }
                }
            }
        }
    }

    if (showActionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionsSheet = false },
            sheetState = actionsSheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current
            val useZapBoltIcon = com.wisp.app.ui.util.useBoltIcon()
            val sheetScroll = rememberScrollState()
            val reactScroll = rememberScrollState()
            val stripBg = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
            val chipFill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            val reactionPick = remember(unicodeEmojis) {
                val customOnly = unicodeEmojis.filter { it.startsWith(":") && it.endsWith(":") }
                if (customOnly.isNotEmpty()) customOnly.take(28) else unicodeEmojis.take(28)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(sheetScroll)
                    .navigationBarsPadding()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Comment / Reply section
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = stripBg,
                    border = BorderStroke(0.33.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .clickable {
                            showActionsSheet = false
                            onReply(message)
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Message preview bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(chipFill)
                                .height(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Reply indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .scale(scaleX = -1f, scaleY = -1f)
                            )
                            Text(
                                text = stringResource(R.string.group_room_comment),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                // React section
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_room_eyebrow_react),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )
                    GroupChatHorizontalChipStrip(
                        scrollState = reactScroll,
                        stripBackground = stripBg,
                        chipFill = chipFill,
                        chevronBackground = MaterialTheme.colorScheme.surface,
                        verticalPadding = 8.dp,
                        chipSpacing = 6.dp,
                        trailingOnClick = {
                            showActionsSheet = false
                            pendingEmojiReactCallback = { emoji -> onReact(message, emoji) }
                            onOpenEmojiLibrary?.invoke()
                        },
                        trailingEnabled = true,
                        trailingContentDescription = stringResource(R.string.cd_open_reaction_picker)
                    ) {
                        reactionPick.forEach { em ->
                            val isCustom = em.startsWith(":") && em.endsWith(":")
                            val sc = if (isCustom) em.removeSurrounding(":") else null
                            val emojiUrl = sc?.let { resolvedEmojis[it] }
                            if (isCustom && emojiUrl == null) return@forEach
                            Box(
                                modifier = Modifier
                                    .clickable {
                                        showActionsSheet = false
                                        onReact(message, em)
                                    }
                                    .padding(horizontal = 3.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (emojiUrl != null) {
                                    coil3.compose.AsyncImage(
                                        model = emojiUrl,
                                        contentDescription = em,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    Text(text = em, fontSize = 26.sp)
                                }
                            }
                        }
                    }
                }

                // Actions panel
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_room_eyebrow_actions),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Spacer(Modifier.width(4.dp))
                        DmActionPanelButton(
                            modifier = Modifier.width(82.dp),
                            onClick = { showActionsSheet = false; onZap(message) },
                            icon = {
                                if (useZapBoltIcon) {
                                    Icon(
                                        Icons.Outlined.FlashOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.CurrencyBitcoin,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            },
                            label = stringResource(R.string.group_room_eyebrow_zap)
                        )
                        DmActionPanelButton(
                            modifier = Modifier.width(82.dp),
                            onClick = {
                                showActionsSheet = false
                                clipboardManager.setText(AnnotatedString(message.content))
                            },
                            icon = {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = "Text"
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotedDmPreview(
    message: DmMessage,
    isSentByMe: Boolean,
    eventRepo: EventRepository?,
    parentIsSent: Boolean
) {
    val senderName = remember(message.senderPubkey) {
        eventRepo?.getProfileData(message.senderPubkey)?.displayString
            ?: message.senderPubkey.take(8) + "…"
    }
    val accentColor = if (parentIsSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val bgColor = if (parentIsSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    val textColor = if (parentIsSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .widthIn(max = 240.dp)
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(48.dp)
                .background(accentColor)
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReactionChips(
    reactions: List<DmReaction>,
    eventRepo: EventRepository?,
    isSent: Boolean,
    onToggle: (String) -> Unit,
    resolvedEmojis: Map<String, String> = emptyMap()
) {
    val grouped = remember(reactions) { reactions.groupBy { it.emoji } }
    val pillColor = if (isSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        grouped.forEach { (emoji, list) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(pillColor)
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
                    resolvedEmojis[emoji.removeSurrounding(":")]
                        ?: list.firstNotNullOfOrNull { it.emojiUrl }
                } else null
                if (emojiUrl != null) {
                    coil3.compose.AsyncImage(
                        model = emojiUrl,
                        contentDescription = emoji,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(emoji, fontSize = 14.sp)
                }
                Spacer(Modifier.width(3.dp))
                // Stack up to 3 reactor avatars
                list.take(3).forEachIndexed { index, reaction ->
                    val profile = remember(reaction.authorPubkey) {
                        eventRepo?.getProfileData(reaction.authorPubkey)
                    }
                    ProfilePicture(
                        url = profile?.picture,
                        size = 14,
                        modifier = if (index > 0) Modifier.offset(x = (-4).dp) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun DmExpandedDetails(
    relayIcons: List<Pair<String, String?>>,
    reactions: List<DmReaction>,
    eventRepo: EventRepository?,
    isSent: Boolean,
    resolvedEmojis: Map<String, String> = emptyMap()
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        // Relay chips
        if (relayIcons.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
            ) {
                relayIcons.forEach { (relayUrl, iconUrl) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            RelayIcon(iconUrl = iconUrl, relayUrl = relayUrl, size = 12.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = relayUrl.removePrefix("wss://").take(30),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Reactions detail
        if (reactions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val grouped = reactions.groupBy { it.emoji }
            grouped.forEach { (emoji, list) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
                        resolvedEmojis[emoji.removeSurrounding(":")]
                            ?: list.firstNotNullOfOrNull { it.emojiUrl }
                    } else null
                    if (emojiUrl != null) {
                        coil3.compose.AsyncImage(
                            model = emojiUrl,
                            contentDescription = emoji,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(emoji, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Row {
                        list.take(5).forEach { reaction ->
                            val profile = remember(reaction.authorPubkey) {
                                eventRepo?.getProfileData(reaction.authorPubkey)
                            }
                            ProfilePicture(
                                url = profile?.picture,
                                size = 18,
                                modifier = Modifier.padding(end = 2.dp)
                            )
                        }
                        if (list.size > 5) {
                            Text(
                                "+${list.size - 5}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DmActionPanelButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun formatTime(epoch: Long): String {
    return timeFormat.format(Date(epoch * 1000))
}

/** In-memory LRU cache for decrypted media bitmaps. ~32 MB max. */
private val decryptedBitmapCache = LruCache<String, Bitmap>(32)

private val mediaHttpClient by lazy {
    com.wisp.app.relay.HttpClientFactory.createHttpClient(
        connectTimeoutSeconds = 15,
        readTimeoutSeconds = 60,
        writeTimeoutSeconds = 15
    )
}

@Composable
private fun EncryptedMediaContent(
    metadata: EncryptedMedia.EncryptedFileMetadata,
    messageId: String,
    tintColor: androidx.compose.ui.graphics.Color
) {
    val isImage = metadata.mimeType.startsWith("image/")

    if (isImage) {
        var bitmap by remember(messageId) { mutableStateOf(decryptedBitmapCache.get(messageId)) }
        var loading by remember(messageId) { mutableStateOf(bitmap == null) }
        var error by remember(messageId) { mutableStateOf<String?>(null) }

        LaunchedEffect(messageId) {
            if (bitmap != null) return@LaunchedEffect
            loading = true
            try {
                val decrypted = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(metadata.fileUrl).build()
                    val response = mediaHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val encryptedBytes = response.body?.bytes() ?: throw Exception("Empty response")
                    EncryptedMedia.decryptFile(encryptedBytes, metadata.keyHex, metadata.nonceHex)
                }
                val bmp = BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
                if (bmp != null) {
                    decryptedBitmapCache.put(messageId, bmp)
                    bitmap = bmp
                } else {
                    error = "Could not decode image"
                }
            } catch (e: Exception) {
                error = e.message ?: "Decryption failed"
            } finally {
                loading = false
            }
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .size(200.dp, 150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tintColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Decrypting...",
                        style = MaterialTheme.typography.labelSmall,
                        color = tintColor.copy(alpha = 0.6f)
                    )
                }
            }
            error != null -> {
                Text(
                    "Failed to load media",
                    style = MaterialTheme.typography.bodySmall,
                    color = tintColor.copy(alpha = 0.6f)
                )
            }
            bitmap != null -> {
                var showFullscreen by remember { mutableStateOf(false) }
                
                Box(
                    modifier = Modifier
                        .widthIn(max = 256.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showFullscreen = true }
                ) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Encrypted image",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                    )
                }
                
                if (showFullscreen) {
                    FullScreenEncryptedImageViewer(
                        bitmap = bitmap!!,
                        imageUrl = metadata.fileUrl,
                        onDismiss = { showFullscreen = false }
                    )
                }
            }
        }
    } else {
        // Non-image file: show type and size
        val sizeText = metadata.size?.let { bytes ->
            when {
                bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        } ?: ""
        Text(
            text = "${metadata.mimeType} $sizeText",
            style = MaterialTheme.typography.bodySmall,
            color = tintColor.copy(alpha = 0.7f)
        )
    }
}

