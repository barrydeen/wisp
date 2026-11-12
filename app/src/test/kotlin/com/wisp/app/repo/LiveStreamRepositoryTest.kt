package com.wisp.app.repo

import com.wisp.app.nostr.Nip53
import com.wisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kind-30311 is replaceable, and the discovery subscription has no `since` —
 * relays may deliver an older copy after a newer one. An out-of-order copy
 * must never remove a live stream from the pill row or roll one back.
 */
class LiveStreamRepositoryTest {
    private val now = 1_800_000_000L
    private val host = "a".repeat(64)

    private fun liveEvent(createdAt: Long, title: String = "live"): NostrEvent =
        NostrEvent.createUnsigned(
            pubkeyHex = host,
            kind = Nip53.KIND_LIVE_ACTIVITY,
            content = "",
            tags = listOf(
                listOf("d", "stream"),
                listOf("title", title),
                listOf("status", "live"),
                listOf("streaming", "https://stream.example/live.m3u8")
            ),
            createdAt = createdAt
        )

    private fun endedEvent(createdAt: Long): NostrEvent =
        NostrEvent.createUnsigned(
            pubkeyHex = host,
            kind = Nip53.KIND_LIVE_ACTIVITY,
            content = "",
            tags = listOf(
                listOf("d", "stream"),
                listOf("status", "ended")
            ),
            createdAt = createdAt
        )

    @Test
    fun `a live event enters the pill row`() {
        val repo = LiveStreamRepository()
        repo.addActivity(liveEvent(createdAt = now - 60))
        assertEquals(1, repo.liveStreams.value.size)
        assertTrue(Nip53.isLiveNow(repo.liveStreams.value.values.single().activity, now))
    }

    @Test
    fun `an out-of-order ended copy does not remove a live stream`() {
        val repo = LiveStreamRepository()
        repo.addActivity(liveEvent(createdAt = now - 60))
        repo.addActivity(endedEvent(createdAt = now - 600))
        assertEquals(1, repo.liveStreams.value.size)
    }

    @Test
    fun `an out-of-order live copy does not roll a newer live event back`() {
        val repo = LiveStreamRepository()
        repo.addActivity(liveEvent(createdAt = now - 60, title = "newer"))
        repo.addActivity(liveEvent(createdAt = now - 600, title = "older"))
        val stream = repo.liveStreams.value.values.single()
        assertEquals("newer", stream.activity.title)
        assertEquals(now - 60, stream.activity.createdAt)
    }

    @Test
    fun `an ended event newer than the live stream removes it`() {
        val repo = LiveStreamRepository()
        repo.addActivity(liveEvent(createdAt = now - 600))
        repo.addActivity(endedEvent(createdAt = now - 60))
        assertNull(repo.liveStreams.value[Nip53.aTagValue(host, "stream")])
    }

    @Test
    fun `a newer live event replaces an ended entry`() {
        val repo = LiveStreamRepository()
        repo.addActivity(endedEvent(createdAt = now - 600))
        repo.addActivity(liveEvent(createdAt = now - 60))
        assertEquals(1, repo.liveStreams.value.size)
    }
}
