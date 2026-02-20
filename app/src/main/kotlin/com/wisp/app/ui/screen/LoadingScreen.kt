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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.ProfileData
import com.wisp.app.ui.component.WispLogo
import com.wisp.app.viewmodel.FeedViewModel
import kotlinx.coroutines.delay

private val loadingMessages = listOf(
    "Connecting to relays...",
    "Fetching your feed...",
    "Loading profiles...",
    "Syncing messages...",
    "Almost there...",
    "Catching up on notes...",
    "Decrypting DMs...",
    "Building your timeline..."
)

@Composable
fun LoadingScreen(
    viewModel: FeedViewModel,
    onReady: () -> Unit
) {
    val feed by viewModel.feed.collectAsState()
    val initialLoadDone by viewModel.initialLoadDone.collectAsState()

    var minTimeElapsed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var messageIndex by remember { mutableIntStateOf(0) }

    val pubkey = remember { viewModel.getUserPubkey() }
    val profile = remember { pubkey?.let { viewModel.profileRepo.get(it) } }

    // Minimum display time
    LaunchedEffect(Unit) {
        delay(1500)
        minTimeElapsed = true
    }

    // Safety timeout
    LaunchedEffect(Unit) {
        delay(10_000)
        timedOut = true
    }

    // Rotate loading messages
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            messageIndex = (messageIndex + 1) % loadingMessages.size
        }
    }

    // Navigate when ready
    LaunchedEffect(minTimeElapsed, initialLoadDone, timedOut, feed.size) {
        val hasContent = feed.size >= 5 || initialLoadDone
        if (minTimeElapsed && (hasContent || timedOut)) {
            onReady()
        }
    }

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
                verticalArrangement = Arrangement.Center
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
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = profile.displayString,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                } else {
                    WispLogo(size = 80.dp)
                }

                Spacer(Modifier.height(32.dp))

                AnimatedContent(
                    targetState = loadingMessages[messageIndex],
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "status"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
