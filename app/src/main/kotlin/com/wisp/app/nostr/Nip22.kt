package com.wisp.app.nostr

object Nip22 {
    const val KIND_COMMENT = 1111

    /**
     * A comment (kind 1111) should not appear in feeds — it is only rendered in
     * the thread/article view of the event it comments on.
     */
    fun isComment(kind: Int): Boolean = kind == KIND_COMMENT
}
