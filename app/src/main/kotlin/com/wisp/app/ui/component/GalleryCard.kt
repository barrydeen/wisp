package com.wisp.app.ui.component

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip68
import com.wisp.app.nostr.Nip71
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.Nip05Status
import com.wisp.app.repo.ZapDetail
import com.wisp.app.util.MediaDownloader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GALLERY_KINDS = setOf(20, 21, 22)

private val galleryDateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private val galleryDateTimeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

@Composable
fun GalleryCard(
    event: NostrEvent,
    profile: ProfileData?,
    onReply: () -> Unit = {},
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
    repostPubkeys: List<String> = emptyList(),
    reactionDetails: Map<String, List<String>> = emptyMap(),
    zapDetails: List<ZapDetail> = emptyList(),
    repostDetails: List<String> = emptyList(),
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onNavigateToProfileFromDetails: ((String) -> Unit)? = null,
    onFollowAuthor: () -> Unit = {},
    onBlockAuthor: () -> Unit = {},
    isFollowingAuthor: Boolean = false,
    isOwnEvent: Boolean = false,
    nip05Repo: Nip05Repository? = null,
    onQuotedNoteClick: ((String) -> Unit)? = null,
    noteActions: NoteActions? = null,
    onAddToList: () -> Unit = {},
    isInList: Boolean = false,
    onPin: () -> Unit = {},
    isPinned: Boolean = false,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    val title = remember(event.id) {
        Nip68.getTitle(event) ?: Nip71.getTitle(event)
    }

    val contentWarning = remember(event.id) {
        event.tags.firstOrNull { it.size >= 1 && it[0] == "content-warning" }
    }
    var contentRevealed by remember { mutableStateOf(false) }

    // Parse media entries
    val imageEntries = remember(event.id) { Nip68.parseImetaEntries(event) }
    val videoEntries = remember(event.id) { Nip71.parseVideoMeta(event) }

    // If no imeta tags, fall back to PostCard
    if (imageEntries.isEmpty() && videoEntries.isEmpty()) {
        PostCard(
            event = event,
            profile = profile,
            onReply = onReply,
            onProfileClick = onProfileClick,
            onNavigateToProfile = onNavigateToProfile,
            onNoteClick = onNoteClick,
            onReact = onReact,
            userReactionEmojis = userReactionEmojis,
            onRepost = onRepost,
            onQuote = onQuote,
            hasUserReposted = hasUserReposted,
            repostCount = repostCount,
            onZap = onZap,
            hasUserZapped = hasUserZapped,
            likeCount = likeCount,
            replyCount = replyCount,
            zapSats = zapSats,
            isZapAnimating = isZapAnimating,
            isZapInProgress = isZapInProgress,
            eventRepo = eventRepo,
            relayIcons = relayIcons,
            repostPubkeys = repostPubkeys,
            reactionDetails = reactionDetails,
            zapDetails = zapDetails,
            repostDetails = repostDetails,
            reactionEmojiUrls = reactionEmojiUrls,
            resolvedEmojis = resolvedEmojis,
            unicodeEmojis = unicodeEmojis,
            onOpenEmojiLibrary = onOpenEmojiLibrary,
            onNavigateToProfileFromDetails = onNavigateToProfileFromDetails,
            onFollowAuthor = onFollowAuthor,
            onBlockAuthor = onBlockAuthor,
            isFollowingAuthor = isFollowingAuthor,
            isOwnEvent = isOwnEvent,
            nip05Repo = nip05Repo,
            onQuotedNoteClick = onQuotedNoteClick,
            noteActions = noteActions,
            onAddToList = onAddToList,
            isInList = isInList,
            onPin = onPin,
            isPinned = isPinned,
            onDelete = onDelete,
            modifier = modifier,
            showDivider = showDivider
        )
        return
    }

    val displayName = remember(event.pubkey, profile?.displayString) {
        profile?.displayString
            ?: event.pubkey.take(8) + "..." + event.pubkey.takeLast(4)
    }
    val timestamp = remember(event.created_at) { formatGalleryTimestamp(event.created_at) }

    var fullScreenInitialPage by remember { mutableIntStateOf(-1) }
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenVideoStartMs by remember { mutableStateOf(0L) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onNoteClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Author header — matches PostCard exactly
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
                                contentDescription = stringResource(R.string.cd_verified),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        if (status == Nip05Status.ERROR) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_retry_verification),
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
                        text = stringResource(R.string.post_pow_x, powBits),
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
                        contentDescription = stringResource(R.string.cd_more_options),
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
                            text = { Text(if (isFollowingAuthor) stringResource(R.string.btn_unfollow) else stringResource(R.string.btn_follow)) },
                            onClick = {
                                menuExpanded = false
                                onFollowAuthor()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_block)) },
                            onClick = {
                                menuExpanded = false
                                onBlockAuthor()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_add_to_list)) },
                        onClick = {
                            menuExpanded = false
                            onAddToList()
                        }
                    )
                    if (isOwnEvent) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) stringResource(R.string.btn_unpin_from_profile) else stringResource(R.string.btn_pin_to_profile)) },
                            onClick = {
                                menuExpanded = false
                                onPin()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_share)) },
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
                        text = { Text(stringResource(R.string.btn_copy_note_id)) },
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
                        text = { Text(stringResource(R.string.btn_copy_note_json)) },
                        onClick = {
                            menuExpanded = false
                            clipboardManager.setText(AnnotatedString(event.toJson()))
                        }
                    )
                }
            }
        }

        // Title
        if (!title.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        // Content warning overlay
        if (contentWarning != null && !contentRevealed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { contentRevealed = true },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Sensitive content",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tap to reveal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (event.kind == 20 && imageEntries.isNotEmpty()) {
            // Picture event — always use pager for swipe support
            val firstDim = imageEntries.firstOrNull()?.dim
            val aspectRatio = (parseAspectRatio(firstDim) ?: (4f / 3f)).coerceIn(0.5f, 2.5f)
            val pagerState = rememberPagerState(pageCount = { imageEntries.size })
            Column {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(12.dp))
                ) { page ->
                    val entry = imageEntries[page]
                    AsyncImage(
                        model = entry.url,
                        contentDescription = entry.alt ?: title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { fullScreenInitialPage = page }
                    )
                }
                if (imageEntries.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        repeat(imageEntries.size) { index ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                }
            }
        } else if (videoEntries.isNotEmpty()) {
            // Video event (kind 21/22) — use inline video player, same as kind 1 feed
            val video = videoEntries[0]
            InlineVideoPlayerWithFullscreen(
                url = video.url,
                onFullScreen = { positionMs ->
                    fullScreenVideoUrl = video.url
                    fullScreenVideoStartMs = positionMs
                }
            )
        }

        // Description (event.content)
        if (event.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Text(
                text = event.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }

        Spacer(Modifier.height(4.dp))

        // Action bar
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
            likeCount = likeCount,
            replyCount = replyCount,
            zapSats = zapSats,
            isZapAnimating = isZapAnimating,
            isZapInProgress = isZapInProgress,
            resolvedEmojis = resolvedEmojis,
            unicodeEmojis = unicodeEmojis,
            onOpenEmojiLibrary = onOpenEmojiLibrary
        )
    }

    if (showDivider) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }

    // Full-screen gallery viewer (images)
    if (fullScreenInitialPage >= 0) {
        FullScreenGalleryViewer(
            imageEntries = imageEntries,
            caption = event.content.takeIf { it.isNotBlank() },
            initialPage = fullScreenInitialPage,
            onDismiss = { fullScreenInitialPage = -1 }
        )
    }

    // Full-screen video player (uses existing ExoPlayer-based player)
    if (fullScreenVideoUrl != null) {
        FullScreenVideoPlayer(
            videoUrl = fullScreenVideoUrl!!,
            startPositionMs = fullScreenVideoStartMs,
            onDismiss = { fullScreenVideoUrl = null }
        )
    }
}

@Composable
fun FullScreenGalleryViewer(
    imageEntries: List<Nip68.ImetaEntry>,
    caption: String?,
    initialPage: Int = 0,
    onDismiss: () -> Unit
) {
    if (imageEntries.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, imageEntries.size - 1),
            pageCount = { imageEntries.size }
        )

        val buttonColors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Swipeable image pager with pinch-to-zoom
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = imageEntries[page]

                AsyncImage(
                    model = entry.url,
                    contentDescription = entry.alt ?: "Image ${page + 1} of ${imageEntries.size}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        )
                )
            }

            // Page counter (e.g. "2 / 5")
            if (imageEntries.size > 1) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${imageEntries.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Page indicator dots
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (caption != null) 60.dp else 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(imageEntries.size) { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage)
                                        Color.White
                                    else
                                        Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }

            // Current image URL for download/copy
            val currentUrl = imageEntries.getOrNull(pagerState.currentPage)?.url

            // Top-right buttons
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                if (currentUrl != null) {
                    IconButton(
                        onClick = { scope.launch { MediaDownloader.downloadMedia(context, currentUrl) } },
                        colors = buttonColors,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "Download"
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(currentUrl)) },
                        colors = buttonColors,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = onDismiss,
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Caption strip at bottom
            if (caption != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun parseAspectRatio(dim: String?): Float? {
    if (dim == null) return null
    val parts = dim.split("x")
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull() ?: return null
    val h = parts[1].toFloatOrNull() ?: return null
    if (h == 0f) return null
    return w / h
}

private fun formatGalleryTimestamp(epoch: Long): String {
    val now = System.currentTimeMillis()
    val millis = epoch * 1000
    val diff = now - millis

    if (diff < 0) return galleryDateTimeFormat.format(Date(millis))

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
        galleryDateTimeYearFormat.format(date)
    } else {
        galleryDateTimeFormat.format(date)
    }
}

fun isGalleryEvent(event: NostrEvent): Boolean = event.kind in GALLERY_KINDS
