package com.wisp.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.sin

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
    modifier: Modifier = Modifier
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onReply) {
            Icon(
                Icons.AutoMirrored.Outlined.Reply,
                contentDescription = "Reply",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (replyCount > 0) {
            Text(
                text = replyCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { showEmojiPicker = true }) {
                val displayEmoji = userReactionEmojis.firstOrNull()
                if (displayEmoji != null) {
                    Text(
                        text = displayEmoji,
                        fontSize = 20.sp
                    )
                } else {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = "React",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            if (showEmojiPicker) {
                EmojiReactionPopup(
                    onSelect = onReact,
                    onDismiss = { showEmojiPicker = false },
                    selectedEmojis = userReactionEmojis
                )
            }
        }
        if (likeCount > 0) {
            Text(
                text = likeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (userReactionEmojis.isNotEmpty()) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { showRepostMenu = true }) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = "Repost",
                    tint = if (hasUserReposted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (repostCount > 0) {
            Text(
                text = repostCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasUserReposted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = onZap, enabled = !isZapInProgress) {
                if (isZapInProgress) {
                    LightningAnimation(modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        Icons.Outlined.CurrencyBitcoin,
                        contentDescription = "Zaps",
                        tint = if (hasUserZapped) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = if (hasUserZapped) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onAddToList) {
            Icon(
                if (isInList) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "Add to List",
                tint = if (isInList) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LightningAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lightning")

    val glowAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val flicker by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flicker"
    )

    val jitter by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "jitter"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val jitterX = sin(jitter) * w * 0.04f

        val boltPath = Path().apply {
            moveTo(w * 0.55f + jitterX, h * 0.05f)
            lineTo(w * 0.35f + jitterX, h * 0.42f)
            lineTo(w * 0.52f + jitterX, h * 0.42f)
            lineTo(w * 0.40f + jitterX, h * 0.95f)
            lineTo(w * 0.70f + jitterX, h * 0.48f)
            lineTo(w * 0.53f + jitterX, h * 0.48f)
            lineTo(w * 0.65f + jitterX, h * 0.05f)
            close()
        }

        // Outer glow
        drawPath(
            path = boltPath,
            color = Color(0xFFFFD54F).copy(alpha = glowAlpha * 0.4f),
            style = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Mid glow
        drawPath(
            path = boltPath,
            color = Color(0xFFFF9800).copy(alpha = glowAlpha * 0.6f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Bolt fill — flickers between orange and bright yellow
        val boltColor = if (flicker > 0.5f) Color(0xFFFFE082) else Color(0xFFFF9800)
        drawPath(path = boltPath, color = boltColor)

        // White-hot core highlight
        drawPath(
            path = boltPath,
            color = Color.White.copy(alpha = glowAlpha * 0.5f * if (flicker > 0.7f) 1f else 0f)
        )
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
                    Text("Retweet")
                }
                TextButton(onClick = onQuote) {
                    Text("Quote")
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
