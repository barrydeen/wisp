package com.antz.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antz.app.repo.EventRepository
import com.antz.app.repo.LiveStream

@Composable
fun LiveNowRow(
    streams: List<LiveStream>,
    eventRepo: EventRepository,
    onStreamClick: (hostPubkey: String, dTag: String, relayHint: String?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    ) {
        // LIVE badge as the first item in the row
        item(key = "live-badge") {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFE53935)
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        items(streams, key = { it.aTagValue }) { stream ->
            LiveNowPill(
                stream = stream,
                eventRepo = eventRepo,
                onClick = { onStreamClick(stream.activity.hostPubkey, stream.activity.dTag, stream.activity.relayHints.firstOrNull()) }
            )
        }
    }
}

@Composable
private fun LiveNowPill(
    stream: LiveStream,
    eventRepo: EventRepository,
    onClick: () -> Unit
) {
    val displayPubkey = stream.activity.streamerPubkey ?: stream.activity.hostPubkey
    val hostProfile = remember(displayPubkey) {
        eventRepo.getProfileData(displayPubkey)
    }
    val title = stream.activity.title
        ?: hostProfile?.displayString?.let { "$it's stream" }
        ?: "Live Stream"
    val chatterCount = stream.chatters.size

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
        ) {
            // Streamer avatar
            ProfilePicture(url = hostProfile?.picture, size = 40, onClick = null)
            Spacer(Modifier.width(10.dp))
            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
            if (chatterCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$chatterCount chatting",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
