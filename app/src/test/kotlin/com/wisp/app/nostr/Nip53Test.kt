package com.wisp.app.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sidecar's liveness rule: a kind-30311 counts as live only while it says
 * "live", carries a streaming URL, and was republished inside the freshness
 * window — abandoned events stay stuck on status "live" otherwise.
 */
class Nip53Test {
    private val now = 1_800_000_000L

    private fun activity(
        status: String? = "live",
        streamingUrl: String? = "https://stream.example/live.m3u8",
        createdAt: Long = now - 60
    ): Nip53.LiveActivity = Nip53.LiveActivity(
        dTag = "stream",
        hostPubkey = "a".repeat(64),
        streamerPubkey = null,
        title = null,
        summary = null,
        image = null,
        status = status,
        streamingUrl = streamingUrl,
        participants = emptyList(),
        relayHints = emptyList(),
        createdAt = createdAt
    )

    @Test
    fun `a fresh live event with a streaming url is live`() {
        assertTrue(Nip53.isLiveNow(activity(), now))
    }

    @Test
    fun `an ended event is not live regardless of freshness`() {
        assertFalse(Nip53.isLiveNow(activity(status = "ended"), now))
        assertFalse(Nip53.isLiveNow(activity(status = "planned"), now))
    }

    @Test
    fun `a live event without a streaming url is not live`() {
        assertFalse(Nip53.isLiveNow(activity(streamingUrl = null), now))
        assertFalse(Nip53.isLiveNow(activity(streamingUrl = ""), now))
    }

    @Test
    fun `a live event older than the freshness window is not live`() {
        assertTrue(Nip53.isLiveNow(activity(createdAt = now - Nip53.LIVE_FRESHNESS_SECONDS + 1), now))
        assertFalse(Nip53.isLiveNow(activity(createdAt = now - Nip53.LIVE_FRESHNESS_SECONDS), now))
        assertFalse(Nip53.isLiveNow(activity(createdAt = now - Nip53.LIVE_FRESHNESS_SECONDS - 3600), now))
    }
}
