package cooking.zap.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.auth.NostrCredentialSaver
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.RemoteSignerBridge
import cooking.zap.app.nostr.toHex
import cooking.zap.app.ui.component.QrScanner
import cooking.zap.app.viewmodel.AuthViewModel
import cooking.zap.app.viewmodel.SplashViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BG_COLOR = Color(0xFF111827)
private val TILE_SIZE = 80.dp
private val TILE_GAP = 6.dp
private val TILE_RADII = 12.dp

private val TILE_ALPHAS = floatArrayOf(
    0.10f, 0.06f, 0.14f, 0.08f, 0.12f, 0.05f, 0.16f, 0.07f, 0.11f, 0.09f,
    0.13f, 0.07f, 0.09f, 0.15f, 0.06f, 0.11f, 0.08f, 0.14f, 0.10f, 0.12f
)

@Composable
private fun AnimatedFoodTile(
    photos: List<String>,
    tileIndex: Int,
    numCols: Int,
    alpha: Float
) {
    var displayUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(photos) {
        if (photos.isEmpty()) return@LaunchedEffect
        // Spread starting photos so no two adjacent tiles share the same image.
        // Column offset of 7 and row offset of 1 are coprime to typical photo counts.
        val startIndex = (tileIndex * 7 + (tileIndex / numCols) * 13) % photos.size
        displayUrl = photos[startIndex]
        if (photos.size <= 1) return@LaunchedEffect
        var idx = startIndex
        delay((tileIndex * 371L) % 8000L)          // stagger so tiles don't all swap at once
        while (true) {
            delay(2500L + (tileIndex * 197L) % 3500L)  // 2.5–6s per tile
            idx = (idx + 1) % photos.size
            displayUrl = photos[idx]
        }
    }

    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(RoundedCornerShape(TILE_RADII))
            .background(Color.White.copy(alpha = alpha))
    ) {
        AnimatedContent(
            targetState = displayUrl,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
            label = "tile_$tileIndex"
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    authViewModel: AuthViewModel,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onContinueWithGoogle: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val foodPhotos by viewModel.foodPhotos.collectAsState()
    val context = LocalContext.current

    var showNostrSheet by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    var signerLoginComplete by remember { mutableStateOf(false) }
    if (signerLoginComplete) {
        LaunchedEffect(Unit) {
            signerLoginComplete = false
            onLoggedIn()
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
        authViewModel.loginWithSigner(pubkeyHex, pkg)
        signerLoginComplete = true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BG_COLOR)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val cols = ((maxWidth + TILE_GAP) / (TILE_SIZE + TILE_GAP)).toInt().coerceAtLeast(1)
        val rows = ((maxHeight + TILE_GAP) / (TILE_SIZE + TILE_GAP)).toInt().coerceAtLeast(1) + 1

        // Animated food photo tile grid
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            repeat(rows) { row ->
                Row {
                    repeat(cols) { col ->
                        val tileIndex = row * cols + col
                        val alpha = TILE_ALPHAS[(row * 3 + col * 7) % TILE_ALPHAS.size]
                        AnimatedFoodTile(
                            photos = foodPhotos,
                            tileIndex = tileIndex,
                            numCols = cols,
                            alpha = alpha
                        )
                        if (col < cols - 1) Spacer(Modifier.width(TILE_GAP))
                    }
                }
                Spacer(Modifier.height(TILE_GAP))
            }
        }

        // Gradient: transparent at top, soft mid-screen darkening so content is readable
        // while food tiles still peek through, fully opaque at bottom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.30f to Color.Transparent,
                            0.50f to BG_COLOR.copy(alpha = 0.50f),
                            0.72f to BG_COLOR.copy(alpha = 0.88f),
                            1.00f to BG_COLOR
                        ),
                        startY = 0f,
                        endY = screenHeightPx
                    )
                )
        )

        // Cancel pill — only shown when adding an account over an existing session
        if (onCancel != null) {
            Text(
                text = stringResource(R.string.btn_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }

        // Content — centered as one block
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {

            val logoTransition = rememberInfiniteTransition(label = "logo")
            val bob by logoTransition.animateFloat(
                initialValue = 0f,
                targetValue = -8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bob"
            )
            val sway by logoTransition.animateFloat(
                initialValue = -3f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "sway"
            )

            Icon(
                painter = painterResource(R.drawable.ic_wisp_logo_splash),
                contentDescription = stringResource(R.string.cd_wisp_logo),
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(88.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Black.copy(alpha = 0.15f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = size.minDimension * 2.2f,
                            ),
                            radius = size.minDimension * 2.2f,
                            center = center,
                        )
                    }
                    .graphicsLayer {
                        translationY = bob * density
                        rotationZ = sway
                    }
            )

            Spacer(Modifier.height(20.dp))

            Icon(
                painter = painterResource(R.drawable.ic_zc_wordmark_splash),
                // Decorative: the logo above already carries the "Zap Cooking logo"
                // label, so the wordmark is null to avoid a duplicate TalkBack read.
                contentDescription = null,
                // Always-white wordmark variant (keeps the gradient dot) so it stays
                // legible on the dark splash regardless of system light/dark mode.
                tint = Color.Unspecified,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(32.dp)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.auth_tagline),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = cooking.zap.app.ui.theme.WispBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = Color.White
            )

            Spacer(Modifier.height(120.dp))

            // Sign-in buttons
            Button(
                onClick = onContinueWithGoogle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1F2937),
                    contentColor = Color(0xFFE3E3E3)
                ),
                border = BorderStroke(1.dp, Color(0xFF374151))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_google_g),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.splash_continue_with_google),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = cooking.zap.app.ui.theme.WispBodyFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = { showNostrSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A0E2E),
                    contentColor = Color(0xFFE9DDFF)
                ),
                border = BorderStroke(1.dp, Color(0xFF8E30EB))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_nostr_ostrich),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.splash_continue_with_nostr),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = cooking.zap.app.ui.theme.WispBodyFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                )
            }

        }

        if (onCancel != null) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.btn_cancel),
                    tint = Color.White
                )
            }
        }
    }

    if (showNostrSheet) {
        NostrLoginSheet(
            authViewModel = authViewModel,
            signerAvailable = remember { RemoteSignerBridge.isSignerAvailable(context) },
            onDismiss = { showNostrSheet = false },
            onAccountCreated = {
                showNostrSheet = false
                onAccountCreated()
            },
            onLoggedIn = {
                showNostrSheet = false
                onLoggedIn()
            },
            onScanQr = {
                showNostrSheet = false
                showQrScanner = true
            },
            onLoginWithSigner = {
                showNostrSheet = false
                signerLauncher.launch(RemoteSignerBridge.buildGetPublicKeyIntent(RemoteSignerBridge.DEFAULT_PERMISSIONS))
            }
        )
    }

    if (showQrScanner) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQrScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                QrScanner(
                    onResult = { raw ->
                        showQrScanner = false
                        authViewModel.updateNsecInput(raw.trim())
                        if (authViewModel.logIn()) onLoggedIn()
                    },
                    modifier = Modifier.fillMaxSize(),
                    promptText = "Scan nsec, npub, or nprofile QR"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NostrLoginSheet(
    authViewModel: AuthViewModel,
    signerAvailable: Boolean = false,
    onDismiss: () -> Unit,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onScanQr: () -> Unit = {},
    onLoginWithSigner: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nsecInput by authViewModel.nsecInput.collectAsState()
    val error by authViewModel.error.collectAsState()
    var nsecVisible by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var autofillRequested by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isCreating) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_nostr_ostrich),
                contentDescription = stringResource(R.string.cd_nostr_logo),
                tint = Color.Unspecified,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.nostr_sheet_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.W600
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.nostr_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = nsecInput,
                onValueChange = { authViewModel.updateNsecInput(it) },
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
                        IconButton(onClick = onScanQr) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = "Scan QR code"
                            )
                        }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !autofillRequested && nsecInput.isBlank()) {
                            autofillRequested = true
                            val activity = context as? ComponentActivity
                                ?: return@onFocusChanged
                            scope.launch {
                                val saved = NostrCredentialSaver.loadSavedNsec(activity)
                                if (!saved.isNullOrBlank() && authViewModel.nsecInput.value.isBlank()) {
                                    authViewModel.updateNsecInput(saved)
                                }
                            }
                        }
                    }
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (authViewModel.logIn()) onLoggedIn()
                },
                enabled = nsecInput.isNotBlank() && !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.auth_log_in))
            }

            if (signerAvailable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onLoginWithSigner,
                    enabled = !isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(stringResource(R.string.auth_login_with_signer))
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = {
                    if (isCreating) return@OutlinedButton
                    scope.launch {
                        isCreating = true
                        try {
                            if (authViewModel.signUp()) {
                                val nsec = authViewModel.getCurrentNsec()
                                val npub = authViewModel.npub.value
                                val activity = context as? ComponentActivity
                                if (activity != null && nsec != null && npub != null) {
                                    NostrCredentialSaver.saveNsec(activity, npub, nsec)
                                }
                                onAccountCreated()
                            }
                        } finally {
                            isCreating = false
                        }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    if (isCreating) stringResource(R.string.nostr_sheet_creating)
                    else stringResource(R.string.nostr_sheet_create)
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
