package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
            Spacer(modifier = Modifier.height(4.dp))

            if (keyRepository.isReadOnly()) {
                Text(
                    text = "No private key is stored on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                PrivateKeyRevealSection(
                    keypair = keypair,
                    avatarUrl = avatarUrl
                )
            }
        }
    }
}
