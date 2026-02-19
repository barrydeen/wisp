package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * Extracts a single letter from a relay URL for the fallback avatar.
 * e.g. "wss://relay.damus.io" → "D", "wss://nos.lol" → "N"
 */
private fun relayLetter(relayUrl: String): String {
    val domain = relayUrl
        .removePrefix("wss://")
        .removePrefix("ws://")
        .trimEnd('/')
        .split("/").first()
    // Use the first meaningful segment: skip "relay." prefix if present
    val label = if (domain.startsWith("relay.")) {
        domain.removePrefix("relay.")
    } else {
        domain
    }
    return label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
fun RelayIcon(
    iconUrl: String?,
    relayUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    val letter = remember(relayUrl) { relayLetter(relayUrl) }
    var showFallback by remember(iconUrl) { mutableStateOf(iconUrl == null) }

    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape)

    if (showFallback) {
        Box(
            modifier = baseModifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                fontSize = (size.value * 0.55f).sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    } else {
        AsyncImage(
            model = iconUrl,
            contentDescription = "Relay icon",
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    showFallback = true
                }
            },
            modifier = baseModifier
        )
    }
}
