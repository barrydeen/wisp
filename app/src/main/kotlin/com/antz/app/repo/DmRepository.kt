package com.antz.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.antz.app.nostr.DmConversation
import com.antz.app.nostr.DmMessage
import com.antz.app.nostr.DmReaction
import com.antz.app.nostr.DmZap
import com.antz.app.nostr.NostrEvent
import com.antz.app.nostr.wipe
import com.antz.app.nostr.FlatNotificationItem
import com.antz.app.nostr.NotificationType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class DmRepository(private val context: Context? = null, pubkeyHex: String? = null) {

    companion object {
        /**
         * Stable conversation key computed from all participant pubkeys (including the
         * local user's own pubkey). Sort + join so the same set always produces the same key.
         */
        fun conversationKey(participants: List<String>): String =
            participants.toSortedSet().joinToString(",")
    }

    private var myPubkey: String? = pubkeyHex
    private var prefs: SharedPreferences? =
        context?.getSharedPreferences("antz_dm_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)
    private var lastReadDmTimestamp: Long = prefs?.getLong("last_read_dm", 0L) ?: 0L
    private var latestGiftWrapTs: Long = prefs?.getLong("latest_gwrap_ts", 0L) ?: 0L
    private val lock = Any()
    // No LRU eviction — DM conversations must never be silently dropped since there's no
    // persistence layer to recover them from, and seenEvents dedup blocks re-delivery.
    private val conversations = ConcurrentHashMap<String, MutableList<DmMessage>>()
    // Stores known participants for conversations (needed for empty group convos before first message)
    private val conversationParticipants = ConcurrentHashMap<String, List<String>>()
    private val conversationKeyCache = object : LruCache<String, ByteArray>(200) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: ByteArray?, newValue: ByteArray?) {
            oldValue?.wipe()
        }
    }
    // Maps giftWrapId → messageId so we can merge relay URLs on duplicate receipt
    private val seenGiftWraps = ConcurrentHashMap<String, String>()
    // Maps rumorId → (convKey, msgId) for associating 9735 receipts with DM messages
    private val rumorIdIndex = ConcurrentHashMap<String, Pair<String, String>>()
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

    fun addMessage(msg: DmMessage, convKey: String) {
        var isNewIncoming = false
        synchronized(lock) {
            val existingMsgId = seenGiftWraps.get(msg.giftWrapId)
            if (existingMsgId != null) {
                if (msg.relayUrls.isNotEmpty()) {
                    mergeRelayUrlsLocked(convKey, existingMsgId, msg.relayUrls)
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
                    val peerPubkey = msg.participants.firstOrNull() ?: convKey
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM,
                        actorPubkey = msg.senderPubkey,
                        referencedEventId = msg.id,
                        timestamp = msg.createdAt,
                        dmContent = msg.content,
                        dmPeerPubkey = peerPubkey,
                        dmRumorId = msg.rumorId.ifEmpty { null }
                    ))
                    val sorted = dmNotifItems.sortedByDescending { it.timestamp }
                    _dmNotifications.value = if (sorted.size > 200) sorted.take(200) else sorted
                }
                if (msg.createdAt >= soundEligibleAfter && appIsActive) {
                    isNewIncoming = true
                }
            }

            if (msg.participants.isNotEmpty()) {
                conversationParticipants[convKey] = msg.participants
            }
            if (msg.rumorId.isNotEmpty()) {
                rumorIdIndex[msg.rumorId] = Pair(convKey, msg.id)
            }
            val messages = conversations.get(convKey) ?: mutableListOf<DmMessage>().also {
                conversations.put(convKey, it)
            }
            messages.add(msg)
            messages.sortBy { it.createdAt }
        }
        if (isNewIncoming) {
            _dmReceived.tryEmit(Unit)
        }
        updateConversationList()
    }

    /** Look up which conversation/message owns a given rumorId (for associating 9735 receipts). */
    fun findByRumorId(rumorId: String): Pair<String, String>? = rumorIdIndex[rumorId]

    /**
     * Add a zap receipt to the DM message identified by [messageId] (rumorId or internal id)
     * in conversation [convKey].
     */
    fun addZap(convKey: String, messageId: String, zap: DmZap) {
        synchronized(lock) {
            val messages = conversations.get(convKey) ?: return
            val idx = messages.indexOfFirst { it.rumorId == messageId || it.id == messageId }
            if (idx < 0) return
            val existing = messages[idx]
            // Dedupe by zapperPubkey + timestamp
            if (existing.zaps.any { it.zapperPubkey == zap.zapperPubkey && it.timestamp == zap.timestamp }) return
            messages[idx] = existing.copy(zaps = existing.zaps + zap)

            // Notify if the original message was sent by the local user
            val isMine = myPubkey != null && existing.senderPubkey == myPubkey
            if (isMine) {
                val flatId = "dmzap:${zap.zapperPubkey}:${messageId}"
                if (dmNotifIds.add(flatId)) {
                    val peerPubkey = if (existing.participants.size > 1) convKey
                                     else existing.participants.firstOrNull() ?: convKey
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM_ZAP,
                        actorPubkey = zap.zapperPubkey,
                        referencedEventId = messageId,
                        timestamp = zap.timestamp,
                        zapSats = zap.sats,
                        dmPeerPubkey = peerPubkey
                    ))
                    val sorted = dmNotifItems.sortedByDescending { it.timestamp }
                    _dmNotifications.value = if (sorted.size > 200) sorted.take(200) else sorted
                }
            }
        }
        updateConversationList()
    }

    /**
     * Add a private DM reaction to the message with [messageId] in conversation [convKey].
     * If the original message was sent by the local user, also emits a DM_REACTION notification.
     */
    fun addReaction(convKey: String, messageId: String, reaction: DmReaction) {
        synchronized(lock) {
            val messages = conversations.get(convKey) ?: return
            val idx = messages.indexOfFirst { it.rumorId == messageId || it.id == messageId }
            if (idx < 0) return
            val existing = messages[idx]
            val alreadyReacted = existing.reactions.any {
                it.authorPubkey == reaction.authorPubkey && it.emoji == reaction.emoji
            }
            if (alreadyReacted) return
            messages[idx] = existing.copy(reactions = existing.reactions + reaction)

            // Notify if the original message was sent by the local user
            val isMine = myPubkey != null && existing.senderPubkey == myPubkey
            if (isMine) {
                val flatId = "dmreact:${reaction.authorPubkey}:${messageId}"
                if (dmNotifIds.add(flatId)) {
                    // For 1:1 use the peer pubkey; for group use the full convKey so
                    // navigation can distinguish between single-pubkey and group routes.
                    val peerPubkey = if (existing.participants.size > 1) convKey
                                     else existing.participants.firstOrNull() ?: convKey
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM_REACTION,
                        actorPubkey = reaction.authorPubkey,
                        referencedEventId = messageId,
                        timestamp = reaction.timestamp,
                        emoji = reaction.emoji,
                        dmPeerPubkey = peerPubkey
                    ))
                    val sorted = dmNotifItems.sortedByDescending { it.timestamp }
                    _dmNotifications.value = if (sorted.size > 200) sorted.take(200) else sorted
                }
            }
        }
        updateConversationList()
    }

    /** Must be called while holding [lock]. */
    private fun mergeRelayUrlsLocked(convKey: String, messageId: String, newUrls: Set<String>) {
        val messages = conversations.get(convKey) ?: return
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val existing = messages[idx]
            messages[idx] = existing.copy(relayUrls = existing.relayUrls + newUrls)
        }
    }

    /**
     * Pre-create an empty conversation entry (for group DMs initiated locally before
     * any message has been sent/received).
     */
    fun initConversation(convKey: String, participants: List<String>) {
        synchronized(lock) {
            conversations.getOrPut(convKey) { mutableListOf() }
            conversationParticipants[convKey] = participants
        }
        updateConversationList()
    }

    /** Pre-register a gift wrap ID as seen so it gets deduped when received from relays. */
    fun markGiftWrapSeen(giftWrapId: String, messageId: String) {
        synchronized(lock) {
            seenGiftWraps.put(giftWrapId, messageId)
        }
    }

    fun getConversation(convKey: String): List<DmMessage> {
        return synchronized(lock) {
            conversations.get(convKey)?.toList() ?: emptyList()
        }
    }

    fun getCachedConversationKey(pubkeyHex: String): ByteArray? {
        return conversationKeyCache.get(pubkeyHex)
    }

    fun cacheConversationKey(pubkeyHex: String, key: ByteArray) {
        conversationKeyCache.put(pubkeyHex, key)
    }

    fun getLatestGiftWrapTimestamp(): Long? = if (latestGiftWrapTs > 0) latestGiftWrapTs else null

    fun updateLatestGiftWrapTimestamp(ts: Long) {
        if (ts > latestGiftWrapTs) {
            latestGiftWrapTs = ts
            prefs?.edit()?.putLong("latest_gwrap_ts", ts)?.apply()
        }
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
            // Remove all conversations that include this pubkey
            val keysToRemove = conversations.keys.filter { key -> key.split(",").contains(pubkey) }
            keysToRemove.forEach {
                conversations.remove(it)
                conversationParticipants.remove(it)
            }
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

    /** Call when switching accounts — clears all state and re-keys to the new pubkey. */
    fun reload(pubkeyHex: String) {
        clear()
        myPubkey = pubkeyHex
        prefs = context?.getSharedPreferences("antz_dm_$pubkeyHex", Context.MODE_PRIVATE)
        lastReadDmTimestamp = prefs?.getLong("last_read_dm", 0L) ?: 0L
        latestGiftWrapTs = prefs?.getLong("latest_gwrap_ts", 0L) ?: 0L
    }

    fun clear() {
        synchronized(lock) {
            conversations.clear()
            conversationParticipants.clear()
            conversationKeyCache.evictAll()
            seenGiftWraps.clear()
            rumorIdIndex.clear()
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
        latestGiftWrapTs = 0L
        prefs?.edit()?.clear()?.apply()
    }

    private fun updateConversationList() {
        val list = synchronized(lock) {
            conversations.map { (convKey, messages) ->
                val participants = conversationParticipants[convKey]
                    ?: messages.firstOrNull()?.participants
                    ?: emptyList()
                DmConversation(
                    conversationKey = convKey,
                    participants = participants,
                    messages = messages.toList(),
                    lastMessageAt = messages.maxOfOrNull { it.createdAt } ?: 0
                )
            }
        }.sortedByDescending { it.lastMessageAt }
        _conversationList.value = list
    }
}
