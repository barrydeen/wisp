package cooking.zap.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Reusable zoomable image surface that mirrors the iOS `FullScreenImageView`
 * gesture model. One unified gesture loop owns all drag/pinch handling so
 * single-finger horizontal drags can fall through to a parent HorizontalPager
 * — that's the only way to coexist with a Compose pager, since the stock
 * [detectTransformGestures] consumes single-finger pan unconditionally once
 * touch slop is crossed.
 *
 * Behavior:
 *   - Pinch to zoom 1x..[maxScale]; release snaps back if user pinched below 1x
 *   - Double-tap toggles 1x ↔ 2x with the tap location pinned under the finger
 *   - When zoomed, single-finger drag pans within the image bounds (clamped)
 *   - When zoom is 1x, vertically-dominant downward drag fires
 *     [onSwipeDownDismiss] once it passes [dismissThreshold] on release;
 *     while dragging, [onDismissDrag] reports the vertical offset
 *   - Horizontal single-finger drag at 1x is NOT consumed, so a parent pager
 *     can use it for paging
 */
@Composable
internal fun ZoomableAsyncImage(
    model: Any?,
    contentDescription: String?,
    onSwipeDownDismiss: () -> Unit,
    onDismissDrag: (yOffset: Float) -> Unit,
    modifier: Modifier = Modifier,
    maxScale: Float = 4f,
    dismissThreshold: Float = 120f
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // Vertical-drag offset (only used when scale == 1). Driven by an
    // Animatable so the failed-dismiss snap-back is a spring rather than a
    // jump cut.
    val dragYAnim = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { dragYAnim.value }
            .distinctUntilChanged()
            .collect {
                dragY = it
                onDismissDrag(it)
            }
    }

    fun clampOffset(target: Offset, s: Float): Offset {
        if (s <= 1f || boxSize == IntSize.Zero) return Offset.Zero
        val maxX = (boxSize.width * (s - 1f)) / 2f
        val maxY = (boxSize.height * (s - 1f)) / 2f
        return Offset(
            x = target.x.coerceIn(-maxX, maxX),
            y = target.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y + dragY
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            scope.launch {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val newScale = 2f
                                    val centerX = boxSize.width / 2f
                                    val centerY = boxSize.height / 2f
                                    val target = Offset(
                                        x = (centerX - tapOffset.x) * (newScale - 1f),
                                        y = (centerY - tapOffset.y) * (newScale - 1f)
                                    )
                                    scale = newScale
                                    offset = clampOffset(target, newScale)
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var mode: Mode = Mode.Undetermined
                        // Track release velocity so a quick down-flick
                        // dismisses even if the finger didn't travel the
                        // full 120px threshold. Matches iOS's
                        // `predictedEndTranslation` (translation + 0.3s ·
                        // velocity).
                        val velocityTracker = VelocityTracker()

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val activeCount = event.changes.count { it.pressed }

                            if (activeCount == 0) {
                                if (mode == Mode.SwipeDown) {
                                    val velocityY = velocityTracker.calculateVelocity().y
                                    val projectedY = dragY + velocityY * 0.3f
                                    if (projectedY >= dismissThreshold) {
                                        onSwipeDownDismiss()
                                    } else {
                                        scope.launch { dragYAnim.animateTo(0f) }
                                    }
                                }
                                return@awaitEachGesture
                            }

                            if (activeCount >= 2) {
                                // Pinch — once two fingers are down, we own
                                // the gesture for zoom + pan.
                                if (mode != Mode.Pinch) mode = Mode.Pinch
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
                                    scale = newScale
                                    offset = if (newScale > 1f) {
                                        clampOffset(offset + panChange, newScale)
                                    } else {
                                        Offset.Zero
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                continue
                            }

                            // Single finger
                            val change = event.changes.firstOrNull { it.pressed } ?: continue
                            val delta = change.positionChange()

                            when (mode) {
                                Mode.Undetermined -> {
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    val crossed = abs(totalDx) > touchSlop || abs(totalDy) > touchSlop
                                    if (!crossed) continue
                                    when {
                                        scale > 1f -> {
                                            mode = Mode.Pan
                                            offset = clampOffset(offset + Offset(totalDx, totalDy), scale)
                                            change.consume()
                                        }
                                        abs(totalDy) > abs(totalDx) && totalDy > 0f -> {
                                            mode = Mode.SwipeDown
                                            scope.launch { dragYAnim.snapTo(totalDy) }
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                            change.consume()
                                        }
                                        else -> {
                                            // Horizontal at 1x — abandon this gesture so the
                                            // parent HorizontalPager can take it. We do not
                                            // consume any events.
                                            return@awaitEachGesture
                                        }
                                    }
                                }
                                Mode.Pan -> {
                                    offset = clampOffset(offset + delta, scale)
                                    change.consume()
                                }
                                Mode.SwipeDown -> {
                                    val next = (dragY + delta.y).coerceAtLeast(0f)
                                    scope.launch { dragYAnim.snapTo(next) }
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                                Mode.Pinch -> {
                                    // Pinch dropped to one finger — keep what we have
                                    // until release. Could switch to pan if zoomed, but
                                    // that adds more state for marginal value.
                                    if (scale > 1f) {
                                        offset = clampOffset(offset + delta, scale)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                }
        )
    }
}

private enum class Mode { Undetermined, Pinch, Pan, SwipeDown }
