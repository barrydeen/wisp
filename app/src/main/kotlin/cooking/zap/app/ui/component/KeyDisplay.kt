package cooking.zap.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cooking.zap.app.R
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip19

/**
 * Shared key-display building blocks used by both [cooking.zap.app.ui.screen.KeysScreen]
 * (the drawer "Keys" surface) and the post-creation backup step. Keeping the reveal /
 * copy / QR logic here means there is exactly one private-key reveal implementation.
 */

internal fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx: android.content.Context = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Copy a private key to the clipboard, flagging it sensitive on API 33+. */
private fun copyNsecToClipboard(context: android.content.Context, nsec: String) {
    val clip = ClipData.newPlainText("", nsec)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.settings_private_key_copied), Toast.LENGTH_SHORT).show()
}

/**
 * Public-key row: the npub with QR + copy buttons. Callers render their own
 * label/description above it.
 */
@Composable
fun PublicKeyCard(
    npub: String?,
    pubkeyHex: String?,
    avatarUrl: String? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { if (pubkeyHex != null) showQr = true }) {
                Icon(
                    Icons.Outlined.QrCode,
                    contentDescription = stringResource(R.string.cd_show_qr_code),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = {
                npub?.let {
                    clipboardManager.setText(AnnotatedString(it))
                    Toast.makeText(context, context.getString(R.string.settings_public_key_copied), Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_npub),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showQr && pubkeyHex != null) {
        QrCodeDialog(
            pubkeyHex = pubkeyHex,
            avatarUrl = avatarUrl,
            onDismiss = { showQr = false }
        )
    }
}

/**
 * Private-key reveal section: masked by default behind a "Reveal" button (gated by
 * device credential when one is set), then a card with the nsec + copy/QR and an
 * optional "Hide" toggle. Manages and clears its own in-memory nsec state.
 *
 * @param showHide       show a "Hide" affordance once revealed (re-masks the key)
 * @param showWarning    render the built-in "never share" warning underneath
 */
@Composable
fun PrivateKeyRevealSection(
    keypair: Keys.Keypair?,
    avatarUrl: String? = null,
    showHide: Boolean = true,
    showWarning: Boolean = true
) {
    val context = LocalContext.current
    val revealTitle = stringResource(R.string.btn_reveal_private_key)
    val revealDescription = stringResource(R.string.settings_authenticate_view_key)

    var nsec by remember { mutableStateOf<String?>(null) }
    var showNsecQr by remember { mutableStateOf(false) }

    // Clear the revealed key from memory when this leaves composition.
    DisposableEffect(Unit) {
        onDispose { nsec = null }
    }

    val revealedNsec = nsec
    if (revealedNsec != null) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = revealedNsec,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { showNsecQr = true }) {
                    Icon(
                        Icons.Outlined.QrCode,
                        contentDescription = stringResource(R.string.cd_show_qr_code),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { copyNsecToClipboard(context, revealedNsec) }) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.btn_copy),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (showHide) {
                    IconButton(onClick = { nsec = null }) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(R.string.btn_hide_private_key),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (showNsecQr) {
            NsecQrDialog(nsec = revealedNsec, avatarUrl = avatarUrl, onDismiss = { showNsecQr = false })
        }
    } else {
        Button(
            onClick = {
                revealNsecWithDeviceCredential(
                    context = context,
                    keypair = keypair,
                    title = revealTitle,
                    description = revealDescription,
                    onRevealed = { nsec = it }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Visibility, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.btn_reveal_private_key))
        }
    }

    if (showWarning) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.private_key_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** Reveal the nsec, gated behind the device credential prompt when one is configured. */
private fun revealNsecWithDeviceCredential(
    context: android.content.Context,
    keypair: Keys.Keypair?,
    title: String,
    description: String,
    onRevealed: (String) -> Unit
) {
    if (keypair == null) return
    val activity = context.findFragmentActivity() ?: return

    val biometricManager = BiometricManager.from(context)
    val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // No device lock set — reveal directly.
        onRevealed(Nip19.nsecEncode(keypair.privkey))
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onRevealed(Nip19.nsecEncode(keypair.privkey))
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) {
                Toast.makeText(context, context.getString(R.string.settings_auth_failed, errString), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setDescription(description)
        .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()

    BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
}

@Composable
fun NsecQrDialog(nsec: String, avatarUrl: String? = null, onDismiss: () -> Unit) {
    val qrBitmap = remember(nsec) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val writer = QRCodeWriter()
        val size = 512
        val matrix = writer.encode(nsec, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width)
            for (y in 0 until matrix.height)
                bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        bmp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nsec_qr_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.nsec_qr_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "nsec QR code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize()
                    )
                    if (avatarUrl != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(3.dp)
                        ) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_done)) }
        }
    )
}
