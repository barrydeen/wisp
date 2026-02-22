package com.wisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.R
import com.wisp.app.nostr.RemoteSignerBridge
import com.wisp.app.nostr.toHex
import com.wisp.app.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: (isNewAccount: Boolean) -> Unit
) {
    val context = LocalContext.current
    val nsecInput by viewModel.nsecInput.collectAsState()
    val error by viewModel.error.collectAsState()
    var nsecVisible by remember { mutableStateOf(false) }
    val signerAvailable = remember { RemoteSignerBridge.isSignerAvailable(context) }

    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val result = data.getStringExtra("result") ?: return@rememberLauncherForActivityResult
        val pkg = data.getStringExtra("package")
        // Amber returns npub bech32 — decode to hex
        val pubkeyHex = if (result.startsWith("npub1")) {
            try { com.wisp.app.nostr.Nip19.npubDecode(result).toHex() } catch (_: Exception) { return@rememberLauncherForActivityResult }
        } else {
            result
        }
        viewModel.loginWithSigner(pubkeyHex, pkg)
        onAuthenticated(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Wisp logo",
            modifier = Modifier.size(108.dp)
        )
        Text(
            text = "wisp",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "a wee interface to scroll posts",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = {
                if (viewModel.signUp()) onAuthenticated(true)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign Up")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Or log in with your key",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = nsecInput,
            onValueChange = { viewModel.updateNsecInput(it) },
            label = { Text("nsec...") },
            singleLine = true,
            visualTransformation = if (nsecVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { nsecVisible = !nsecVisible }) {
                    Icon(
                        imageVector = if (nsecVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (nsecVisible) "Hide key" else "Show key"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                if (viewModel.logIn()) onAuthenticated(false)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Log In")
        }

        if (signerAvailable) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    val permissions = """[{"type":"sign_event","kind":0},{"type":"sign_event","kind":1},{"type":"sign_event","kind":3},{"type":"sign_event","kind":5},{"type":"sign_event","kind":6},{"type":"sign_event","kind":7},{"type":"sign_event","kind":13},{"type":"sign_event","kind":9734},{"type":"sign_event","kind":10000},{"type":"sign_event","kind":10001},{"type":"sign_event","kind":10002},{"type":"sign_event","kind":10030},{"type":"sign_event","kind":10063},{"type":"sign_event","kind":22242},{"type":"sign_event","kind":24242},{"type":"sign_event","kind":30000},{"type":"sign_event","kind":30003},{"type":"sign_event","kind":30030},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""
                    val intent = RemoteSignerBridge.buildGetPublicKeyIntent(permissions)
                    signerLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Login with Signer")
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
