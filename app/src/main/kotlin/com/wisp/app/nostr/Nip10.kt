package com.wisp.app.nostr

object Nip10 {
    /**
     * Build reply tags for a new event replying to [replyTo].
     * If the original event has a root tag, use it as root and [replyTo] as reply.
     * Otherwise, [replyTo] becomes the root.
     * Also adds p-tags for the original author.
     */
    /**
     * Returns true if the event is a reply to another event.
     * E-tags with the "mention" marker (used for quote posts) are not considered replies.
     */
    fun isReply(event: NostrEvent): Boolean =
        event.tags.any { it.size >= 2 && it[0] == "e" && it.getOrNull(3) != "mention" }

    /**
     * Returns the event ID this event directly replies to.
     * Checks "reply" marker first, then "root", then falls back to last e-tag (legacy).
     */
    fun getReplyTarget(event: NostrEvent): String? =
        getReplyTargetWithHint(event)?.first

    /**
     * Returns the reply target event ID paired with its relay hint (if present).
     */
    fun getReplyTargetWithHint(event: NostrEvent): Pair<String, String?>? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" && it.getOrNull(3) != "mention" }
        // Prefer marked reply tag
        eTags.firstOrNull { it.size >= 4 && it[3] == "reply" }?.let {
            return it[1] to it.getOrNull(2)?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") }
        }
        // Fall back to root marker (direct reply to root)
        eTags.firstOrNull { it.size >= 4 && it[3] == "root" }?.let {
            return it[1] to it.getOrNull(2)?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") }
        }
        // Legacy: last e-tag is the reply target
        return eTags.lastOrNull()?.let {
            it[1] to it.getOrNull(2)?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") }
        }
    }

    /**
     * Returns the root event ID of the thread.
     * Checks "root" marker first, falls back to first e-tag (legacy).
     */
    fun getRootId(event: NostrEvent): String? =
        getRootIdWithHint(event)?.first

    /**
     * Returns the root event ID paired with its relay hint (if present).
     */
    fun getRootIdWithHint(event: NostrEvent): Pair<String, String?>? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" && it.getOrNull(3) != "mention" }
        eTags.firstOrNull { it.size >= 4 && it[3] == "root" }?.let {
            return it[1] to it.getOrNull(2)?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") }
        }
        return eTags.firstOrNull()?.let {
            it[1] to it.getOrNull(2)?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") }
        }
    }

    /**
     * Extracts all relay hints from e-tags of an event, keyed by event ID.
     */
    fun extractETagRelayHints(event: NostrEvent): Map<String, String> {
        val hints = mutableMapOf<String, String>()
        for (tag in event.tags) {
            if (tag.size >= 3 && tag[0] == "e") {
                val url = tag[2]
                if (url.startsWith("wss://") || url.startsWith("ws://")) {
                    hints[tag[1]] = url
                }
            }
        }
        return hints
    }

    /**
     * Returns true if the event is a standalone quote (has a `q` tag but no marked
     * `e` tags with root/reply markers). These should not appear in thread views.
     */
    fun isStandaloneQuote(event: NostrEvent): Boolean {
        val hasQTag = event.tags.any { it.size >= 2 && it[0] == "q" }
        if (!hasQTag) return false
        val hasMarkedETag = event.tags.any {
            it.size >= 4 && it[0] == "e" && (it[3] == "root" || it[3] == "reply")
        }
        return !hasMarkedETag
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
