package com.wisp.app.ui.component

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

object AudioMediaSession {
    internal var session: MediaSession? = null
        private set
    private var appContext: Context? = null

    fun attach(context: Context, player: Player) {
        if (session?.player === player) return
        val ctx = context.applicationContext
        release()
        appContext = ctx
        session = MediaSession.Builder(ctx, player).setId("wisp-audio").build()
        ctx.startService(Intent(ctx, WispPlaybackService::class.java))
    }

    fun release() {
        session?.release()
        session = null
        appContext?.let {
            // Only stop the service if video isn't also holding it.
            if (VideoMediaSession.session == null) {
                it.stopService(Intent(it, WispPlaybackService::class.java))
            }
        }
        appContext = null
    }
}
