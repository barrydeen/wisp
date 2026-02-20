package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.DiscoveryState
import com.wisp.app.ui.component.WispLogo

@Composable
fun NetworkDiscoveryScreen(
    discoveryState: DiscoveryState,
    profile: ProfileData?,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                if (profile?.picture != null) {
                    AsyncImage(
                        model = profile.picture,
                        contentDescription = "Profile picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    )
                } else {
                    WispLogo(size = 80.dp)
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Discovering Your Network",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(Modifier.height(24.dp))

                val progress = computeProgress(discoveryState)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                AnimatedContent(
                    targetState = getStatusText(discoveryState),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "status"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(32.dp))

                when (discoveryState) {
                    is DiscoveryState.Failed -> {
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                    is DiscoveryState.Complete -> {
                        // Auto-navigate handled by caller
                    }
                    else -> {
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

private fun computeProgress(state: DiscoveryState): Float {
    // Weighted 6-step progress: follow lists ~60%, compute ~5%, filter ~5%, relay lists ~20%, build ~5%, complete ~5%
    return when (state) {
        is DiscoveryState.Idle -> 0f
        is DiscoveryState.FetchingFollowLists -> {
            val frac = if (state.total > 0) state.fetched.toFloat() / state.total else 0f
            0.0f + frac * 0.60f
        }
        is DiscoveryState.ComputingNetwork -> 0.60f
        is DiscoveryState.Filtering -> 0.65f
        is DiscoveryState.FetchingRelayLists -> {
            val frac = if (state.total > 0) state.fetched.toFloat() / state.total else 0f
            0.70f + frac * 0.20f
        }
        is DiscoveryState.BuildingRelayMap -> 0.90f
        is DiscoveryState.Complete -> 1.0f
        is DiscoveryState.Failed -> 0f
    }
}

private fun getStatusText(state: DiscoveryState): String {
    return when (state) {
        is DiscoveryState.Idle -> "Preparing..."
        is DiscoveryState.FetchingFollowLists -> "Fetching follow lists... ${state.fetched}/${state.total}"
        is DiscoveryState.ComputingNetwork -> "Computing network... ${state.uniqueUsers} users found"
        is DiscoveryState.Filtering -> "Filtering... ${state.qualified} qualified"
        is DiscoveryState.FetchingRelayLists -> "Fetching relay lists... ${state.fetched}/${state.total}"
        is DiscoveryState.BuildingRelayMap -> "Building relay map..."
        is DiscoveryState.Complete -> "Found ${state.stats.qualifiedCount} people across ${state.stats.relaysCovered} relays"
        is DiscoveryState.Failed -> "Discovery failed: ${state.reason}"
    }
}
