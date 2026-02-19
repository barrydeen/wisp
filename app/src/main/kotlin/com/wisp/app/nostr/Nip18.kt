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

    fun appendNoteUri(content: String, eventIdHex: String): String {
        val noteId = Nip19.noteEncode(eventIdHex.hexToByteArray())
        return "$content\nnostr:$noteId"
    }
}
