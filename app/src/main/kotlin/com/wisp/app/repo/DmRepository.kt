package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.DmConversation
import com.wisp.app.nostr.DmMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DmRepository {
    private val conversations = LruCache<String, MutableList<DmMessage>>(100)
    private val conversationKeyCache = LruCache<String, ByteArray>(50)
    private val seenGiftWraps = LruCache<String, Boolean>(2000)

    private val _conversationList = MutableStateFlow<List<DmConversation>>(emptyList())
    val conversationList: StateFlow<List<DmConversation>> = _conversationList

    private val _hasUnreadDms = MutableStateFlow(false)
    val hasUnreadDms: StateFlow<Boolean> = _hasUnreadDms

    fun addMessage(msg: DmMessage, peerPubkey: String) {
        if (seenGiftWraps.get(msg.giftWrapId) != null) return
        seenGiftWraps.put(msg.giftWrapId, true)
        _hasUnreadDms.value = true

        val messages = conversations.get(peerPubkey) ?: mutableListOf<DmMessage>().also {
            conversations.put(peerPubkey, it)
        }
        messages.add(msg)
        messages.sortBy { it.createdAt }
        updateConversationList()
    }

    fun getConversation(peerPubkey: String): List<DmMessage> {
        return conversations.get(peerPubkey)?.toList() ?: emptyList()
    }

    fun getCachedConversationKey(pubkeyHex: String): ByteArray? {
        return conversationKeyCache.get(pubkeyHex)
    }

    fun cacheConversationKey(pubkeyHex: String, key: ByteArray) {
        conversationKeyCache.put(pubkeyHex, key)
    }

    fun markDmsRead() {
        _hasUnreadDms.value = false
    }

    private fun updateConversationList() {
        val snapshot = conversations.snapshot()
        val list = snapshot.map { (peer, messages) ->
            DmConversation(
                peerPubkey = peer,
                messages = messages.toList(),
                lastMessageAt = messages.maxOfOrNull { it.createdAt } ?: 0
            )
        }.sortedByDescending { it.lastMessageAt }
        _conversationList.value = list
    }
}
