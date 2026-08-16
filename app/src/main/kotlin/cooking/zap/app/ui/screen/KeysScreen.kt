package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.SigningMode
import cooking.zap.app.ui.component.EncryptedKeyExportSection
import cooking.zap.app.ui.component.PrivateKeyRevealSection
import cooking.zap.app.ui.component.PublicKeyCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    keyRepository: KeyRepository,
    onBack: () -> Unit,
    avatarUrl: String? = null
) {
    val pubkeyHex = remember { keyRepository.getPubkeyHex() }
    val keypair = remember { keyRepository.getKeypair() }
    val npub = remember {
        pubkeyHex?.let { Nip19.npubEncode(it.hexToByteArray()) }
            ?: keypair?.let { Nip19.npubEncode(it.pubkey) }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_keys)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Public key section (always shown)
            Text(
                text = stringResource(R.string.settings_public_key),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_public_key_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            PublicKeyCard(npub = npub, pubkeyHex = pubkeyHex, avatarUrl = avatarUrl)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_private_key),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (keyRepository.getSigningMode()) {
                SigningMode.REMOTE -> {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.keys_remote_signer_info),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                SigningMode.READ_ONLY -> {
                    Text(
                        text = stringResource(R.string.keys_read_only_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SigningMode.LOCAL -> {
                    PrivateKeyRevealSection(
                        keypair = keypair,
                        avatarUrl = avatarUrl
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = stringResource(R.string.backup_key_encrypted_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.backup_key_encrypted_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    EncryptedKeyExportSection(
                        keypair = keypair,
                        npub = npub,
                        avatarUrl = avatarUrl
                    )
                }
            }
        }
    }
}
