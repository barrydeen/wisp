package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.db.DmPersistence
import com.wisp.app.nostr.DmConversation
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.DmReaction
import com.wisp.app.nostr.DmZap
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.wipe
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DmRepository(
    private val context: Context? = null,
    pubkeyHex: String? = null,
    private val persistence: DmPersistence? = null
) {

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
        context?.getSharedPreferences("wisp_dm_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)
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

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Seeding queries ObjectBox — dispatch off the main thread so cold-start UI isn't blocked.
        ioScope.launch { seedFromPersistence() }
    }

    /**
     * Hydrate in-memory state from disk so we don't need to re-decrypt every gift wrap on
     * each cold start. Populates [conversations], [seenGiftWraps] and [rumorIdIndex]; the
     * existing dedup in [addPendingGiftWrap] then short-circuits relay-redelivered wraps
     * before they hit the signer.
     */
    private fun seedFromPersistence() {
        val owner = myPubkey ?: return
        val p = persistence ?: return
        val loaded = p.loadAll(owner)
        if (loaded.isEmpty()) return
        var newestSeen = 0L
        synchronized(lock) {
            for ((convKey, msg) in loaded) {
                if (seenGiftWraps.containsKey(msg.giftWrapId)) continue
                seenGiftWraps[msg.giftWrapId] = msg.id
                if (msg.rumorId.isNotEmpty()) {
                    rumorIdIndex[msg.rumorId] = Pair(convKey, msg.id)
                }
                if (msg.participants.isNotEmpty()) {
                    conversationParticipants[convKey] = msg.participants
                }
                val list = conversations.getOrPut(convKey) { mutableListOf() }
                list.add(msg)
                if (msg.createdAt > latestGiftWrapTs) latestGiftWrapTs = msg.createdAt
                if (msg.createdAt > newestSeen) newestSeen = msg.createdAt
                rebuildNotifItemsLocked(convKey, msg)
            }
            for ((_, list) in conversations) list.sortBy { it.createdAt }
            val sorted = dmNotifItems.sortedByDescending { it.timestamp }
            _dmNotifications.value = if (sorted.size > 200) sorted.take(200) else sorted
        }
        if (newestSeen > lastReadDmTimestamp) _hasUnreadDms.value = true
        updateConversationList()
    }

    /**
     * Rebuild incoming-DM, reaction and zap notification items for a single persisted message.
     * Caller must hold [lock]. Used only for hydration — the live `addMessage` path inlines
     * the same logic with extra audio/haptic side-effects we want to skip on cold start.
     */
    private fun rebuildNotifItemsLocked(convKey: String, msg: DmMessage) {
        val owner = myPubkey ?: return
        if (msg.senderPubkey != owner) {
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
            }
        } else {
            // Reactions and zaps appear in the notifications screen only for the user's own messages.
            for (reaction in msg.reactions) {
                val flatId = "dmreact:${reaction.authorPubkey}:${msg.rumorId.ifEmpty { msg.id }}"
                if (dmNotifIds.add(flatId)) {
                    val peerPubkey = if (msg.participants.size > 1) convKey
                                     else msg.participants.firstOrNull() ?: convKey
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM_REACTION,
                        actorPubkey = reaction.authorPubkey,
                        referencedEventId = msg.rumorId.ifEmpty { msg.id },
                        timestamp = reaction.timestamp,
                        emoji = reaction.emoji,
                        dmPeerPubkey = peerPubkey
                    ))
                }
            }
            for (zap in msg.zaps) {
                val flatId = "dmzap:${zap.zapperPubkey}:${msg.rumorId.ifEmpty { msg.id }}"
                if (dmNotifIds.add(flatId)) {
                    val peerPubkey = if (msg.participants.size > 1) convKey
                                     else msg.participants.firstOrNull() ?: convKey
                    dmNotifItems.add(FlatNotificationItem(
                        id = flatId,
                        type = NotificationType.DM_ZAP,
                        actorPubkey = zap.zapperPubkey,
                        referencedEventId = msg.rumorId.ifEmpty { msg.id },
                        timestamp = zap.timestamp,
                        zapSats = zap.sats,
                        dmPeerPubkey = peerPubkey
                    ))
                }
            }
        }
    }

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
        myPubkey?.let { persistence?.queueMessage(it, msg) }
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
        var updated: DmMessage? = null
        synchronized(lock) {
            val messages = conversations.get(convKey) ?: return
            val idx = messages.indexOfFirst { it.rumorId == messageId || it.id == messageId }
            if (idx < 0) return
            val existing = messages[idx]
            // Dedupe by zapperPubkey + timestamp
            if (existing.zaps.any { it.zapperPubkey == zap.zapperPubkey && it.timestamp == zap.timestamp }) return
            messages[idx] = existing.copy(zaps = existing.zaps + zap)
            updated = messages[idx]

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
        updated?.let { msg -> myPubkey?.let { persistence?.queueMessage(it, msg) } }
        updateConversationList()
    }

    /**
     * Add a private DM reaction to the message with [messageId] in conversation [convKey].
     * If the original message was sent by the local user, also emits a DM_REACTION notification.
     */
    fun addReaction(convKey: String, messageId: String, reaction: DmReaction) {
        var updated: DmMessage? = null
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
            updated = messages[idx]

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
        updated?.let { msg -> myPubkey?.let { persistence?.queueMessage(it, msg) } }
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
            keysToRemove.forEach { convKey ->
                conversations.remove(convKey)?.forEach { msg ->
                    seenGiftWraps.remove(msg.giftWrapId)
                    if (msg.rumorId.isNotEmpty()) rumorIdIndex.remove(msg.rumorId)
                }
                conversationParticipants.remove(convKey)
            }
            conversationKeyCache.remove(pubkey)
        }
        myPubkey?.let { persistence?.deleteConversationsWithPubkey(it, pubkey) }
        updateConversationList()
    }

    fun addPendingGiftWrap(event: NostrEvent, relayUrl: String) {
        // Already-decrypted (and persisted) wraps must skip the queue — otherwise we'd
        // re-hit the signer for every wrap on every cold start.
        if (seenGiftWraps.containsKey(event.id)) {
            if (relayUrl.isNotEmpty()) {
                synchronized(lock) {
                    val msgId = seenGiftWraps[event.id] ?: return@synchronized
                    val convKey = conversations.entries.firstOrNull { (_, msgs) ->
                        msgs.any { it.id == msgId }
                    }?.key ?: return@synchronized
                    mergeRelayUrlsLocked(convKey, msgId, setOf(relayUrl))
                }
            }
            return
        }
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
        prefs = context?.getSharedPreferences("wisp_dm_$pubkeyHex", Context.MODE_PRIVATE)
        lastReadDmTimestamp = prefs?.getLong("last_read_dm", 0L) ?: 0L
        latestGiftWrapTs = prefs?.getLong("latest_gwrap_ts", 0L) ?: 0L
        // Hydrate decrypted DMs for the new account so we don't re-decrypt on switch.
        ioScope.launch { seedFromPersistence() }
    }

    fun clear() {
        // Wipe persistence for the previous owner — clear() is invoked on logout / account
        // switch where keeping decrypted DMs around would be wrong.
        myPubkey?.let { persistence?.deleteAllForOwner(it) }
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
