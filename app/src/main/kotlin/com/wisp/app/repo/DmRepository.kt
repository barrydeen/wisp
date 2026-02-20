package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.DmConversation
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.wipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DmRepository {
    private val lock = Any()
    private val conversations = LruCache<String, MutableList<DmMessage>>(100)
    private val conversationKeyCache = object : LruCache<String, ByteArray>(50) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: ByteArray?, newValue: ByteArray?) {
            oldValue?.wipe()
        }
    }
    // Maps giftWrapId → messageId so we can merge relay URLs on duplicate receipt
    private val seenGiftWraps = LruCache<String, String>(2000)
    private val dmRelayCache = LruCache<String, List<String>>(200)

    private val _conversationList = MutableStateFlow<List<DmConversation>>(emptyList())
    val conversationList: StateFlow<List<DmConversation>> = _conversationList

    private val _hasUnreadDms = MutableStateFlow(false)
    val hasUnreadDms: StateFlow<Boolean> = _hasUnreadDms

    fun addMessage(msg: DmMessage, peerPubkey: String) {
        synchronized(lock) {
            val existingMsgId = seenGiftWraps.get(msg.giftWrapId)
            if (existingMsgId != null) {
                if (msg.relayUrls.isNotEmpty()) {
                    mergeRelayUrlsLocked(peerPubkey, existingMsgId, msg.relayUrls)
                }
                return
            }
            seenGiftWraps.put(msg.giftWrapId, msg.id)
            _hasUnreadDms.value = true

            val messages = conversations.get(peerPubkey) ?: mutableListOf<DmMessage>().also {
                conversations.put(peerPubkey, it)
            }
            messages.add(msg)
            messages.sortBy { it.createdAt }
        }
        updateConversationList()
    }

    /** Must be called while holding [lock]. */
    private fun mergeRelayUrlsLocked(peerPubkey: String, messageId: String, newUrls: Set<String>) {
        val messages = conversations.get(peerPubkey) ?: return
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val existing = messages[idx]
            messages[idx] = existing.copy(relayUrls = existing.relayUrls + newUrls)
        }
    }

    /** Pre-register a gift wrap ID as seen so it gets deduped when received from relays. */
    fun markGiftWrapSeen(giftWrapId: String, messageId: String) {
        synchronized(lock) {
            seenGiftWraps.put(giftWrapId, messageId)
        }
    }

    fun getConversation(peerPubkey: String): List<DmMessage> {
        return synchronized(lock) {
            conversations.get(peerPubkey)?.toList() ?: emptyList()
        }
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

    fun cacheDmRelays(pubkey: String, urls: List<String>) {
        if (urls.isNotEmpty()) dmRelayCache.put(pubkey, urls)
    }

    fun getCachedDmRelays(pubkey: String): List<String>? = dmRelayCache.get(pubkey)

    fun purgeUser(pubkey: String) {
        synchronized(lock) {
            conversations.remove(pubkey)
            conversationKeyCache.remove(pubkey)
        }
        updateConversationList()
    }

    fun clear() {
        synchronized(lock) {
            conversations.evictAll()
            conversationKeyCache.evictAll()
            seenGiftWraps.evictAll()
            dmRelayCache.evictAll()
        }
        _conversationList.value = emptyList()
        _hasUnreadDms.value = false
    }

    private fun updateConversationList() {
        val list = synchronized(lock) {
            val snapshot = conversations.snapshot()
            snapshot.map { (peer, messages) ->
                DmConversation(
                    peerPubkey = peer,
                    messages = messages.toList(),
                    lastMessageAt = messages.maxOfOrNull { it.createdAt } ?: 0
                )
            }
        }.sortedByDescending { it.lastMessageAt }
        _conversationList.value = list
    }
}
