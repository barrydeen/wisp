package cooking.zap.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cooking.zap.app.R
import cooking.zap.app.repo.FiatPreferences
import cooking.zap.app.ui.theme.WispThemeColors
import cooking.zap.app.ui.util.AmountFormatter
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import cooking.zap.app.nostr.Nip30
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
    onZapLongPress: (() -> Unit)? = null,
    hasUserZapped: Boolean = false,
    onAddToList: () -> Unit = {},
    onAddToListLongPress: (() -> Unit)? = null,
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
    isPrivate: Boolean = false,
    zapEnabled: Boolean = true,
    onZapDisabledTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // React first — mirrors the web action-row order
        // (react · comment · renote · zap).
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
        if (likeCount > 0) {
            Text(
                text = likeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (userReactionEmojis.isNotEmpty()) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onReply) {
            Icon(
                Icons.Outlined.ModeComment,
                contentDescription = stringResource(R.string.cd_reply),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        // Hide the count when zero — matches iOS minimalistic action bar
        // where empty metrics drop entirely instead of showing "0".
        if (replyCount > 0) {
            Text(
                text = replyCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        // React + Zap are available on private replies as gift-wrapped/DIP-03 actions.
        // Repost / Quote stay hidden on private replies because those events would
        // publicly attach an e-tag pointing at the encrypted rumor id.
        if (!isPrivate) {
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
            if (repostCount > 0) {
                Text(
                    text = repostCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUserReposted) WispThemeColors.repostColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            val zapClickable = !isZapInProgress
            val longPressFired = remember { mutableStateOf(false) }
            val haptics = LocalHapticFeedback.current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp),
                        enabled = zapClickable,
                        onClick = {
                            if (longPressFired.value) {
                                longPressFired.value = false
                            } else if (zapEnabled) {
                                onZap()
                            } else {
                                onZapDisabledTap()
                            }
                        },
                        onLongClick = if (zapEnabled && onZapLongPress != null) {
                            {
                                longPressFired.value = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onZapLongPress()
                            }
                        } else null
                    )
            ) {
                val zapTint = when {
                    !zapEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    hasUserZapped -> WispThemeColors.zapColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (isZapInProgress) {
                    LightningAnimation(modifier = Modifier.size(width = 14.dp, height = 22.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = stringResource(R.string.cd_zaps),
                        tint = zapTint,
                        modifier = Modifier.size(18.dp)
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
                    modifier = Modifier.size(160.dp)
                )
            }
        }
        if (!isZapInProgress && zapSats > 0) {
            val context = LocalContext.current
            val fiatPrefs = remember { FiatPreferences.get(context) }
            fiatPrefs.fiatMode.collectAsState().value
            fiatPrefs.currency.collectAsState().value
            Text(
                text = AmountFormatter.formatShort(zapSats, context),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasUserZapped) WispThemeColors.zapColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Single tap → default Saved toggle; long-press → list chooser (when wired).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp),
                    onClick = onAddToList,
                    onLongClick = onAddToListLongPress,
                )
        ) {
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

/**
 * Builds the ic_bolt (Phosphor "Lightning" fill) shape path scaled to the given
 * dimensions, keeping the Canvas-drawn bolt consistent with `ic_bolt.xml`.
 *
 * The points are the path corners with the 8px rounded corners flattened, taken
 * from the 256x256 viewBox path; they are normalized to the bolt's bounding box
 * (see [BOLT_ASPECT_RATIO]) so the shape fills the requested size like the icon.
 */
internal fun icBoltPath(w: Float, h: Float, scale: Float = 1f): Path {
    val sx = w / BOLT_VIEW_W * scale
    val sy = h / BOLT_VIEW_H * scale
    val ox = w * (1f - scale) / 2f
    val oy = h * (1f - scale) / 2f
    return Path().apply {
        moveTo(ox + (BOLT_POINTS[0] - BOLT_MIN_X) * sx, oy + (BOLT_POINTS[1] - BOLT_MIN_Y) * sy)
        var i = 2
        while (i < BOLT_POINTS.size) {
            lineTo(ox + (BOLT_POINTS[i] - BOLT_MIN_X) * sx, oy + (BOLT_POINTS[i + 1] - BOLT_MIN_Y) * sy)
            i += 2
        }
        close()
    }
}

private val BOLT_POINTS = floatArrayOf(
    213.85f, 125.46f,
    101.85f, 245.46f,
    88.16f, 238.46f,
    102.82f, 165.13f,
    45.19f, 143.49f,
    42.19f, 130.49f,
    154.19f, 10.49f,
    167.88f, 17.49f,
    153.18f, 90.9f,
    210.81f, 112.51f,
)
private const val BOLT_MIN_X = 42.19f
private const val BOLT_MIN_Y = 10.49f
private const val BOLT_VIEW_W = 213.85f - BOLT_MIN_X
private const val BOLT_VIEW_H = 245.46f - BOLT_MIN_Y
/** Height-to-width ratio of the bolt's bounding box, for callers that size by width. */
internal const val BOLT_ASPECT_RATIO = BOLT_VIEW_H / BOLT_VIEW_W

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

