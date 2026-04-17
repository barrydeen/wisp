package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.viewmodel.ComposeViewModel

private const val INTRO_PREFIX = "#introductions\n\n"

@Composable
fun OnboardingFirstPostScreen(
    viewModel: ComposeViewModel,
    relayPool: RelayPool,
    outboxRouter: OutboxRouter?,
    signer: NostrSigner?,
    onPosted: () -> Unit,
    onSkip: () -> Unit
) {
    val content by viewModel.content.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val countdown by viewModel.countdownSeconds.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) {
        if (!content.text.contains("#introductions")) {
            val text = INTRO_PREFIX
            viewModel.updateContent(TextFieldValue(text, TextRange(text.length)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Say hello to nostr",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onSkip, enabled = !publishing) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Post a short introduction with the #introductions hashtag — a few words about you and how you found wisp is plenty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = content,
                onValueChange = { viewModel.updateContent(it) },
                placeholder = { Text("Write your introduction...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !publishing
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = {
                    if (countdown != null) {
                        viewModel.cancelPublish()
                    } else {
                        viewModel.publish(
                            relayPool = relayPool,
                            outboxRouter = outboxRouter,
                            signer = signer,
                            onSuccess = onPosted
                        )
                    }
                },
                enabled = (countdown != null) ||
                    (!publishing && content.text.trim().isNotEmpty() && signer != null),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                val label = when {
                    countdown != null -> "Cancel — sending in ${countdown}s"
                    publishing -> "Publishing..."
                    else -> "Post introduction"
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
