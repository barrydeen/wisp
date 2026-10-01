package com.wisp.app.nostr

object Nip22 {
    const val KIND_COMMENT = 1111

    /**
     * Root scope of a comment: the uppercase `E` tag per NIP-22. Lowercase `e`
     * names only the immediate parent, so for a reply-to-a-comment this is the
     * only pointer to the thread root.
     */
    fun getRootScopeId(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)

    /**
     * True if the event belongs to the thread rooted at [rootId], either as a
     * direct reply (lowercase `e`) or via NIP-22 root scope (uppercase `E`).
     */
    fun referencesRoot(event: NostrEvent, rootId: String): Boolean =
        event.tags.any { it.size >= 2 && (it[0] == "e" || it[0] == "E") && it[1] == rootId }

    /**
     * NIP-22 tags for a comment replying to [replyTo] (which may be any kind, but
     * this is what we publish when replying to a 1111). Lowercase `e` names only
     * the parent comment; the thread root travels in the uppercase `E` scope, so
     * the nested comment is fetched via `#E` and validated with [referencesRoot].
     * Addressable-target scopes (`a`/`A`, e.g. article coordinates) are copied from
     * the parent so replies stay fetchable via `#a`.
     */
    fun buildCommentTags(replyTo: NostrEvent, relayHint: String = ""): List<List<String>> {
        val rootScope = getRootScopeId(replyTo) ?: Nip10.getRootId(replyTo) ?: replyTo.id
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("E", rootScope))
        tags.add(listOf("e", replyTo.id, relayHint, "reply"))
        tags.add(listOf("p", replyTo.pubkey))
        tags.add(listOf("k", replyTo.kind.toString()))
        for (tag in replyTo.tags) {
            if (tag.size >= 2 && (tag[0] == "a" || tag[0] == "A")) tags.add(tag)
        }
        return tags
    }

    /**
     * True if [event] is a kind 1 note replying to a kind 1111 comment. Comment
     * threads are a 1111-only namespace: a stray kind 1 there belongs to the main
     * feed, not the comment subtree, so thread views ignore it. Detected via the
     * `k` tag when present, or by resolving the parent's kind through [parentKindOf].
     */
    fun isStrayKind1OnComment(event: NostrEvent, parentKindOf: (String) -> Int?): Boolean {
        if (event.kind != 1) return false
        if (event.tags.any { it.size >= 2 && it[0] == "k" && it[1].toIntOrNull() == KIND_COMMENT }) return true
        val parentId = Nip10.getReplyTarget(event) ?: return false
        return parentKindOf(parentId) == KIND_COMMENT
    }
}
