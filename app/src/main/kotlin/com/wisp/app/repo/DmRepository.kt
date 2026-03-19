package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.DmConversation
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.wipe
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.NotificationType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class DmRepository(context: Context? = null, pubkeyHex: String? = null) {
    private val myPubkey: String? = pubkeyHex
    private val prefs: SharedPreferences? =
        context?.getSharedPreferences("wisp_dm_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)
    private var lastReadDmTimestamp: Long = prefs?.getLong("last_read_dm", 0L) ?: 0L
    private val lock = Any()
    // No LRU eviction — DM conversations must never be silently dropped since there's no
    // persistence layer to recover them from, and seenEvents dedup blocks re-delivery.
    private val conversations = ConcurrentHashMap<String, MutableList<DmMessage>>()
    private val conversationKeyCache = object : LruCache<String, ByteArray>(200) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: ByteArray?, newValue: ByteArray?) {
            oldValue?.wipe()
        }
    }
    // Maps giftWrapId → messageId so we can merge relay URLs on duplicate receipt
    private val seenGiftWraps = ConcurrentHashMap<String, String>()
    private val dmRelayCache = LruCache<String, List<String>>(200)

    // Pending gift wraps for remote signer mode (stored raw, decrypted on demand)
    data class PendingGiftWrap(val event: NostrEvent, val relayUrl: String)
    private val pendingGiftWraps = mutableListOf<PendingGiftWrap>()
    private val pendingLock = Any()

    private val _conversationList = MutableStateFlow<List<DmConversation>>(emptyList())
    val conversationList: StateFlow<List<DmConversation>> = _conversationList

    private val _hasUnreadDms = MutableStateFlow(false)
    val hasUnreadDms: StateFlow<Boolean> = _hasUnreadDms

    /** Number of gift wraps waiting to be decrypted (remote signer mode). */
    private val _pendingDecryptCount = MutableStateFlow(0)
    val pendingDecryptCount: StateFlow<Int> = _pendingDecryptCount

    /** True while any coroutine is actively decrypting pending gift wraps. */
    private val _decrypting = MutableStateFlow(false)
    val decrypting: StateFlow<Boolean> = _decrypting
    private val decryptingRefCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Only play sounds for DMs created after this timestamp (set at subscription time). */
    @Volatile var soundEligibleAfter: Long = System.currentTimeMillis() / 1000

    @Volatile var appIsActive: Boolean = true

    private val _dmReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dmReceived: SharedFlow<Unit> = _dmReceived

    private val dmNotifItems = mutableListOf<FlatNotificationItem>()
    private val dmNotifIds = mutableSetOf<String>()
    private val _dmNotifications = MutableStateFlow<List<FlatNotificationItem>>(emptyList())
    val dmNotifications: StateFlow<List<FlatNotificationItem>> = _dmNotifications

    fun markDecryptingStart() {
        decryptingRefCount.incrementAndGet()
        _decrypting.value = true
    }

    fun markDecryptingEnd() {
        if (decryptingRefCount.decrementAndGet() <= 0) {
            _decrypting.value = false
        }
    }

    fun addMessage(msg: DmMessage, peerPubkey: String) {
        var isNewIncoming = false
        synchronized(lock) {
            val existingMsgId = seenGiftWraps.get(msg.giftWrapId)
            if (existingMsgId != null) {
                if (msg.relayUrls.isNotEmpty()) {
                    mergeRelayUrlsLocked(peerPubkey, existingMsgId, msg.relayUrls)
                }
                return
            }
            seenGiftWraps.put(msg.giftWrapId, msg.id)
            if (msg.createdAt > lastReadDmTimestamp) {
                _hasUnreadDms.value = true
            }

            // Track incoming DMs as notification items
            val incoming = myPubkey != null && msg.senderPubkey != myPubkey
            if (incoming) {
                val flatId = "dm:${msg.id}"
                if (dmNotifIds.add(flatId)) {
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM,
                        actorPubkey = msg.senderPubkey,
                        referencedEventId = msg.id,
                        timestamp = msg.createdAt,
                        dmContent = msg.content,
                        dmPeerPubkey = peerPubkey
                    ))
                    val sorted = dmNotifItems.sortedByDescending { it.timestamp }
                    _dmNotifications.value = if (sorted.size > 200) sorted.take(200) else sorted
                }
                if (msg.createdAt >= soundEligibleAfter && appIsActive) {
                    isNewIncoming = true
                }
            }

            val messages = conversations.get(peerPubkey) ?: mutableListOf<DmMessage>().also {
                conversations.put(peerPubkey, it)
            }
            messages.add(msg)
            messages.sortBy { it.createdAt }
        }
        if (isNewIncoming) {
            _dmReceived.tryEmit(Unit)
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
        // Persist the latest message timestamp so we don't show stale indicators on relaunch
        val latestTimestamp = synchronized(lock) {
            conversations.values.flatMap { it }.maxOfOrNull { it.createdAt } ?: 0L
        }
        if (latestTimestamp > lastReadDmTimestamp) {
            lastReadDmTimestamp = latestTimestamp
            prefs?.edit()?.putLong("last_read_dm", latestTimestamp)?.apply()
        }
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

    fun addPendingGiftWrap(event: NostrEvent, relayUrl: String) {
        synchronized(pendingLock) {
            // Dedup by event id
            if (pendingGiftWraps.any { it.event.id == event.id }) return
            pendingGiftWraps.add(PendingGiftWrap(event, relayUrl))
            _pendingDecryptCount.value = pendingGiftWraps.size
        }
    }

    /** Pop a single pending gift wrap for decryption. Returns null when empty. */
    fun takeNextPendingGiftWrap(): PendingGiftWrap? {
        return synchronized(pendingLock) {
            val wrap = pendingGiftWraps.removeFirstOrNull()
            _pendingDecryptCount.value = pendingGiftWraps.size
            wrap
        }
    }

    fun takePendingGiftWraps(): List<PendingGiftWrap> {
        return synchronized(pendingLock) {
            val copy = pendingGiftWraps.toList()
            pendingGiftWraps.clear()
            _pendingDecryptCount.value = 0
            copy
        }
    }

    fun reload(pubkeyHex: String?) {
        lastReadDmTimestamp = prefs?.getLong("last_read_dm", 0L) ?: 0L
    }

    fun clear() {
        synchronized(lock) {
            conversations.clear()
            conversationKeyCache.evictAll()
            seenGiftWraps.clear()
            dmRelayCache.evictAll()
            dmNotifItems.clear()
            dmNotifIds.clear()
        }
        synchronized(pendingLock) {
            pendingGiftWraps.clear()
            _pendingDecryptCount.value = 0
        }
        _conversationList.value = emptyList()
        _hasUnreadDms.value = false
        _decrypting.value = false
        _dmNotifications.value = emptyList()
        decryptingRefCount.set(0)
        soundEligibleAfter = System.currentTimeMillis() / 1000
        prefs?.edit()?.clear()?.apply()
    }

    private fun updateConversationList() {
        val list = synchronized(lock) {
            conversations.map { (peer, messages) ->
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
