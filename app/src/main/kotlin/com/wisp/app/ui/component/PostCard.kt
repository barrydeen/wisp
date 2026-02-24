package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.Nip30
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.Nip05Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PostCard(
    event: NostrEvent,
    profile: ProfileData?,
    onReply: () -> Unit,
    onProfileClick: () -> Unit = {},
    onNavigateToProfile: ((String) -> Unit)? = null,
    onNoteClick: () -> Unit = {},
    onReact: (String) -> Unit = {},
    userReactionEmojis: Set<String> = emptySet(),
    onRepost: () -> Unit = {},
    onQuote: () -> Unit = {},
    hasUserReposted: Boolean = false,
    repostCount: Int = 0,
    onZap: () -> Unit = {},
    hasUserZapped: Boolean = false,
    likeCount: Int = 0,
    replyCount: Int = 0,
    zapSats: Long = 0,
    isZapAnimating: Boolean = false,
    isZapInProgress: Boolean = false,
    eventRepo: EventRepository? = null,
    relayIcons: List<Pair<String, String?>> = emptyList(),
    onRelayClick: (String) -> Unit = {},
    repostedBy: String? = null,
    reactionDetails: Map<String, List<String>> = emptyMap(),
    zapDetails: List<Triple<String, Long, String>> = emptyList(),
    onNavigateToProfileFromDetails: ((String) -> Unit)? = null,
    onFollowAuthor: () -> Unit = {},
    onBlockAuthor: () -> Unit = {},
    isFollowingAuthor: Boolean = false,
    isOwnEvent: Boolean = false,
    nip05Repo: Nip05Repository? = null,
    onAddToList: () -> Unit = {},
    isInList: Boolean = false,
    onPin: () -> Unit = {},
    isPinned: Boolean = false,
    onQuotedNoteClick: ((String) -> Unit)? = null,
    noteActions: NoteActions? = null,
    repostDetails: List<String> = emptyList(),
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onManageEmojis: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    val displayName = remember(event.pubkey, profile?.displayString) {
        profile?.displayString
            ?: event.pubkey.take(8) + "..." + event.pubkey.takeLast(4)
    }

    val timestamp = remember(event.created_at) {
        formatTimestamp(event.created_at)
    }

    // Avoid allocating a new list on every recomposition when we already have <= 5 icons
    val displayIcons = remember(relayIcons) {
        if (relayIcons.size <= 5) relayIcons else relayIcons.take(5)
    }

    val hasReactionDetails = reactionDetails.isNotEmpty() || zapDetails.isNotEmpty() || repostDetails.isNotEmpty()
    val hasDetails = hasReactionDetails || displayIcons.isNotEmpty()
    var expandedDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onNoteClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (repostedBy != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$repostedBy retweeted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(
                url = profile?.picture,
                showFollowBadge = isFollowingAuthor && !isOwnEvent,
                onClick = onProfileClick,
                onLongPress = if (!isOwnEvent) onFollowAuthor else null
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable(onClick = onProfileClick)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                profile?.nip05?.let { nip05 ->
                    nip05Repo?.checkOrFetch(event.pubkey, nip05)
                    val status = nip05Repo?.getStatus(event.pubkey)
                    val isImpersonator = status == Nip05Status.IMPERSONATOR
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isImpersonator) "\u2715 $nip05" else nip05,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isImpersonator) Color.Red else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (status == Nip05Status.VERIFIED) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                tint = Color(0xFFFF8C00),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        if (status == Nip05Status.ERROR) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry verification",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { nip05Repo?.retry(event.pubkey) }
                            )
                        }
                    }
                }
            }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (!isOwnEvent) {
                        DropdownMenuItem(
                            text = { Text(if (isFollowingAuthor) "Unfollow" else "Follow") },
                            onClick = {
                                menuExpanded = false
                                onFollowAuthor()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Block") },
                            onClick = {
                                menuExpanded = false
                                onBlockAuthor()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Add to List") },
                        onClick = {
                            menuExpanded = false
                            onAddToList()
                        }
                    )
                    if (isOwnEvent) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Unpin from Profile" else "Pin to Profile") },
                            onClick = {
                                menuExpanded = false
                                onPin()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuExpanded = false
                            try {
                                val nevent = Nip19.neventEncode(event.id.hexToByteArray())
                                val url = "https://njump.me/$nevent"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            } catch (_: Exception) {}
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy Note ID") },
                        onClick = {
                            menuExpanded = false
                            try {
                                val relays = eventRepo?.getEventRelays(event.id)?.take(3)?.toList() ?: emptyList()
                                val neventId = Nip19.neventEncode(
                                    eventId = event.id.hexToByteArray(),
                                    relays = relays,
                                    author = event.pubkey.hexToByteArray()
                                )
                                clipboardManager.setText(AnnotatedString(neventId))
                            } catch (_: Exception) {}
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy Note JSON") },
                        onClick = {
                            menuExpanded = false
                            clipboardManager.setText(AnnotatedString(event.toJson()))
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // Collapsible content with max height (~1 viewport)
        val collapsedMaxHeight = 500.dp
        var contentExpanded by remember { mutableStateOf(false) }
        var contentExceedsMax by remember { mutableStateOf(false) }
        val density = LocalDensity.current

        Box {
            Box(
                modifier = Modifier
                    .then(
                        if (!contentExpanded) Modifier.heightIn(max = collapsedMaxHeight) else Modifier
                    )
                    .clipToBounds()
                    .onGloballyPositioned { coordinates ->
                        if (!contentExpanded) {
                            val maxPx = with(density) { collapsedMaxHeight.toPx() }
                            contentExceedsMax = coordinates.size.height >= maxPx.toInt()
                        }
                    }
            ) {
                val emojiMap = remember(event.id) { Nip30.parseEmojiTags(event) }
                RichContent(
                    content = event.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    emojiMap = emojiMap,
                    eventRepo = eventRepo,
                    onProfileClick = onNavigateToProfile,
                    onNoteClick = onQuotedNoteClick,
                    noteActions = noteActions
                )
            }

            // Gradient fade overlay when collapsed and content overflows
            if (contentExceedsMax && !contentExpanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )
            }
        }

        if (contentExceedsMax) {
            TextButton(
                onClick = { contentExpanded = !contentExpanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = if (contentExpanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBar(
                onReply = onReply,
                onReact = onReact,
                userReactionEmojis = userReactionEmojis,
                onRepost = onRepost,
                onQuote = onQuote,
                hasUserReposted = hasUserReposted,
                repostCount = repostCount,
                onZap = onZap,
                hasUserZapped = hasUserZapped,
                onAddToList = onAddToList,
                isInList = isInList,
                likeCount = likeCount,
                replyCount = replyCount,
                zapSats = zapSats,
                isZapAnimating = isZapAnimating,
                isZapInProgress = isZapInProgress,
                reactionEmojiUrls = reactionEmojiUrls,
                resolvedEmojis = resolvedEmojis,
                unicodeEmojis = unicodeEmojis,
                onManageEmojis = onManageEmojis,
                modifier = Modifier.weight(1f)
            )
            if (hasDetails) {
                Icon(
                    imageVector = if (expandedDetails) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expandedDetails) "Collapse details" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { expandedDetails = !expandedDetails }
                )
            }
        }
        if (hasDetails) {
            AnimatedVisibility(
                visible = expandedDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val profileResolver: (String) -> ProfileData? = { pubkey ->
                    eventRepo?.getProfileData(pubkey)
                }
                val navToProfile = onNavigateToProfileFromDetails ?: onNavigateToProfile ?: {}
                Column {
                    if (hasReactionDetails) {
                        ReactionDetailsSection(
                            reactionDetails = reactionDetails,
                            zapDetails = zapDetails,
                            repostDetails = repostDetails,
                            resolveProfile = profileResolver,
                            onProfileClick = navToProfile,
                            reactionEmojiUrls = reactionEmojiUrls
                        )
                    }
                    if (displayIcons.isNotEmpty()) {
                        SeenOnSection(relayIcons = displayIcons, onRelayClick = onRelayClick)
                    }
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        }
    }
}

private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/**
 * Format an epoch timestamp into a relative or absolute time string.
 * Avoids Calendar allocations — uses simple arithmetic for "yesterday" check.
 */
private fun formatTimestamp(epoch: Long): String {
    val now = System.currentTimeMillis()
    val millis = epoch * 1000
    val diff = now - millis

    if (diff < 0) return dateTimeFormat.format(Date(millis))

    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    if (seconds < 60) return "${seconds}s"
    if (minutes < 60) return "${minutes}m"
    if (hours < 24) return "${hours}h"

    val days = diff / (24 * 60 * 60 * 1000L)
    if (days == 1L) return "yesterday"

    return dateTimeFormat.format(Date(millis))
}
