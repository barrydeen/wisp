package com.wisp.app.ui.component

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.wisp.app.R
import kotlinx.coroutines.flow.MutableStateFlow

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
fun FloatingVideoPlayer() {
    val state by PipController.pipState.collectAsState()
    val currentState = state ?: return
    val context = LocalContext.current

    DisposableEffect(currentState.url) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val density = activity.resources.displayMetrics.density
        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val pipWidthPx = (200 * density).toInt()
        val pipHeightPx = ((200f / currentState.aspectRatio).coerceAtMost(260f) * density).toInt()
        val marginPx = (12 * density).toInt()
        val bottomMarginPx = (72 * density).toInt()
        val buttonSize = (28 * density).toInt()
        val buttonPadding = (4 * density).toInt()
        val fsButtonSize = (40 * density).toInt()
        val fsButtonMargin = (16 * density).toInt()
        val fsButtonSpacing = (8 * density).toInt()

        var isFullScreen = false

        // Saved PiP position for restoring after fullscreen
        var savedPipX = screenWidth - pipWidthPx - marginPx
        var savedPipY = screenHeight - pipHeightPx - bottomMarginPx

        val pipDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        pipDialog.setCancelable(false)

        // --- PiP UI ---
        var lastTouchX = 0f
        var lastTouchY = 0f
        var isDragging = false

        val pipRoot = FrameLayout(activity)
        pipRoot.setOnTouchListener { _, event ->
            if (isFullScreen) return@setOnTouchListener false
            val params = pipDialog.window?.attributes ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    if (!isDragging && (dx * dx + dy * dy) > (10 * density) * (10 * density)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        pipDialog.window?.attributes = params
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                    }
                    true
                }
                else -> false
            }
        }

        val playerView = PlayerView(activity).apply {
            player = currentState.player
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        pipRoot.addView(playerView)

        // --- PiP overlay buttons ---
        val pipButtonsOverlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val closeButton = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close"
            setBackgroundColor(0x99000000.toInt())
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END).apply {
                setMargins(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
            }
            setOnClickListener { PipController.exitPip() }
        }
        pipButtonsOverlay.addView(closeButton)

        val expandButton = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_fullscreen)
            contentDescription = "Fullscreen"
            setBackgroundColor(0x99000000.toInt())
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.START).apply {
                setMargins(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
            }
        }
        pipButtonsOverlay.addView(expandButton)
        pipRoot.addView(pipButtonsOverlay)

        // --- Fullscreen overlay buttons ---
        val fsButtonsOverlay = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(fsButtonMargin, fsButtonMargin, fsButtonMargin, fsButtonMargin) }
            visibility = android.view.View.GONE
        }

        fun makeFsButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(activity).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundColor(0x80000000.toInt())
                setColorFilter(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(fsButtonSize, fsButtonSize).apply {
                    marginEnd = fsButtonSpacing
                }
                setOnClickListener { onClick() }
            }
        }

        val fsPipButton = makeFsButton(R.drawable.ic_pip, "Mini player") {
            // Exit fullscreen, return to PiP
            goToPipMode(pipDialog, pipRoot, pipButtonsOverlay, fsButtonsOverlay,
                playerView, pipWidthPx, pipHeightPx, savedPipX, savedPipY, activity)
            isFullScreen = false
        }
        fsButtonsOverlay.addView(fsPipButton)

        val fsCloseButton = makeFsButton(android.R.drawable.ic_menu_close_clear_cancel, "Close") {
            PipController.exitPip()
        }
        fsButtonsOverlay.addView(fsCloseButton)

        pipRoot.addView(fsButtonsOverlay)

        // --- Expand button action ---
        expandButton.setOnClickListener {
            // Save current PiP position
            pipDialog.window?.attributes?.let { attrs ->
                savedPipX = attrs.x
                savedPipY = attrs.y
            }
            goToFullScreen(pipDialog, pipRoot, pipButtonsOverlay, fsButtonsOverlay,
                playerView, screenWidth, screenHeight, activity)
            isFullScreen = true
        }

        // --- Back button handling for fullscreen ---
        pipDialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP && isFullScreen) {
                goToPipMode(pipDialog, pipRoot, pipButtonsOverlay, fsButtonsOverlay,
                    playerView, pipWidthPx, pipHeightPx, savedPipX, savedPipY, activity)
                isFullScreen = false
                true
            } else {
                false
            }
        }

        // --- Initial PiP setup ---
        pipDialog.setContentView(pipRoot, FrameLayout.LayoutParams(pipWidthPx, pipHeightPx))

        pipDialog.window?.let { window ->
            window.setLayout(pipWidthPx, pipHeightPx)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedPipX
                y = savedPipY
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
        }

        pipDialog.show()

        currentState.player.volume = if (PipController.globalMuted.value) 0f else 1f

        onDispose {
            if (pipDialog.isShowing) pipDialog.dismiss()
            val current = PipController.pipState.value
            if (current?.url == currentState.url) {
                PipController.exitPip()
            }
        }
    }
}

private fun goToFullScreen(
    dialog: Dialog,
    root: FrameLayout,
    pipButtons: FrameLayout,
    fsButtons: LinearLayout,
    playerView: PlayerView,
    screenWidth: Int,
    screenHeight: Int,
    activity: Activity
) {
    pipButtons.visibility = android.view.View.GONE
    fsButtons.visibility = android.view.View.VISIBLE
    playerView.useController = true

    dialog.window?.let { window ->
        window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    root.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
}

@OptIn(UnstableApi::class)
private fun goToPipMode(
    dialog: Dialog,
    root: FrameLayout,
    pipButtons: FrameLayout,
    fsButtons: LinearLayout,
    playerView: PlayerView,
    pipWidthPx: Int,
    pipHeightPx: Int,
    pipX: Int,
    pipY: Int,
    activity: Activity
) {
    pipButtons.visibility = android.view.View.VISIBLE
    fsButtons.visibility = android.view.View.GONE
    playerView.useController = false

    dialog.window?.let { window ->
        // Restore system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())

        window.setLayout(pipWidthPx, pipHeightPx)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            x = pipX
            y = pipY
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    root.layoutParams = FrameLayout.LayoutParams(pipWidthPx, pipHeightPx)
    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
