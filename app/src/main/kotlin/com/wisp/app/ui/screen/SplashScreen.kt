package com.wisp.app.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.relay.TorStatus
import com.wisp.app.viewmodel.LiveMetrics
import com.wisp.app.viewmodel.SplashViewModel

private val AVATAR_SIZE = 44.dp
private val AVATAR_GAP = 4.dp

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    isTorEnabled: Boolean = false,
    torStatus: TorStatus = TorStatus.DISABLED,
    onToggleTor: (Boolean) -> Unit = {},
    onSignUp: () -> Unit,
    onLogIn: () -> Unit
) {
    val profilePictures by viewModel.profilePictures.collectAsState()
    val liveMetrics by viewModel.liveMetrics.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val cols = ((maxWidth + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt().coerceAtLeast(1)
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Use real pictures, or placeholder circles while loading
        val pics = profilePictures.ifEmpty {
            val placeholderRows = ((maxHeight + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt() + 1
            List(placeholderRows * cols) { "" }
        }

        val rows = (pics.size + cols - 1) / cols

        // Background collage — each picture shown at most once, no cycling
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            for (row in 0 until rows) {
                Row {
                    for (col in 0 until cols) {
                        val idx = row * cols + col
                        if (idx >= pics.size) break
                        val url = pics[idx]
                        // Background circle always visible; image loads on top.
                        // Slow or failed loads show the filled circle instead of a gap.
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

        // Gradient fades the collage into the background toward the bottom
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

        // Logo, tagline, and action buttons pinned to bottom
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.wisp_logo_500x500),
                contentDescription = stringResource(R.string.cd_wisp_logo),
                modifier = Modifier.size(96.dp)
            )
            Text(
                text = "wisp",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            liveMetrics?.let { OnlineCard(it) }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onSignUp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.splash_create_account))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onLogIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.splash_log_in))
            }

            Spacer(Modifier.height(16.dp))

            val pillBorderColor = when (torStatus) {
                TorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                TorStatus.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .border(1.dp, pillBorderColor, RoundedCornerShape(24.dp))
                    .clickable { onToggleTor(!isTorEnabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                    if (torStatus == TorStatus.STARTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_tor_onion),
                            contentDescription = stringResource(R.string.cd_toggle_tor),
                            modifier = Modifier.size(18.dp),
                            tint = when (torStatus) {
                                TorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                                TorStatus.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (torStatus) {
                        TorStatus.STARTING -> stringResource(R.string.splash_connecting_tor)
                        TorStatus.CONNECTED -> stringResource(R.string.splash_connected_tor)
                        TorStatus.ERROR -> stringResource(R.string.splash_tor_error)
                        else -> stringResource(R.string.splash_connect_tor)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (torStatus) {
                        TorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                        TorStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
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

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000f)}k"
    else -> n.toString()
}
