package com.wisp.app.ui.component

import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

data class PipState(
    val url: String,
    val player: ExoPlayer,
    val aspectRatio: Float
)

object PipController {
    val globalMuted = MutableStateFlow(true)
    val activeVideoUrl = MutableStateFlow<String?>(null)
    val pipState = MutableStateFlow<PipState?>(null)

    fun enterPip(url: String, player: ExoPlayer, aspectRatio: Float) {
        val old = pipState.value
        if (old != null && old.url != url) {
            old.player.release()
        }
        pipState.value = PipState(url, player, aspectRatio)
        activeVideoUrl.value = url
    }

    fun exitPip() {
        val state = pipState.value ?: return
        state.player.release()
        pipState.value = null
        activeVideoUrl.compareAndSet(state.url, null)
    }

    fun reclaimPlayer(url: String): ExoPlayer? {
        val state = pipState.value ?: return null
        if (state.url != url) return null
        pipState.value = null
        return state.player
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FloatingVideoPlayer(
    onExpandToFullScreen: (url: String, positionMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by PipController.pipState.collectAsState()
    val currentState = state

    AnimatedVisibility(
        visible = currentState != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier.zIndex(Float.MAX_VALUE)
    ) {
        val pipState = currentState ?: return@AnimatedVisibility
        val isMuted by PipController.globalMuted.collectAsState()

        LaunchedEffect(isMuted) {
            pipState.player.volume = if (isMuted) 0f else 1f
        }

        DisposableEffect(pipState.url) {
            onDispose {
                val current = PipController.pipState.value
                if (current?.url == pipState.url) {
                    PipController.exitPip()
                }
            }
        }

        val pipWidth = 200.dp
        val pipHeight = (200f / pipState.aspectRatio).dp.coerceAtMost(260.dp)

        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(pipWidth)
                .height(pipHeight)
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    // Wrap PlayerView in a FrameLayout with high translationZ
                    // so it renders above inline video AndroidViews in the feed
                    FrameLayout(ctx).apply {
                        translationZ = 1000f
                        clipChildren = false
                        addView(
                            PlayerView(ctx).apply {
                                player = pipState.player
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            val buttonColors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White
            )

            IconButton(
                onClick = { PipController.exitPip() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp),
                colors = buttonColors
            ) {
                Icon(Icons.Filled.Close, "Close", Modifier.size(16.dp))
            }

            IconButton(
                onClick = {
                    val position = pipState.player.currentPosition
                    val url = pipState.url
                    PipController.pipState.value = null
                    onExpandToFullScreen(url, position)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(28.dp),
                colors = buttonColors
            ) {
                Icon(Icons.Filled.Fullscreen, "Expand", Modifier.size(16.dp))
            }
        }
    }
}
