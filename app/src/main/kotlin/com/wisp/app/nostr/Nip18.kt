package com.wisp.app.nostr

object Nip18 {
    fun buildRepostTags(event: NostrEvent, relayUrl: String = ""): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("e", event.id, relayUrl))
        tags.add(listOf("p", event.pubkey))
        return tags
    }

    fun buildQuoteTags(event: NostrEvent, relayUrl: String = ""): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("q", event.id, relayUrl))
        tags.add(listOf("p", event.pubkey))
        return tags
    }

    fun appendNoteUri(content: String, eventIdHex: String, relayHints: List<String> = emptyList(), authorHex: String? = null): String {
        val nevent = Nip19.neventEncode(eventIdHex.hexToByteArray(), relayHints, authorHex?.hexToByteArray())
        return "$content\nnostr:$nevent"
    }

    /**
     * If [event] is a kind-6 repost whose `content` parses to an inner kind-1, return the
     * inner event. Otherwise return [event] unchanged. Used by callers (e.g. ThreadViewModel
     * cache seed) that need to operate on the original note rather than the repost wrapper —
     * without this, replies / reactions e-tag the inner id but the model would query the
     * wrapper id.
     */
    fun unwrapRepostForFocal(event: NostrEvent): NostrEvent {
        if (event.kind != 6 || event.content.isEmpty()) return event
        return try {
            val inner = NostrEvent.fromJson(event.content)
            if (inner.kind == 1) inner else event
        } catch (_: Exception) {
            event
        }
    }
}
