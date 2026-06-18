package cooking.zap.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import cooking.zap.app.ui.component.QrScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.auth.NostrCredentialSaver
import cooking.zap.app.viewmodel.AuthViewModel
import cooking.zap.app.viewmodel.LiveMetrics
import cooking.zap.app.viewmodel.SplashViewModel
import kotlinx.coroutines.launch

private val AVATAR_SIZE = 44.dp
private val AVATAR_GAP = 4.dp

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    authViewModel: AuthViewModel,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onContinueWithGoogle: () -> Unit
) {
    val profilePictures by viewModel.profilePictures.collectAsState()
    val liveMetrics by viewModel.liveMetrics.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    var showNostrSheet by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val cols = ((maxWidth + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt().coerceAtLeast(1)
        val screenHeightPx = constraints.maxHeight.toFloat()

        val pics = profilePictures.ifEmpty {
            val placeholderRows = ((maxHeight + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt() + 1
            List(placeholderRows * cols) { "" }
        }

        val rows = (pics.size + cols - 1) / cols

        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            for (row in 0 until rows) {
                Row {
                    for (col in 0 until cols) {
                        val idx = row * cols + col
                        if (idx >= pics.size) break
                        val url = pics[idx]
                        Box(
                            modifier = Modifier
                                .size(AVATAR_SIZE)
                                .clip(CircleShape)
                                .background(surfaceVariant)
                        ) {
                            if (url.isNotEmpty()) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }
                        if (col < cols - 1) Spacer(Modifier.width(AVATAR_GAP))
                    }
                }
                if (row < rows - 1) Spacer(Modifier.height(AVATAR_GAP))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, backgroundColor),
                        startY = 0.25f * screenHeightPx,
                        endY = 0.72f * screenHeightPx
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
        ) {
            val wispTransition = rememberInfiniteTransition(label = "zapCooking")
            val bob by wispTransition.animateFloat(
                initialValue = 0f,
                targetValue = -8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bob"
            )
            val sway by wispTransition.animateFloat(
                initialValue = -3f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "sway"
            )
            Icon(
                painter = painterResource(R.drawable.ic_wisp_logo),
                contentDescription = stringResource(R.string.cd_wisp_logo),
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        translationY = bob * density
                        rotationZ = sway
                    }
                    .drawBehind {
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    Color.Black,
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Transparent
                                ),
                                radius = size.minDimension * 0.65f
                            ),
                            radius = size.minDimension * 0.65f
                        )
                    }
            )
            Text(
                text = "Zap Cooking",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.W500
                ),
                color = Color.White
            )
            liveMetrics?.let { OnlineCard(it) }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onContinueWithGoogle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF131314),
                    contentColor = Color(0xFFE3E3E3)
                ),
                border = BorderStroke(1.dp, Color(0xFF8E918F))
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
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { showNostrSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
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
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                )
            }
        }
    }

    if (showNostrSheet) {
        NostrLoginSheet(
            authViewModel = authViewModel,
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
                    .background(androidx.compose.ui.graphics.Color.Black)
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
    onDismiss: () -> Unit,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onScanQr: () -> Unit = {}
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

@Composable
private fun OnlineCard(metrics: LiveMetrics) {
    Spacer(Modifier.height(16.dp))
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.splash_people_online, metrics.online),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
