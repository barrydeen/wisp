package com.wisp.app.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/** Shared keep-awake helper so every video surface (inline feed players, the
 *  PiP mini player, live streams) can hold FLAG_KEEP_SCREEN_ON the same way
 *  the fullscreen player's dialog window already does. */

internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** Per-window holder registry: FLAG_KEEP_SCREEN_ON is one bit on a window
 *  several surfaces share, so a bare boolean lets one surface's pause (an
 *  inline player embedded in the live stream screen) clear another surface's
 *  hold. Each surface declares itself with [updateKeepScreenOn] on mount /
 *  play and drops itself on pause / dispose; the flag stays up while any
 *  holder wants it. Keyed weakly so dead activities release their sets. */
private val windowKeepAwakeHolders = WeakHashMap<Window, MutableSet<String>>()

/** Adds or removes `holder`'s claim on the hosting Activity's keep-awake
 *  flag. Pass the player's isPlaying state: pause / playback end /
 *  interruption drop the claim so normal idle behavior resumes. */
internal fun Context.updateKeepScreenOn(holder: String, playing: Boolean) {
    val window = findHostActivity()?.window ?: return
    synchronized(windowKeepAwakeHolders) {
        val holders = windowKeepAwakeHolders.getOrPut(window) { mutableSetOf() }
        if (playing) holders.add(holder) else holders.remove(holder)
        if (holders.isNotEmpty()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
