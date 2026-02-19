package com.wisp.app.nostr

object Nip10 {
    /**
     * Build reply tags for a new event replying to [replyTo].
     * If the original event has a root tag, use it as root and [replyTo] as reply.
     * Otherwise, [replyTo] becomes the root.
     * Also adds p-tags for the original author.
     */
    /**
     * Returns the event ID this event directly replies to.
     * Checks "reply" marker first, then "root", then falls back to last e-tag (legacy).
     */
    fun getReplyTarget(event: NostrEvent): String? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        // Prefer marked reply tag
        eTags.firstOrNull { it.size >= 4 && it[3] == "reply" }?.let { return it[1] }
        // Fall back to root marker (direct reply to root)
        eTags.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        // Legacy: last e-tag is the reply target
        return eTags.lastOrNull()?.get(1)
    }

    /**
     * Returns the root event ID of the thread.
     * Checks "root" marker first, falls back to first e-tag (legacy).
     */
    fun getRootId(event: NostrEvent): String? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        eTags.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return eTags.firstOrNull()?.get(1)
    }

    fun buildReplyTags(replyTo: NostrEvent, relayHint: String = ""): List<List<String>> {
        val tags = mutableListOf<List<String>>()

        // Find existing root tag in the event we're replying to
        val existingRoot = replyTo.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "root" }

        if (existingRoot != null) {
            // Thread reply: keep the original root hint, mark replyTo as reply
            tags.add(listOf("e", existingRoot[1], existingRoot.getOrElse(2) { "" }, "root"))
            tags.add(listOf("e", replyTo.id, relayHint, "reply"))
        } else {
            // Direct reply: replyTo is the root
            tags.add(listOf("e", replyTo.id, relayHint, "root"))
        }

        // Add p-tag for the author we're replying to
        tags.add(listOf("p", replyTo.pubkey))

        return tags
    }
}
