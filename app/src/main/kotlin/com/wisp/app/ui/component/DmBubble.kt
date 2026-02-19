package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wisp.app.repo.EventRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DmBubble(
    content: String,
    timestamp: Long,
    isSent: Boolean,
    eventRepo: EventRepository? = null,
    relayIcons: List<Pair<String, String?>> = emptyList(),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isSent) 16.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isSent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                RichContent(
                    content = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSent) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    eventRepo = eventRepo
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (relayIcons.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Box(modifier = Modifier.padding(start = 6.dp)) {
                            relayIcons.forEachIndexed { index, (relayUrl, iconUrl) ->
                                RelayIcon(
                                    iconUrl = iconUrl,
                                    relayUrl = relayUrl,
                                    size = 14.dp,
                                    modifier = Modifier.offset(x = (index * 10).dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun formatTime(epoch: Long): String {
    return timeFormat.format(Date(epoch * 1000))
}
