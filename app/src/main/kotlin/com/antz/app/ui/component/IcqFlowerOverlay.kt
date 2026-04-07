package com.antz.app.ui.component

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import com.antz.app.ui.theme.AntzThemeColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * ICQ-style green flower burst that blooms outward from center when triggered.
 * Each petal grows and rotates slightly, then fades out.
 */
@Composable
fun IcqFlowerBurstEffect(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    soundEnabled: Boolean = true
) {
    val context = LocalContext.current
    val replySound = remember { ReplySound(context) }

    DisposableEffect(Unit) {
        onDispose { replySound.release() }
    }

    var petalCount by remember { mutableStateOf(8) }
    val progress = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }

    val repostColor = AntzThemeColors.repostColor

    if (!isActive && progress.value <= 0f) return

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect

        petalCount = (6..8).random()
        if (soundEnabled) replySound.play()

        progress.snapTo(0f)
        rotation.snapTo(0f)

        coroutineScope {
            launch { rotation.animateTo(30f, tween(900, easing = LinearEasing)) }
            launch { progress.animateTo(1f, animationSpec = tween(900, easing = LinearEasing)) }
        }
        progress.snapTo(0f)
        rotation.snapTo(0f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        val rot = rotation.value

        // Scale up in first 50%, then hold and fade
        val scale = (p / 0.5f).coerceAtMost(1f)
        val alpha = if (p < 0.4f) 1f else 1f - ((p - 0.4f) / 0.6f)

        if (alpha <= 0f) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f

        drawIcqFlower(cx, cy, scale, alpha, rot, petalCount, repostColor)
    }
}

private fun DrawScope.drawIcqFlower(
    cx: Float,
    cy: Float,
    scale: Float,
    alpha: Float,
    rotationDeg: Float,
    petalCount: Int,
    repostColor: Color
) {
    val petalLength = 24f * density * scale
    val petalWidth = 10f * density * scale
    val rotRad = Math.toRadians(rotationDeg.toDouble()).toFloat()

    // Draw petals
    for (i in 0 until petalCount) {
        val baseAngle = (2f * Math.PI.toFloat() * i / petalCount) + rotRad
        drawPetal(cx, cy, baseAngle, petalLength, petalWidth, alpha, repostColor)
    }

    // Center circle
    val centerRadius = 5f * density * scale
    drawCircle(
        color = Color(0xFF8BC34A).copy(alpha = alpha),
        radius = centerRadius,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFFCDDC39).copy(alpha = alpha * 0.8f),
        radius = centerRadius * 0.6f,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawPetal(
    cx: Float,
    cy: Float,
    angle: Float,
    length: Float,
    width: Float,
    alpha: Float,
    repostColor: Color
) {
    val cosA = cos(angle)
    val sinA = sin(angle)
    val perpX = -sinA
    val perpY = cosA

    // Petal shape: oval-ish path from center outward
    val tipX = cx + cosA * length
    val tipY = cy + sinA * length

    val midDist = length * 0.55f
    val controlDist = length * 0.45f

    val path = Path().apply {
        moveTo(cx, cy)
        // Left side curve
        cubicTo(
            cx + perpX * width * 0.5f + cosA * controlDist,
            cy + perpY * width * 0.5f + sinA * controlDist,
            cx + perpX * width * 0.3f + cosA * midDist,
            cy + perpY * width * 0.3f + sinA * midDist,
            tipX, tipY
        )
        // Right side curve back
        cubicTo(
            cx - perpX * width * 0.3f + cosA * midDist,
            cy - perpY * width * 0.3f + sinA * midDist,
            cx - perpX * width * 0.5f + cosA * controlDist,
            cy - perpY * width * 0.5f + sinA * controlDist,
            cx, cy
        )
        close()
    }

    // Outer glow
    drawPath(
        path = path,
        color = repostColor.copy(alpha = alpha * 0.3f),
        style = Fill
    )

    // Main petal fill
    val innerPath = Path().apply {
        val inset = 0.85f
        val iLength = length * inset
        val iWidth = width * inset
        val iTipX = cx + cosA * iLength
        val iTipY = cy + sinA * iLength
        val iMidDist = iLength * 0.55f
        val iControlDist = iLength * 0.45f

        moveTo(cx, cy)
        cubicTo(
            cx + perpX * iWidth * 0.5f + cosA * iControlDist,
            cy + perpY * iWidth * 0.5f + sinA * iControlDist,
            cx + perpX * iWidth * 0.3f + cosA * iMidDist,
            cy + perpY * iWidth * 0.3f + sinA * iMidDist,
            iTipX, iTipY
        )
        cubicTo(
            cx - perpX * iWidth * 0.3f + cosA * iMidDist,
            cy - perpY * iWidth * 0.3f + sinA * iMidDist,
            cx - perpX * iWidth * 0.5f + cosA * iControlDist,
            cy - perpY * iWidth * 0.5f + sinA * iControlDist,
            cx, cy
        )
        close()
    }

    drawPath(
        path = innerPath,
        color = Color(0xFF66BB6A).copy(alpha = alpha * 0.8f),
        style = Fill
    )
}

class ReplySound(context: Context) {
    private var pool: SoundPool? = null
    private var soundId: Int = 0
    @Volatile private var loaded = false

    init {
        val resId = context.resources.getIdentifier("icq_reply", "raw", context.packageName)
        if (resId != 0) {
            try {
                val p = SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                p.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) loaded = true
                }
                soundId = p.load(context, resId, 1)
                pool = p
            } catch (_: Exception) { }
        }
    }

    fun play() {
        if (loaded) {
            pool?.play(soundId, 0.4f, 0.4f, 1, 0, 1f)
        }
    }

    fun release() {
        pool?.release()
        pool = null
    }
}
