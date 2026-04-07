package com.antz.app.ui.component

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antz.app.nostr.Nip29
import com.antz.app.repo.EventRepository
import com.antz.app.repo.GroupPreview

@Composable
fun GroupCard(
    relayUrl: String,
    groupId: String,
    initialMetadata: Nip29.GroupMetadata? = null,
    initialMembers: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    onFetchPreview: (suspend (String, String) -> GroupPreview?)? = null,
    eventRepo: EventRepository? = null
) {
    var metadata by remember(relayUrl, groupId) { mutableStateOf(initialMetadata) }
    var members by remember(relayUrl, groupId) { mutableStateOf(initialMembers) }

    LaunchedEffect(relayUrl, groupId) {
        if ((metadata == null || members.isEmpty()) && onFetchPreview != null) {
            val preview = onFetchPreview(relayUrl, groupId) ?: return@LaunchedEffect
            if (metadata == null) metadata = preview.metadata
            if (members.isEmpty()) members = preview.members
        }
    }

    val host = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePicture(url = metadata?.picture, size = 48)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metadata?.name ?: groupId,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    metadata?.about?.takeIf { it.isNotEmpty() }?.let { about ->
                        Text(
                            text = about,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val displayed = members.take(6)
                val overflow = members.size - displayed.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .width(((displayed.size - 1) * 14 + 20).dp)
                    ) {
                        displayed.forEachIndexed { index, pubkey ->
                            val picture = remember(pubkey) { eventRepo?.getProfileData(pubkey)?.picture }
                            Box(modifier = Modifier.offset(x = (index * 14).dp)) {
                                ProfilePicture(url = picture, size = 20)
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (overflow > 0) "and $overflow more in this chat room"
                               else "${members.size} in this chat room",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
