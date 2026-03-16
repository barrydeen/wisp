package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Surface
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.hexToByteArray
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import com.wisp.app.nostr.Nip30
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import com.wisp.app.nostr.Nip88
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.ZapDetail
import com.wisp.app.repo.Nip05Status
import com.wisp.app.repo.TranslationState
import com.wisp.app.repo.TranslationStatus
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
    repostPubkeys: List<String> = emptyList(),
    repostTime: Long? = null,
    reactionDetails: Map<String, List<String>> = emptyMap(),
    zapDetails: List<ZapDetail> = emptyList(),
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
    onDelete: () -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    noteActions: NoteActions? = null,
    repostDetails: List<String> = emptyList(),
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onMuteThread: (() -> Unit)? = null,
    pollVoteCounts: Map<String, Int> = emptyMap(),
    pollTotalVotes: Int = 0,
    userPollVotes: List<String> = emptyList(),
    onPollVote: (List<String>) -> Unit = {},
    translationState: TranslationState = TranslationState(),
    onTranslate: () -> Unit = {},
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

    val contentWarning = remember(event.id) {
        event.tags.firstOrNull { it.size >= 1 && it[0] == "content-warning" }
    }
    var contentRevealed by remember { mutableStateOf(false) }

    val clientName = remember(event.id) {
        event.tags.firstOrNull { it.size >= 2 && it[0] == "client" }?.get(1)
    }

    val hasReactionDetails = reactionDetails.isNotEmpty() || zapDetails.isNotEmpty() || repostDetails.isNotEmpty()
    var expandedDetails by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onNoteClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (repostPubkeys.isNotEmpty()) {
            val maxAvatars = 10
            val displayPubkeys = repostPubkeys.take(maxAvatars)
            val overflow = repostPubkeys.size - maxAvatars
            val formattedRepostTime = repostTime?.let { formatTimestamp(it) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))

                // Overlapping avatars
                Box(modifier = Modifier.height(20.dp).width((displayPubkeys.size * 14 + 6 + 4).dp)) {
                    displayPubkeys.forEachIndexed { index, pubkey ->
                        val avatarUrl = eventRepo?.getProfileData(pubkey)?.picture
                        Box(modifier = Modifier.offset(x = (index * 14).dp)) {
                            ProfilePicture(
                                url = avatarUrl,
                                size = 20,
                                showFollowBadge = false,
                                onClick = { onNavigateToProfileFromDetails?.invoke(pubkey) }
                            )
                        }
                    }
                }

                // Label text
                val labelText = if (repostPubkeys.size == 1) {
                    val name = eventRepo?.getProfileData(repostPubkeys.first())?.displayString
                        ?: (repostPubkeys.first().take(8) + "...")
                    "$name retweeted"
                } else if (overflow > 0) {
                    "and $overflow others retweeted"
                } else {
                    "retweeted"
                }
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (formattedRepostTime != null) {
                    Text(
                        text = " \u00B7 $formattedRepostTime",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onProfileClick)
                )
                profile?.nip05?.let { nip05 ->
                    nip05Repo?.checkOrFetch(event.pubkey, nip05)
                    val status = nip05Repo?.getStatus(event.pubkey)
                    val isImpersonator = status == Nip05Status.IMPERSONATOR
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onProfileClick)) {
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
                                tint = MaterialTheme.colorScheme.primary,
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
            val powBits = remember(event.id) { Nip13.verifyDifficulty(event) }
            if (powBits >= 16) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "PoW $powBits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
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
                    if (onMuteThread != null) {
                        DropdownMenuItem(
                            text = { Text("Mute Thread") },
                            onClick = {
                                menuExpanded = false
                                onMuteThread()
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
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
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
                    val translating = translationState.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
                        translationState.status == TranslationStatus.DOWNLOADING_MODEL ||
                        translationState.status == TranslationStatus.TRANSLATING
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    translationState.status == TranslationStatus.DONE && showTranslation -> "Show Original"
                                    translationState.status == TranslationStatus.DONE && !showTranslation -> "Show Translation"
                                    translationState.status == TranslationStatus.SAME_LANGUAGE -> "Same Language"
                                    else -> "Translate"
                                }
                            )
                        },
                        enabled = !translating && translationState.status != TranslationStatus.SAME_LANGUAGE,
                        onClick = {
                            menuExpanded = false
                            if (translationState.status == TranslationStatus.DONE) {
                                showTranslation = !showTranslation
                            } else {
                                onTranslate()
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (contentWarning != null && !contentRevealed) {
            // Content warning overlay
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { contentRevealed = true }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp)
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = "Content warning",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    val reason = contentWarning.getOrNull(1)?.takeIf { it.isNotBlank() }
                    Text(
                        text = reason ?: "Sensitive content",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap to reveal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Normal content display
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
                        },
                    contentAlignment = Alignment.TopStart
                ) {
                    val emojiMap = remember(event.id) { Nip30.parseEmojiTags(event) }
                    val imetaMap = remember(event.id) { parseImetaTags(event) }
                    RichContent(
                        content = event.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        emojiMap = emojiMap,
                        imetaMap = imetaMap,
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

            // Hide button to re-collapse CW content
            if (contentWarning != null) {
                TextButton(
                    onClick = { contentRevealed = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "Hide",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Inline translation display
            when (translationState.status) {
                TranslationStatus.IDENTIFYING_LANGUAGE,
                TranslationStatus.DOWNLOADING_MODEL,
                TranslationStatus.TRANSLATING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = when (translationState.status) {
                                TranslationStatus.IDENTIFYING_LANGUAGE -> "Detecting language..."
                                TranslationStatus.DOWNLOADING_MODEL -> "Downloading language model..."
                                else -> "Translating..."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TranslationStatus.DONE -> {
                    if (showTranslation) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                thickness = 0.5.dp
                            )
                            Text(
                                text = "Translated from ${translationState.sourceLanguage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                            val emojiMap = remember(event.id) { Nip30.parseEmojiTags(event) }
                            val imetaMap = remember(event.id) { parseImetaTags(event) }
                            RichContent(
                                content = translationState.translatedText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                emojiMap = emojiMap,
                                imetaMap = imetaMap,
                                eventRepo = eventRepo,
                                onProfileClick = onNavigateToProfile,
                                onNoteClick = onQuotedNoteClick,
                                noteActions = noteActions
                            )
                        }
                    }
                }
                TranslationStatus.ERROR -> {
                    Text(
                        text = translationState.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                else -> {}
            }

            // Poll section
            if (event.kind == Nip88.KIND_POLL) {
                PollSection(
                    event = event,
                    voteCounts = pollVoteCounts,
                    totalVotes = pollTotalVotes,
                    userVotes = userPollVotes,
                    onVote = onPollVote
                )
            }

            // Top zapper banner
            if (zapDetails.isNotEmpty()) {
                val topZap = remember(zapDetails) {
                    zapDetails.maxByOrNull { it.sats }
                }
                if (topZap != null) {
                    val zapperProfile = eventRepo?.getProfileData(topZap.pubkey)
                    val zapperName = zapperProfile?.displayString
                        ?: (topZap.pubkey.take(8) + "...")
                    TopZapperBanner(
                        avatarUrl = zapperProfile?.picture,
                        name = zapperName,
                        sats = topZap.sats,
                        message = topZap.message,
                        onClick = {
                            val nav = onNavigateToProfileFromDetails ?: onNavigateToProfile
                            nav?.invoke(topZap.pubkey)
                        }
                    )
                }
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
                onOpenEmojiLibrary = onOpenEmojiLibrary,
                modifier = Modifier.weight(1f)
            )
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
                        reactionEmojiUrls = reactionEmojiUrls,
                        eventRepo = eventRepo
                    )
                }
                if (displayIcons.isNotEmpty()) {
                    SeenOnSection(relayIcons = displayIcons, onRelayClick = onRelayClick)
                }
                if (clientName != null) {
                    ClientTagSection(clientName = clientName)
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun PollSection(
    event: NostrEvent,
    voteCounts: Map<String, Int>,
    totalVotes: Int,
    userVotes: List<String>,
    onVote: (List<String>) -> Unit
) {
    val options = remember(event.id) { Nip88.parsePollOptions(event) }
    val pollType = remember(event.id) { Nip88.parsePollType(event) }
    val isEnded = remember(event.id) { Nip88.isPollEnded(event) }
    val hasVoted = userVotes.isNotEmpty()
    val showResults = hasVoted || isEnded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        if (showResults) {
            // Results mode
            options.forEach { option ->
                val count = voteCounts[option.id] ?: 0
                val percentage = if (totalVotes > 0) count.toFloat() / totalVotes else 0f
                val isUserChoice = option.id in userVotes
                PollResultRow(
                    label = option.label,
                    percentage = percentage,
                    count = count,
                    isUserChoice = isUserChoice
                )
            }
            Text(
                text = "$totalVotes vote${if (totalVotes != 1) "s" else ""}${if (isEnded) " · Poll ended" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            // Voting mode
            if (pollType == Nip88.PollType.SINGLECHOICE) {
                options.forEach { option ->
                    PollOptionRow(
                        label = option.label,
                        selected = false,
                        isRadio = true,
                        onClick = { onVote(listOf(option.id)) }
                    )
                }
            } else {
                // Multiplechoice — track local selection and submit
                var selected by remember { mutableStateOf(setOf<String>()) }
                options.forEach { option ->
                    PollOptionRow(
                        label = option.label,
                        selected = option.id in selected,
                        isRadio = false,
                        onClick = {
                            selected = if (option.id in selected) selected - option.id
                            else selected + option.id
                        }
                    )
                }
                if (selected.isNotEmpty()) {
                    TextButton(
                        onClick = { onVote(selected.toList()) }
                    ) {
                        Text("Vote")
                    }
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    label: String,
    selected: Boolean,
    isRadio: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (isRadio) {
                    if (selected) "◉" else "○"
                } else {
                    if (selected) "☑" else "☐"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PollResultRow(
    label: String,
    percentage: Float,
    count: Int,
    isUserChoice: Boolean
) {
    val animatedFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = percentage.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
        label = "pollBar"
    )
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

    val fillHeight = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .onGloballyPositioned { fillHeight.intValue = it.size.height }
    ) {
        // Filled bar — primary color for all options
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = if (animatedFraction > 0f) animatedFraction else 0.001f)
                .height(with(density) { fillHeight.intValue.toDp() })
                .background(color = barColor)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (isUserChoice) {
                Text(
                    text = "✓ ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(percentage * 100).toInt()}% ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopZapperBanner(
    avatarUrl: String?,
    name: String,
    sats: Long,
    message: String,
    onClick: () -> Unit
) {
    val orange = Color(0xFFFF9800)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = orange.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(50)
                )
                .clickable(onClick = onClick)
                .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
        ) {
            // Zapper avatar
            ProfilePicture(
                url = avatarUrl,
                size = 18,
                onClick = onClick
            )
            Spacer(Modifier.width(5.dp))

            // Bitcoin icon
            Icon(
                Icons.Outlined.CurrencyBitcoin,
                contentDescription = null,
                tint = orange,
                modifier = Modifier.size(16.dp)
            )

            // Amount
            Text(
                text = formatZapAmount(sats),
                style = MaterialTheme.typography.labelSmall,
                color = orange
            )

            // Message (if present)
            if (message.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "\u201C${message}\u201D",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatZapAmount(sats: Long): String = when {
    sats >= 1_000_000 -> String.format("%.1fM", sats / 1_000_000.0)
    sats >= 1_000 -> String.format("%.1fk", sats / 1_000.0)
    else -> "$sats"
}

private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private val dateTimeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

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

    val date = Date(millis)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    val dateYear = cal.get(java.util.Calendar.YEAR)

    return if (dateYear != currentYear) {
        dateTimeYearFormat.format(date)
    } else {
        dateTimeFormat.format(date)
    }
}
