package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ZapDetail
import com.wisp.app.ui.theme.WispThemeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StackedAvatarRow(
    pubkeys: List<String>,
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFollowing: ((String) -> Boolean)? = null,
    highlightFirst: Boolean = false,
    maxAvatars: Int = 5,
    onProfileLongPress: ((String) -> Unit)? = null,
    showAll: Boolean = false
) {
    if (showAll) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy((-9).dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            pubkeys.forEachIndexed { index, pubkey ->
                val profile = resolveProfile(pubkey)
                ProfilePicture(
                    url = profile?.picture,
                    size = 36,
                    showFollowBadge = isFollowing?.invoke(pubkey) ?: false,
                    highlighted = highlightFirst && index == 0,
                    onClick = { onProfileClick(pubkey) },
                    onLongPress = onProfileLongPress?.let { { it(pubkey) } },
                    modifier = Modifier.zIndex((pubkeys.size - index).toFloat())
                )
            }
        }
    } else {
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
                        onClick = { onProfileClick(pubkey) },
                        onLongPress = onProfileLongPress?.let { { it(pubkey) } },
                        modifier = Modifier
                            .zIndex((displayed.size - index).toFloat())
                            .offset(x = (27 * index).dp)
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ZapRow(
    pubkey: String,
    sats: Long,
    message: String,
    profile: ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPrivate: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = { onProfileClick(pubkey) },
                        onLongClick = onLongPress
                    )
                } else {
                    Modifier.clickable { onProfileClick(pubkey) }
                }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(
            url = profile?.picture,
            size = 30
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message.ifBlank { profile?.displayString ?: (pubkey.take(8) + "...") },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        if (isPrivate) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.wisp.app.R.drawable.ic_private_zap),
                contentDescription = "Private zap",
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF8C00)
            )
            Spacer(Modifier.width(2.dp))
        }
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
    zapDetails: List<ZapDetail>,
    repostDetails: List<String> = emptyList(),
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    eventRepo: EventRepository? = null
) {
    val sortedZaps = zapDetails.sortedByDescending { it.sats }
    val hasZaps = sortedZaps.isNotEmpty()
    val hasReactions = reactionDetails.isNotEmpty()
    val hasReposts = repostDetails.isNotEmpty()

    var inspectedZap by remember { mutableStateOf<ZapDetail?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (hasZaps) {
            sortedZaps.forEach { zap ->
                ZapRow(
                    pubkey = zap.pubkey,
                    sats = zap.sats,
                    message = zap.message,
                    profile = resolveProfile(zap.pubkey),
                    onProfileClick = onProfileClick,
                    isPrivate = zap.isPrivate,
                    onLongPress = if (zap.receiptEventId != null) {
                        { inspectedZap = zap }
                    } else null
                )
            }
        }

        if (hasZaps && (hasReactions || hasReposts)) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }

        if (hasReposts) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = WispThemeColors.repostColor
                )
                Spacer(Modifier.width(8.dp))
                StackedAvatarRow(
                    pubkeys = repostDetails,
                    resolveProfile = resolveProfile,
                    onProfileClick = onProfileClick
                )
            }
        }

        if (hasReposts && hasReactions) {
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

    if (inspectedZap != null && eventRepo != null) {
        ZapInspectorDialog(
            zapDetail = inspectedZap!!,
            eventRepo = eventRepo,
            onDismiss = { inspectedZap = null }
        )
    }
}

@Composable
fun ZapInspectorDialog(
    zapDetail: ZapDetail,
    eventRepo: EventRepository,
    onDismiss: () -> Unit
) {
    val receiptEvent = zapDetail.receiptEventId?.let { eventRepo.getEvent(it) }
    val relayUrls = zapDetail.receiptEventId?.let { eventRepo.getEventRelays(it) } ?: emptySet()

    // Parse the embedded 9734 zap request from the receipt's description tag
    val zapRequest: NostrEvent? = remember(receiptEvent) {
        receiptEvent?.tags?.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)?.let {
            try { NostrEvent.fromJson(it) } catch (_: Exception) { null }
        }
    }
    val zapRequestRelays = remember(zapRequest) {
        zapRequest?.tags?.firstOrNull { it.size >= 2 && it[0] == "relays" }?.drop(1) ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Zap Receipt Inspector",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                InfoLabel("Amount", "${zapDetail.sats} sats")
                InfoLabel("Sender", zapDetail.pubkey)

                if (zapDetail.message.isNotBlank()) {
                    InfoLabel("Message", zapDetail.message)
                }

                InfoLabel("Private", if (zapDetail.isPrivate) "Yes" else "No")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                if (receiptEvent != null) {
                    Text(
                        "Receipt (kind 9735)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    InfoLabel("Event ID", receiptEvent.id)
                    InfoLabel("Created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(receiptEvent.created_at * 1000)))
                } else {
                    Text(
                        "Receipt event not cached",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                Text(
                    "Found on relays (${relayUrls.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                if (relayUrls.isEmpty()) {
                    Text(
                        "No relay provenance tracked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    relayUrls.forEach { url ->
                        RelayUrlChip(url)
                    }
                }

                if (zapRequestRelays.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Zap request relays tag (${zapRequestRelays.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    zapRequestRelays.forEach { url ->
                        RelayUrlChip(url)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InfoLabel(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            maxLines = 1
        )
    }
}

@Composable
private fun RelayUrlChip(url: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                text = stringResource(R.string.cd_seen_on),
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

@Composable
fun ClientTagSection(
    clientName: String,
    modifier: Modifier = Modifier
) {
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
                text = stringResource(R.string.cd_sent_with),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = clientName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
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
