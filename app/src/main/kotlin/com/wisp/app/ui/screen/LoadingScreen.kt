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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.viewmodel.FeedViewModel
import com.wisp.app.viewmodel.InitLoadingState
import kotlinx.coroutines.delay

@Composable
fun LoadingScreen(
    viewModel: FeedViewModel,
    onReady: () -> Unit
) {
    val initLoadingState by viewModel.initLoadingState.collectAsState()
    val feed by viewModel.feed.collectAsState()
    val initialLoadDone by viewModel.initialLoadDone.collectAsState()

    var minTimeElapsed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }

    val pubkey = remember { viewModel.getUserPubkey() }
    val cachedProfile = remember { pubkey?.let { viewModel.profileRepo.get(it) } }

    // Use locally cached avatar file if available (instant, no network/decode overhead),
    // otherwise fall back to the remote URL
    val localAvatar = remember { pubkey?.let { viewModel.profileRepo.getLocalAvatar(it) } }

    // Sticky profile state — once we learn the profile pic/name, keep showing it
    var stickyPicture by remember { mutableStateOf(localAvatar ?: cachedProfile?.picture) }
    var stickyName by remember { mutableStateOf(cachedProfile?.displayString) }

    // Update sticky state whenever FoundProfile is emitted
    if (initLoadingState is InitLoadingState.FoundProfile) {
        val fp = initLoadingState as InitLoadingState.FoundProfile
        stickyPicture = fp.picture
        stickyName = fp.name
    }

    val showProfilePic = stickyPicture != null

    // Minimum display time
    LaunchedEffect(Unit) {
        delay(1500)
        minTimeElapsed = true
    }

    // Safety timeout
    LaunchedEffect(Unit) {
        delay(30_000)
        timedOut = true
    }

    // Navigate when ready: require Done + feed content, with a brief settle delay.
    // Early exit: if min time elapsed and we already have 5+ notes, go to feed even
    // before EOSE — notes will continue trickling in via the open subscription.
    LaunchedEffect(minTimeElapsed, initLoadingState, timedOut, feed.size, initialLoadDone) {
        val initDone = initLoadingState == InitLoadingState.Done
        val feedReady = feed.isNotEmpty()
        if (timedOut && feedReady) {
            viewModel.markLoadingComplete()
            onReady()
        } else if (timedOut && initDone) {
            // Safety: init finished but no notes arrived — don't wait forever
            viewModel.markLoadingComplete()
            onReady()
        } else if (minTimeElapsed && initDone && feedReady) {
            // Brief settle so "Done" state is visible
            delay(300)
            viewModel.markLoadingComplete()
            onReady()
        } else if (minTimeElapsed && feed.size >= 5) {
            // Early exit: enough notes to show a useful feed, don't wait for full EOSE
            viewModel.markLoadingComplete()
            onReady()
        }
    }

    val isWarmStart = initLoadingState is InitLoadingState.WarmLoading ||
        (initLoadingState is InitLoadingState.Subscribing && stickyPicture != null && cachedProfile != null) ||
        (initLoadingState is InitLoadingState.Done && stickyPicture != null && cachedProfile != null)

    // Rotating status text for warm start
    var warmMessageIndex by remember { mutableStateOf(0) }
    if (isWarmStart) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                warmMessageIndex = (warmMessageIndex + 1) % 3
            }
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
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                // Profile picture / blank circle with crossfade
                AnimatedContent(
                    targetState = showProfilePic,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "avatar"
                ) { showPic ->
                    if (showPic && stickyPicture != null) {
                        AsyncImage(
                            model = stickyPicture,
                            contentDescription = "Profile picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        )
                    }
                }

                // Show name if available (sticky — stays once set)
                if (showProfilePic && stickyName != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stickyName!!,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(32.dp))

                if (isWarmStart) {
                    // Warm start: no progress bar, just rotating status text
                    AnimatedContent(
                        targetState = warmMessageIndex,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "warm-status"
                    ) { index ->
                        val warmMessage = when (index) {
                            0 -> stringResource(R.string.loading_connecting_relays)
                            1 -> stringResource(R.string.loading_finding_posts)
                            else -> stringResource(R.string.loading_preparing_feed)
                        }
                        Text(
                            text = warmMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Cold start: determinate progress bar + status text
                    if (feed.isEmpty()) {
                        if (initLoadingState is InitLoadingState.Done) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { initLoadingProgress(initLoadingState) },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                    }

                    val (textRes, args) = initLoadingText(initLoadingState)
                    if (textRes != 0) {
                        AnimatedContent(
                            targetState = textRes to args,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "status"
                        ) { (resId, formatArgs) ->
                            Text(
                                text = stringResource(resId, *formatArgs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun initLoadingProgress(state: InitLoadingState): Float {
    return when (state) {
        is InitLoadingState.SearchingProfile -> 0.05f
        is InitLoadingState.FoundProfile -> 0.12f
        is InitLoadingState.FindingFriends -> {
            val frac = if (state.total > 0) state.found.toFloat() / state.total else 0f
            0.12f + frac * 0.33f
        }
        is InitLoadingState.DiscoveringNetwork -> {
            val frac = if (state.total > 0) state.fetched.toFloat() / state.total else 0f
            0.45f + frac * 0.35f
        }
        is InitLoadingState.ExpandingRelays -> 0.85f
        is InitLoadingState.WarmLoading -> 0f
        is InitLoadingState.Subscribing -> 1f
        is InitLoadingState.Done -> 1f
    }
}

internal fun initLoadingText(state: InitLoadingState): Pair<Int, Array<Any>> {
    return when (state) {
        is InitLoadingState.SearchingProfile -> R.string.loading_searching_profile to emptyArray()
        is InitLoadingState.FoundProfile -> 0 to emptyArray()
        is InitLoadingState.FindingFriends -> {
            if (state.total > 0) R.string.loading_finding_friends_progress to arrayOf(state.found, state.total)
            else R.string.loading_finding_friends to emptyArray()
        }
        is InitLoadingState.DiscoveringNetwork -> R.string.loading_discovering_network to arrayOf(state.fetched, state.total)
        is InitLoadingState.ExpandingRelays -> R.string.loading_expanding_relays to arrayOf(state.relayCount)
        is InitLoadingState.WarmLoading -> 0 to emptyArray()
        is InitLoadingState.Subscribing -> R.string.loading_subscribing to emptyArray()
        is InitLoadingState.Done -> R.string.loading_notes to emptyArray()
    }
}
