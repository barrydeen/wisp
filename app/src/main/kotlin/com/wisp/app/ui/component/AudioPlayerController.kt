package com.wisp.app.ui.component

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioTrack(
    val url: String,
    val title: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val authorPubkey: String? = null
)

data class AudioPlaybackState(
    val track: AudioTrack,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val speed: Float,
    val isBuffering: Boolean
)

object AudioPlayerController {
    private val _state = MutableStateFlow<AudioPlaybackState?>(null)
    val state: StateFlow<AudioPlaybackState?> = _state.asStateFlow()

    private val scope: CoroutineScope = MainScope()
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var pollJob: Job? = null
    private var appContext: Context? = null
    private var currentTrack: AudioTrack? = null

    val speedSteps = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    fun play(context: Context, track: AudioTrack) {
        val ctx = context.applicationContext
        appContext = ctx
        val existing = player
        if (existing != null && currentTrack?.url == track.url) {
            existing.play()
            return
        }

        // Pause any currently-playing video so we don't double-play.
        PipController.pipState.value?.player?.pause()

        val p = existing ?: ExoPlayer.Builder(ctx).build().also { newPlayer ->
            attachListener(newPlayer)
            player = newPlayer
        }

        val item = MediaItem.Builder()
            .setUri(track.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title ?: track.url.substringAfterLast('/').substringBeforeLast('.'))
                    .setArtist(track.artist)
                    .apply { track.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
            )
            .build()

        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true

        currentTrack = track
        _state.value = AudioPlaybackState(
            track = track,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            bufferedMs = 0L,
            speed = p.playbackParameters.speed,
            isBuffering = true
        )

        AudioMediaSession.attach(ctx, p)
        startPolling()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
        updateStateFromPlayer()
    }

    fun skipForward(deltaMs: Long = 15_000L) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtMost(
            if (p.duration > 0) p.duration else Long.MAX_VALUE
        )
        p.seekTo(target)
        updateStateFromPlayer()
    }

    fun skipBackward(deltaMs: Long = 15_000L) {
        val p = player ?: return
        p.seekTo((p.currentPosition - deltaMs).coerceAtLeast(0L))
        updateStateFromPlayer()
    }

    fun cycleSpeed() {
        val p = player ?: return
        val current = p.playbackParameters.speed
        val idx = speedSteps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val next = speedSteps[(if (idx < 0) 1 else idx + 1) % speedSteps.size]
        p.playbackParameters = PlaybackParameters(next)
        updateStateFromPlayer()
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        updateStateFromPlayer()
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
        listener?.let { l -> player?.removeListener(l) }
        listener = null
        AudioMediaSession.release()
        player?.release()
        player = null
        currentTrack = null
        _state.value = null
    }

    private fun attachListener(p: ExoPlayer) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateStateFromPlayer()
                if (isPlaying) startPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    p.pause()
                    p.seekTo(0L)
                }
                updateStateFromPlayer()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                updateStateFromPlayer()
            }
        }
        p.addListener(l)
        listener = l
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.Main) {
            while (true) {
                updateStateFromPlayer()
                val p = player ?: break
                if (!p.isPlaying) break
                delay(250L)
            }
        }
    }

    private fun updateStateFromPlayer() {
        val p = player ?: return
        val track = currentTrack ?: return
        _state.value = AudioPlaybackState(
            track = track,
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.coerceAtLeast(0L),
            bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
            speed = p.playbackParameters.speed,
            isBuffering = p.playbackState == Player.STATE_BUFFERING
        )
    }
}
