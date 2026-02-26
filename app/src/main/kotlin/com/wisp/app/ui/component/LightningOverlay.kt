package com.wisp.app.ui.component

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mini lightning bolts that burst outward from center when triggered.
 * Designed to overlay on top of the zap icon in the ActionBar.
 */
@Composable
fun ZapBurstEffect(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val zapSound = remember { ZapSound(context) }

    DisposableEffect(Unit) {
        onDispose { zapSound.release() }
    }

    var bolts by remember { mutableStateOf<List<MiniBolt>>(emptyList()) }
    val progress = remember { Animatable(0f) }

    if (!isActive && progress.value <= 0f) return

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect

        bolts = generateMiniBolts()
        zapSound.play()

        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(800, easing = LinearEasing))
        progress.snapTo(0f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        // Grow outward in first 40%, then hold and fade
        val extension = (p / 0.4f).coerceAtMost(1f)
        val alpha = if (p < 0.5f) 1f else 1f - ((p - 0.5f) / 0.5f)

        if (alpha <= 0f) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f

        for (bolt in bolts) {
            drawMiniBolt(bolt, cx, cy, extension, alpha)
        }
    }
}

private data class MiniBolt(
    val angle: Float,
    val length: Float,
    val segments: List<Float>,
    val width: Float
)

private fun generateMiniBolts(): List<MiniBolt> {
    val rng = Random(System.nanoTime())
    val count = rng.nextInt(5, 8)
    val baseStep = (2f * Math.PI / count).toFloat()

    return (0 until count).map { i ->
        val angle = baseStep * i + (rng.nextFloat() - 0.5f) * baseStep * 0.6f
        val length = 28f + rng.nextFloat() * 36f
        val segCount = rng.nextInt(3, 6)
        val segments = (0 until segCount).map { (rng.nextFloat() - 0.5f) * 8f }
        val width = 1.5f + rng.nextFloat() * 1.5f
        MiniBolt(angle, length, segments, width)
    }
}

private fun DrawScope.drawMiniBolt(
    bolt: MiniBolt,
    cx: Float,
    cy: Float,
    extension: Float,
    alpha: Float
) {
    val startR = 14f * density
    val endR = startR + bolt.length * density * extension

    val cosA = cos(bolt.angle)
    val sinA = sin(bolt.angle)
    val perpX = -sinA
    val perpY = cosA

    val totalSegs = bolt.segments.size + 1
    val path = Path().apply {
        val sx = cx + cosA * startR
        val sy = cy + sinA * startR
        moveTo(sx, sy)

        for (i in bolt.segments.indices) {
            val t = (i + 1).toFloat() / totalSegs
            val r = startR + (endR - startR) * t
            val jag = bolt.segments[i] * density
            val px = cx + cosA * r + perpX * jag
            val py = cy + sinA * r + perpY * jag
            lineTo(px, py)
        }

        val fx = cx + cosA * endR
        val fy = cy + sinA * endR
        lineTo(fx, fy)
    }

    drawPath(
        path = path,
        color = Color(0xFFFFD54F).copy(alpha = alpha * 0.4f),
        style = Stroke(width = bolt.width * density * 3f, cap = StrokeCap.Round)
    )

    drawPath(
        path = path,
        color = Color(0xFFFF9800).copy(alpha = alpha * 0.7f),
        style = Stroke(width = bolt.width * density * 1.5f, cap = StrokeCap.Round)
    )

    drawPath(
        path = path,
        color = Color.White.copy(alpha = alpha * 0.9f),
        style = Stroke(width = bolt.width * density * 0.6f, cap = StrokeCap.Round)
    )
}

/**
 * Wraps SoundPool with proper async loading.
 * SoundPool.load() is async — play() silently fails if called before loading completes.
 * We track the loaded state via setOnLoadCompleteListener.
 */
class NotifBlipSound(context: Context) {
    private var pool: SoundPool? = null
    private var soundId: Int = 0
    @Volatile private var loaded = false

    init {
        val resId = context.resources.getIdentifier("notif_blip", "raw", context.packageName)
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
            pool?.play(soundId, 0.2f, 0.2f, 1, 0, 1f)
        }
    }

    fun release() {
        pool?.release()
        pool = null
    }
}

private class ZapSound(context: Context) {
    private var pool: SoundPool? = null
    private var soundId: Int = 0
    @Volatile private var loaded = false

    init {
        val resId = context.resources.getIdentifier("zap_thunder", "raw", context.packageName)
        if (resId != 0) {
            try {
                val p = SoundPool.Builder()
                    .setMaxStreams(3)
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
