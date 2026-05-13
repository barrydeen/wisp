package com.wisp.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Animatable
import com.wisp.app.R
import com.wisp.app.util.MediaDownloader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-screen swipeable viewer for any mixed run of images and videos.
 * Image pages use [ZoomableAsyncImage] for pinch/pan/double-tap and
 * swipe-down-to-dismiss. Video pages show a poster with a centered play
 * overlay; tapping the play button hands off to [FullScreenVideoState] —
 * the existing single-video full-screen player — so the pager itself
 * doesn't have to manage ExoPlayer lifecycles across page changes.
 *
 * Swipe-down dismissal on a video page is handled by the page itself
 * (since [ZoomableAsyncImage] only lives on image pages) using the same
 * direction-locked drag detector pattern: horizontal drags fall through
 * to the HorizontalPager, vertical drags drive the dismiss.
 */
@Composable
fun FullScreenMediaPager(
    items: List<MediaPagerItem>,
    initialPage: Int,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    val startPage = initialPage.coerceIn(0, items.size - 1)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { items.size })
        var dismissDragY by remember { mutableFloatStateOf(0f) }
        // Track each page's contribution so the bg fade only follows the
        // page the user is actually pulling, not a stale value from a page
        // they swiped past.
        val backgroundAlpha = max(0.3f, 1f - dismissDragY / 250f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                when (item) {
                    is MediaPagerItem.Image -> ZoomableAsyncImage(
                        model = item.url,
                        contentDescription = "Image ${page + 1} of ${items.size}",
                        onSwipeDownDismiss = onDismiss,
                        onDismissDrag = { y ->
                            if (page == pagerState.currentPage) dismissDragY = y
                        }
                    )
                    is MediaPagerItem.Video -> VideoPagerPage(
                        url = item.url,
                        posterModel = item.posterModel,
                        onPlay = { FullScreenVideoState.enter(item.url, 0L) },
                        onSwipeDownDismiss = onDismiss,
                        onDismissDrag = { y ->
                            if (page == pagerState.currentPage) dismissDragY = y
                        }
                    )
                }
            }

            if (items.size > 1) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    repeat(items.size) { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                val buttonColors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )

                val currentUrl = items.getOrNull(pagerState.currentPage)?.url

                IconButton(
                    onClick = {
                        currentUrl?.let { url ->
                            scope.launch { MediaDownloader.downloadMedia(context, url) }
                        }
                    },
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Download"
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        currentUrl?.let { clipboardManager.setText(AnnotatedString(it)) }
                    },
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }
    }
}

/** Items the pager knows how to render. */
sealed interface MediaPagerItem {
    val url: String
    data class Image(override val url: String) : MediaPagerItem
    /** [posterModel] is a Coil-compatible model used as a still — typically
     *  the video URL itself (Coil 3's video frame decoder pulls frame 0) or
     *  a thumbhash painter. */
    data class Video(override val url: String, val posterModel: Any? = null) : MediaPagerItem
}

@Composable
private fun VideoPagerPage(
    url: String,
    posterModel: Any?,
    onPlay: () -> Unit,
    onSwipeDownDismiss: () -> Unit,
    onDismissDrag: (Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Same direction-locked drag pattern as ZoomableAsyncImage:
                // horizontal drags fall through to the parent pager,
                // vertical drags drive swipe-to-dismiss.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var verticalLocked = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.size != 1) return@awaitEachGesture
                        val change = event.changes.first()
                        val delta = change.positionChange()
                        if (!verticalLocked) {
                            totalDx += delta.x
                            totalDy += delta.y
                            val crossed = abs(totalDx) > touchSlop || abs(totalDy) > touchSlop
                            if (crossed) {
                                if (abs(totalDy) > abs(totalDx) && totalDy > 0f) {
                                    verticalLocked = true
                                    scope.launch { dragYAnim.snapTo(totalDy) }
                                    change.consume()
                                } else {
                                    return@awaitEachGesture
                                }
                            }
                        } else {
                            val next = (dragY + delta.y).coerceAtLeast(0f)
                            scope.launch { dragYAnim.snapTo(next) }
                            change.consume()
                        }
                        if (!change.pressed) {
                            if (verticalLocked) {
                                if (dragY >= 120f) onSwipeDownDismiss()
                                else scope.launch { dragYAnim.animateTo(0f) }
                            }
                            return@awaitEachGesture
                        }
                    }
                }
            }
    ) {
        if (posterModel != null) {
            coil3.compose.AsyncImage(
                model = posterModel,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(translationY = dragY)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play video",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}
