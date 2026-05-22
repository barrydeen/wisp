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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import com.wisp.app.ui.theme.WispThemeColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bolt icons that burst outward from center when a zap is confirmed.
 * Uses the ic_bolt shape for visual consistency with the rest of the app.
 */
@Composable
fun ZapBurstEffect(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    soundEnabled: Boolean = true
) {
    val context = LocalContext.current
    val zapSound = remember { ZapSound(context) }

    DisposableEffect(Unit) {
        onDispose { zapSound.release() }
    }

    var bolts by remember { mutableStateOf<List<BoltParticle>>(emptyList()) }
    var sparks by remember { mutableStateOf<List<SparkParticle>>(emptyList()) }
    val progress = remember { Animatable(0f) }

    val zapColor = WispThemeColors.zapAnimationColor

    if (!isActive && progress.value <= 0f) return

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect

        val rng = Random(System.nanoTime())
        bolts = generateBoltParticles(rng)
        sparks = generateSparkParticles(rng)
        if (soundEnabled) zapSound.play()

        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(1100, easing = FastOutSlowInEasing))
        progress.snapTo(0f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        if (p <= 0f) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f

        // Bright center flash — fills the area then fades
        if (p < 0.25f) {
            val flashP = p / 0.25f
            val flashAlpha = (1f - flashP) * 0.7f
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = flashAlpha),
                        zapColor.copy(alpha = flashAlpha * 0.6f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(cx, cy),
                    radius = size.minDimension * 0.4f * (0.5f + flashP * 0.5f)
                ),
                radius = size.minDimension * 0.4f
            )
        }

        // Expanding ring
        if (p < 0.5f) {
            val ringP = p / 0.5f
            val ringRadius = 10f * density + size.minDimension * 0.45f * ringP
            val ringAlpha = (1f - ringP) * 0.5f
            drawCircle(
                color = zapColor.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = (4f - 3f * ringP) * density)
            )
        }

        // Bolt particles
        for (bolt in bolts) {
            drawBoltParticle(bolt, cx, cy, p, zapColor)
        }

        // Spark particles — small dots that fly outward fast
        for (spark in sparks) {
            drawSparkParticle(spark, cx, cy, p, zapColor)
        }
    }
}

private data class BoltParticle(
    val angle: Float,
    val distance: Float,
    val boltSize: Float,
    val rotationDir: Float,
    val delay: Float
)

private data class SparkParticle(
    val angle: Float,
    val distance: Float,
    val sparkSize: Float,
    val delay: Float
)

private fun generateBoltParticles(rng: Random): List<BoltParticle> {
    val count = rng.nextInt(5, 8)
    val baseStep = (2f * Math.PI / count).toFloat()

    return (0 until count).map { i ->
        BoltParticle(
            angle = baseStep * i + (rng.nextFloat() - 0.5f) * baseStep * 0.5f,
            distance = 30f + rng.nextFloat() * 25f,
            boltSize = 6f + rng.nextFloat() * 5f,
            rotationDir = if (rng.nextBoolean()) 1f else -1f,
            delay = rng.nextFloat() * 0.15f
        )
    }
}

private fun generateSparkParticles(rng: Random): List<SparkParticle> {
    val count = rng.nextInt(12, 20)
    return (0 until count).map {
        SparkParticle(
            angle = rng.nextFloat() * 2f * Math.PI.toFloat(),
            distance = 40f + rng.nextFloat() * 35f,
            sparkSize = 1.5f + rng.nextFloat() * 2.5f,
            delay = rng.nextFloat() * 0.15f
        )
    }
}

private fun DrawScope.drawBoltParticle(
    particle: BoltParticle,
    cx: Float,
    cy: Float,
    progress: Float,
    zapColor: Color
) {
    val localP = ((progress - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
    if (localP <= 0f) return

    val eased = 1f - (1f - localP) * (1f - localP)
    val dist = particle.distance * density * eased
    val px = cx + cos(particle.angle) * dist
    val py = cy + sin(particle.angle) * dist
    val scale = if (localP < 0.3f) localP / 0.3f else 1f - (localP - 0.3f) / 0.7f * 0.6f
    val alpha = if (localP > 0.6f) 1f - (localP - 0.6f) / 0.4f else 1f

    if (alpha <= 0f || scale <= 0f) return

    val boltW = particle.boltSize * density * scale
    val boltH = boltW * (94f / 55f)
    val boltPath = icBoltPath(boltW, boltH)

    translate(left = px - boltW / 2f, top = py - boltH / 2f) {
        drawPath(
            path = boltPath,
            color = zapColor.copy(alpha = alpha * 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2f * density * scale,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
        drawPath(path = boltPath, color = zapColor.copy(alpha = alpha * 0.9f), style = Fill)
        drawPath(path = boltPath, color = Color.White.copy(alpha = alpha * 0.3f), style = Fill)
    }
}

private fun DrawScope.drawSparkParticle(
    spark: SparkParticle,
    cx: Float,
    cy: Float,
    progress: Float,
    zapColor: Color
) {
    val localP = ((progress - spark.delay) / (1f - spark.delay)).coerceIn(0f, 1f)
    if (localP <= 0f) return

    val eased = 1f - (1f - localP) * (1f - localP) * (1f - localP) // cubic ease-out
    val dist = spark.distance * density * eased
    val px = cx + cos(spark.angle) * dist
    val py = cy + sin(spark.angle) * dist
    val alpha = if (localP > 0.5f) 1f - (localP - 0.5f) / 0.5f else 1f
    val r = spark.sparkSize * density * (1f - localP * 0.5f)

    if (alpha <= 0f) return

    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.9f),
        radius = r,
        center = androidx.compose.ui.geometry.Offset(px, py)
    )
    drawCircle(
        color = zapColor.copy(alpha = alpha * 0.4f),
        radius = r * 2f,
        center = androidx.compose.ui.geometry.Offset(px, py)
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
