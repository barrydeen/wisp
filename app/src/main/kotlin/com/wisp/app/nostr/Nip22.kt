package com.wisp.app.nostr

/**
 * NIP-22 comments (kind 1111).
 *
 * Partial: enough to *read* comment threads. A comment is scoped to a root by
 * uppercase `E`/`A`/`I` tags, with lowercase tags naming the immediate parent.
 *
 * Composing replies is not handled here. NIP-22 forbids answering a comment with
 * a kind 1, so a reply to a comment must itself be kind 1111 — until that's
 * wired up, replies to comments are still built as NIP-10 kind-1 events.
 */
object Nip22 {
    const val KIND_COMMENT = 1111

    fun isComment(event: NostrEvent): Boolean = event.kind == KIND_COMMENT
}
