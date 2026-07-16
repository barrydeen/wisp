package cooking.zap.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.viewmodel.PlannerViewModel

/**
 * DEBUG-only PR 7 planner stop-gate dialog. Tag-shape audit, decrypt
 * round-trip (unknown-field survival), save+reload smoke.
 */
@Composable
fun PlannerStopGateDialog(
    signer: NostrSigner?,
    plannerVm: PlannerViewModel?,
    onPlantUndecryptable: (() -> Unit)? = null,
    onPlantReadOnly: (() -> Unit)? = null,
    onCleanupPlanted: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PR7 Planner stop-gate") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Smoke: recipe+text slots → save → refresh. " +
                        "Audit logcat tag PR7-PLANNER. Pantry must not receive events.",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Button(
                    onClick = { PlannerStopGateHarness.tagShapeAudit() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("1. Tag shape (d + client only)") }
                Button(
                    onClick = {
                        signer?.let { PlannerStopGateHarness.decryptRoundTrip(it, scope) }
                    },
                    enabled = signer != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("2. Decrypt round-trip + unknown field") }
                Button(
                    onClick = {
                        plannerVm?.let { PlannerStopGateHarness.smokeSaveAndReload(it, scope) }
                    },
                    enabled = plannerVm != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("3. Save week + reload smoke") }
                Button(
                    onClick = { onPlantUndecryptable?.invoke() },
                    enabled = onPlantUndecryptable != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("4. Plant undecryptable (current week)") }
                Button(
                    onClick = { onPlantReadOnly?.invoke() },
                    enabled = onPlantReadOnly != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("5. Plant read-only v2 (current week)") }
                Button(
                    onClick = { onCleanupPlanted?.invoke() },
                    enabled = onCleanupPlanted != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("6. Clean up planted event") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
