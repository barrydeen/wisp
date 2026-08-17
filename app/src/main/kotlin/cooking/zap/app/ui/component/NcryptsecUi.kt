package cooking.zap.app.ui.component

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip49
import cooking.zap.app.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NIP-49 (`ncryptsec`) surfaces shared by every screen that touches keys:
 *
 *  - [NcryptsecUnlockDialog] — the password prompt shown when someone signs in with an
 *    `ncryptsec1…` instead of an `nsec1…`. Drop it into any login surface that drives
 *    [AuthViewModel]; it renders only while a pending ncryptsec is parked in the view model.
 *  - [EncryptedKeyExportSection] — "export my key, encrypted with a password", used by the
 *    Keys screen and the post-signup backup step.
 *
 * Both sides run scrypt off the main thread and show progress: at the default log_n of 16
 * it needs 64 MiB and takes noticeably longer on a phone than on the spec's "fast computer".
 */

/** Minimum export password length. Short passwords are what make an ncryptsec crackable. */
private const val MIN_EXPORT_PASSWORD_LENGTH = 8

/**
 * Password prompt for a pending `ncryptsec1…` login. Renders nothing until
 * [AuthViewModel.pendingNcryptsec] is set, so it is safe to place unconditionally.
 *
 * @param onUnlocked invoked after the key is decrypted and the account is active.
 */
@Composable
fun NcryptsecUnlockDialog(
    viewModel: AuthViewModel,
    onUnlocked: () -> Unit
) {
    val pending by viewModel.pendingNcryptsec.collectAsState()
    val unlocking by viewModel.unlockingNcryptsec.collectAsState()
    val error by viewModel.error.collectAsState()

    if (pending == null) return

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Don't leave the password sitting in composition state after the prompt closes.
    DisposableEffect(Unit) {
        onDispose { password = "" }
    }

    val submit: () -> Unit = submit@{
        if (unlocking) return@submit
        viewModel.unlockPendingNcryptsec(password) { ok ->
            if (ok) {
                password = ""
                onUnlocked()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!unlocking) viewModel.cancelPendingNcryptsec() },
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.ncryptsec_unlock_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ncryptsec_unlock_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ncryptsec_password_label)) },
                    singleLine = true,
                    enabled = !unlocking,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.auth_hide_key else R.string.auth_show_key
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (unlocking) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.ncryptsec_unlocking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !unlocking && password.isNotEmpty()) {
                Text(stringResource(R.string.ncryptsec_unlock_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.cancelPendingNcryptsec() }, enabled = !unlocking) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * "Export encrypted key" button plus its dialog. Unlike the plain nsec reveal, the
 * exported blob is useless without the password, which is what makes it the safe thing
 * to put in cloud storage or a password manager.
 *
 * @param npub used only to label the saved file; the file's secret is the ncryptsec.
 * @param onExportedToFile fired after the encrypted key is successfully written to a file —
 *   that is a real backup, same as the plaintext export.
 */
@Composable
fun EncryptedKeyExportSection(
    keypair: Keys.Keypair?,
    npub: String?,
    avatarUrl: String? = null,
    onExportedToFile: () -> Unit = {}
) {
    if (keypair == null) return

    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    val authTitle = stringResource(R.string.ncryptsec_export_title)
    val authDescription = stringResource(R.string.settings_authenticate_view_key)

    OutlinedButton(
        onClick = {
            authenticateForKeyAccess(context, authTitle, authDescription) { showDialog = true }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ncryptsec_export_action))
    }

    if (showDialog) {
        EncryptedKeyExportDialog(
            keypair = keypair,
            npub = npub,
            avatarUrl = avatarUrl,
            onExportedToFile = onExportedToFile,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun EncryptedKeyExportDialog(
    keypair: Keys.Keypair,
    npub: String?,
    avatarUrl: String?,
    onExportedToFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ncryptsec by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }

    // Clear both the password and the encrypted key from composition state on close.
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            confirm = ""
            ncryptsec = null
        }
    }

    val tooShortMsg = stringResource(R.string.ncryptsec_password_too_short, MIN_EXPORT_PASSWORD_LENGTH)
    val mismatchMsg = stringResource(R.string.ncryptsec_password_mismatch)
    val failedMsg = stringResource(R.string.ncryptsec_export_failed)
    val copiedMsg = stringResource(R.string.ncryptsec_copied)
    val savedMsg = stringResource(R.string.backup_key_downloaded)
    val saveFailedMsg = stringResource(R.string.backup_key_download_failed)

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val encrypted = ncryptsec
        if (uri == null || encrypted == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(buildEncryptedBackupFileContent(npub, encrypted).toByteArray())
            } ?: throw IllegalStateException("no output stream")
        }.isSuccess
        Toast.makeText(context, if (ok) savedMsg else saveFailedMsg, Toast.LENGTH_SHORT).show()
        if (ok) onExportedToFile()
    }

    val encrypt: () -> Unit = encrypt@{
        if (working) return@encrypt
        if (password.length < MIN_EXPORT_PASSWORD_LENGTH) {
            error = tooShortMsg
            return@encrypt
        }
        if (password != confirm) {
            error = mismatchMsg
            return@encrypt
        }

        error = null
        working = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { Nip49.encrypt(keypair.privkey, password) }
            }
            working = false
            result.fold(
                onSuccess = {
                    ncryptsec = it
                    // The password has done its job — don't keep it around.
                    password = ""
                    confirm = ""
                },
                onFailure = { error = failedMsg },
            )
        }
    }

    val encrypted = ncryptsec
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (encrypted == null) R.string.ncryptsec_export_title
                    else R.string.ncryptsec_export_ready_title
                )
            )
        },
        text = {
            if (encrypted == null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.ncryptsec_export_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text(stringResource(R.string.ncryptsec_password_label)) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.auth_hide_key else R.string.auth_show_key
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text(stringResource(R.string.ncryptsec_password_confirm_label)) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { encrypt() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ncryptsec_export_no_reset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (working) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.ncryptsec_encrypting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.ncryptsec_export_ready_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = encrypted,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyKeyToClipboard(context, encrypted, copiedMsg) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_copy))
                        }
                        OutlinedButton(onClick = { showQr = true }) {
                            Icon(
                                Icons.Outlined.QrCode,
                                contentDescription = stringResource(R.string.cd_show_qr_code)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { saveLauncher.launch(suggestedEncryptedBackupFileName()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ncryptsec_export_save_file))
                    }
                }
            }
        },
        confirmButton = {
            if (encrypted == null) {
                TextButton(
                    onClick = encrypt,
                    enabled = !working && password.isNotEmpty() && confirm.isNotEmpty()
                ) {
                    Text(stringResource(R.string.ncryptsec_export_encrypt_action))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_done)) }
            }
        },
        dismissButton = {
            if (encrypted == null) {
                TextButton(onClick = onDismiss, enabled = !working) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    )

    if (showQr && encrypted != null) {
        NsecQrDialog(
            nsec = encrypted,
            avatarUrl = avatarUrl,
            title = stringResource(R.string.ncryptsec_qr_title),
            warning = stringResource(R.string.ncryptsec_qr_warning),
            qrContentDescription = stringResource(R.string.cd_ncryptsec_qr_code),
            onDismiss = { showQr = false }
        )
    }
}

/** `zapcooking-encrypted-key-YYYY-MM-DD.txt`, alongside the plaintext backup filename. */
private fun suggestedEncryptedBackupFileName(): String {
    val now = java.time.LocalDate.now()
    val date = "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    return "zapcooking-encrypted-key-$date.txt"
}

/** Mirrors the plaintext backup file, but the secret half is password-protected. */
internal fun buildEncryptedBackupFileContent(npub: String?, ncryptsec: String): String = buildString {
    appendLine("Zap Cooking Nostr Backup (encrypted)")
    appendLine()
    if (npub != null) {
        appendLine("Public key (npub): $npub")
        appendLine()
    }
    appendLine("Encrypted private key (ncryptsec): $ncryptsec")
    appendLine()
    appendLine("About this file:")
    appendLine("- This key is encrypted with the password you chose (NIP-49).")
    appendLine("- Without that password it cannot be recovered — there is no password reset.")
    appendLine("- Any Nostr client that supports NIP-49 can restore your profile from it.")
    appendLine("- Zap Cooking: https://zap.cooking")
}
