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

class EventRepository(val profileRepo: ProfileRepository? = null, val muteRepo: MuteRepository? = null) {
    private val eventCache = LruCache<String, NostrEvent>(5000)
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

    // Reaction tracking: eventId -> map of emoji -> count
    private val reactionCounts = LruCache<String, MutableMap<String, Int>>(2000)
    // Track which events the current user has reacted to: "eventId:pubkey" -> emoji string
    private val userReactions = LruCache<String, String>(5000)
    private val _reactionVersion = MutableStateFlow(0)
    val reactionVersion: StateFlow<Int> = _reactionVersion

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
            var pendingRelaySource = false
            for (signal in versionDirty) {
                // Drain all pending flags and wait for a quiet period
                delay(50)
                if (pendingProfile || profileDirty) { _profileVersion.value++; profileDirty = false }
                if (pendingReaction || reactionDirty) { _reactionVersion.value++; reactionDirty = false }
                if (pendingReplyCount || replyCountDirtyFlag) { _replyCountVersion.value++; replyCountDirtyFlag = false }
                if (pendingZap || zapDirty) { _zapVersion.value++; zapDirty = false }
                if (pendingRelaySource || relaySourceDirtyFlag) { _relaySourceVersion.value++; relaySourceDirtyFlag = false }
                pendingProfile = false
                pendingReaction = false
                pendingReplyCount = false
                pendingZap = false
                pendingRelaySource = false
            }
        }
    }

    @Volatile private var profileDirty = false
    @Volatile private var reactionDirty = false
    @Volatile private var replyCountDirtyFlag = false
    @Volatile private var zapDirty = false
    @Volatile private var relaySourceDirtyFlag = false

    private fun markVersionDirty() {
        versionDirty.trySend(Unit)
    }

    fun addEvent(event: NostrEvent) {
        if (eventCache.get(event.id) != null) return
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
                        if (eventCache.get(inner.id) == null) {
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
                val sats = Nip57.getZapAmountSats(event)
                if (sats > 0) addZapSats(targetId, sats)
            }
        }
    }

    private fun addReaction(event: NostrEvent) {
        val targetEventId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val emoji = event.content.ifBlank { "+" }

        val counts = reactionCounts.get(targetEventId) ?: mutableMapOf<String, Int>().also {
            reactionCounts.put(targetEventId, it)
        }
        counts[emoji] = (counts[emoji] ?: 0) + 1

        userReactions.put("${targetEventId}:${event.pubkey}", emoji)
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
        if (eventCache.get(event.id) != null) return
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

    fun getEvent(id: String): NostrEvent? = eventCache.get(id)

    fun getProfileData(pubkey: String): ProfileData? = profileRepo?.get(pubkey)

    fun getReactionCount(eventId: String): Int {
        return reactionCounts.get(eventId)?.values?.sum() ?: 0
    }

    fun hasUserReacted(eventId: String, userPubkey: String): Boolean {
        return userReactions.get("${eventId}:${userPubkey}") != null
    }

    fun getUserReactionEmoji(eventId: String, userPubkey: String): String? {
        return userReactions.get("${eventId}:${userPubkey}")
    }

    fun addZapSats(eventId: String, sats: Long) {
        val current = zapSats.get(eventId) ?: 0L
        zapSats.put(eventId, current + sats)
        zapDirty = true
        markVersionDirty()
    }

    fun getZapSats(eventId: String): Long = zapSats.get(eventId) ?: 0L

    fun addReplyCount(eventId: String) {
        val current = replyCounts.get(eventId) ?: 0
        replyCounts.put(eventId, current + 1)
        replyCountDirtyFlag = true
        markVersionDirty()
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
        feedDirty.trySend(Unit)
    }

    fun clearFeed() {
        synchronized(feedList) {
            feedList.clear()
            feedIds.clear()
        }
        eventCache.evictAll()
        _feed.value = emptyList()
        _newNoteCount.value = 0
        countNewNotes = false
    }
}
