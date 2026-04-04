package com.wisp.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.R
import com.wisp.app.ui.theme.WispThemeColors
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.wisp.app.nostr.Nip30
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionBar(
    onReply: () -> Unit,
    onReact: (String) -> Unit = {},
    userReactionEmojis: Set<String> = emptySet(),
    onRepost: () -> Unit = {},
    onQuote: () -> Unit = {},
    hasUserReposted: Boolean = false,
    onZap: () -> Unit = {},
    hasUserZapped: Boolean = false,
    onAddToList: () -> Unit = {},
    isInList: Boolean = false,
    likeCount: Int = 0,
    repostCount: Int = 0,
    replyCount: Int = 0,
    zapSats: Long = 0,
    isZapAnimating: Boolean = false,
    isZapInProgress: Boolean = false,
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE) }
    val useZapBoltIcon = prefs.getBoolean("zap_bolt_icon", false)
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onReply) {
            Icon(
                Icons.Outlined.ModeComment,
                contentDescription = stringResource(R.string.cd_reply),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = replyCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Box {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp),
                        onClick = { showEmojiPicker = true },
                        onLongClick = { onReact("") }
                    )
            ) {
                val displayEmoji = userReactionEmojis.firstOrNull()
                val displayEmojiUrl = displayEmoji?.let { reactionEmojiUrls[it] ?: resolvedEmojis[it.removeSurrounding(":")] }
                if (displayEmoji != null && displayEmojiUrl != null) {
                    AsyncImage(
                        model = displayEmojiUrl,
                        contentDescription = displayEmoji,
                        modifier = Modifier.size(20.dp)
                    )
                } else if (displayEmoji != null) {
                    Text(
                        text = displayEmoji,
                        fontSize = 20.sp
                    )
                } else {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.cd_react),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            if (showEmojiPicker) {
                EmojiReactionPopup(
                    onSelect = onReact,
                    onDismiss = { showEmojiPicker = false },
                    selectedEmojis = userReactionEmojis,
                    resolvedEmojis = resolvedEmojis,
                    unicodeEmojis = unicodeEmojis,
                    onOpenEmojiLibrary = onOpenEmojiLibrary,
                    onRemoveEmoji = emojiRemoveCallback
                )
            }
        }
        Text(
            text = likeCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (userReactionEmojis.isNotEmpty()) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { showRepostMenu = true }) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = stringResource(R.string.cd_repost),
                    tint = if (hasUserReposted) WispThemeColors.repostColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (showRepostMenu) {
                RepostPopup(
                    onRepost = {
                        onRepost()
                        showRepostMenu = false
                    },
                    onQuote = {
                        onQuote()
                        showRepostMenu = false
                    },
                    onDismiss = { showRepostMenu = false }
                )
            }
        }
        Text(
            text = repostCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (hasUserReposted) WispThemeColors.repostColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = onZap, enabled = !isZapInProgress) {
                if (isZapInProgress) {
                    LightningAnimation(modifier = Modifier.size(width = 14.dp, height = 22.dp))
                } else if (useZapBoltIcon) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = stringResource(R.string.cd_zaps),
                        tint = if (hasUserZapped) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        Icons.Outlined.CurrencyBitcoin,
                        contentDescription = stringResource(R.string.cd_zaps),
                        tint = if (hasUserZapped) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Lightning burst — zero layout footprint, draws above the icon
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentSize(unbounded = true, align = Alignment.Center)
            ) {
                ZapBurstEffect(
                    isActive = isZapAnimating,
                    modifier = Modifier.size(120.dp)
                )
            }
        }
        if (!isZapInProgress && zapSats > 0) {
            Text(
                text = formatSats(zapSats),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasUserZapped) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onAddToList) {
            Icon(
                if (isInList) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(R.string.cd_add_to_list),
                tint = if (isInList) WispThemeColors.bookmarkColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun LightningAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lightning")

    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val zapColor = WispThemeColors.zapColor

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val boltPath = icBoltPath(w, h, scale)

        // Soft outer glow
        drawPath(
            path = boltPath,
            color = zapColor.copy(alpha = pulse * 0.3f),
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Bolt fill
        drawPath(path = boltPath, color = zapColor)

        // White-hot core
        drawPath(
            path = boltPath,
            color = Color.White.copy(alpha = pulse * 0.4f)
        )
    }
}

/** Builds the ic_bolt shape path scaled to the given dimensions with optional scale. */
internal fun icBoltPath(w: Float, h: Float, scale: Float = 1f): Path {
    // Original ic_bolt viewBox: 55 x 94
    // Path: M35.563,0 V40.406 H54.969 L21.016,93.75 V51.719 H0 L35.563,0 Z
    val sx = w / 55f * scale
    val sy = h / 94f * scale
    val ox = w * (1f - scale) / 2f
    val oy = h * (1f - scale) / 2f
    return Path().apply {
        moveTo(ox + 35.563f * sx, oy + 0f)
        lineTo(ox + 35.563f * sx, oy + 40.406f * sy)
        lineTo(ox + 54.969f * sx, oy + 40.406f * sy)
        lineTo(ox + 21.016f * sx, oy + 93.75f * sy)
        lineTo(ox + 21.016f * sx, oy + 51.719f * sy)
        lineTo(ox + 0f * sx, oy + 51.719f * sy)
        lineTo(ox + 35.563f * sx, oy + 0f)
        close()
    }
}

@Composable
private fun RepostPopup(
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                TextButton(onClick = onRepost) {
                    Text(stringResource(R.string.btn_retweet))
                }
                TextButton(onClick = onQuote) {
                    Text(stringResource(R.string.title_quote))
                }
            }
        }
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> String.format("%.1fM", sats / 1_000_000.0)
    sats >= 1_000 -> String.format("%.1fk", sats / 1_000.0)
    else -> sats.toString()
}
