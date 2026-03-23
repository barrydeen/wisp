package com.wisp.app.repo

import android.util.Log
import android.util.LruCache
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip88
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
import com.wisp.app.db.EventPersistence
import java.util.concurrent.ConcurrentHashMap

data class ZapDetail(
    val pubkey: String,
    val sats: Long,
    val message: String,
    val isPrivate: Boolean = false,
    val receiptEventId: String? = null
)

class EventRepository(val profileRepo: ProfileRepository? = null, val muteRepo: MuteRepository? = null, val relayHintStore: RelayHintStore? = null) {
    var metadataFetcher: MetadataFetcher? = null
    var deletedEventsRepo: DeletedEventsRepository? = null
    var currentUserPubkey: String? = null
    var eventPersistence: EventPersistence? = null
    /** Set of current user's DM relay URLs — used to detect private zaps. */
    var dmRelayUrls: Set<String> = emptySet()
    private val eventCache = LruCache<String, NostrEvent>(15000)
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()  // thread-safe dedup that doesn't evict
    private val feedList = mutableListOf<NostrEvent>()
    private val feedIds = HashSet<String>()  // O(1) dedup that doesn't evict like LruCache

    private val _feed = MutableStateFlow<List<NostrEvent>>(emptyList())
    val feed: StateFlow<List<NostrEvent>> = _feed

    // Isolated relay feed state — completely separate from the main feed pipeline
    private val relayFeedList = mutableListOf<NostrEvent>()
    private val relayFeedIds = HashSet<String>()
    private val _relayFeed = MutableStateFlow<List<NostrEvent>>(emptyList())
    val relayFeed: StateFlow<List<NostrEvent>> = _relayFeed

    // Author filter: null = show all, non-null = only show events from these pubkeys
    private val _authorFilter = MutableStateFlow<Set<String>?>(null)

    private val _newNoteCount = MutableStateFlow(0)
    val newNoteCount: StateFlow<Int> = _newNoteCount
    var countNewNotes = false
    private var newNotesCutoff: Long = Long.MAX_VALUE

    private val _profileVersion = MutableStateFlow(0)
    val profileVersion: StateFlow<Int> = _profileVersion

    private val _quotedEventVersion = MutableStateFlow(0)
    val quotedEventVersion: StateFlow<Int> = _quotedEventVersion

    private val _eventCacheVersion = MutableStateFlow(0)
    val eventCacheVersion: StateFlow<Int> = _eventCacheVersion

    // Reply count tracking
    private val replyCounts = LruCache<String, Int>(15000)
    private val _replyCountVersion = MutableStateFlow(0)
    val replyCountVersion: StateFlow<Int> = _replyCountVersion

    // Zap tracking
    private val zapSats = LruCache<String, Long>(15000)
    private val _zapVersion = MutableStateFlow(0)
    val zapVersion: StateFlow<Int> = _zapVersion

    // Relay provenance tracking: eventId -> set of relay URLs
    private val eventRelays = LruCache<String, MutableSet<String>>(15000)
    private val _relaySourceVersion = MutableStateFlow(0)
    val relaySourceVersion: StateFlow<Int> = _relaySourceVersion

    // Repost tracking: inner event id -> set of reposter pubkeys
    private val repostAuthors = LruCache<String, MutableSet<String>>(15000)
    // Feed sort time override: eventId -> effective sort timestamp (e.g. repost time)
    private val feedSortTime = LruCache<String, Long>(15000)
    // Track which events the current user has reposted: eventId -> true
    private val userReposts = LruCache<String, Boolean>(15000)
    private val _repostVersion = MutableStateFlow(0)
    val repostVersion: StateFlow<Int> = _repostVersion

    // Reaction tracking: eventId -> map of emoji -> count
    private val reactionCounts = LruCache<String, ConcurrentHashMap<String, Int>>(15000)
    // Track which events the current user has reacted to: "eventId:pubkey" -> (emoji -> reactionEventId)
    private val userReactions = LruCache<String, ConcurrentHashMap<String, String>>(15000)
    private val _reactionVersion = MutableStateFlow(0)
    val reactionVersion: StateFlow<Int> = _reactionVersion
    // Per-target-event dedup sets — evict with the same lifecycle as their count caches
    private val countedReactionIds = LruCache<String, MutableSet<String>>(15000)
    private val countedZapIds = LruCache<String, MutableSet<String>>(15000)
    // Reply dedup: track individual reply event IDs to prevent double-counting
    private val countedReplyIds = ConcurrentHashMap.newKeySet<String>()
    // Reply index: rootEventId -> set of reply event IDs (for thread cache seeding)
    private val rootReplyIds = ConcurrentHashMap<String, MutableSet<String>>()

    // Detailed reaction tracking: eventId -> (emoji -> list of reactor pubkeys)
    private val reactionDetails = LruCache<String, ConcurrentHashMap<String, MutableList<String>>>(15000)
    // Custom emoji URL tracking for reactions: target eventId -> (":shortcode:" -> url)
    private val reactionEmojiUrls = LruCache<String, ConcurrentHashMap<String, String>>(15000)

    // Detailed zap tracking: eventId -> synchronized list of ZapDetail
    private val zapDetails = LruCache<String, MutableList<ZapDetail>>(15000)
    // Track which events the current user has zapped: eventId -> true
    private val userZaps = LruCache<String, Boolean>(15000)
    // Events where we optimistically added the user's own zap (to avoid double-counting receipts)
    private val optimisticZaps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Poll vote tracking: pollId -> (optionId -> count)
    private val pollVoteCounts = LruCache<String, ConcurrentHashMap<String, Int>>(5000)
    // pollId -> (voterPubkey -> timestamp) for one-vote-per-pubkey enforcement
    private val pollVoters = LruCache<String, ConcurrentHashMap<String, Long>>(5000)
    // pollId -> selected option IDs for current user
    private val userPollVotes = LruCache<String, List<String>>(5000)
    private val _pollVoteVersion = MutableStateFlow(0)
    val pollVoteVersion: StateFlow<Int> = _pollVoteVersion

    // Debouncing: coalesce rapid-fire feed list and version updates
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val feedDirty = Channel<Unit>(Channel.CONFLATED)
    private val feedInserted = Channel<Unit>(Channel.CONFLATED)
    private val relayFeedInserted = Channel<Unit>(Channel.CONFLATED)
    private val versionDirty = Channel<Unit>(Channel.CONFLATED)

    init {
        // Emit feed updates when new events are inserted. Uses a conflated channel so
        // rapid-fire inserts from multiple relays coalesce into a single emission after
        // a brief 50ms settle window — 5x faster than the previous 250ms polling loop
        // while still batching concurrent inserts.
        scope.launch {
            for (signal in feedInserted) {
                delay(50)  // settle window: coalesce concurrent inserts
                val filter = _authorFilter.value
                val raw = synchronized(feedList) { feedList.toList() }
                _feed.value = if (filter == null) raw else raw.filter {
                    it.pubkey in filter || isRepostedByAny(it.id, filter)
                }
            }
        }
        // Relay feed emission — same 50ms settle pattern but for isolated relay feed
        scope.launch {
            for (signal in relayFeedInserted) {
                delay(50)
                _relayFeed.value = synchronized(relayFeedList) { relayFeedList.toList() }
            }
        }
        // Immediate emission channel — used for explicit flushes (purge, filter change, etc.)
        scope.launch {
            for (signal in feedDirty) {
                val filter = _authorFilter.value
                val raw = synchronized(feedList) { feedList.toList() }
                _feed.value = if (filter == null) raw else raw.filter {
                    it.pubkey in filter || isRepostedByAny(it.id, filter)
                }
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
            var pendingPollVote = false
            for (signal in versionDirty) {
                // Drain all pending flags and wait for a quiet period
                delay(50)
                if (pendingProfile || profileDirty) { _profileVersion.value++; profileDirty = false }
                if (pendingReaction || reactionDirty) { _reactionVersion.value++; reactionDirty = false }
                if (pendingReplyCount || replyCountDirtyFlag) { _replyCountVersion.value++; replyCountDirtyFlag = false }
                if (pendingZap || zapDirty) { _zapVersion.value++; zapDirty = false }
                if (pendingRepost || repostDirty) { _repostVersion.value++; repostDirty = false }
                if (pendingRelaySource || relaySourceDirtyFlag) { _relaySourceVersion.value++; relaySourceDirtyFlag = false }
                if (pendingPollVote || pollVoteDirty) { _pollVoteVersion.value++; pollVoteDirty = false }
                pendingProfile = false
                pendingReaction = false
                pendingReplyCount = false
                pendingZap = false
                pendingRepost = false
                pendingRelaySource = false
                pendingPollVote = false
            }
        }
    }

    @Volatile private var profileDirty = false
    @Volatile private var reactionDirty = false
    @Volatile private var replyCountDirtyFlag = false
    @Volatile private var zapDirty = false
    @Volatile private var repostDirty = false
    @Volatile private var relaySourceDirtyFlag = false
    @Volatile private var pollVoteDirty = false

    private fun markVersionDirty() {
        versionDirty.trySend(Unit)
    }

    fun addEvent(event: NostrEvent) {
        if (event.kind == Nip88.KIND_POLL_RESPONSE) {
            val isNew = event.id !in seenEventIds
            Log.d("POLL", "[EventRepo] addEvent kind=1018 id=${event.id.take(12)} isNew=$isNew pubkey=${event.pubkey.take(8)}")
        }
        if (!seenEventIds.add(event.id)) return  // atomic dedup across all relay threads
        if (event.created_at > System.currentTimeMillis() / 1000 + 30) return  // reject future-dated notes (30s grace for clock skew)
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        if ((event.kind == 1 || event.kind == 30023) && muteRepo?.containsMutedWord(event.content) == true) return
        if (deletedEventsRepo?.isDeleted(event.id) == true) return
        // Engagement events (reactions, reposts) are only needed for their
        // side effects (counts, details, etc.) — skip eventCache to avoid evicting the
        // kind 0/1 events that screens actually navigate to.
        // Zap receipts (9735) are cached for the zap inspector debug feature.
        if (event.kind != 7 && event.kind != 6 && event.kind != Nip88.KIND_POLL_RESPONSE) {
            eventCache.put(event.id, event)
        }
        eventPersistence?.persistEvent(event)
        relayHintStore?.extractHintsFromTags(event)

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
                val isReply = Nip10.isReply(event)
                if (!isReply) binaryInsert(event, fromFeed = true)
            }
            30023 -> {
                binaryInsert(event, fromFeed = true)
            }
            6 -> {
                // Repost: parse embedded event from content and insert it into the feed
                if (event.content.isNotBlank()) {
                    try {
                        val inner = fromJson(event.content)
                        if (muteRepo?.isBlocked(inner.pubkey) == true) return
                        if (muteRepo?.containsMutedWord(inner.content) == true) return
                        val authors = repostAuthors.get(inner.id)
                            ?: mutableSetOf<String>().also { repostAuthors.put(inner.id, it) }
                        authors.add(event.pubkey)
                        // Auto-mark if this is the current user's repost
                        if (event.pubkey == currentUserPubkey) {
                            userReposts.put(inner.id, true)
                        }
                        repostDirty = true
                        markVersionDirty()
                        val isReply = Nip10.isReply(inner)
                        // Only bump feed sort time if the reposter is a followed author.
                        // Engagement subscriptions bring in reposts from anyone — non-followed
                        // reposters should update counts but not re-sort the feed.
                        val filter = _authorFilter.value
                        val reposterIsFollowed = filter == null || event.pubkey in filter
                        if (seenEventIds.add(inner.id)) {
                            eventCache.put(inner.id, inner)
                            if (!isReply) {
                                if (reposterIsFollowed) {
                                    feedSortTime.put(inner.id, event.created_at)
                                }
                                val sortTime = if (reposterIsFollowed) event.created_at else inner.created_at
                                binaryInsert(inner, sortTime = sortTime, fromFeed = true)
                            }
                        } else if (!isReply && reposterIsFollowed) {
                            // Already seen — update sort time if repost is newer so it surfaces to top
                            val prevTime = feedSortTime.get(inner.id) ?: inner.created_at
                            if (event.created_at > prevTime) {
                                feedSortTime.put(inner.id, event.created_at)
                                synchronized(feedList) {
                                    val idx = feedList.indexOfFirst { it.id == inner.id }
                                    if (idx >= 0) {
                                        feedList.removeAt(idx)
                                        feedIds.remove(inner.id)
                                    }
                                }
                                binaryInsert(inner, sortTime = event.created_at, fromFeed = true)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            5 -> {
                val deletedIds = Nip09.getDeletedEventIds(event)
                for (id in deletedIds) {
                    val target = eventCache.get(id)
                    if (target == null || target.pubkey == event.pubkey) {
                        deletedEventsRepo?.markDeleted(id)
                        removeEvent(id)
                    }
                }
            }
            Nip88.KIND_POLL -> {
                // Polls are top-level feed events like kind 1
                binaryInsert(event, fromFeed = true)
            }
            Nip88.KIND_POLL_RESPONSE -> addPollVote(event)
            7 -> addReaction(event)
            9735 -> {
                val targetId = Nip57.getZappedEventId(event)
                    ?: resolveAddressableTarget(event)
                    ?: return
                // Per-target dedup — atomic get-or-create under lock to prevent
                // concurrent threads from creating separate sets for the same target
                val dedupSet = synchronized(countedZapIds) {
                    countedZapIds.get(targetId)
                        ?: mutableSetOf<String>().also { countedZapIds.put(targetId, it) }
                }
                synchronized(dedupSet) { if (!dedupSet.add(event.id)) return }
                val sats = Nip57.getZapAmountSats(event)
                if (sats > 0) {
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    // Skip if this is our own zap and we already added it optimistically
                    val isOwnOptimistic = zapperPubkey == currentUserPubkey && optimisticZaps.remove(targetId)
                    if (!isOwnOptimistic) {
                        addZapSats(targetId, sats)
                        if (zapperPubkey != null) {
                            val zapMessage = Nip57.getZapMessage(event)
                            val isPrivateZap = dmRelayUrls.isNotEmpty() && Nip57.getZapRequestRelays(event).let { reqRelays ->
                                reqRelays.isNotEmpty() && reqRelays.all { it in dmRelayUrls }
                            }
                            val zaps = zapDetails.get(targetId)
                                ?: java.util.Collections.synchronizedList(mutableListOf<ZapDetail>()).also {
                                    zapDetails.put(targetId, it)
                                }
                            zaps.add(ZapDetail(zapperPubkey, sats, zapMessage, isPrivate = isPrivateZap, receiptEventId = event.id))
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
        val targetEventId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: resolveAddressableTarget(event)
            ?: return
        // Per-target dedup — evicts alongside the count cache so re-fetched data can be re-counted
        val dedupSet = countedReactionIds.get(targetEventId)
            ?: mutableSetOf<String>().also { countedReactionIds.put(targetEventId, it) }
        synchronized(dedupSet) { if (!dedupSet.add(event.id)) return }
        val emoji = if (event.content.isBlank() || event.content == "+") "❤️" else event.content

        // Cache custom emoji URLs from reaction event tags
        val emojiTags = Nip30.parseEmojiTags(event)
        if (emojiTags.isNotEmpty()) {
            val urlMap = reactionEmojiUrls.get(targetEventId)
                ?: ConcurrentHashMap<String, String>().also { reactionEmojiUrls.put(targetEventId, it) }
            for ((shortcode, url) in emojiTags) {
                urlMap[":$shortcode:"] = url
            }
        }

        val counts = reactionCounts.get(targetEventId)
            ?: ConcurrentHashMap<String, Int>().also { reactionCounts.put(targetEventId, it) }
        counts[emoji] = (counts[emoji] ?: 0) + 1

        // Track reactor pubkeys per emoji
        val details = reactionDetails.get(targetEventId)
            ?: ConcurrentHashMap<String, MutableList<String>>().also { reactionDetails.put(targetEventId, it) }
        val pubkeys = details.getOrPut(emoji) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(pubkeys) { if (event.pubkey !in pubkeys) pubkeys.add(event.pubkey) }

        // Only track the current user's reactions — other users' reactions are in
        // reactionDetails already.  Storing every reactor would fill the 5000-entry LRU,
        // evicting the current user's entries and losing highlight state while counts
        // (keyed by eventId alone) remain correct.
        if (event.pubkey == currentUserPubkey) {
            val key = "${targetEventId}:${event.pubkey}"
            val emojiMap = userReactions.get(key)
                ?: ConcurrentHashMap<String, String>().also { userReactions.put(key, it) }
            emojiMap[emoji] = event.id
        }
        reactionDirty = true
        markVersionDirty()
    }

    private fun addPollVote(event: NostrEvent) {
        val pollId = Nip88.getPollEventId(event)
        if (pollId == null) {
            Log.d("POLL", "[addPollVote] SKIP: no poll ID in event ${event.id.take(12)}")
            return
        }
        val optionIds = Nip88.getResponseOptionIds(event)
        if (optionIds.isEmpty()) {
            Log.d("POLL", "[addPollVote] SKIP: no response tags in event ${event.id.take(12)}")
            return
        }

        // Check if poll has ended
        val pollEvent = eventCache.get(pollId)
        if (pollEvent != null && Nip88.isPollEnded(pollEvent)) {
            Log.d("POLL", "[addPollVote] SKIP: poll ended for ${pollId.take(12)}")
            return
        }

        // One-vote-per-pubkey enforcement (latest timestamp wins)
        val voters = pollVoters.get(pollId)
            ?: ConcurrentHashMap<String, Long>().also { pollVoters.put(pollId, it) }
        val prevTimestamp = voters[event.pubkey]
        if (prevTimestamp != null && event.created_at <= prevTimestamp) {
            Log.d("POLL", "[addPollVote] SKIP: older vote from ${event.pubkey.take(8)} for poll ${pollId.take(12)}")
            return
        }

        val counts = pollVoteCounts.get(pollId)
            ?: ConcurrentHashMap<String, Int>().also { pollVoteCounts.put(pollId, it) }

        voters[event.pubkey] = event.created_at
        for (optionId in optionIds) {
            counts[optionId] = (counts[optionId] ?: 0) + 1
        }

        val isOwnVote = event.pubkey == currentUserPubkey
        Log.d("POLL", "[addPollVote] OK: poll=${pollId.take(12)} pubkey=${event.pubkey.take(8)} options=$optionIds isOwn=$isOwnVote currentUser=${currentUserPubkey?.take(8)} totalVoters=${voters.size}")

        // Track current user's vote
        if (isOwnVote) {
            userPollVotes.put(pollId, optionIds)
            Log.d("POLL", "[addPollVote] STORED user vote for poll ${pollId.take(12)}: $optionIds")
        }

        pollVoteDirty = true
        markVersionDirty()
    }

    fun getPollVoteCounts(pollId: String): Map<String, Int> {
        return pollVoteCounts.get(pollId)?.toMap() ?: emptyMap()
    }

    fun getPollTotalVotes(pollId: String): Int {
        return pollVoters.get(pollId)?.size ?: 0
    }

    fun getUserPollVotes(pollId: String): List<String> {
        return userPollVotes.get(pollId) ?: emptyList()
    }

    /**
     * Resolve an engagement event's target when it only has an a-tag (addressable
     * coordinate like "30023:<pubkey>:<dtag>") and no e-tag. Looks up the cached
     * addressable event to return its event ID, so engagement counts are keyed correctly.
     */
    private fun resolveAddressableTarget(event: NostrEvent): String? {
        val aTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "a" }?.get(1) ?: return null
        val parts = aTag.split(":", limit = 3)
        if (parts.size < 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        val author = parts[1]
        val dTag = parts[2]
        return findAddressableEvent(kind, author, dTag)?.id
    }

    private fun effectiveSortTime(event: NostrEvent): Long =
        feedSortTime.get(event.id) ?: event.created_at

    private fun binaryInsert(event: NostrEvent, sortTime: Long = event.created_at, fromFeed: Boolean = false) {
        synchronized(feedList) {
            if (!feedIds.add(event.id)) return  // already in feed
            var low = 0
            var high = feedList.size
            while (low < high) {
                val mid = (low + high) / 2
                if (effectiveSortTime(feedList[mid]) > sortTime) low = mid + 1 else high = mid
            }
            feedList.add(low, event)
        }
        feedInserted.trySend(Unit)  // coalesced emission via 50ms settle window
        if (fromFeed && countNewNotes && sortTime > newNotesCutoff) {
            val filter = _authorFilter.value
            if (filter == null || event.pubkey in filter || isRepostedByAny(event.id, filter)) _newNoteCount.value++
        }
    }

    fun getRecentEventIdsByAuthor(pubkey: String, limit: Int = 50): List<String> {
        return eventCache.snapshot().values
            .asSequence()
            .filter { it.kind == 1 && it.pubkey == pubkey }
            .sortedByDescending { it.created_at }
            .take(limit)
            .map { it.id }
            .toList()
    }

    fun getLatestEventTimestamp(pubkey: String, kind: Int): Long? {
        return eventCache.snapshot().values
            .asSequence()
            .filter { it.pubkey == pubkey && it.kind == kind }
            .maxOfOrNull { it.created_at }
    }

    fun cacheEvent(event: NostrEvent) {
        if (eventCache.get(event.id) != null) return  // already cached
        seenEventIds.add(event.id)
        eventCache.put(event.id, event)
        eventPersistence?.persistEvent(event)
        relayHintStore?.extractHintsFromTags(event)
        if (event.kind == 0) {
            val updated = profileRepo?.updateFromEvent(event)
            if (updated != null) {
                profileDirty = true
                markVersionDirty()
            }
        }
        _quotedEventVersion.value++
    }

    fun removeEvent(eventId: String) {
        eventCache.remove(eventId)
        synchronized(feedList) {
            if (feedIds.remove(eventId)) {
                feedList.removeAll { it.id == eventId }
            }
        }
        synchronized(relayFeedList) {
            if (relayFeedIds.remove(eventId)) {
                relayFeedList.removeAll { it.id == eventId }
                _relayFeed.value = relayFeedList.toList()
            }
        }
        feedDirty.trySend(Unit)
    }

    fun requestQuotedEvent(eventId: String, relayHints: List<String> = emptyList()) {
        metadataFetcher?.requestQuotedEvent(eventId, relayHints)
    }

    /** Fetch an event expected to live on our own write/read relays (e.g. our own note). */
    fun requestOwnEvent(eventId: String) {
        metadataFetcher?.requestOwnEvent(eventId)
    }

    fun requestAddressableEvent(kind: Int, author: String, dTag: String, relayHints: List<String> = emptyList()) {
        metadataFetcher?.requestAddressableEvent(kind, author, dTag, relayHints)
    }

    fun findAddressableEvent(kind: Int, author: String, dTag: String): NostrEvent? {
        return eventCache.snapshot().values.firstOrNull { event ->
            event.kind == kind && event.pubkey == author &&
                event.tags.any { it.size >= 2 && it[0] == "d" && it[1] == dTag }
        }
    }

    fun getEvent(id: String): NostrEvent? = eventCache.get(id)

    fun getCacheSize(): Int = eventCache.size()

    fun bumpEventCacheVersion() { _eventCacheVersion.value++ }

    fun getProfileData(pubkey: String): ProfileData? = profileRepo?.get(pubkey)

    /**
     * Build maps of bolt11 payment hash → counterparty pubkey from persisted zap receipts (kind 9735).
     * Returns a pair: (senderMap for incoming zaps, recipientMap for outgoing zaps).
     * Queries ObjectBox for persistent data across app restarts.
     */
    // Cached zap receipt counterparty maps (payment hash → pubkey)
    private var cachedZapSenders: Map<String, String> = emptyMap()
    private var cachedZapRecipients: Map<String, String> = emptyMap()
    // Fuzzy lookup: "amountMsats:timestampBucket" → (sender, recipient)
    private var cachedZapByAmountTime: Map<String, Pair<String?, String?>> = emptyMap()
    private var cachedZapReceiptCount = 0

    data class ZapCounterpartyMaps(
        val senders: Map<String, String>,
        val recipients: Map<String, String>,
        val byAmountTime: Map<String, Pair<String?, String?>>
    )

    fun getZapReceiptCounterparties(): ZapCounterpartyMaps {
        val receipts = eventPersistence?.getZapReceipts(limit = 2000)
            ?: return ZapCounterpartyMaps(cachedZapSenders, cachedZapRecipients, cachedZapByAmountTime)
        // Only rebuild if new receipts arrived
        if (receipts.size == cachedZapReceiptCount)
            return ZapCounterpartyMaps(cachedZapSenders, cachedZapRecipients, cachedZapByAmountTime)

        val senders = mutableMapOf<String, String>()    // payment hash → sender pubkey
        val recipients = mutableMapOf<String, String>()  // payment hash → recipient pubkey
        val byAmountTime = mutableMapOf<String, Pair<String?, String?>>()
        for (event in receipts) {
            val bolt11 = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }?.get(1) ?: continue
            val decoded = Bolt11.decode(bolt11)
            val hash = decoded?.paymentHash ?: continue
            // Sender from embedded kind 9734 zap request
            val sender = Nip57.getZapperPubkey(event)
            if (sender != null) senders[hash] = sender
            // Recipient from 'p' tag of the receipt
            val recipient = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            if (recipient != null) recipients[hash] = recipient

            // Build fuzzy index: amount in msats + timestamp bucketed to 60s windows
            // Only store one entry per exact bucket; collisions are resolved at lookup time
            val amountMsats = (decoded.amountSats ?: 0) * 1000
            if (amountMsats > 0) {
                val bucket = event.created_at / 60  // 1-minute buckets
                val key = "${amountMsats}:${bucket}"
                val existing = byAmountTime[key]
                if (existing == null) {
                    byAmountTime[key] = Pair(sender, recipient)
                } else if (existing.first != sender || existing.second != recipient) {
                    // Collision — mark ambiguous
                    byAmountTime[key] = Pair(null, null)
                }
            }
        }
        cachedZapSenders = senders
        cachedZapRecipients = recipients
        cachedZapByAmountTime = byAmountTime
        cachedZapReceiptCount = receipts.size
        return ZapCounterpartyMaps(senders, recipients, byAmountTime)
    }

    fun requestProfileIfMissing(pubkey: String, relayHints: List<String> = emptyList()) {
        metadataFetcher?.addToPendingProfiles(pubkey, relayHints)
    }

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

    fun getReactionEmojiUrl(eventId: String, emojiKey: String): String? {
        return reactionEmojiUrls.get(eventId)?.get(emojiKey)
    }

    fun getReactionEmojiUrls(eventId: String): Map<String, String> {
        return reactionEmojiUrls.get(eventId)?.toMap() ?: emptyMap()
    }

    fun getReactionDetails(eventId: String): Map<String, List<String>> {
        val details = reactionDetails.get(eventId) ?: return emptyMap()
        return details.mapValues { (_, pubkeys) -> synchronized(pubkeys) { pubkeys.toList() } }
    }

    fun getZapDetails(eventId: String): List<ZapDetail> {
        val list = zapDetails.get(eventId) ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun addReplyCount(parentEventId: String, replyEventId: String): Boolean {
        if (!countedReplyIds.add(replyEventId)) return false
        rootReplyIds.getOrPut(parentEventId) { ConcurrentHashMap.newKeySet() }.add(replyEventId)
        val current = replyCounts.get(parentEventId) ?: 0
        replyCounts.put(parentEventId, current + 1)
        replyCountDirtyFlag = true
        markVersionDirty()
        return true
    }

    fun getReplyCount(eventId: String): Int = replyCounts.get(eventId) ?: 0

    /** Returns the current user's most recent reply to [eventId], if cached. */
    fun getMyReplyTo(eventId: String, myPubkey: String): NostrEvent? {
        val replyIds = rootReplyIds[eventId] ?: return null
        var best: NostrEvent? = null
        for (replyId in replyIds) {
            val event = eventCache.get(replyId) ?: continue
            if (event.pubkey == myPubkey && (best == null || event.created_at > best.created_at)) {
                best = event
            }
        }
        return best
    }

    fun getCachedThreadEvents(rootId: String): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        visited.add(rootId)
        eventCache.get(rootId)?.let { result.add(it) }
        while (queue.isNotEmpty()) {
            val parentId = queue.removeFirst()
            val childIds = rootReplyIds[parentId] ?: continue
            for (id in childIds) {
                if (id in visited) continue
                visited.add(id)
                eventCache.get(id)?.let { result.add(it) }
                queue.add(id)
            }
        }
        return result
    }

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

    fun getRelayHintsForEvents(eventIds: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (id in eventIds) {
            val relay = getEventRelays(id).firstOrNull()
            if (relay != null) {
                result[id] = relay
            } else {
                // Fall back to author's known relays from RelayHintStore
                val event = eventCache.get(id) ?: continue
                val authorHint = relayHintStore?.getHints(event.pubkey)?.firstOrNull()
                if (authorHint != null) result[id] = authorHint
            }
        }
        return result
    }

    fun getRepostAuthor(eventId: String): String? = repostAuthors.get(eventId)?.firstOrNull()

    fun getReposterPubkeys(eventId: String): List<String> =
        repostAuthors.get(eventId)?.toList() ?: emptyList()

    /** Check if any of the reposters of [eventId] are in the given [pubkeys] set. */
    fun isRepostedByAny(eventId: String, pubkeys: Set<String>): Boolean {
        val authors = repostAuthors.get(eventId) ?: return false
        return authors.any { it in pubkeys }
    }

    fun getRepostCount(eventId: String): Int = repostAuthors.get(eventId)?.size ?: 0

    fun getRepostTime(eventId: String): Long? = feedSortTime.get(eventId)

    fun markUserRepost(eventId: String) {
        userReposts.put(eventId, true)
        val authors = repostAuthors.get(eventId)
            ?: mutableSetOf<String>().also { repostAuthors.put(eventId, it) }
        if (currentUserPubkey != null) authors.add(currentUserPubkey!!)
        repostDirty = true
        markVersionDirty()
    }

    fun hasUserReposted(eventId: String): Boolean = userReposts.get(eventId) == true

    /**
     * Optimistically record the current user's zap so the UI updates immediately
     * without waiting for the 9735 receipt from relays.
     */
    fun addOptimisticZap(eventId: String, zapperPubkey: String, sats: Long, message: String = "", isPrivate: Boolean = false) {
        // If the 9735 receipt already arrived and was counted, skip the optimistic add
        // to avoid double-counting (receipt can beat the NWC confirmation)
        if (userZaps.get(eventId) == true) return
        userZaps.put(eventId, true)
        optimisticZaps.add(eventId)
        addZapSats(eventId, sats)
        val zaps = zapDetails.get(eventId)
            ?: java.util.Collections.synchronizedList(mutableListOf<ZapDetail>()).also {
                zapDetails.put(eventId, it)
            }
        zaps.add(ZapDetail(zapperPubkey, sats, message, isPrivate))
    }

    fun hasUserZapped(eventId: String): Boolean = userZaps.get(eventId) == true

    fun setAuthorFilter(pubkeys: Set<String>?) {
        _authorFilter.value = pubkeys
        rebuildFilteredFeed()
    }

    private fun rebuildFilteredFeed() {
        val filter = _authorFilter.value
        val raw = synchronized(feedList) { feedList.toList() }
        _feed.value = if (filter == null) raw else raw.filter {
            it.pubkey in filter || isRepostedByAny(it.id, filter)
        }
    }

    fun getNewestFeedEventTimestamp(): Long? {
        return synchronized(feedList) {
            feedList.maxOfOrNull { it.created_at }
        }
    }

    fun getOldestTimestamp(): Long? {
        // Return oldest from the filtered feed so loadMore pages correctly
        val filter = _authorFilter.value
        return synchronized(feedList) {
            if (filter == null) feedList.lastOrNull()?.let { effectiveSortTime(it) }
            else feedList.lastOrNull { it.pubkey in filter || isRepostedByAny(it.id, filter) }?.let { effectiveSortTime(it) }
        }
    }

    fun resetNewNoteCount() {
        _newNoteCount.value = 0
    }

    fun enableNewNoteCounting() {
        newNotesCutoff = synchronized(feedList) {
            feedList.firstOrNull()?.let { effectiveSortTime(it) } ?: (System.currentTimeMillis() / 1000)
        }
        countNewNotes = true
    }

    fun purgeUser(pubkey: String) {
        synchronized(feedList) {
            val removed = feedList.filter { it.pubkey == pubkey }
            feedList.removeAll { it.pubkey == pubkey }
            removed.forEach { feedIds.remove(it.id) }
        }
        synchronized(relayFeedList) {
            val removed = relayFeedList.filter { it.pubkey == pubkey }
            relayFeedList.removeAll { it.pubkey == pubkey }
            removed.forEach { relayFeedIds.remove(it.id) }
            if (removed.isNotEmpty()) _relayFeed.value = relayFeedList.toList()
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



    fun searchNotes(query: String, limit: Int = 50): List<NostrEvent> {
        if (query.isBlank()) return emptyList()
        // Search LRU cache first
        val cacheResults = eventCache.snapshot().values
            .asSequence()
            .filter { it.kind == 1 && it.content.contains(query, ignoreCase = true) }
            .sortedByDescending { it.created_at }
            .take(limit)
            .toList()
        // Merge with ObjectBox results for cross-session search
        val dbResults = eventPersistence?.searchNotes(query, limit) ?: emptyList()
        val seenIds = cacheResults.mapTo(HashSet()) { it.id }
        val merged = cacheResults + dbResults.filter { it.id !in seenIds }
        return merged.sortedByDescending { it.created_at }.take(limit)
    }

    /**
     * Clear only the display state (feedList, feedIds, feedSortTime) without touching
     * seenEventIds or eventCache. This preserves dedup state so in-flight events from
     * non-feed subscriptions are still rejected on feed switch.
     */
    fun resetFeedDisplay() {
        synchronized(feedList) {
            feedList.clear()
            feedIds.clear()
        }
        feedSortTime.evictAll()
        _feed.value = emptyList()
        _newNoteCount.value = 0
        countNewNotes = false
        newNotesCutoff = Long.MAX_VALUE
    }

    /**
     * Rebuild the feed display from eventCache. Used when switching feed types
     * (e.g. LIST → FOLLOWS) where resetFeedDisplay() cleared feedList/feedIds
     * but seenEventIds still contains all previously seen events — causing
     * relay-resent events to be silently deduped by addEvent().
     *
     * Scans eventCache for kind 1 root notes, re-inserts them via binaryInsert,
     * then emits the filtered feed. The optional [sinceTimestamp] limits which
     * events are re-inserted (defaults to 24h).
     */
    fun rebuildFeedFromCache(sinceTimestamp: Long = System.currentTimeMillis() / 1000 - 60 * 60 * 24) {
        val snapshot = eventCache.snapshot()
        var inserted = 0
        for ((_, event) in snapshot) {
            if (event.kind != 1) continue
            if (event.created_at < sinceTimestamp) continue
            if (muteRepo?.isBlocked(event.pubkey) == true) continue
            if (deletedEventsRepo?.isDeleted(event.id) == true) continue
            val isReply = Nip10.isReply(event)
            if (isReply) continue
            val sortTime = feedSortTime.get(event.id) ?: event.created_at
            binaryInsert(event, sortTime = sortTime)
            inserted++
        }
        rebuildFilteredFeed()
        android.util.Log.d("RLC", "[EventRepo] rebuildFeedFromCache: $inserted events re-inserted from cache (since=$sinceTimestamp)")
    }

    // -- Isolated relay feed methods --

    fun addRelayFeedEvent(event: NostrEvent) {
        if (event.created_at > System.currentTimeMillis() / 1000 + 30) return
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        if (deletedEventsRepo?.isDeleted(event.id) == true) return

        when (event.kind) {
            1 -> {
                val isReply = Nip10.isReply(event)
                if (!isReply) {
                    eventCache.put(event.id, event)
                    relayHintStore?.extractHintsFromTags(event)
                    relayFeedBinaryInsert(event)
                }
            }
            Nip88.KIND_POLL -> {
                eventCache.put(event.id, event)
                relayHintStore?.extractHintsFromTags(event)
                relayFeedBinaryInsert(event)
            }
            30023 -> {
                eventCache.put(event.id, event)
                relayHintStore?.extractHintsFromTags(event)
                relayFeedBinaryInsert(event)
            }
            6 -> {
                if (event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (muteRepo?.isBlocked(inner.pubkey) == true) return
                        if (muteRepo?.containsMutedWord(inner.content) == true) return
                        // Track repost metadata for badges
                        val authors = repostAuthors.get(inner.id)
                            ?: mutableSetOf<String>().also { repostAuthors.put(inner.id, it) }
                        authors.add(event.pubkey)
                        if (event.pubkey == currentUserPubkey) {
                            userReposts.put(inner.id, true)
                        }
                        repostDirty = true
                        markVersionDirty()
                        val isReply = Nip10.isReply(inner)
                        if (!isReply) {
                            eventCache.put(inner.id, inner)
                            relayHintStore?.extractHintsFromTags(inner)
                            feedSortTime.put(inner.id, event.created_at)
                            relayFeedBinaryInsert(inner, sortTime = event.created_at)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun addTrendingFeedEvent(event: NostrEvent) {
        if (event.created_at > System.currentTimeMillis() / 1000 + 30) return
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        if (deletedEventsRepo?.isDeleted(event.id) == true) return

        when (event.kind) {
            1 -> {
                val isReply = Nip10.isReply(event)
                if (!isReply) {
                    eventCache.put(event.id, event)
                    relayHintStore?.extractHintsFromTags(event)
                    trendingFeedAppend(event)
                }
            }
            Nip88.KIND_POLL -> {
                eventCache.put(event.id, event)
                relayHintStore?.extractHintsFromTags(event)
                trendingFeedAppend(event)
            }
            30023 -> {
                eventCache.put(event.id, event)
                relayHintStore?.extractHintsFromTags(event)
                trendingFeedAppend(event)
            }
            6 -> {
                if (event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (muteRepo?.isBlocked(inner.pubkey) == true) return
                        if (muteRepo?.containsMutedWord(inner.content) == true) return
                        val authors = repostAuthors.get(inner.id)
                            ?: mutableSetOf<String>().also { repostAuthors.put(inner.id, it) }
                        authors.add(event.pubkey)
                        if (event.pubkey == currentUserPubkey) {
                            userReposts.put(inner.id, true)
                        }
                        repostDirty = true
                        markVersionDirty()
                        val isReply = Nip10.isReply(inner)
                        if (!isReply) {
                            eventCache.put(inner.id, inner)
                            relayHintStore?.extractHintsFromTags(inner)
                            feedSortTime.put(inner.id, event.created_at)
                            trendingFeedAppend(inner)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun trendingFeedAppend(event: NostrEvent) {
        synchronized(relayFeedList) {
            if (!relayFeedIds.add(event.id)) return
            relayFeedList.add(event)  // append to end — preserves relay ordering
        }
        relayFeedInserted.trySend(Unit)
    }

    private fun relayFeedBinaryInsert(event: NostrEvent, sortTime: Long = event.created_at) {
        synchronized(relayFeedList) {
            if (!relayFeedIds.add(event.id)) return
            var low = 0
            var high = relayFeedList.size
            while (low < high) {
                val mid = (low + high) / 2
                if (effectiveSortTime(relayFeedList[mid]) > sortTime) low = mid + 1 else high = mid
            }
            relayFeedList.add(low, event)
        }
        relayFeedInserted.trySend(Unit)
    }

    fun clearRelayFeed() {
        synchronized(relayFeedList) {
            relayFeedList.clear()
            relayFeedIds.clear()
        }
        _relayFeed.value = emptyList()
    }

    fun getOldestRelayFeedTimestamp(): Long? {
        return synchronized(relayFeedList) {
            relayFeedList.lastOrNull()?.let { effectiveSortTime(it) }
        }
    }

    fun clearFeed() {
        resetFeedDisplay()
        eventCache.evictAll()
        seenEventIds.clear()
    }

    fun clearAll() {
        _authorFilter.value = null
        clearFeed()
        clearRelayFeed()
        replyCounts.evictAll()
        zapSats.evictAll()
        eventRelays.evictAll()
        repostAuthors.evictAll()
        reactionCounts.evictAll()
        userReactions.evictAll()
        reactionDetails.evictAll()
        reactionEmojiUrls.evictAll()
        zapDetails.evictAll()
        userReposts.evictAll()
        userZaps.evictAll()
        countedReactionIds.evictAll()
        countedZapIds.evictAll()
        countedReplyIds.clear()
        rootReplyIds.clear()
        _profileVersion.value = 0
        _quotedEventVersion.value = 0
        _replyCountVersion.value = 0
        _zapVersion.value = 0
        _relaySourceVersion.value = 0
        _reactionVersion.value = 0
    }
}
