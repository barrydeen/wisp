package cooking.zap.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.RemoteSignerBridge
import cooking.zap.app.nostr.toHex
import cooking.zap.app.ui.component.NsecPasteGuard
import cooking.zap.app.ui.component.QrScanner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import cooking.zap.app.R
import cooking.zap.app.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    showSignUp: Boolean = true,
    onAuthenticated: (isNewAccount: Boolean) -> Unit
) {
    val context = LocalContext.current
    val nsecInput by viewModel.nsecInput.collectAsState()
    val error by viewModel.error.collectAsState()
    var nsecVisible by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val signerAvailable = remember { RemoteSignerBridge.isSignerAvailable(context) }

    // Navigate only once the composable is back in RESUMED state — activity result
    // callbacks fire during STARTED and navigateSafe() silently drops calls then.
    var signerLoginComplete by remember { mutableStateOf(false) }
    if (signerLoginComplete) {
        LaunchedEffect(Unit) {
            signerLoginComplete = false
            onAuthenticated(false)
        }
    }

    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val pubkeyResult = data.getStringExtra("result") ?: return@rememberLauncherForActivityResult
        val pkg = data.getStringExtra("package")
        val pubkeyHex = if (pubkeyResult.startsWith("npub1")) {
            try { Nip19.npubDecode(pubkeyResult).toHex() } catch (_: Exception) { return@rememberLauncherForActivityResult }
        } else {
            pubkeyResult
        }
        viewModel.loginWithSigner(pubkeyHex, pkg)
        signerLoginComplete = true
    }

    if (showQrScanner) {
        QrScanner(
            onResult = { raw ->
                showQrScanner = false
                viewModel.updateNsecInput(raw.trim())
                if (viewModel.logIn()) onAuthenticated(false)
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            promptText = "Scan nsec, npub, or nprofile QR"
        )
        return
    }

    DisposableEffect(Unit) {
        NsecPasteGuard.nsecPasteAllowed = true
        onDispose { NsecPasteGuard.nsecPasteAllowed = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_wisp_logo),
            contentDescription = stringResource(R.string.onboarding_wisp_logo),
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier
                .size(108.dp)
                .drawBehind {
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Black,
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                androidx.compose.ui.graphics.Color.Transparent
                            ),
                            radius = size.minDimension * 0.65f
                        ),
                        radius = size.minDimension * 0.65f
                    )
                }
        )
        Text(
            text = stringResource(R.string.auth_wisp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = cooking.zap.app.ui.theme.WispBodyFont,
                fontSize = 36.sp,
                fontWeight = FontWeight.W500
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (showSignUp) {
            Button(
                onClick = {
                    if (viewModel.signUp()) onAuthenticated(true)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.auth_sign_up))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.auth_or_log_in_with_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }


        OutlinedTextField(
            value = nsecInput,
            onValueChange = { viewModel.updateNsecInput(it) },
            label = { Text(stringResource(R.string.auth_nsec_or_npub)) },
            singleLine = true,
            visualTransformation = if (nsecVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    IconButton(onClick = { nsecVisible = !nsecVisible }) {
                        Icon(
                            imageVector = if (nsecVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (nsecVisible) stringResource(R.string.auth_hide_key) else stringResource(R.string.auth_show_key)
                        )
                    }
                    IconButton(onClick = { showQrScanner = true }) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = "Scan QR code"
                        )
                    }
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
            Text(stringResource(R.string.auth_log_in))
        }

        if (signerAvailable) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    signerLauncher.launch(RemoteSignerBridge.buildGetPublicKeyIntent(RemoteSignerBridge.DEFAULT_PERMISSIONS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.auth_login_with_signer))
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

/** Superellipse-based squircle shape (n≈5) for smooth continuous-curvature corners. */
private val SquircleShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val w = size.width
        val h = size.height
        val n = 5.0 // superellipse exponent — higher = more square, lower = more round
        val steps = 360
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val cosA = cos(angle)
            val sinA = sin(angle)
            val x = (w / 2) * abs(cosA).pow(2.0 / n) * sign(cosA) + w / 2
            val y = (h / 2) * abs(sinA).pow(2.0 / n) * sign(sinA) + h / 2
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return Outline.Generic(path)
    }
}
