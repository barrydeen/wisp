package cooking.zap.app.nostr

import java.net.URI

/**
 * NIP-22 comments (kind 1111).
 *
 * A comment is always scoped to a root — either a nostr event (uppercase `E`/`A`
 * tags) or an external identifier (an uppercase `I` tag, per NIP-73: a URL,
 * podcast GUID, geohash, ISBN…). Uppercase tags name the *root* scope; lowercase
 * tags name the *immediate parent*; so a top-level comment repeats the same
 * value in both.
 *
 * Only the external-root case is rendered specially: a comment rooted on a nostr
 * event already shows its parent inline, so it needs no extra treatment. See
 * [externalRoot] and [ExternalRef].
 */
object Nip22 {
    const val KIND_COMMENT = 1111

    /**
     * The external thing a comment is scoped to, when the scope isn't a nostr
     * event. [kind] is NIP-73's identifier type (`web`, `podcast:item:guid`,
     * `isbn`, …); [value] is the identifier itself; [hint] is the tag's optional
     * third position — for non-URL identifiers this is where a human-openable
     * page lives (e.g. a podcast GUID pointing at its episode page).
     */
    data class ExternalRef(
        val value: String,
        val kind: String,
        val hint: String?
    ) {
        /**
         * The URI a "view the original" affordance should open, if any. Prefers
         * the hint, since for non-`web` kinds the value itself isn't openable
         * (`podcast:item:guid:…` is an identifier, not a link). Only `http(s)`,
         * so a `javascript:` value in an `I` tag can't become a tappable link.
         */
        val openableUri: URI?
            get() {
                hint?.let { h -> openableHttp(h)?.let { return it } }
                if (kind == "web") return openableHttp(value)
                return null
            }

        /** Host shown as the source label, e.g. "bitcoinmagazine.com" (strips `www.`). */
        val displayHost: String?
            get() = openableUri?.host?.removePrefix("www.")

        private fun openableHttp(raw: String): URI? =
            runCatching { URI(raw) }.getOrNull()?.let { u ->
                if ((u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()) u else null
            }
    }

    fun isComment(event: NostrEvent): Boolean = event.kind == KIND_COMMENT

    /**
     * The comment's root scope when it's external (an uppercase `I` tag), else null.
     * Returns null for comments rooted on a nostr event (uppercase `E`/`A`) — those
     * already render with their parent inline, so they need no extra treatment.
     */
    fun externalRoot(event: NostrEvent): ExternalRef? {
        if (!isComment(event)) return null
        // Only treat I as the root when no uppercase E/A root is present, matching
        // the spec's "root scope" exclusivity.
        val hasEventRoot = event.tags.any { it.isNotEmpty() && (it[0] == "E" || it[0] == "A") }
        if (hasEventRoot) return null
        val iTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "I" } ?: return null
        val kind = event.tags.firstOrNull { it.size >= 2 && it[0] == "K" }?.get(1) ?: "web"
        val hint = iTag.takeIf { it.size >= 3 && it[2].isNotEmpty() }?.get(2)
        return ExternalRef(value = iTag[1], kind = kind, hint = hint)
    }

    /**
     * The comment's immediate parent when it's external (a lowercase `i` tag).
     * Equals the root for a top-level comment; differs when replying to another
     * comment on the same external item.
     */
    fun externalParent(event: NostrEvent): ExternalRef? {
        if (!isComment(event)) return null
        val hasEventParent = event.tags.any { it.isNotEmpty() && (it[0] == "e" || it[0] == "a") }
        if (hasEventParent) return null
        val iTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "i" } ?: return null
        val kind = event.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1) ?: "web"
        val hint = iTag.takeIf { it.size >= 3 && it[2].isNotEmpty() }?.get(2)
        return ExternalRef(value = iTag[1], kind = kind, hint = hint)
    }

    /**
     * Build the tag set for a kind-1111 reply to [parent], carrying its root scope
     * forward unchanged and pointing the lowercase tags at [parent]. Returns null
     * when [parent] isn't an external-rooted comment — callers fall back to NIP-10
     * kind-1 threading. NIP-22 forbids answering a comment with a kind-1, so a
     * reply to a comment must stay kind 1111.
     */
    fun buildReplyTags(parent: NostrEvent, relayHint: String = ""): List<List<String>>? {
        val root = externalRoot(parent) ?: return null
        val tags = mutableListOf<List<String>>()
        val rootTag = mutableListOf("I", root.value)
        root.hint?.let { rootTag.add(it) }
        tags.add(rootTag)
        tags.add(listOf("K", root.kind))
        // Parent is the comment itself — an event — so the lowercase side uses
        // e/k/p rather than repeating the I tag.
        tags.add(listOf("e", parent.id, relayHint, parent.pubkey))
        tags.add(listOf("k", KIND_COMMENT.toString()))
        tags.add(listOf("p", parent.pubkey))
        // Carry the root author forward when the parent named one.
        parent.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.let { tags.add(it) }
        return tags
    }
}
