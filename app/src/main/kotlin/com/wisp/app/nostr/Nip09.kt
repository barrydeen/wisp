package com.wisp.app.nostr

object Nip09 {
    fun buildDeletionTags(eventId: String, kind: Int): List<List<String>> {
        return listOf(
            listOf("e", eventId),
            listOf("k", kind.toString())
        )
    }
}
