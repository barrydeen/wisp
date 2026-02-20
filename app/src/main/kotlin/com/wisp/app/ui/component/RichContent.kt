package com.wisp.app.ui.component

import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.toHex
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.repo.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private sealed interface ContentSegment {
    data class TextSegment(val text: String) : ContentSegment
    data class ImageSegment(val url: String) : ContentSegment
    data class VideoSegment(val url: String) : ContentSegment
    data class LinkSegment(val url: String) : ContentSegment
    data class NostrNoteSegment(val eventId: String, val relayHints: List<String> = emptyList()) : ContentSegment
    data class NostrProfileSegment(val pubkey: String) : ContentSegment
}

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")
private val videoExtensions = setOf("mp4", "mov", "webm")

private val combinedRegex = Regex("""nostr:(note1|nevent1|npub1|nprofile1)[a-z0-9]+|(?<!\w)(npub1[a-z0-9]{58})(?!\w)|https?://\S+""", RegexOption.IGNORE_CASE)

private fun parseContent(content: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    var lastEnd = 0

    for (match in combinedRegex.findAll(content)) {
        if (match.range.first > lastEnd) {
            segments.add(ContentSegment.TextSegment(content.substring(lastEnd, match.range.first)))
        }
        val token = match.value
        if (token.startsWith("nostr:")) {
            when (val decoded = Nip19.decodeNostrUri(token)) {
                is NostrUriData.NoteRef -> segments.add(ContentSegment.NostrNoteSegment(decoded.eventId, decoded.relays))
                is NostrUriData.ProfileRef -> segments.add(ContentSegment.NostrProfileSegment(decoded.pubkey))
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
            val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
            when {
                ext in imageExtensions -> segments.add(ContentSegment.ImageSegment(url))
                ext in videoExtensions -> segments.add(ContentSegment.VideoSegment(url))
                else -> segments.add(ContentSegment.LinkSegment(url))
            }
        }
        lastEnd = match.range.last + 1
    }

    if (lastEnd < content.length) {
        segments.add(ContentSegment.TextSegment(content.substring(lastEnd)))
    }

    return segments
}

@Composable
fun RichContent(
    content: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    eventRepo: EventRepository? = null,
    onProfileClick: ((String) -> Unit)? = null,
    onNoteClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val segments = parseContent(content)
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    if (fullScreenImageUrl != null) {
        FullScreenImageViewer(
            imageUrl = fullScreenImageUrl!!,
            onDismiss = { fullScreenImageUrl = null }
        )
    }

    Column(modifier = modifier) {
        for (segment in segments) {
            when (segment) {
                is ContentSegment.TextSegment -> {
                    val trimmed = segment.text.trim()
                    if (trimmed.isNotEmpty()) {
                        Text(text = segment.text, style = style, color = color)
                    }
                }
                is ContentSegment.ImageSegment -> {
                    AsyncImage(
                        model = segment.url,
                        contentDescription = "Image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { fullScreenImageUrl = segment.url }
                    )
                }
                is ContentSegment.VideoSegment -> {
                    InlineVideoPlayer(
                        url = segment.url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
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
                            onNoteClick = onNoteClick
                        )
                    } else {
                        Text(
                            text = "nostr:${segment.eventId.take(8)}...",
                            style = style,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is ContentSegment.NostrProfileSegment -> {
                    val profile = eventRepo?.let { repo ->
                        val version by repo.quotedEventVersion.collectAsState()
                        remember(segment.pubkey, version) { repo.getProfileData(segment.pubkey) }
                    }
                    val displayName = profile?.displayString
                        ?: "${segment.pubkey.take(8)}...${segment.pubkey.takeLast(4)}"
                    Text(
                        text = "@$displayName",
                        style = style,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onProfileClick != null) {
                            Modifier.clickable { onProfileClick(segment.pubkey) }
                        } else Modifier
                    )
                }
            }
        }
    }
}

@Composable
fun QuotedNote(eventId: String, eventRepo: EventRepository, relayHints: List<String> = emptyList(), onNoteClick: ((String) -> Unit)? = null) {
    // Observe version so we recompose when quoted events arrive from relays
    val version by eventRepo.quotedEventVersion.collectAsState()
    val event = remember(eventId, version) { eventRepo.getEvent(eventId) }
    val profile = remember(event, version) { event?.let { eventRepo.getProfileData(it.pubkey) } }

    // Trigger on-demand fetch if the quoted event isn't cached
    LaunchedEffect(eventId) {
        if (eventRepo.getEvent(eventId) == null) {
            eventRepo.requestQuotedEvent(eventId, relayHints)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (onNoteClick != null) Modifier.clickable { onNoteClick(eventId) }
                else Modifier
            )
    ) {
        if (event != null) {
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
        } else {
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

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private val ogTagRegex = Regex(
    """<meta[^>]+property\s*=\s*["']og:(\w+)["'][^>]+content\s*=\s*["']([^"']*)["'][^>]*/?>|<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]+property\s*=\s*["']og:(\w+)["'][^>]*/?>""",
    RegexOption.IGNORE_CASE
)

private val titleTagRegex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

private suspend fun fetchOgData(url: String): OgData? = withContext(Dispatchers.IO) {
    ogCache.get(url)?.let { return@withContext it }
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
