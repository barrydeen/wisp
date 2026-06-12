package com.wisp.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Inline carousel for posts containing two or more media items. Mirrors the
 * iOS `MediaGridView`: a horizontally paged row of 4:5 tiles, ~70% of the
 * post width with the next tile peeking from the right edge. Tap a tile to
 * open a full-screen viewer.
 *
 * Videos render as their thumbhash/blurhash placeholder with a play overlay;
 * tapping invokes [onFullScreenVideo] which routes to the existing single-
 * video full-screen player. Tapping an image tile invokes [onFullScreenImage]
 * with the image-only URL list and the position of the tapped item in that
 * list, letting the caller open a multi-image pager at the right page.
 */
internal sealed interface CarouselItem {
    val meta: MediaMeta
    data class Image(override val meta: MediaMeta) : CarouselItem
    data class Video(override val meta: MediaMeta) : CarouselItem
    data class Unknown(override val meta: MediaMeta) : CarouselItem
}

@Composable
internal fun MediaCarousel(
    items: List<CarouselItem>,
    onOpenPager: (startIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })

    // Tile is sized so the next one peeks ~48dp from the right edge. Using
    // PageSize.Fixed (rather than contentPadding) means the pager naturally
    // snaps the last page flush to the right edge instead of leaving an empty
    // strip after the final image.
    val peekWidth = 48.dp
    val pageSpacing = 8.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tileWidth = maxWidth - peekWidth - pageSpacing
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(tileWidth),
            pageSpacing = pageSpacing,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            CarouselTile(
                item = items[page],
                onTap = { onOpenPager(page) }
            )
        }

        if (items.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun CarouselTile(
    item: CarouselItem,
    onTap: () -> Unit
) {
    val meta = item.meta
    val placeholder = rememberMediaPlaceholderPainter(meta.thumbhash, meta.blurhash, meta.dimension)
    val tileShape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is CarouselItem.Image, is CarouselItem.Unknown -> {
                AsyncImage(
                    model = meta.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = placeholder,
                    error = placeholder,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is CarouselItem.Video -> {
                if (meta.image != null) {
                    // Uploader-provided preview frame (NIP-92 imeta "image")
                    AsyncImage(
                        model = meta.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholder = placeholder,
                        error = placeholder,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (placeholder != null) {
                    Image(
                        painter = placeholder,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
