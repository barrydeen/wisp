package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wisp.app.nostr.Nip19
import com.wisp.app.repo.KeyRepository

private fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    keyRepository: KeyRepository,
    onBack: () -> Unit
) {
    val keypair = remember { keyRepository.getKeypair() }
    val npub = remember { keypair?.let { Nip19.npubEncode(it.pubkey) } }
    var nsec by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Clear nsec from memory when the composable leaves composition
    DisposableEffect(Unit) {
        onDispose { nsec = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Public key section
            Text(
                text = "Public Key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = npub ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        npub?.let {
                            clipboardManager.setText(AnnotatedString(it))
                            Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy public key",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Private key section
            Text(
                text = "Private Key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (nsec != null) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = nsec ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            nsec?.let { key ->
                                val clip = ClipData.newPlainText("", key)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    clip.description.extras = PersistableBundle().apply {
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    }
                                }
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, "Private key copied", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "Copy private key",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        val activity = context.findFragmentActivity()
                            ?: return@Button

                        val biometricManager = BiometricManager.from(context)
                        val canAuth = biometricManager.canAuthenticate(
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                            // No device lock set — reveal directly
                            keypair?.let { nsec = Nip19.nsecEncode(it.privkey) }
                            return@Button
                        }

                        val executor = ContextCompat.getMainExecutor(context)
                        val callback = object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                keypair?.let { nsec = Nip19.nsecEncode(it.privkey) }
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                ) {
                                    Toast.makeText(context, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Reveal Private Key")
                            .setDescription("Authenticate to view your private key")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                            .build()

                        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reveal Private Key")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Never share your private key. Anyone with your nsec has full control of your account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
