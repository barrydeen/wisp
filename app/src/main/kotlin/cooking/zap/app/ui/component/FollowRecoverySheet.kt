package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.viewmodel.FollowListCandidate
import cooking.zap.app.viewmodel.FollowRecoveryState
import cooking.zap.app.viewmodel.FollowRecoveryViewModel
import cooking.zap.app.viewmodel.FollowScanResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val candidateDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatCandidateDate(epoch: Long): String =
    candidateDateFormat.format(Date(epoch * 1000))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowRecoverySheet(
    viewModel: FollowRecoveryViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState
    ) {
        when (val s = state) {
            FollowRecoveryState.Idle -> IdleContent(
                onScan = { viewModel.startScan() },
                onDismiss = {
                    viewModel.reset()
                    onDismiss()
                }
            )
            FollowRecoveryState.Scanning -> ScanningContent()
            is FollowRecoveryState.Results -> ResultsContent(
                result = s.result,
                onSelect = { viewModel.selectCandidate(it) },
                onScanAgain = { viewModel.startScan() }
            )
            is FollowRecoveryState.Confirming -> ConfirmingContent(
                candidate = s.candidate,
                onBack = { viewModel.backToResults() },
                onConfirm = { viewModel.confirmRestore(s.candidate) }
            )
            FollowRecoveryState.Restoring -> RestoringContent()
            is FollowRecoveryState.Done -> DoneContent(
                followCount = s.followCount,
                onDone = {
                    viewModel.reset()
                    onDismiss()
                }
            )
            is FollowRecoveryState.Error -> ErrorContent(
                message = s.message,
                onRetry = { viewModel.startScan() },
                onDismiss = {
                    viewModel.reset()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Restore Follow List",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Search your relays for older versions of your follow list and restore one if you've lost follows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan Relays for Old Versions")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Powered by",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(3.dp))
            val wordmarkSize = Modifier.height(22.dp).width(76.dp)
            val textTint = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            Box(wordmarkSize) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_mutable_wordmark_icon),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 0.5f
                )
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_mutable_wordmark_text),
                    contentDescription = "Mutable",
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = textTint,
                    alpha = 0.5f
                )
            }
        }
    }
}

@Composable
private fun ScanningContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Scanning relays…",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "This may take a few seconds",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultsContent(
    result: FollowScanResult,
    onSelect: (FollowListCandidate) -> Unit,
    onScanAgain: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "Follow List Versions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Found ${result.candidates.size} version${if (result.candidates.size != 1) "s" else ""} " +
                "across ${result.respondingRelays} relay${if (result.respondingRelays != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (result.candidates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No other versions found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            result.candidates.forEach { candidate ->
                CandidateCard(
                    candidate = candidate,
                    onRestore = { onSelect(candidate) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onScanAgain,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Scan Again")
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: FollowListCandidate,
    onRestore: () -> Unit
) {
    val borderColor = if (candidate.isRecommended)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outline

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(
            width = if (candidate.isRecommended) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${candidate.followCount} follows",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (candidate.isCurrent) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Current", style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                        if (candidate.isRecommended) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Best", style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatCandidateDate(candidate.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Found on ${candidate.foundOnRelays.size} relay${if (candidate.foundOnRelays.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!candidate.isCurrent) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onRestore,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text("Restore")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmingContent(
    candidate: FollowListCandidate,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "Restore Follow List?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "${candidate.followCount} follows",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatCandidateDate(candidate.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This will publish a new follow list with these ${candidate.followCount} accounts. " +
                "Your current follow list will be replaced.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Restore Follow List")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun RestoringContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Restoring follow list…",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun DoneContent(followCount: Int, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Follow List Restored",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$followCount follow${if (followCount != 1) "s" else ""} restored successfully.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try Again")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}
