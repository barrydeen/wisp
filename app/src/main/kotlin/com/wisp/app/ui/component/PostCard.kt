package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
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
    userReactionEmoji: String? = null,
    onRepost: () -> Unit = {},
    onQuote: () -> Unit = {},
    onZap: () -> Unit = {},
    likeCount: Int = 0,
    replyCount: Int = 0,
    zapSats: Long = 0,
    isZapAnimating: Boolean = false,
    eventRepo: EventRepository? = null,
    relayIcons: List<String> = emptyList(),
    repostedBy: String? = null,
    reactionDetails: Map<String, List<String>> = emptyMap(),
    zapDetails: List<Pair<String, Long>> = emptyList(),
    onNavigateToProfileFromDetails: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
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

    val hasDetails = reactionDetails.isNotEmpty() || zapDetails.isNotEmpty()
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
                modifier = Modifier.clickable(onClick = onProfileClick)
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
                    Text(
                        text = nip05,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(6.dp))
        RichContent(
            content = event.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            eventRepo = eventRepo,
            onProfileClick = onNavigateToProfile
        )
        ActionBar(
            onReply = onReply,
            onReact = onReact,
            userReactionEmoji = userReactionEmoji,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = onZap,
            likeCount = likeCount,
            replyCount = replyCount,
            zapSats = zapSats,
            isZapAnimating = isZapAnimating
        )
        if (hasDetails) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedDetails = !expandedDetails },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expandedDetails) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expandedDetails) "Collapse details" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expandedDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val profileResolver: (String) -> ProfileData? = { pubkey ->
                    eventRepo?.getProfileData(pubkey)
                }
                val navToProfile = onNavigateToProfileFromDetails ?: onNavigateToProfile ?: {}
                ReactionDetailsSection(
                    reactionDetails = reactionDetails,
                    zapDetails = zapDetails,
                    resolveProfile = profileResolver,
                    onProfileClick = navToProfile
                )
            }
        }
        if (displayIcons.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row {
                    displayIcons.forEachIndexed { index, iconUrl ->
                        RelayIcon(
                            url = iconUrl,
                            modifier = Modifier
                                .zIndex((displayIcons.size - index).toFloat())
                                .offset(x = (-8 * index).dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
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
