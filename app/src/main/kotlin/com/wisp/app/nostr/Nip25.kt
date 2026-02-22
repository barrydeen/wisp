package com.wisp.app.nostr

object Nip25 {
    fun buildReactionTags(targetEvent: NostrEvent): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("e", targetEvent.id))
        tags.add(listOf("p", targetEvent.pubkey))
        tags.add(listOf("k", targetEvent.kind.toString()))
        return tags
    }

    fun buildReactionTagsWithEmoji(targetEvent: NostrEvent, emoji: CustomEmoji): List<List<String>> {
        val tags = buildReactionTags(targetEvent).toMutableList()
        tags.add(Nip30.buildEmojiTag(emoji))
        return tags
    }
}
