package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wisp.app.R
import com.wisp.app.util.MediaDownloader
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        var dismissDragY by remember { mutableFloatStateOf(0f) }
        // Background fades out as the user pulls the image downward, matching
        // iOS `max(0.3, 1.0 - dismissY / 250.0)`. Stays fully opaque when not
        // dragging.
        val backgroundAlpha = max(0.3f, 1f - dismissDragY / 250f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha)),
            contentAlignment = Alignment.Center
        ) {
            ZoomableAsyncImage(
                model = imageUrl,
                contentDescription = "Full screen image",
                onSwipeDownDismiss = onDismiss,
                onDismissDrag = { dismissDragY = it }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                val buttonColors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )

                IconButton(
                    onClick = { scope.launch { MediaDownloader.downloadMedia(context, imageUrl) } },
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
                    onClick = { clipboardManager.setText(AnnotatedString(imageUrl)) },
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
