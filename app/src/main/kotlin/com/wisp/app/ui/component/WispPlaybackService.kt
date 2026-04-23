package com.wisp.app.ui.component

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class WispPlaybackService : MediaSessionService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        VideoMediaSession.session?.let { addSession(it) }
        AudioMediaSession.session?.let { addSession(it) }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Prefer audio when both exist (more likely to be the backgrounded stream).
        return AudioMediaSession.session ?: VideoMediaSession.session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val audio = AudioMediaSession.session
        val video = VideoMediaSession.session
        val audioActive = audio != null && audio.player.playWhenReady
        val videoActive = video != null && video.player.playWhenReady
        if (!audioActive && !videoActive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        VideoMediaSession.session?.let { removeSession(it) }
        AudioMediaSession.session?.let { removeSession(it) }
        super.onDestroy()
    }
}
