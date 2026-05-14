package com.wisp.app.ui.screen

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.R
import com.wisp.app.viewmodel.GoogleAuthViewModel

private const val TAG = "GoogleAuth"

@Composable
fun GoogleAuthScreen(
    viewModel: GoogleAuthViewModel,
    onCancel: () -> Unit,
    onDone: (isNewAccount: Boolean) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val webClientId = stringResource(R.string.google_web_client_id)

    LaunchedEffect(Unit) {
        Log.d(TAG, "GoogleAuthScreen LaunchedEffect(Unit) fired, state=${state::class.simpleName}, contextClass=${context.javaClass.simpleName}, webClientIdLen=${webClientId.length}")
        if (state is GoogleAuthViewModel.State.Idle) {
            val activity = context as? ComponentActivity
            if (activity == null) {
                Log.w(TAG, "context is not a ComponentActivity — cancelling")
                onCancel()
                return@LaunchedEffect
            }
            if (webClientId.isBlank()) {
                Log.w(TAG, "google_web_client_id is blank — cancelling")
                onCancel()
                return@LaunchedEffect
            }
            viewModel.beginSignIn(activity, webClientId)
        }
    }

    LaunchedEffect(state) {
        Log.d(TAG, "GoogleAuthScreen state changed -> ${state::class.simpleName}")
        val current = state
        if (current is GoogleAuthViewModel.State.Done) {
            Log.d(TAG, "calling onDone(isNewAccount=${current.isNewAccount})")
            onDone(current.isNewAccount)
            viewModel.reset()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = {
                Log.d(TAG, "back arrow tapped")
                viewModel.reset()
                onCancel()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Header()
            Spacer(Modifier.height(32.dp))

            when (val s = state) {
                GoogleAuthViewModel.State.Idle,
                GoogleAuthViewModel.State.SigningIn,
                GoogleAuthViewModel.State.CheckingDrive,
                GoogleAuthViewModel.State.Working -> {
                    LoadingBlock(
                        label = when (s) {
                            GoogleAuthViewModel.State.SigningIn -> stringResource(R.string.google_auth_signing_in)
                            GoogleAuthViewModel.State.CheckingDrive -> stringResource(R.string.google_auth_checking_drive)
                            GoogleAuthViewModel.State.Working -> stringResource(R.string.google_auth_working)
                            else -> stringResource(R.string.google_auth_starting)
                        }
                    )
                }

                is GoogleAuthViewModel.State.Choose -> ChooseBlock(
                    backups = s.backups,
                    onRestore = { viewModel.restoreAccount(it.fileId) },
                    onCreate = { viewModel.createNewAccount() }
                )

                is GoogleAuthViewModel.State.Error -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_retry))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }

                is GoogleAuthViewModel.State.Done -> {
                    LoadingBlock(label = stringResource(R.string.google_auth_working))
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Icon(
        painter = painterResource(R.drawable.ic_wisp_logo),
        contentDescription = stringResource(R.string.onboarding_wisp_logo),
        tint = Color.Unspecified,
        modifier = Modifier
            .size(96.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
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
        text = stringResource(R.string.auth_wisp),
        style = MaterialTheme.typography.titleLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontSize = 36.sp,
            fontWeight = FontWeight.W500
        ),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun LoadingBlock(label: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChooseBlock(
    backups: List<GoogleAuthViewModel.BackupSummary>,
    onRestore: (GoogleAuthViewModel.BackupSummary) -> Unit,
    onCreate: () -> Unit
) {
    val titleRes = if (backups.isEmpty())
        R.string.google_auth_choose_title_empty
    else
        R.string.google_auth_choose_title_with_backups

    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(
            if (backups.isEmpty()) R.string.google_auth_choose_body_empty
            else R.string.google_auth_choose_body_with_backups
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    if (backups.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
        ) {
            items(backups, key = { it.npub }) { backup ->
                BackupRow(backup = backup, onClick = { onRestore(backup) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(
                if (backups.isEmpty()) R.string.google_auth_create_first
                else R.string.google_auth_create_another
            )
        )
    }
}

@Composable
private fun BackupRow(
    backup: GoogleAuthViewModel.BackupSummary,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (!backup.picture.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(backup.picture)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.displayName?.takeIf { it.isNotBlank() }
                        ?: formatShortNpub(backup.npub),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatShortNpub(npub: String): String {
    if (npub.length <= 18) return npub
    return npub.take(12) + "…" + npub.takeLast(6)
}
