package com.wisp.app.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun ActionBar(
    onReply: () -> Unit,
    onReact: (String) -> Unit = {},
    userReactionEmoji: String? = null,
    onRepost: () -> Unit = {},
    onQuote: () -> Unit = {},
    onZap: () -> Unit = {},
    onBookmark: () -> Unit = {},
    isBookmarked: Boolean = false,
    likeCount: Int = 0,
    replyCount: Int = 0,
    zapSats: Long = 0,
    isZapAnimating: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }

    val zapScale by animateFloatAsState(
        targetValue = if (isZapAnimating) 1.4f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "zapScale"
    )

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
            IconButton(onClick = {
                if (userReactionEmoji == null) showEmojiPicker = true
            }) {
                if (userReactionEmoji != null) {
                    Text(
                        text = userReactionEmoji,
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
                    onDismiss = { showEmojiPicker = false }
                )
            }
        }
        if (likeCount > 0) {
            Text(
                text = likeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (userReactionEmoji != null) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { showRepostMenu = true }) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = "Repost",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onZap) {
            Icon(
                Icons.Outlined.CurrencyBitcoin,
                contentDescription = "Zaps",
                tint = if (zapSats > 0) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .scale(zapScale)
            )
        }
        if (zapSats > 0) {
            Text(
                text = formatSats(zapSats),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF9800)
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onBookmark) {
            Icon(
                if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                tint = if (isBookmarked) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
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
