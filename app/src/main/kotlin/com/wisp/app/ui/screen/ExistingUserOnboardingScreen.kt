package com.wisp.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.FeedViewModel
import kotlinx.coroutines.delay

private enum class OnboardingStep {
    WELCOME, DECENTRALIZATION, LONG_PRESS_DEMO, NWC_SETUP, WAITING
}

private const val UTXO_PICTURE = "https://nostr.build/i/p/e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb/pfp/1710366908.webp"

@Composable
fun ExistingUserOnboardingScreen(
    feedViewModel: FeedViewModel,
    onReady: () -> Unit
) {
    BackHandler { /* disable back during onboarding */ }

    // Start relay discovery after a short delay so the welcome animation renders smoothly
    LaunchedEffect(Unit) {
        delay(2000)
        feedViewModel.initRelays()
    }

    val feed by feedViewModel.feed.collectAsState()

    // Ready as soon as we have enough notes to show a useful feed.
    // Notes stream in immediately from relays — no need to wait for EOSE.
    // The subscription stays open so more notes will keep arriving after we navigate.
    val backgroundReady = feed.size >= 5

    // Persist step across config changes / process death
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentStep = OnboardingStep.entries[stepIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                (fadeIn(
                    animationSpec = tween(300)
                ) + slideInHorizontally(
                    animationSpec = tween(300),
                    initialOffsetX = { it / 3 }
                )) togetherWith (fadeOut(
                    animationSpec = tween(200)
                ) + slideOutHorizontally(
                    animationSpec = tween(200),
                    targetOffsetX = { -it / 3 }
                ))
            },
            label = "onboarding-step"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onContinue = { stepIndex = OnboardingStep.DECENTRALIZATION.ordinal }
                )
                OnboardingStep.DECENTRALIZATION -> DecentralizationStep(
                    onContinue = { stepIndex = OnboardingStep.LONG_PRESS_DEMO.ordinal }
                )
                OnboardingStep.LONG_PRESS_DEMO -> LongPressDemoStep(
                    onContinue = { stepIndex = OnboardingStep.NWC_SETUP.ordinal }
                )
                OnboardingStep.NWC_SETUP -> NwcInfoStep(
                    onContinue = { stepIndex = OnboardingStep.WAITING.ordinal }
                )
                OnboardingStep.WAITING -> WaitingStep(
                    backgroundReady = backgroundReady,
                    onReady = {
                        feedViewModel.markLoadingComplete()
                        onReady()
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    // Auto-advance after 3s
    LaunchedEffect(Unit) {
        delay(3000)
        onContinue()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onContinue() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            AsyncImage(
                model = null,
                contentDescription = "Wisp logo",
                placeholder = painterResource(R.drawable.ic_launcher_foreground),
                fallback = painterResource(R.drawable.ic_launcher_foreground),
                error = painterResource(R.drawable.ic_launcher_foreground),
                modifier = Modifier.size(120.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Welcome back",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Let's get you set up",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DecentralizationStep(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Your network, your relays",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Wisp discovers which relays your friends actually use, " +
                    "then connects directly to those relays. This means faster delivery " +
                    "and fewer missed posts — no central server needed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(Modifier.height(40.dp))

            Button(onClick = onContinue) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun LongPressDemoStep(onContinue: () -> Unit) {
    var showFollowBadge by remember { mutableStateOf(false) }
    var hasLongPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Quick follow",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Long-press any profile picture to follow or unfollow",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Demo profile picture with breathing glow
            ProfilePicture(
                url = UTXO_PICTURE,
                size = 80,
                highlighted = true,
                showFollowBadge = showFollowBadge,
                onLongPress = {
                    showFollowBadge = !showFollowBadge
                    hasLongPressed = true
                }
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "utxo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(visible = !hasLongPressed) {
                Text(
                    text = "Try it — long-press the picture above",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(32.dp))

            if (hasLongPressed) {
                Button(onClick = onContinue) {
                    Text("Continue")
                }
            } else {
                OutlinedButton(onClick = onContinue) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun NwcInfoStep(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Zaps with NWC",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Wisp supports Nostr Wallet Connect, letting you send " +
                    "and receive zaps directly from your Lightning wallet. " +
                    "You can set this up anytime from the Wallet screen in settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(Modifier.height(40.dp))

            Button(onClick = onContinue) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun WaitingStep(
    backgroundReady: Boolean,
    onReady: () -> Unit
) {
    var minTimeElapsed by remember { mutableStateOf(false) }
    var hasNavigated by remember { mutableStateOf(false) }

    val messages = remember {
        listOf(
            "Mapping your social graph...",
            "Finding your friends' relays...",
            "Connecting to your network...",
            "Locating gm notes...",
            "Cheering up fiatjaf...",
            "Have fun playing with your bitcoins...",
            "This is a one time setup, lower your time preference...",
            "Almost there..."
        )
    }
    var messageIndex by remember { mutableIntStateOf(0) }

    // Minimum display time
    LaunchedEffect(Unit) {
        delay(1500)
        minTimeElapsed = true
    }

    // Rotate friendly messages
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }

    // Auto-navigate when ready
    LaunchedEffect(backgroundReady, minTimeElapsed) {
        if (hasNavigated) return@LaunchedEffect
        if (backgroundReady && minTimeElapsed) {
            hasNavigated = true
            onReady()
        }
    }

    // Safety timeout
    LaunchedEffect(Unit) {
        delay(30_000)
        if (!hasNavigated) {
            hasNavigated = true
            onReady()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (backgroundReady) {
                Text(
                    text = "You're all set!",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Button(onClick = {
                    if (!hasNavigated) {
                        hasNavigated = true
                        onReady()
                    }
                }) {
                    Text("Let's go")
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Setting things up",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                AnimatedContent(
                    targetState = messages[messageIndex],
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "waiting-msg"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
