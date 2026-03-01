package com.wisp.app.ui.component

import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.toHex
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.util.MediaDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Bundles event-generic action callbacks so quoted notes can render
 * a full PostCard (action bar, triple-dot menu, expandable details, etc.).
 */
data class NoteActions(
    val onReply: (NostrEvent) -> Unit = {},
    val onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    val onRepost: (NostrEvent) -> Unit = {},
    val onQuote: (NostrEvent) -> Unit = {},
    val onZap: (NostrEvent) -> Unit = {},
    val onProfileClick: (String) -> Unit = {},
    val onNoteClick: (String) -> Unit = {},
    val onAddToList: (String) -> Unit = {},
    val onFollowAuthor: (String) -> Unit = {},
    val onBlockAuthor: (String) -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onDelete: (String, Int) -> Unit = { _, _ -> },
    val isFollowing: (String) -> Boolean = { false },
    val userPubkey: String? = null,
    val nip05Repo: Nip05Repository? = null,
    val onHashtagClick: ((String) -> Unit)? = null,
    val onRelayClick: ((String) -> Unit)? = null,
)

private sealed interface ContentSegment {
    data class TextSegment(val text: String) : ContentSegment
    data class ImageSegment(val url: String) : ContentSegment
    data class VideoSegment(val url: String) : ContentSegment
    data class LinkSegment(val url: String) : ContentSegment
    data class InlineLinkSegment(val url: String) : ContentSegment
    data class NostrNoteSegment(val eventId: String, val relayHints: List<String> = emptyList()) : ContentSegment
    data class NostrProfileSegment(val pubkey: String) : ContentSegment
    data class NostrAddressableSegment(val dTag: String, val relays: List<String>, val author: String?, val kind: Int?) : ContentSegment
    data class CustomEmojiSegment(val shortcode: String, val url: String) : ContentSegment
    data class HashtagSegment(val tag: String) : ContentSegment
}

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")
private val videoExtensions = setOf("mp4", "mov", "webm")

private val combinedRegex = Regex("""nostr:(note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]+|(?<!\w)(npub1[a-z0-9]{58})(?!\w)|(?:https?|wss?)://\S+|(?<!\w)([a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.(?:com|net|org|io|dev|app|pro|ai|co|me|info|xyz|cc|tv|to|gg|sh|im|is|it|rs|ly|site|online|store|tech|cloud|social|world|earth|space|lol|wtf|family|life|art|design|blog|news|live|video|media|chat|games|money|finance|agency|studio|build|run|codes|systems|network|zone|pub|blue|limo|fyi|wiki|page|link|click|exchange|markets|fun|club|today)(?:/\S*)?)(?!\w)|(?<!\w)#([a-zA-Z0-9_][a-zA-Z0-9_-]*)""", RegexOption.IGNORE_CASE)

private val emojiShortcodeRegex = Regex(""":([a-zA-Z0-9_]+):""")

private fun isStandaloneUrl(content: String, matchRange: IntRange): Boolean {
    // Look back: only whitespace between previous newline (or start) and match start
    val before = content.substring(0, matchRange.first)
    val lineStart = before.lastIndexOf('\n') + 1
    val prefix = before.substring(lineStart)
    if (prefix.isNotBlank()) return false

    // Look forward: only whitespace between match end and next newline (or end)
    val after = content.substring(matchRange.last + 1)
    val lineEnd = after.indexOf('\n')
    val suffix = if (lineEnd == -1) after else after.substring(0, lineEnd)
    if (suffix.isNotBlank()) return false

    return true
}

private fun parseContent(content: String, emojiMap: Map<String, String> = emptyMap()): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    var lastEnd = 0

    for (match in combinedRegex.findAll(content)) {
        if (match.range.first > lastEnd) {
            segments.add(ContentSegment.TextSegment(content.substring(lastEnd, match.range.first)))
        }
        val token = match.value
        val bareDomainCapture = match.groupValues.getOrNull(3)
        val hashtagCapture = match.groupValues.getOrNull(4)
        if (!hashtagCapture.isNullOrEmpty() && token.startsWith("#")) {
            segments.add(ContentSegment.HashtagSegment(hashtagCapture))
        } else if (!bareDomainCapture.isNullOrEmpty() && !token.startsWith("http")) {
            val url = "https://$bareDomainCapture"
            val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
            when {
                ext in imageExtensions -> segments.add(ContentSegment.ImageSegment(url))
                ext in videoExtensions -> segments.add(ContentSegment.VideoSegment(url))
                else -> segments.add(ContentSegment.LinkSegment(url))
            }
        } else if (token.startsWith("nostr:")) {
            when (val decoded = Nip19.decodeNostrUri(token)) {
                is NostrUriData.NoteRef -> segments.add(ContentSegment.NostrNoteSegment(decoded.eventId, decoded.relays))
                is NostrUriData.ProfileRef -> segments.add(ContentSegment.NostrProfileSegment(decoded.pubkey))
                is NostrUriData.AddressRef -> segments.add(ContentSegment.NostrAddressableSegment(decoded.dTag, decoded.relays, decoded.author, decoded.kind))
                null -> segments.add(ContentSegment.TextSegment(token))
            }
        } else if (token.startsWith("npub1", ignoreCase = true)) {
            val pubkey = try { Nip19.npubDecode(token).toHex() } catch (_: Exception) { null }
            if (pubkey != null) {
                segments.add(ContentSegment.NostrProfileSegment(pubkey))
            } else {
                segments.add(ContentSegment.TextSegment(token))
            }
        } else {
            val url = token.trimEnd('.', ',', ')', ']', ';', ':', '!', '?')
            val isWebSocket = url.startsWith("wss://") || url.startsWith("ws://")
            val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
            when {
                ext in imageExtensions -> segments.add(ContentSegment.ImageSegment(url))
                ext in videoExtensions -> segments.add(ContentSegment.VideoSegment(url))
                isWebSocket -> segments.add(ContentSegment.InlineLinkSegment(url))
                isStandaloneUrl(content, match.range) -> segments.add(ContentSegment.LinkSegment(url))
                else -> segments.add(ContentSegment.InlineLinkSegment(url))
            }
        }
        lastEnd = match.range.last + 1
    }

    if (lastEnd < content.length) {
        segments.add(ContentSegment.TextSegment(content.substring(lastEnd)))
    }

    // Second pass: split TextSegments that contain :shortcode: where shortcode is in emojiMap
    if (emojiMap.isEmpty()) return segments
    val result = mutableListOf<ContentSegment>()
    for (segment in segments) {
        if (segment is ContentSegment.TextSegment) {
            result.addAll(splitTextForEmojis(segment.text, emojiMap))
        } else {
            result.add(segment)
        }
    }
    return result
}

private fun splitTextForEmojis(text: String, emojiMap: Map<String, String>): List<ContentSegment> {
    val result = mutableListOf<ContentSegment>()
    var lastEnd = 0
    for (match in emojiShortcodeRegex.findAll(text)) {
        val shortcode = match.groupValues[1]
        val url = emojiMap[shortcode] ?: continue
        if (match.range.first > lastEnd) {
            result.add(ContentSegment.TextSegment(text.substring(lastEnd, match.range.first)))
        }
        result.add(ContentSegment.CustomEmojiSegment(shortcode, url))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        result.add(ContentSegment.TextSegment(text.substring(lastEnd)))
    } else if (lastEnd == 0 && result.isEmpty()) {
        result.add(ContentSegment.TextSegment(text))
    }
    return result
}

@Composable
fun RichContent(
    content: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    emojiMap: Map<String, String> = emptyMap(),
    plainLinks: Boolean = false,
    eventRepo: EventRepository? = null,
    onProfileClick: ((String) -> Unit)? = null,
    onNoteClick: ((String) -> Unit)? = null,
    onHashtagClick: ((String) -> Unit)? = null,
    noteActions: NoteActions? = null,
    modifier: Modifier = Modifier
) {
    val segments = parseContent(content.trimEnd('\n', '\r'), emojiMap)
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }

    if (fullScreenImageUrl != null) {
        FullScreenImageViewer(
            imageUrl = fullScreenImageUrl!!,
            onDismiss = { fullScreenImageUrl = null }
        )
    }

    if (fullScreenVideoUrl != null) {
        FullScreenVideoPlayer(
            videoUrl = fullScreenVideoUrl!!,
            onDismiss = { fullScreenVideoUrl = null }
        )
    }

    // Group segments into inline runs vs block-level items
    val groups = mutableListOf<Any>() // Either MutableList<ContentSegment> (inline run) or ContentSegment (block)
    fun isInline(s: ContentSegment) = s is ContentSegment.TextSegment ||
            s is ContentSegment.HashtagSegment ||
            s is ContentSegment.NostrProfileSegment ||
            s is ContentSegment.CustomEmojiSegment ||
            s is ContentSegment.InlineLinkSegment ||
            (plainLinks && s is ContentSegment.LinkSegment)

    for (segment in segments) {
        if (isInline(segment)) {
            val last = groups.lastOrNull()
            if (last is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                (last as MutableList<ContentSegment>).add(segment)
            } else {
                groups.add(mutableListOf(segment))
            }
        } else {
            groups.add(segment)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val effectiveHashtagClick = onHashtagClick ?: noteActions?.onHashtagClick
    val effectiveRelayClick = noteActions?.onRelayClick

    SelectionContainer {
    Column(modifier = modifier) {
        for (group in groups) {
            if (group is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val inlineSegments = group as List<ContentSegment>

                // Build profile display names for this run
                val profileNames = mutableMapOf<String, String>()
                for (seg in inlineSegments) {
                    if (seg is ContentSegment.NostrProfileSegment) {
                        val profile = eventRepo?.getProfileData(seg.pubkey)
                        profileNames[seg.pubkey] = profile?.displayString
                            ?: "${seg.pubkey.take(8)}...${seg.pubkey.takeLast(4)}"
                    }
                }

                // Check if run is only whitespace/empty text
                val hasContent = inlineSegments.any { seg ->
                    when (seg) {
                        is ContentSegment.TextSegment -> seg.text.trim().isNotEmpty()
                        else -> true
                    }
                }
                if (!hasContent) continue

                // Build annotated string for inline segments (skip custom emoji images for now)
                val hasCustomEmoji = inlineSegments.any { it is ContentSegment.CustomEmojiSegment }
                if (hasCustomEmoji) {
                    // Fall back to individual rendering for runs with custom emojis
                    for (seg in inlineSegments) {
                        when (seg) {
                            is ContentSegment.TextSegment -> {
                                if (seg.text.trim().isNotEmpty()) {
                                    Text(text = seg.text, style = style, color = color)
                                }
                            }
                            is ContentSegment.CustomEmojiSegment -> {
                                AsyncImage(
                                    model = seg.url,
                                    contentDescription = seg.shortcode,
                                    modifier = Modifier
                                        .height(with(LocalDensity.current) { style.fontSize.toDp() * 1.3f })
                                        .padding(horizontal = 1.dp)
                                )
                            }
                            else -> {}
                        }
                    }
                } else {
                    val annotated = buildAnnotatedString {
                        for (seg in inlineSegments) {
                            when (seg) {
                                is ContentSegment.TextSegment -> {
                                    withStyle(SpanStyle(color = color)) {
                                        append(seg.text)
                                    }
                                }
                                is ContentSegment.HashtagSegment -> {
                                    val tag = seg.tag
                                    withLink(
                                        LinkAnnotation.Clickable("hashtag") {
                                            effectiveHashtagClick?.invoke(tag)
                                        }
                                    ) {
                                        withStyle(SpanStyle(color = primaryColor)) {
                                            append("#${seg.tag}")
                                        }
                                    }
                                }
                                is ContentSegment.NostrProfileSegment -> {
                                    val pubkey = seg.pubkey
                                    val displayName = profileNames[seg.pubkey] ?: seg.pubkey.take(8)
                                    withLink(
                                        LinkAnnotation.Clickable("profile") {
                                            onProfileClick?.invoke(pubkey)
                                        }
                                    ) {
                                        withStyle(SpanStyle(color = primaryColor)) {
                                            append("@$displayName")
                                        }
                                    }
                                }
                                is ContentSegment.LinkSegment -> {
                                    val linkUrl = seg.url
                                    val displayUrl = linkUrl.removePrefix("https://").removePrefix("http://")
                                    withLink(
                                        LinkAnnotation.Clickable("url") {
                                            uriHandler.openUri(linkUrl)
                                        }
                                    ) {
                                        withStyle(SpanStyle(color = primaryColor)) {
                                            append(displayUrl)
                                        }
                                    }
                                }
                                is ContentSegment.InlineLinkSegment -> {
                                    val linkUrl = seg.url
                                    val isRelay = linkUrl.startsWith("wss://") || linkUrl.startsWith("ws://")
                                    val displayUrl = linkUrl
                                        .removePrefix("https://")
                                        .removePrefix("http://")
                                    withLink(
                                        LinkAnnotation.Clickable("url") {
                                            if (isRelay) {
                                                effectiveRelayClick?.invoke(linkUrl)
                                            } else {
                                                uriHandler.openUri(linkUrl)
                                            }
                                        }
                                    ) {
                                        withStyle(SpanStyle(color = primaryColor)) {
                                            append(displayUrl)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }

                    Text(text = annotated, style = style)
                }
            } else {
                val segment = group as ContentSegment
                when (segment) {
                    is ContentSegment.ImageSegment -> {
                        ImageWithContextMenu(
                            url = segment.url,
                            onFullScreen = { fullScreenImageUrl = segment.url }
                        )
                    }
                    is ContentSegment.VideoSegment -> {
                        InlineVideoPlayerWithFullscreen(
                            url = segment.url,
                            onFullScreen = { fullScreenVideoUrl = segment.url }
                        )
                    }
                    is ContentSegment.LinkSegment -> {
                        LinkPreview(url = segment.url)
                    }
                    is ContentSegment.NostrNoteSegment -> {
                        if (eventRepo != null) {
                            QuotedNote(
                                eventId = segment.eventId,
                                eventRepo = eventRepo,
                                relayHints = segment.relayHints,
                                onNoteClick = onNoteClick,
                                noteActions = noteActions
                            )
                        } else {
                            Text(
                                text = "nostr:${segment.eventId.take(8)}...",
                                style = style,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is ContentSegment.NostrAddressableSegment -> {
                        val kind = segment.kind
                        when {
                            kind == 1 || kind == 0 -> {
                                if (eventRepo != null && segment.author != null) {
                                    QuotedAddressableNote(
                                        kind = kind,
                                        dTag = segment.dTag,
                                        author = segment.author,
                                        relayHints = segment.relays,
                                        eventRepo = eventRepo,
                                        onNoteClick = onNoteClick,
                                        onProfileClick = onProfileClick,
                                        noteActions = noteActions,
                                        style = style
                                    )
                                } else {
                                    Text(
                                        text = "nostr:${segment.dTag.take(12)}...",
                                        style = style,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            kind == 30311 -> {
                                if (eventRepo != null && segment.author != null) {
                                    LiveStreamCard(
                                        dTag = segment.dTag,
                                        author = segment.author,
                                        relayHints = segment.relays,
                                        eventRepo = eventRepo,
                                        onProfileClick = onProfileClick
                                    )
                                } else {
                                    UnsupportedKindBadge(kind = kind, style = style)
                                }
                            }
                            else -> UnsupportedKindBadge(kind = kind, style = style)
                        }
                    }
                    else -> {}
                }
            }
        }
    }
    }
}

@Composable
fun QuotedNote(
    eventId: String,
    eventRepo: EventRepository,
    relayHints: List<String> = emptyList(),
    onNoteClick: ((String) -> Unit)? = null,
    noteActions: NoteActions? = null
) {
    // Observe versions so we recompose when data arrives from relays
    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(eventId, version) { eventRepo.getEvent(eventId) }
    val profile = remember(event, version) { event?.let { eventRepo.getProfileData(it.pubkey) } }

    // Trigger on-demand fetch if the quoted event isn't cached
    LaunchedEffect(eventId) {
        if (eventRepo.getEvent(eventId) == null) {
            eventRepo.requestQuotedEvent(eventId, relayHints)
        }
    }

    val effectiveNoteClick = noteActions?.onNoteClick ?: onNoteClick

    if (event != null && noteActions != null) {
        // Full PostCard rendering with all interactive features
        val reactionVersion by eventRepo.reactionVersion.collectAsState()
        val zapVersion by eventRepo.zapVersion.collectAsState()
        val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
        val repostVersion by eventRepo.repostVersion.collectAsState()

        val likeCount = remember(reactionVersion, eventId) { eventRepo.getReactionCount(eventId) }
        val replyCount = remember(replyCountVersion, eventId) { eventRepo.getReplyCount(eventId) }
        val zapSats = remember(zapVersion, eventId) { eventRepo.getZapSats(eventId) }
        val repostCount = remember(repostVersion, eventId) { eventRepo.getRepostCount(eventId) }
        val repostPubkeys = remember(repostVersion, eventId) { eventRepo.getReposterPubkeys(eventId) }
        val userEmojis = remember(reactionVersion, eventId, noteActions.userPubkey) {
            noteActions.userPubkey?.let { eventRepo.getUserReactionEmojis(eventId, it) } ?: emptySet()
        }
        val hasUserReposted = remember(repostVersion, eventId) { eventRepo.hasUserReposted(eventId) }
        val hasUserZapped = remember(zapVersion, eventId) { eventRepo.hasUserZapped(eventId) }
        val reactionDetails = remember(reactionVersion, eventId) { eventRepo.getReactionDetails(eventId) }
        val zapDetails = remember(zapVersion, eventId) { eventRepo.getZapDetails(eventId) }

        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            PostCard(
                event = event,
                profile = profile,
                onReply = { noteActions.onReply(event) },
                onProfileClick = { noteActions.onProfileClick(event.pubkey) },
                onNavigateToProfile = noteActions.onProfileClick,
                onNoteClick = { effectiveNoteClick?.invoke(eventId) },
                onReact = { emoji -> noteActions.onReact(event, emoji) },
                userReactionEmojis = userEmojis,
                onRepost = { noteActions.onRepost(event) },
                onQuote = { noteActions.onQuote(event) },
                hasUserReposted = hasUserReposted,
                repostCount = repostCount,
                onZap = { noteActions.onZap(event) },
                hasUserZapped = hasUserZapped,
                likeCount = likeCount,
                replyCount = replyCount,
                zapSats = zapSats,
                eventRepo = eventRepo,
                reactionDetails = reactionDetails,
                zapDetails = zapDetails,
                repostDetails = repostPubkeys,
                onNavigateToProfileFromDetails = noteActions.onProfileClick,
                onFollowAuthor = { noteActions.onFollowAuthor(event.pubkey) },
                onBlockAuthor = { noteActions.onBlockAuthor(event.pubkey) },
                isFollowingAuthor = noteActions.isFollowing(event.pubkey),
                isOwnEvent = event.pubkey == noteActions.userPubkey,
                nip05Repo = noteActions.nip05Repo,
                onAddToList = { noteActions.onAddToList(eventId) },
                onPin = { noteActions.onPin(eventId) },
                onQuotedNoteClick = effectiveNoteClick,
                noteActions = noteActions,
                showDivider = false
            )
        }
    } else if (event != null) {
        // Simple fallback rendering (no noteActions available)
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .then(
                    if (effectiveNoteClick != null) Modifier.clickable { effectiveNoteClick(eventId) }
                    else Modifier
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfilePicture(url = profile?.picture, size = 34)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayString
                                ?: event.pubkey.take(8) + "..." + event.pubkey.takeLast(4),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatQuotedTimestamp(event.created_at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                RichContent(
                    content = event.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    eventRepo = eventRepo
                )
            }
        }
    } else {
        // Loading state
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Loading note...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuotedAddressableNote(
    kind: Int,
    dTag: String,
    author: String,
    relayHints: List<String>,
    eventRepo: EventRepository,
    onNoteClick: ((String) -> Unit)?,
    onProfileClick: ((String) -> Unit)?,
    noteActions: NoteActions?,
    style: TextStyle
) {
    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(kind, author, dTag, version) {
        eventRepo.findAddressableEvent(kind, author, dTag)
    }

    LaunchedEffect(kind, author, dTag) {
        if (eventRepo.findAddressableEvent(kind, author, dTag) == null) {
            eventRepo.requestAddressableEvent(kind, author, dTag, relayHints)
        }
    }

    if (event != null) {
        QuotedNote(
            eventId = event.id,
            eventRepo = eventRepo,
            onNoteClick = onNoteClick,
            noteActions = noteActions
        )
    } else {
        // Loading state
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Loading note...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UnsupportedKindBadge(kind: Int?, style: TextStyle) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = if (kind != null) "Unsupported event kind: $kind" else "Unsupported event kind",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun LiveStreamCard(
    dTag: String,
    author: String,
    relayHints: List<String>,
    eventRepo: EventRepository,
    onProfileClick: ((String) -> Unit)?
) {
    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(author, dTag, version) {
        eventRepo.findAddressableEvent(30311, author, dTag)
    }
    val profile = remember(author, version) { eventRepo.getProfileData(author) }

    LaunchedEffect(author, dTag) {
        if (eventRepo.findAddressableEvent(30311, author, dTag) == null) {
            eventRepo.requestAddressableEvent(30311, author, dTag, relayHints)
        }
    }

    val title = remember(event) { event?.tags?.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) }
    val summary = remember(event) { event?.tags?.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1) }
    val image = remember(event) { event?.tags?.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1) }
    val status = remember(event) { event?.tags?.firstOrNull { it.size >= 2 && it[0] == "status" }?.get(1) }
    val streamUrl = remember(event) { event?.tags?.firstOrNull { it.size >= 2 && it[0] == "streaming" }?.get(1) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        if (event == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(14.dp).height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Loading stream...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column {
                if (streamUrl != null) {
                    InlineVideoPlayer(
                        url = streamUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                } else if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status == "live") {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFE53935),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (status == "ended") {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "ENDED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = title ?: "Live Stream",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        ProfilePicture(url = profile?.picture, size = 20)
                        Spacer(Modifier.width(6.dp))
                        val displayName = profile?.displayString
                            ?: "${author.take(8)}...${author.takeLast(4)}"
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onProfileClick != null) {
                                Modifier.clickable { onProfileClick(author) }
                            } else Modifier
                        )
                    }
                }
            }
        }
    }
}

private fun formatQuotedTimestamp(epoch: Long): String {
    val diff = System.currentTimeMillis() - epoch * 1000
    if (diff < 0) return ""
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "${seconds}s"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days == 1L -> "yesterday"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(epoch * 1000))
    }
}


@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageWithContextMenu(url: String, onFullScreen: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Box {
        AsyncImage(
            model = url,
            contentDescription = "Image",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onFullScreen,
                    onLongClick = { showMenu = true }
                )
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy URL") },
                onClick = {
                    clipboardManager.setText(AnnotatedString(url))
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Download") },
                onClick = {
                    showMenu = false
                    scope.launch { MediaDownloader.downloadMedia(context, url) }
                }
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InlineVideoPlayerWithFullscreen(url: String, onFullScreen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        InlineVideoPlayer(
            url = url,
            modifier = Modifier.fillMaxWidth()
        )
        IconButton(
            onClick = onFullScreen,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fullscreen),
                contentDescription = "Fullscreen"
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InlineVideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(url) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier.aspectRatio(16f / 9f)
    )
}

// --- Link Preview (OG tags) ---

private data class OgData(
    val title: String?,
    val description: String?,
    val image: String?,
    val siteName: String?
)

private val ogCache = LruCache<String, OgData>(200)

private val httpClient
    get() = com.wisp.app.relay.HttpClientFactory.createHttpClient(
        connectTimeoutSeconds = 5,
        readTimeoutSeconds = 5
    )

private val ogTagRegex = Regex(
    """<meta[^>]+property\s*=\s*["']og:(\w+)["'][^>]+content\s*=\s*["']([^"']*)["'][^>]*/?>|<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]+property\s*=\s*["']og:(\w+)["'][^>]*/?>""",
    RegexOption.IGNORE_CASE
)

private val titleTagRegex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

private val youtubeRegex = Regex(
    """(?:https?://)?(?:www\.)?(?:youtube\.com/(?:watch\?.*v=|shorts/|embed/|live/)|youtu\.be/)([a-zA-Z0-9_-]{11})""",
    RegexOption.IGNORE_CASE
)

private suspend fun fetchYoutubeOembed(url: String): OgData? = withContext(Dispatchers.IO) {
    try {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://www.youtube.com/oembed?url=$encoded&format=json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val videoId = youtubeRegex.find(url)?.groupValues?.get(1)
            OgData(
                title = json.optString("title").ifEmpty { null },
                description = json.optString("author_name").ifEmpty { null },
                image = videoId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }
                    ?: json.optString("thumbnail_url").ifEmpty { null },
                siteName = "YouTube"
            )
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun fetchOgData(url: String): OgData? = withContext(Dispatchers.IO) {
    ogCache.get(url)?.let { return@withContext it }
    // YouTube blocks bot User-Agents; use their oEmbed API instead
    if (youtubeRegex.containsMatchIn(url)) {
        val yt = fetchYoutubeOembed(url)
        if (yt != null) { ogCache.put(url, yt); return@withContext yt }
    }
    try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; Wisp/1.0)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("text/html", ignoreCase = true)) return@withContext null
            // Read only the first 32KB — OG tags are in <head>
            val body = response.body?.source()?.let { source ->
                source.request(32 * 1024)
                source.buffer.snapshot().utf8()
            } ?: return@withContext null

            val ogProps = mutableMapOf<String, String>()
            for (match in ogTagRegex.findAll(body)) {
                val prop = (match.groupValues[1].ifEmpty { match.groupValues[4] }).lowercase()
                val content = match.groupValues[2].ifEmpty { match.groupValues[3] }
                if (prop.isNotEmpty() && content.isNotEmpty()) {
                    ogProps.putIfAbsent(prop, content)
                }
            }

            val title = ogProps["title"]
                ?: titleTagRegex.find(body)?.groupValues?.get(1)?.trim()
            val ogData = OgData(
                title = title?.let { unescapeHtml(it) },
                description = ogProps["description"]?.let { unescapeHtml(it) },
                image = ogProps["image"],
                siteName = ogProps["site_name"]?.let { unescapeHtml(it) }
            )
            if (ogData.title != null || ogData.image != null) {
                ogCache.put(url, ogData)
                ogData
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

private fun unescapeHtml(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#x27;", "'")

@Composable
private fun LinkPreview(url: String) {
    var ogData by remember(url) { mutableStateOf(ogCache.get(url)) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(url) {
        if (ogData == null) {
            ogData = fetchOgData(url)
        }
    }

    val data = ogData
    if (data != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { uriHandler.openUri(url) }
        ) {
            Column {
                data.image?.let { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                }
                Column(modifier = Modifier.padding(12.dp)) {
                    data.siteName?.let { site ->
                        Text(
                            text = site.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } ?: run {
                        // Fall back to domain
                        val host = try {
                            Uri.parse(url).host?.removePrefix("www.")?.uppercase() ?: ""
                        } catch (_: Exception) { "" }
                        if (host.isNotEmpty()) {
                            Text(
                                text = host,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    data.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    data.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Show clickable link text while loading / if OG fetch fails
        Text(
            text = url,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clickable { uriHandler.openUri(url) }
        )
    }
}
