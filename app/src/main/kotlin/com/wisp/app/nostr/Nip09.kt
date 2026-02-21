package com.wisp.app.nostr

object Nip09 {
    fun buildDeletionTags(eventId: String, kind: Int): List<List<String>> {
        return listOf(
            listOf("e", eventId),
            listOf("k", kind.toString())
        )
    }

    /** Build deletion tags for an addressable event (kinds 30000-39999) using an "a" tag. */
    fun buildAddressableDeletionTags(kind: Int, pubkey: String, dTag: String): List<List<String>> {
        return listOf(
            listOf("a", "$kind:$pubkey:$dTag"),
            listOf("k", kind.toString())
        )
    }
}
