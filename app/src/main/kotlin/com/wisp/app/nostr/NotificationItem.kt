package com.wisp.app.nostr

sealed class NotificationItem {
    abstract val id: String
    abstract val senderPubkey: String
    abstract val createdAt: Long
    abstract val referencedEventId: String?

    data class Reaction(
        override val id: String,
        override val senderPubkey: String,
        override val createdAt: Long,
        override val referencedEventId: String?,
        val emoji: String
    ) : NotificationItem()

    data class Reply(
        override val id: String,
        override val senderPubkey: String,
        override val createdAt: Long,
        override val referencedEventId: String?,
        val replyEventId: String,
        val contentPreview: String
    ) : NotificationItem()

    data class Zap(
        override val id: String,
        override val senderPubkey: String,
        override val createdAt: Long,
        override val referencedEventId: String?,
        val amountSats: Long
    ) : NotificationItem()

    data class Quote(
        override val id: String,
        override val senderPubkey: String,
        override val createdAt: Long,
        override val referencedEventId: String?,
        val contentPreview: String,
        val quotedEventId: String
    ) : NotificationItem()

    data class Mention(
        override val id: String,
        override val senderPubkey: String,
        override val createdAt: Long,
        override val referencedEventId: String?,
        val contentPreview: String,
        val eventId: String
    ) : NotificationItem()
}
