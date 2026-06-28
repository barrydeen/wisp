package cooking.zap.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cooking.zap.app.viewmodel.CookingTimer
import cooking.zap.app.viewmodel.CookingTimerViewModel

@Composable
fun FloatingTimerBar(
    viewModel: CookingTimerViewModel,
    isSheetVisible: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timers by viewModel.timers.collectAsState()
    val activeTimers = timers.filter { !it.isFinished }
    val finishedTimers = timers.filter { it.isFinished }
    val showBar = !isSheetVisible && (activeTimers.isNotEmpty() || finishedTimers.isNotEmpty())

    AnimatedVisibility(
        visible = showBar,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        val featured = activeTimers.minByOrNull { it.remainingSeconds } ?: finishedTimers.firstOrNull()
        if (featured != null) {
            FloatingTimerBarContent(
                timer = featured,
                extraCount = (activeTimers.size - 1).coerceAtLeast(0),
                onExpand = onExpand,
                onDismiss = { viewModel.removeTimer(featured.id) }
            )
        }
    }
}

@Composable
private fun FloatingTimerBarContent(
    timer: CookingTimer,
    extraCount: Int,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "countdown_pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onExpand)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Large pulsing countdown — LEFT
        Text(
            text = if (timer.isFinished) "Done!" else formatSeconds(timer.remainingSeconds),
            style = TextStyle(
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = (-0.01).em,
                fontFeatureSettings = "tnum"
            ),
            color = if (timer.isFinished) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            modifier = if (!timer.isFinished) Modifier.alpha(alpha) else Modifier
        )

        // Push label + icon + X to the RIGHT
        Spacer(Modifier.weight(1f))

        Icon(
            Icons.Outlined.Timer,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (extraCount > 0) "${timer.label}  +$extraCount" else timer.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss timer",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatSeconds(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
