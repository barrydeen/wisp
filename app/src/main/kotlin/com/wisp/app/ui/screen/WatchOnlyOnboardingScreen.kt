package com.wisp.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.viewmodel.FeedViewModel
import kotlinx.coroutines.delay

private enum class WatchOnlyStep { INFO, WAITING }

@Composable
fun WatchOnlyOnboardingScreen(
    feedViewModel: FeedViewModel,
    onReady: () -> Unit
) {
    BackHandler { /* disable back during onboarding */ }

    val feed by feedViewModel.feed.collectAsState()
    val backgroundReady = feed.size >= 5

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentStep = WatchOnlyStep.entries[stepIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "watch-only-step"
        ) { step ->
            when (step) {
                WatchOnlyStep.INFO -> WatchOnlyInfoStep(
                    onContinue = { stepIndex = WatchOnlyStep.WAITING.ordinal }
                )
                WatchOnlyStep.WAITING -> WatchOnlyWaitingStep(
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
private fun WatchOnlyInfoStep(onContinue: () -> Unit) {
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
            Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Watch-only mode",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "You're signed in with a public key. You can read posts, follow accounts, and browse the network — but posting, reacting, and zapping require a private key.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start watching")
            }
        }
    }
}

@Composable
private fun WatchOnlyWaitingStep(
    backgroundReady: Boolean,
    onReady: () -> Unit
) {
    var minTimeElapsed by remember { mutableStateOf(false) }
    var hasNavigated by remember { mutableStateOf(false) }

    val messages = listOf(
        "Finding relays…",
        "Mapping the network…",
        "Loading your feed…",
        "Almost there…"
    )
    var messageIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(1500)
        minTimeElapsed = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }

    LaunchedEffect(backgroundReady, minTimeElapsed) {
        if (hasNavigated) return@LaunchedEffect
        if (backgroundReady && minTimeElapsed) {
            hasNavigated = true
            onReady()
        }
    }

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
                    text = "You're all set",
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
                    text = "Setting things up…",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                AnimatedContent(
                    targetState = messages[messageIndex],
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "watch-waiting-msg"
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
