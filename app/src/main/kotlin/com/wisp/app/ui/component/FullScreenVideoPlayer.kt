package com.wisp.app.ui.component

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wisp.app.R
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.util.MediaDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class FullScreenVideoRequest(
    val url: String,
    val startPositionMs: Long = 0L
)

object FullScreenVideoState {
    val request = MutableStateFlow<FullScreenVideoRequest?>(null)

    fun enter(url: String, positionMs: Long = 0L) {
        request.value = FullScreenVideoRequest(url, positionMs)
    }

    fun dismiss() {
        request.value = null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    videoUrl: String,
    startPositionMs: Long = 0L,
    onDismiss: () -> Unit,
    existingPlayer: ExoPlayer? = null,
    onMinimizeToPip: ((ExoPlayer, Long) -> Unit)? = null
) {
    val context = LocalContext.current

    DisposableEffect(videoUrl) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}

        val ownsPlayer = existingPlayer == null
        val exoPlayer = existingPlayer ?: HttpClientFactory.createExoPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            prepare()
            seekTo(startPositionMs)
            playWhenReady = true
        }

        var minimizedToPip = false

        fun minimizeToPip() {
            if (onMinimizeToPip != null && !minimizedToPip) {
                minimizedToPip = true
                onMinimizeToPip(exoPlayer, exoPlayer.currentPosition)
            }
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (onMinimizeToPip != null && !minimizedToPip) {
                minimizeToPip()
            }
            onDismiss()
        }

        val root = FrameLayout(activity)

        val playerView = PlayerView(activity).apply {
            player = exoPlayer
            useController = true
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        root.addView(playerView)

        val buttonSize = (40 * activity.resources.displayMetrics.density).toInt()
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val spacing = (8 * activity.resources.displayMetrics.density).toInt()

        val buttonBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(padding, padding, padding, padding) }
        }

        fun makeButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(activity).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundColor(0x80000000.toInt())
                setColorFilter(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    marginEnd = spacing
                }
                setOnClickListener { onClick() }
            }
        }

        if (onMinimizeToPip != null) {
            buttonBar.addView(makeButton(R.drawable.ic_pip, "Mini player") {
                minimizeToPip()
                dialog.dismiss()
            })
        }

        buttonBar.addView(makeButton(R.drawable.ic_download, "Download") {
            CoroutineScope(Dispatchers.Main).launch {
                MediaDownloader.downloadMedia(activity, videoUrl)
            }
        })

        buttonBar.addView(makeButton(android.R.drawable.ic_menu_share, "Copy URL") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", videoUrl))
            Toast.makeText(activity, "URL copied", Toast.LENGTH_SHORT).show()
        })

        buttonBar.addView(makeButton(android.R.drawable.ic_menu_close_clear_cancel, "Close") {
            if (onMinimizeToPip != null) {
                minimizeToPip()
            }
            dialog.dismiss()
        })

        root.addView(buttonBar)
        dialog.setContentView(root)

        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        dialog.show()

        onDispose {
            if (ownsPlayer) exoPlayer.release()
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
