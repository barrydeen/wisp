package com.wisp.app.nostr

data class DmReaction(
    val authorPubkey: String,
    val emoji: String,
    val timestamp: Long,
    /** URL for custom emoji reactions (from the rumor's emoji tag). */
    val emojiUrl: String? = null
)

data class DmZap(
    val zapperPubkey: String,
    val sats: Long,
    val timestamp: Long
)

data class DmMessage(
    val id: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val giftWrapId: String,
    val relayUrls: Set<String> = emptySet(),
    /** Computed rumor event ID — used as e-tag target for replies and reactions. */
    val rumorId: String = "",
    /** rumorId of the message this message is replying to, if any. */
    val replyToId: String? = null,
    /** All participant pubkeys in this conversation, excluding the local user's own pubkey. */
    val participants: List<String> = emptyList(),
    /** Private reactions received on this message (gift-wrapped kind 14 reactions). */
    val reactions: List<DmReaction> = emptyList(),
    /** Zap receipts (kind 9735) targeting this message's rumorId. */
    val zaps: List<DmZap> = emptyList(),
    /** Emoji shortcode → URL map from the rumor's emoji tags (NIP-30). */
    val emojiMap: Map<String, String> = emptyMap(),
    /** Raw gift wrap event JSON (kind 1059) — for debug inspection only. */
    val debugGiftWrapJson: String? = null,
    /** Decrypted rumor JSON — for debug inspection only. */
    val debugRumorJson: String? = null
)

data class DmConversation(
    /** Stable key computed from all participant pubkeys (including own), sorted and joined. */
    val conversationKey: String,
    /** All participant pubkeys in this conversation, excluding the local user's own pubkey. */
    val participants: List<String>,
    val messages: List<DmMessage>,
    val lastMessageAt: Long
) {
    val isGroup: Boolean get() = participants.size > 1
    /** For 1:1 conversations — the peer's pubkey. */
    val peerPubkey: String get() = participants.firstOrNull() ?: conversationKey
}
