package com.wisp.app.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfilePicture(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 40,
    showFollowBadge: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    if (highlighted) {
        HighlightedProfilePicture(url, modifier, size, showFollowBadge, onClick, onLongPress)
    } else {
        BaseProfilePicture(url, modifier, size, showFollowBadge, onClick, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightedProfilePicture(
    url: String?,
    modifier: Modifier,
    size: Int,
    showFollowBadge: Boolean,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    val transition = rememberInfiniteTransition(label = "highlight")

    // Breathing scale
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Glow alpha synced with scale
    val glowAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val orange = Color(0xFFFF9800)
    val glowSpread = (size * 0.2f).coerceIn(5f, 10f)

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val radius = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orange.copy(alpha = glowAlpha * 0.8f),
                            orange.copy(alpha = glowAlpha * 0.3f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius + glowSpread.dp.toPx()
                    ),
                    center = center,
                    radius = radius + glowSpread.dp.toPx()
                )
            }
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Profile picture",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .then(
                    if (onClick != null || onLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClick = onLongPress?.let { lp -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                lp()
                            }}
                        )
                    } else Modifier
                )
        )

        if (showFollowBadge) {
            FollowBadge(size, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BaseProfilePicture(
    url: String?,
    modifier: Modifier,
    size: Int,
    showFollowBadge: Boolean,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    Box(modifier = modifier) {
        AsyncImage(
            model = url,
            contentDescription = "Profile picture",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .then(
                    if (onClick != null || onLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClick = onLongPress?.let { lp -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                lp()
                            }}
                        )
                    } else Modifier
                )
        )
        if (showFollowBadge) {
            FollowBadge(size, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun FollowBadge(size: Int, modifier: Modifier = Modifier) {
    val badgeSize = (size * 0.3f).coerceIn(10f, 16f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(x = 1.dp, y = 1.dp)
            .size(badgeSize.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = "Following",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size((badgeSize * 0.65f).dp)
        )
    }
}
