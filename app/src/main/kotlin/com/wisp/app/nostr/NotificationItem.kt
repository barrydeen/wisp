package com.wisp.app.nostr

enum class NotificationType { REACTION, ZAP, REPOST, REPLY, QUOTE, MENTION, VOTE, DM }

data class FlatNotificationItem(
    val id: String,
    val type: NotificationType,
    val actorPubkey: String,
    val referencedEventId: String,
    val timestamp: Long,
    val emoji: String? = null,
    val emojiUrl: String? = null,
    val zapSats: Long = 0,
    val zapMessage: String = "",
    val isPrivateZap: Boolean = false,
    val replyEventId: String? = null,
    val quoteEventId: String? = null,
    val voteOptionIds: List<String> = emptyList(),
    val dmContent: String? = null,
    val dmPeerPubkey: String? = null,
)

data class NotificationSummary(
    val replyCount: Int = 0,
    val reactionCount: Int = 0,
    val zapCount: Int = 0,
    val zapSats: Long = 0,
    val repostCount: Int = 0,
    val mentionCount: Int = 0,
    val quoteCount: Int = 0,
    val dmCount: Int = 0
)

data class ZapEntry(
    val pubkey: String,
    val sats: Long,
    val message: String,
    val createdAt: Long,
    val receiptEventId: String? = null,
    val isPrivate: Boolean = false
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
        val relayHints: List<String> = emptyList(), // relay hints for referencedEventId
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class ReplyNotification(
        override val groupId: String,
        val senderPubkey: String,
        val replyEventId: String,
        val referencedEventId: String?,
        val referencedEventHints: List<String> = emptyList(), // relay hints for referencedEventId
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class QuoteNotification(
        override val groupId: String,
        val senderPubkey: String,
        val quoteEventId: String,
        val relayHints: List<String> = emptyList(), // relay hints for quoteEventId
        override val latestTimestamp: Long
    ) : NotificationGroup()

    data class MentionNotification(
        override val groupId: String,
        val senderPubkey: String,
        val eventId: String,
        val relayHints: List<String> = emptyList(), // relay hints for eventId
        override val latestTimestamp: Long
    ) : NotificationGroup()

}
