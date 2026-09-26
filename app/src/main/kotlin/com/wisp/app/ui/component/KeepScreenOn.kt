package com.wisp.app.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

/** Shared keep-awake helper so every video surface (inline feed players, the
 *  PiP mini player, live streams) can hold FLAG_KEEP_SCREEN_ON the same way
 *  the fullscreen player's dialog window already does. */

internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** Sets or clears FLAG_KEEP_SCREEN_ON on the hosting Activity's window. Pass
 *  the player's isPlaying state: pause / playback end / interruption clear the
 *  flag so normal idle behavior resumes. */
internal fun Context.updateKeepScreenOn(playing: Boolean) {
    findHostActivity()?.window?.let { window ->
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
