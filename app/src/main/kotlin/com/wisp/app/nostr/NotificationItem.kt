package com.wisp.app.nostr

data class NotificationSummary(
    val replyCount: Int = 0,
    val reactionCount: Int = 0,
    val zapCount: Int = 0,
    val zapSats: Long = 0,
    val repostCount: Int = 0,
    val mentionCount: Int = 0,
    val quoteCount: Int = 0
)

data class ZapEntry(
    val pubkey: String,
    val sats: Long,
    val message: String,
    val createdAt: Long
)

sealed class NotificationGroup {
    abstract val groupId: String
    abstract val latestTimestamp: Long

    companion object {
        /** Sentinel key used in ReactionGroup.reactions to represent reposts. */
        const val REPOST_EMOJI = "__repost__"
        /** Sentinel key used in ReactionGroup.reactions to represent zaps. */
        const val ZAP_EMOJI = "__zap__"
    }

    data class ReactionGroup(
        override val groupId: String,
        val referencedEventId: String,
        val reactions: Map<String, List<String>>, // emoji -> list of pubkeys
        val reactionTimestamps: Map<String, Long> = emptyMap(), // pubkey -> created_at
        val emojiUrls: Map<String, String> = emptyMap(), // ":shortcode:" -> url for custom emojis
        val zapEntries: List<ZapEntry> = emptyList(),
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

}
