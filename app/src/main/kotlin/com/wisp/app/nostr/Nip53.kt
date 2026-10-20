package com.wisp.app.nostr

object Nip53 {
    const val KIND_LIVE_ACTIVITY = 30311
    const val KIND_LIVE_CHAT_MESSAGE = 1311

    /**
     * A kind-30311 only counts as live while it was republished within this
     * window. Real streams republish every few minutes; abandoned or test
     * events stay stuck on status "live" forever and would otherwise haunt
     * the LIVE row indefinitely.
     */
    const val LIVE_FRESHNESS_SECONDS = 3600L

    /**
     * Sidecar's liveness rule: status "live" alone is not enough — the event
     * must also carry a streaming URL and have been republished recently.
     */
    fun isLiveNow(activity: LiveActivity, now: Long = System.currentTimeMillis() / 1000): Boolean =
        activity.status?.equals("live", ignoreCase = true) == true &&
            !activity.streamingUrl.isNullOrEmpty() &&
            now - activity.createdAt < LIVE_FRESHNESS_SECONDS

    data class LiveActivity(
        val dTag: String,
        val hostPubkey: String,
        val streamerPubkey: String?,
        val title: String?,
        val summary: String?,
        val image: String?,
        val status: String?,
        val streamingUrl: String?,
        val participants: List<Pair<String, String?>>,
        val relayHints: List<String>,
        val createdAt: Long
    )

    fun parseLiveActivity(event: NostrEvent): LiveActivity? {
        if (event.kind != KIND_LIVE_ACTIVITY) return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        val summary = event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1)
        val image = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val status = event.tags.firstOrNull { it.size >= 2 && it[0] == "status" }?.get(1)
        val streamingUrl = event.tags.firstOrNull { it.size >= 2 && it[0] == "streaming" }?.get(1)
        val participants = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] to it.getOrNull(3) }
        val streamHost = participants.firstOrNull {
            it.second.equals("Host", ignoreCase = true)
        }?.first
        val relayHints = event.tags
            .filter { it.size >= 2 && (it[0] == "relay" || it[0] == "r") && it[1].startsWith("wss://") }
            .map { it[1] }
            .distinct()
        return LiveActivity(
            dTag = dTag,
            hostPubkey = event.pubkey,
            streamerPubkey = streamHost,
            title = title,
            summary = summary,
            image = image,
            status = status,
            streamingUrl = streamingUrl,
            participants = participants,
            relayHints = relayHints,
            createdAt = event.created_at
        )
    }

    fun aTagValue(hostPubkey: String, dTag: String): String =
        "$KIND_LIVE_ACTIVITY:$hostPubkey:$dTag"

    fun getChatActivityRef(event: NostrEvent): String? {
        if (event.kind != KIND_LIVE_CHAT_MESSAGE) return null
        return event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
    }

    fun buildChatMessage(
        privkey: ByteArray,
        pubkey: ByteArray,
        hostPubkey: String,
        dTag: String,
        relayHint: String,
        content: String,
        replyToId: String? = null
    ): NostrEvent {
        val tags = mutableListOf(
            listOf("a", aTagValue(hostPubkey, dTag), relayHint),
            listOf("p", hostPubkey)
        )
        if (replyToId != null) {
            tags.add(listOf("e", replyToId, "", "reply"))
        }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_LIVE_CHAT_MESSAGE,
            content = content,
            tags = tags
        )
    }
}
