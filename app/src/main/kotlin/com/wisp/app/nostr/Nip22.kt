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
}
