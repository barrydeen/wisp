package com.wisp.app.ui.component

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioItemStateTest {
    private val playing = AudioPlaybackState(
        track = AudioTrack(url = "https://example.com/active.mp3"),
        isPlaying = true,
        positionMs = 0L,
        durationMs = 10_000L,
        bufferedMs = 10_000L,
        speed = 1f,
        isBuffering = false
    )

    @Test
    fun inactiveItemIgnoresProgressAndPlaybackChanges() = runBlocking {
        val states = flowOf(null, playing, playing.copy(positionMs = 250L), playing.copy(isPlaying = false), null)
            .forAudioItem("https://example.com/inactive.mp3").toList()

        assertEquals(listOf<AudioPlaybackState?>(null), states)
    }

    @Test
    fun activeItemGetsProgressPauseAndTrackSwitch() = runBlocking {
        val progressed = playing.copy(positionMs = 250L)
        val paused = progressed.copy(isPlaying = false)
        val otherTrack = playing.copy(track = AudioTrack(url = "https://example.com/other.mp3"))
        val states = flowOf(null, playing, playing, progressed, paused, otherTrack, otherTrack.copy(positionMs = 250L))
            .forAudioItem(playing.track.url).toList()

        assertEquals(listOf(null, playing, progressed, paused, null), states)
    }

    @Test
    fun unresolvedAudioUrlNeverBecomesActive() = runBlocking {
        val states = flowOf(null, playing, playing.copy(positionMs = 250L))
            .forAudioItem(null).toList()

        assertEquals(listOf<AudioPlaybackState?>(null), states)
    }
}
