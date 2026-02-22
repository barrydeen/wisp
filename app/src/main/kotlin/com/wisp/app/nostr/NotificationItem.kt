package com.wisp.app.nostr

data class ZapEntry(
    val pubkey: String,
    val sats: Long,
    val message: String,
    val createdAt: Long
)

sealed class NotificationGroup {
    abstract val groupId: String
    abstract val latestTimestamp: Long

    data class ReactionGroup(
        override val groupId: String,
        val referencedEventId: String,
        val reactions: Map<String, List<String>>, // emoji -> list of pubkeys
        val reactionTimestamps: Map<String, Long> = emptyMap(), // pubkey -> created_at
        val emojiUrls: Map<String, String> = emptyMap(), // ":shortcode:" -> url for custom emojis
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class ZapGroup(
        override val groupId: String,
        val referencedEventId: String,
        val zaps: List<ZapEntry>,
        val totalSats: Long,
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class ReplyNotification(
        override val groupId: String,
        val senderPubkey: String,
        val replyEventId: String,
        val referencedEventId: String?,
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class QuoteNotification(
        override val groupId: String,
        val senderPubkey: String,
        val quoteEventId: String,
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class MentionNotification(
        override val groupId: String,
        val senderPubkey: String,
        val eventId: String,
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class RepostNotification(
        override val groupId: String,
        val senderPubkey: String,
        val repostEventId: String,
        val repostedEventId: String,
        override val latestTimestamp: Long
    ) : NotificationGroup()
}
