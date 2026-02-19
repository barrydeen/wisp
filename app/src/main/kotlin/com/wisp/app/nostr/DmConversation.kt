package com.wisp.app.nostr

data class DmMessage(
    val id: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val giftWrapId: String,
    val relayUrls: Set<String> = emptySet()
)

data class DmConversation(
    val peerPubkey: String,
    val messages: List<DmMessage>,
    val lastMessageAt: Long
)
