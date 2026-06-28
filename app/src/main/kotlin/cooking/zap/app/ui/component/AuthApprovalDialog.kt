package cooking.zap.app.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cooking.zap.app.R

@Composable
fun AuthApprovalDialog(
    relayUrl: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.auth_dialog_title)) },
        text = {
            Text(
                text = stringResource(R.string.auth_dialog_body, relayUrl),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.auth_dialog_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.auth_dialog_deny))
            }
        }
    )
}
