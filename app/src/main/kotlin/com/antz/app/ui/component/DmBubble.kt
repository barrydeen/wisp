package com.antz.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.antz.app.nostr.DmMessage
import com.antz.app.nostr.DmReaction
import com.antz.app.nostr.EncryptedMedia
import com.antz.app.repo.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.antz.app.ui.theme.AntzThemeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    var showDetails by remember(message.id) { mutableStateOf(false) }
    var showEmojiPicker by remember(message.id) { mutableStateOf(false) }
    var swipeOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }
    var swipeTriggered by remember(message.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }

    val animatedSwipe by animateFloatAsState(
        targetValue = swipeOffsetPx,
        animationSpec = if (swipeOffsetPx == 0f) spring(dampingRatio = Spring.DampingRatioMediumBouncy) else spring(stiffness = Spring.StiffnessHigh),
        label = "swipe"
    )

    val bubbleColor = if (isSent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSent) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    val quotedMessage = remember(message.replyToId, conversationMessages.size) {
        message.replyToId?.let { rid -> conversationMessages.firstOrNull { it.rumorId == rid || it.id == rid } }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        // --- Bubble (swipeable to reply) ---
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
        ) {
            if (!isSent) {
                val senderProfile = remember(message.senderPubkey) {
                    eventRepo?.getProfileData(message.senderPubkey)
                }
                ProfilePicture(
                    url = senderProfile?.picture,
                    size = 28,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Box(
                modifier = Modifier
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
                    )
            ) {
            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 280.dp)
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
                        onClick = { onSelect() },
                        onDoubleClick = { onDebugTap?.invoke(message) }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    // Quoted reply header
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
                            tintColor = textColor
                        )
                    } else {
                        RichContent(
                            content = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            linkColor = textColor,
                            emojiMap = resolvedEmojis + message.emojiMap,
                            eventRepo = eventRepo,
                            onProfileClick = onProfileClick,
                            onNoteClick = onNoteClick,
                            noteActions = noteActions
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            // Reply hint shown while swiping
            if (animatedSwipe > 4f) {
                Icon(
                    Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(20.dp)
                        .offset { IntOffset((-animatedSwipe * 0.6f).roundToInt(), 0) }
                        .alpha((animatedSwipe / swipeThresholdPx).coerceIn(0f, 1f))
                )
            }
            } // end swipe Box

            if (isSent) {
                val senderProfile = remember(message.senderPubkey) {
                    eventRepo?.getProfileData(message.senderPubkey)
                }
                ProfilePicture(
                    url = senderProfile?.picture,
                    size = 28,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        } // end avatar Row

        // --- Action bar (always visible) ---
        // For sent messages: Reply · React · Zap · Expand (expand closest to bubble on right)
        // For received messages: Expand · Zap · React · Reply (expand closest to bubble on left)
        Row(
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        ) {
            val expandButton: @Composable () -> Unit = {
                IconButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showDetails) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            val zapButton: @Composable () -> Unit = {
                IconButton(
                    onClick = { if (!isZapInProgress) onZap(message) },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isZapInProgress) {
                        LightningAnimation(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.antz.app.R.drawable.ic_bolt),
                            contentDescription = "Zap",
                            tint = if (zapSats > 0) AntzThemeColors.zapColor
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            val zapLabel: @Composable () -> Unit = {
                if (zapSats > 0 && !isZapInProgress) {
                    Text(
                        text = if (zapSats >= 1000) "${zapSats / 1000}k" else "$zapSats",
                        style = MaterialTheme.typography.labelSmall,
                        color = AntzThemeColors.zapColor
                    )
                }
            }
            val reactButton: @Composable () -> Unit = {
                IconButton(onClick = { showEmojiPicker = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.AddReaction, "React", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            val replyButton: @Composable () -> Unit = {
                IconButton(onClick = { onReply(message) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "Reply", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            if (isSent) {
                replyButton()
                reactButton()
                zapButton()
                zapLabel()
                expandButton()
            } else {
                expandButton()
                zapButton()
                zapLabel()
                reactButton()
                replyButton()
            }
        }

        // --- Expandable details (relays + reactions) ---
        AnimatedVisibility(
            visible = showDetails,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            DmExpandedDetails(
                relayIcons = relayIcons,
                reactions = message.reactions,
                eventRepo = eventRepo,
                isSent = isSent,
                resolvedEmojis = resolvedEmojis
            )
        }

        // --- Emoji picker popup ---
        if (showEmojiPicker) {
            EmojiReactionPopup(
                onSelect = { emoji ->
                    onReact(message, emoji)
                    showEmojiPicker = false
                },
                onDismiss = { showEmojiPicker = false },
                resolvedEmojis = resolvedEmojis,
                unicodeEmojis = unicodeEmojis,
                onOpenEmojiLibrary = onOpenEmojiLibrary?.let { { showEmojiPicker = false; it() } }
            )
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

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun formatTime(epoch: Long): String {
    return timeFormat.format(Date(epoch * 1000))
}

/** In-memory LRU cache for decrypted media bitmaps. ~32 MB max. */
private val decryptedBitmapCache = LruCache<String, Bitmap>(32)

private val mediaHttpClient by lazy {
    com.antz.app.relay.HttpClientFactory.createHttpClient(
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
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Encrypted image",
                    modifier = Modifier
                        .widthIn(max = 256.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
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

