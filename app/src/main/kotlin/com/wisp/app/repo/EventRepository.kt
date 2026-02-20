package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrEvent.Companion.fromJson
import com.wisp.app.nostr.ProfileData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class EventRepository(val profileRepo: ProfileRepository? = null, val muteRepo: MuteRepository? = null) {
    var metadataFetcher: MetadataFetcher? = null
    var currentUserPubkey: String? = null
    private val eventCache = LruCache<String, NostrEvent>(5000)
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()  // thread-safe dedup that doesn't evict
    private val feedList = mutableListOf<NostrEvent>()
    private val feedIds = HashSet<String>()  // O(1) dedup that doesn't evict like LruCache

    private val _feed = MutableStateFlow<List<NostrEvent>>(emptyList())
    val feed: StateFlow<List<NostrEvent>> = _feed

    private val _newNoteCount = MutableStateFlow(0)
    val newNoteCount: StateFlow<Int> = _newNoteCount
    var countNewNotes = false

    private val _profileVersion = MutableStateFlow(0)
    val profileVersion: StateFlow<Int> = _profileVersion

    private val _quotedEventVersion = MutableStateFlow(0)
    val quotedEventVersion: StateFlow<Int> = _quotedEventVersion

    // Reply count tracking
    private val replyCounts = LruCache<String, Int>(5000)
    private val _replyCountVersion = MutableStateFlow(0)
    val replyCountVersion: StateFlow<Int> = _replyCountVersion

    // Zap tracking
    private val zapSats = LruCache<String, Long>(5000)
    private val _zapVersion = MutableStateFlow(0)
    val zapVersion: StateFlow<Int> = _zapVersion

    // Relay provenance tracking: eventId -> set of relay URLs
    private val eventRelays = LruCache<String, MutableSet<String>>(5000)
    private val _relaySourceVersion = MutableStateFlow(0)
    val relaySourceVersion: StateFlow<Int> = _relaySourceVersion

    // Repost tracking: inner event id -> reposter pubkey
    private val repostAuthors = LruCache<String, String>(2000)
    // Repost count tracking: inner event id -> count
    private val repostCounts = LruCache<String, Int>(5000)
    // Track which events the current user has reposted: eventId -> true
    private val userReposts = LruCache<String, Boolean>(5000)
    private val _repostVersion = MutableStateFlow(0)
    val repostVersion: StateFlow<Int> = _repostVersion

    // Reaction tracking: eventId -> map of emoji -> count
    private val reactionCounts = LruCache<String, ConcurrentHashMap<String, Int>>(5000)
    // Track which events the current user has reacted to: "eventId:pubkey" -> (emoji -> reactionEventId)
    private val userReactions = LruCache<String, ConcurrentHashMap<String, String>>(5000)
    private val _reactionVersion = MutableStateFlow(0)
    val reactionVersion: StateFlow<Int> = _reactionVersion
    // Per-target-event dedup sets — evict with the same lifecycle as their count caches
    private val countedReactionIds = LruCache<String, MutableSet<String>>(5000)
    private val countedZapIds = LruCache<String, MutableSet<String>>(5000)
    // Reply dedup: track individual reply event IDs to prevent double-counting
    private val countedReplyIds = ConcurrentHashMap.newKeySet<String>()

    // Detailed reaction tracking: eventId -> (emoji -> list of reactor pubkeys)
    private val reactionDetails = LruCache<String, ConcurrentHashMap<String, MutableList<String>>>(5000)

    // Detailed zap tracking: eventId -> synchronized list of (zapper pubkey, sats)
    private val zapDetails = LruCache<String, MutableList<Pair<String, Long>>>(5000)
    // Track which events the current user has zapped: eventId -> true
    private val userZaps = LruCache<String, Boolean>(5000)
    // Events where we optimistically added the user's own zap (to avoid double-counting receipts)
    private val optimisticZaps = HashSet<String>()

    // Debouncing: coalesce rapid-fire feed list and version updates
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val feedDirty = Channel<Unit>(Channel.CONFLATED)
    private val versionDirty = Channel<Unit>(Channel.CONFLATED)

    init {
        // Debounce feed list emissions — emit at most once per 16ms frame
        scope.launch {
            for (signal in feedDirty) {
                _feed.value = synchronized(feedList) { feedList.toList() }
                delay(16)
            }
        }
        // Debounce version counter emissions — coalesce into one bump per 50ms window
        scope.launch {
            var pendingProfile = false
            var pendingReaction = false
            var pendingReplyCount = false
            var pendingZap = false
            var pendingRepost = false
            var pendingRelaySource = false
            for (signal in versionDirty) {
                // Drain all pending flags and wait for a quiet period
                delay(50)
                if (pendingProfile || profileDirty) { _profileVersion.value++; profileDirty = false }
                if (pendingReaction || reactionDirty) { _reactionVersion.value++; reactionDirty = false }
                if (pendingReplyCount || replyCountDirtyFlag) { _replyCountVersion.value++; replyCountDirtyFlag = false }
                if (pendingZap || zapDirty) { _zapVersion.value++; zapDirty = false }
                if (pendingRepost || repostDirty) { _repostVersion.value++; repostDirty = false }
                if (pendingRelaySource || relaySourceDirtyFlag) { _relaySourceVersion.value++; relaySourceDirtyFlag = false }
                pendingProfile = false
                pendingReaction = false
                pendingReplyCount = false
                pendingZap = false
                pendingRepost = false
                pendingRelaySource = false
            }
        }
    }

    @Volatile private var profileDirty = false
    @Volatile private var reactionDirty = false
    @Volatile private var replyCountDirtyFlag = false
    @Volatile private var zapDirty = false
    @Volatile private var repostDirty = false
    @Volatile private var relaySourceDirtyFlag = false

    private fun markVersionDirty() {
        versionDirty.trySend(Unit)
    }

    fun addEvent(event: NostrEvent) {
        if (!seenEventIds.add(event.id)) return  // atomic dedup across all relay threads
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        if (event.kind == 1 && muteRepo?.containsMutedWord(event.content) == true) return
        eventCache.put(event.id, event)

        when (event.kind) {
            0 -> {
                val updated = profileRepo?.updateFromEvent(event)
                if (updated != null) {
                    profileDirty = true
                    markVersionDirty()
                }
            }
            1 -> {
                // Only show root notes in feed, not replies
                val isReply = event.tags.any { it.size >= 2 && it[0] == "e" }
                if (!isReply) binaryInsert(event)
            }
            6 -> {
                // Repost: parse embedded event from content and insert it into the feed
                if (event.content.isNotBlank()) {
                    try {
                        val inner = fromJson(event.content)
                        repostAuthors.put(inner.id, event.pubkey)
                        val count = repostCounts.get(inner.id) ?: 0
                        repostCounts.put(inner.id, count + 1)
                        // Auto-mark if this is the current user's repost
                        if (event.pubkey == currentUserPubkey) {
                            userReposts.put(inner.id, true)
                        }
                        repostDirty = true
                        markVersionDirty()
                        if (seenEventIds.add(inner.id)) {
                            eventCache.put(inner.id, inner)
                            val isReply = inner.tags.any { it.size >= 2 && it[0] == "e" }
                            if (!isReply) binaryInsert(inner)
                        }
                    } catch (_: Exception) {}
                }
            }
            7 -> addReaction(event)
            9735 -> {
                val targetId = Nip57.getZappedEventId(event) ?: return
                // Per-target dedup — evicts alongside the zap count cache
                val dedupSet = countedZapIds.get(targetId)
                    ?: mutableSetOf<String>().also { countedZapIds.put(targetId, it) }
                synchronized(dedupSet) { if (!dedupSet.add(event.id)) return }
                val sats = Nip57.getZapAmountSats(event)
                if (sats > 0) {
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    // Skip if this is our own zap and we already added it optimistically
                    val isOwnOptimistic = zapperPubkey == currentUserPubkey && optimisticZaps.remove(targetId)
                    if (!isOwnOptimistic) {
                        addZapSats(targetId, sats)
                        if (zapperPubkey != null) {
                            val zaps = zapDetails.get(targetId)
                                ?: java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>()).also {
                                    zapDetails.put(targetId, it)
                                }
                            zaps.add(zapperPubkey to sats)
                        }
                    }
                    // Always mark user zap flag from receipts
                    if (zapperPubkey == currentUserPubkey) {
                        userZaps.put(targetId, true)
                    }
                }
            }
        }
    }

    private fun addReaction(event: NostrEvent) {
        val targetEventId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        // Per-target dedup — evicts alongside the count cache so re-fetched data can be re-counted
        val dedupSet = countedReactionIds.get(targetEventId)
            ?: mutableSetOf<String>().also { countedReactionIds.put(targetEventId, it) }
        synchronized(dedupSet) { if (!dedupSet.add(event.id)) return }
        val emoji = event.content.ifBlank { "❤️" }

        val counts = reactionCounts.get(targetEventId)
            ?: ConcurrentHashMap<String, Int>().also { reactionCounts.put(targetEventId, it) }
        counts[emoji] = (counts[emoji] ?: 0) + 1

        // Track reactor pubkeys per emoji
        val details = reactionDetails.get(targetEventId)
            ?: ConcurrentHashMap<String, MutableList<String>>().also { reactionDetails.put(targetEventId, it) }
        val pubkeys = details.getOrPut(emoji) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(pubkeys) { if (event.pubkey !in pubkeys) pubkeys.add(event.pubkey) }

        val key = "${targetEventId}:${event.pubkey}"
        val emojiMap = userReactions.get(key)
            ?: ConcurrentHashMap<String, String>().also { userReactions.put(key, it) }
        emojiMap[emoji] = event.id
        reactionDirty = true
        markVersionDirty()
    }

    private fun binaryInsert(event: NostrEvent) {
        synchronized(feedList) {
            if (!feedIds.add(event.id)) return  // already in feed
            var low = 0
            var high = feedList.size
            while (low < high) {
                val mid = (low + high) / 2
                if (feedList[mid].created_at > event.created_at) low = mid + 1 else high = mid
            }
            feedList.add(low, event)
        }
        feedDirty.trySend(Unit)
        if (countNewNotes) _newNoteCount.value++
    }

    fun cacheEvent(event: NostrEvent) {
        if (eventCache.get(event.id) != null) return  // already cached
        seenEventIds.add(event.id)
        eventCache.put(event.id, event)
        if (event.kind == 0) {
            val updated = profileRepo?.updateFromEvent(event)
            if (updated != null) {
                profileDirty = true
                markVersionDirty()
            }
        }
        _quotedEventVersion.value++
    }

    fun requestQuotedEvent(eventId: String, relayHints: List<String> = emptyList()) {
        metadataFetcher?.requestQuotedEvent(eventId, relayHints)
    }

    fun getEvent(id: String): NostrEvent? = eventCache.get(id)

    fun getProfileData(pubkey: String): ProfileData? = profileRepo?.get(pubkey)

    fun getReactionCount(eventId: String): Int {
        return reactionCounts.get(eventId)?.values?.sum() ?: 0
    }

    fun hasUserReacted(eventId: String, userPubkey: String): Boolean {
        val map = userReactions.get("${eventId}:${userPubkey}") ?: return false
        return map.isNotEmpty()
    }

    fun getUserReactionEmoji(eventId: String, userPubkey: String): String? {
        return userReactions.get("${eventId}:${userPubkey}")?.keys?.firstOrNull()
    }

    fun getUserReactionEmojis(eventId: String, userPubkey: String): Set<String> {
        return userReactions.get("${eventId}:${userPubkey}")?.keys?.toSet() ?: emptySet()
    }

    fun getUserReactionEventId(eventId: String, userPubkey: String, emoji: String): String? {
        return userReactions.get("${eventId}:${userPubkey}")?.get(emoji)
    }

    fun removeReaction(eventId: String, userPubkey: String, emoji: String) {
        val key = "${eventId}:${userPubkey}"
        val emojiMap = userReactions.get(key) ?: return
        emojiMap.remove(emoji)

        // Decrement reaction count
        val counts = reactionCounts.get(eventId)
        if (counts != null) {
            val current = counts[emoji] ?: 0
            if (current > 1) counts[emoji] = current - 1 else counts.remove(emoji)
        }

        // Remove from reaction details
        val details = reactionDetails.get(eventId)
        if (details != null) {
            val pubkeys = details[emoji]
            if (pubkeys != null) {
                synchronized(pubkeys) { pubkeys.remove(userPubkey) }
                if (pubkeys.isEmpty()) details.remove(emoji)
            }
        }

        reactionDirty = true
        markVersionDirty()
    }

    fun addZapSats(eventId: String, sats: Long) {
        val current = zapSats.get(eventId) ?: 0L
        zapSats.put(eventId, current + sats)
        zapDirty = true
        markVersionDirty()
    }

    fun getZapSats(eventId: String): Long = zapSats.get(eventId) ?: 0L

    fun getReactionDetails(eventId: String): Map<String, List<String>> {
        val details = reactionDetails.get(eventId) ?: return emptyMap()
        return details.mapValues { (_, pubkeys) -> synchronized(pubkeys) { pubkeys.toList() } }
    }

    fun getZapDetails(eventId: String): List<Pair<String, Long>> {
        val list = zapDetails.get(eventId) ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun addReplyCount(parentEventId: String, replyEventId: String): Boolean {
        if (!countedReplyIds.add(replyEventId)) return false
        val current = replyCounts.get(parentEventId) ?: 0
        replyCounts.put(parentEventId, current + 1)
        replyCountDirtyFlag = true
        markVersionDirty()
        return true
    }

    fun getReplyCount(eventId: String): Int = replyCounts.get(eventId) ?: 0

    fun addEventRelay(eventId: String, relayUrl: String) {
        val relays = eventRelays.get(eventId) ?: mutableSetOf<String>().also {
            eventRelays.put(eventId, it)
        }
        if (relays.add(relayUrl)) {
            relaySourceDirtyFlag = true
            markVersionDirty()
        }
    }

    fun getEventRelays(eventId: String): Set<String> = eventRelays.get(eventId) ?: emptySet()

    fun getRepostAuthor(eventId: String): String? = repostAuthors.get(eventId)

    fun getRepostCount(eventId: String): Int = repostCounts.get(eventId) ?: 0

    fun markUserRepost(eventId: String) {
        userReposts.put(eventId, true)
        val count = repostCounts.get(eventId) ?: 0
        repostCounts.put(eventId, count + 1)
        repostDirty = true
        markVersionDirty()
    }

    fun hasUserReposted(eventId: String): Boolean = userReposts.get(eventId) == true

    /**
     * Optimistically record the current user's zap so the UI updates immediately
     * without waiting for the 9735 receipt from relays.
     */
    fun addOptimisticZap(eventId: String, zapperPubkey: String, sats: Long) {
        userZaps.put(eventId, true)
        optimisticZaps.add(eventId)
        addZapSats(eventId, sats)
        val zaps = zapDetails.get(eventId)
            ?: java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>()).also {
                zapDetails.put(eventId, it)
            }
        zaps.add(zapperPubkey to sats)
    }

    fun hasUserZapped(eventId: String): Boolean = userZaps.get(eventId) == true

    fun getOldestTimestamp(): Long? = synchronized(feedList) { feedList.lastOrNull()?.created_at }

    fun resetNewNoteCount() {
        _newNoteCount.value = 0
    }

    fun purgeUser(pubkey: String) {
        synchronized(feedList) {
            val removed = feedList.filter { it.pubkey == pubkey }
            feedList.removeAll { it.pubkey == pubkey }
            removed.forEach { feedIds.remove(it.id) }
        }
        // Evict from eventCache so blocked content doesn't appear in threads/quotes
        val snapshot = eventCache.snapshot()
        for ((id, event) in snapshot) {
            if (event.pubkey == pubkey) {
                eventCache.remove(id)
                seenEventIds.remove(id)  // allow re-entry if later unblocked
            }
        }
        feedDirty.trySend(Unit)
    }

    fun trimSeenEvents(maxSize: Int = 50_000) {
        if (seenEventIds.size > maxSize) {
            seenEventIds.clear()
            // Re-add current feed IDs so they stay deduped
            synchronized(feedList) { feedIds.forEach { seenEventIds.add(it) } }
        }
    }

    fun clearFeed() {
        synchronized(feedList) {
            feedList.clear()
            feedIds.clear()
        }
        eventCache.evictAll()
        seenEventIds.clear()
        _feed.value = emptyList()
        _newNoteCount.value = 0
        countNewNotes = false
    }

    fun clearAll() {
        clearFeed()
        replyCounts.evictAll()
        zapSats.evictAll()
        eventRelays.evictAll()
        repostAuthors.evictAll()
        reactionCounts.evictAll()
        userReactions.evictAll()
        reactionDetails.evictAll()
        zapDetails.evictAll()
        repostCounts.evictAll()
        userReposts.evictAll()
        userZaps.evictAll()
        countedReactionIds.evictAll()
        countedZapIds.evictAll()
        countedReplyIds.clear()
        _profileVersion.value = 0
        _quotedEventVersion.value = 0
        _replyCountVersion.value = 0
        _zapVersion.value = 0
        _relaySourceVersion.value = 0
        _reactionVersion.value = 0
    }
}
