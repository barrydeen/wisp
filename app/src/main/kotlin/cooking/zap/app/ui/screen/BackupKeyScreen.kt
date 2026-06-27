package cooking.zap.app.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.ui.component.PrivateKeyRevealSection
import cooking.zap.app.ui.component.PublicKeyCard

/**
 * Backup step shown right after a NEW key is generated (and re-shown later via the
 * persistent nudge). Mirrors the web LoginOverlay's "Save your backup key" step:
 * the key is presented before profile setup, masked behind a reveal toggle, with
 * copy + download-to-file. Unlike the web it is skippable — deferring keeps the
 * backup need alive (see KeyBackupPreferences) rather than blocking progress.
 */
@Composable
fun BackupKeyScreen(
    keyRepository: KeyRepository,
    avatarUrl: String? = null,
    onSaved: () -> Unit,
    onSkip: () -> Unit,
    onBackupExported: () -> Unit = {}
) {
    val context = LocalContext.current
    val keypair = remember { keyRepository.getKeypair() }
    val pubkeyHex = remember { keyRepository.getPubkeyHex() }
    val npub = remember {
        pubkeyHex?.let { Nip19.npubEncode(it.hexToByteArray()) }
            ?: keypair?.let { Nip19.npubEncode(it.pubkey) }
    }

    val downloadSavedMsg = stringResource(R.string.backup_key_downloaded)
    val downloadFailedMsg = stringResource(R.string.backup_key_download_failed)

    // SAF document picker — the user chooses where the backup file lands; the nsec
    // is written straight to that stream, never to a shared cache directory.
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null || keypair == null || npub == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(buildBackupFileContent(npub, Nip19.nsecEncode(keypair.privkey)).toByteArray())
            } ?: error("no output stream")
        }.isSuccess
        Toast.makeText(
            context,
            if (ok) downloadSavedMsg else downloadFailedMsg,
            Toast.LENGTH_SHORT
        ).show()
        // A successful export is a real backup — clear the nudge (the user can still
        // tap "I've saved it" / "Skip" to leave the screen).
        if (ok) onBackupExported()
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.backup_key_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_key_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // Private key — the thing to actually save.
            Text(
                text = stringResource(R.string.backup_key_private_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PrivateKeyRevealSection(
                keypair = keypair,
                avatarUrl = avatarUrl,
                showHide = true,
                showWarning = false
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    createDocLauncher.launch(suggestedBackupFileName())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.backup_key_download))
            }

            Spacer(Modifier.height(24.dp))

            // Public key — safe to share.
            Text(
                text = stringResource(R.string.backup_key_public_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PublicKeyCard(npub = npub, pubkeyHex = pubkeyHex, avatarUrl = avatarUrl)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.backup_key_npub_safe),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // Honest, non-alarmist statement of the stakes.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.backup_key_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaved,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_key_saved))
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_key_skip))
                }
            }
        }
    }
}

/** `zapcooking-keys-YYYY-MM-DD.txt`, matching the web backup filename. */
private fun suggestedBackupFileName(): String {
    val now = java.time.LocalDate.now()
    val date = "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    return "zapcooking-keys-$date.txt"
}

/** Verbatim parity with the web LoginOverlay `downloadKeysBackup()` file contents. */
private fun buildBackupFileContent(npub: String, nsec: String): String = buildString {
    appendLine("Zap Cooking Nostr Backup")
    appendLine()
    appendLine("Public key (npub): $npub")
    appendLine()
    appendLine("Private key (nsec): $nsec")
    appendLine()
    appendLine("Keep this file safe:")
    appendLine("- Do not share your private key.")
    appendLine("- Store in a secure password manager or offline storage.")
    appendLine("- You can restore your profile in any Nostr client.")
    appendLine("- Zap Cooking: https://zap.cooking")
    appendLine("- Anyone with this file can access your profile.")
}
