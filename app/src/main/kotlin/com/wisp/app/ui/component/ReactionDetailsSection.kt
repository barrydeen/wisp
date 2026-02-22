package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.wisp.app.nostr.ProfileData

@Composable
fun StackedAvatarRow(
    pubkeys: List<String>,
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFollowing: ((String) -> Boolean)? = null,
    highlightFirst: Boolean = false,
    maxAvatars: Int = 5
) {
    val displayed = if (pubkeys.size <= maxAvatars) pubkeys else pubkeys.take(maxAvatars)
    val overflow = pubkeys.size - displayed.size

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            displayed.forEachIndexed { index, pubkey ->
                val profile = resolveProfile(pubkey)
                ProfilePicture(
                    url = profile?.picture,
                    size = 36,
                    showFollowBadge = isFollowing?.invoke(pubkey) ?: false,
                    highlighted = highlightFirst && index == 0,
                    modifier = Modifier
                        .zIndex((displayed.size - index).toFloat())
                        .offset(x = (27 * index).dp)
                        .clickable { onProfileClick(pubkey) }
                )
            }
        }
        // Account for the stacked width
        Spacer(Modifier.width((27 * (displayed.size - 1) + 36).dp))
        if (overflow > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ZapRow(
    pubkey: String,
    sats: Long,
    message: String,
    profile: ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(
            url = profile?.picture,
            size = 30,
            modifier = Modifier.clickable { onProfileClick(pubkey) }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message.ifBlank { profile?.displayString ?: (pubkey.take(8) + "...") },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onProfileClick(pubkey) }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "\u26A1 ${formatSats(sats)}",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFFF8C00)
        )
    }
}

@Composable
fun ReactionDetailsSection(
    reactionDetails: Map<String, List<String>>,
    zapDetails: List<Triple<String, Long, String>>,
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val sortedZaps = zapDetails.sortedByDescending { it.second }
    val hasZaps = sortedZaps.isNotEmpty()
    val hasReactions = reactionDetails.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (hasZaps) {
            sortedZaps.forEach { (pubkey, sats, message) ->
                ZapRow(
                    pubkey = pubkey,
                    sats = sats,
                    message = message,
                    profile = resolveProfile(pubkey),
                    onProfileClick = onProfileClick
                )
            }
        }

        if (hasZaps && hasReactions) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }

        if (hasReactions) {
            reactionDetails.forEach { (emoji, pubkeys) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val emojiUrl = reactionEmojiUrls[emoji]
                    if (emojiUrl != null) {
                        AsyncImage(
                            model = emojiUrl,
                            contentDescription = emoji,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StackedAvatarRow(
                        pubkeys = pubkeys,
                        resolveProfile = resolveProfile,
                        onProfileClick = onProfileClick
                    )
                }
            }
        }
    }
}

@Composable
fun SeenOnSection(
    relayIcons: List<Pair<String, String?>>,
    onRelayClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxIcons: Int = 5
) {
    val displayed = if (relayIcons.size <= maxIcons) relayIcons else relayIcons.take(maxIcons)
    val overflow = relayIcons.size - displayed.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Seen on",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Box {
                displayed.forEachIndexed { index, (relayUrl, iconUrl) ->
                    RelayIcon(
                        iconUrl = iconUrl,
                        relayUrl = relayUrl,
                        size = 24.dp,
                        modifier = Modifier
                            .zIndex((displayed.size - index).toFloat())
                            .offset(x = (18 * index).dp)
                            .clickable { onRelayClick(relayUrl) }
                    )
                }
            }
            Spacer(Modifier.width((18 * (displayed.size - 1) + 24).dp))
            if (overflow > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSats(sats: Long): String {
    return when {
        sats >= 1_000_000 -> "${sats / 1_000_000}M sats"
        sats >= 1_000 -> "${sats / 1_000}K sats"
        else -> "$sats sats"
    }
}
