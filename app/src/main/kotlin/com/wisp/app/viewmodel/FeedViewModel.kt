package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.Relay
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOLLOWS, RELAY, LIST }

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    val relayPool = RelayPool()
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app)
    val eventRepo = EventRepository(profileRepo, muteRepo)
    val keyRepo = KeyRepository(app)
    val contactRepo = ContactRepository(app)
    val listRepo = ListRepository(app)
    val dmRepo = DmRepository()
    val notifRepo = NotificationRepository()
    val relayListRepo = RelayListRepository(app)
    val blossomRepo = BlossomRepository(app)
    val relayInfoRepo = RelayInfoRepository()
    val outboxRouter = OutboxRouter(relayPool, relayListRepo)
    private var feedSubId = "feed"
    private var isLoadingMore = false
    private val pendingQuoteFetches = mutableSetOf<String>()
    private val activeReactionSubIds = mutableListOf<String>()
    private val activeZapSubIds = mutableListOf<String>()

    // Batched profile fetching
    private val pendingProfilePubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private var metaBatchCounter = 0
    private val profileAttempts = mutableMapOf<String, Int>()  // pubkey → attempt count

    private companion object {
        const val MAX_PROFILE_ATTEMPTS = 3
    }

    // Batched reply count fetching
    private val pendingReplyCountIds = mutableSetOf<String>()
    private var replyCountBatchJob: Job? = null
    private var replyCountBatchCounter = 0

    // Batched zap count fetching
    private val pendingZapCountIds = mutableSetOf<String>()
    private var zapCountBatchJob: Job? = null
    private var zapCountBatchCounter = 0

    // Dispatcher for event processing (off main thread)
    private val processingDispatcher = Dispatchers.Default

    val nwcRepo = NwcRepository(app)
    val zapSender = ZapSender(keyRepo, nwcRepo, relayPool, Relay.createClient())

    private val _zapInProgress = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgress: StateFlow<Set<String>> = _zapInProgress

    private val _zapSuccess = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapSuccess: SharedFlow<String> = _zapSuccess

    private val _zapError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapError: SharedFlow<String> = _zapError

    val feed: StateFlow<List<NostrEvent>> = eventRepo.feed
    val newNoteCount: StateFlow<Int> = eventRepo.newNoteCount

    private val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone

    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType

    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList

    fun getUserPubkey(): String? = keyRepo.getKeypair()?.pubkey?.toHex()

    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()

    private var relaysInitialized = false

    fun initRelays() {
        if (relaysInitialized) return
        relaysInitialized = true
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.updateRelays(relays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())

        viewModelScope.launch { relayInfoRepo.prefetchAll(relays.map { it.url }) }

        // Main event processing loop — runs on Default dispatcher to keep UI thread free
        viewModelScope.launch(processingDispatcher) {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                processRelayEvent(event, relayUrl, subscriptionId)
            }
        }

        // Handle EOSE for relay-lists subscription: re-route feed with fresh data
        viewModelScope.launch {
            awaitEoseWithTimeout("relay-lists")
            relayPool.closeOnAllRelays("relay-lists")
            resubscribeFeed()
        }

        // Periodic profile & quote sweep — runs on Default dispatcher
        viewModelScope.launch(processingDispatcher) {
            // First sweep after 5 seconds
            delay(5_000)
            sweepMissingProfiles()
            // Then every 15s for the first 2 minutes
            repeat(7) {
                delay(15_000)
                sweepMissingProfiles()
            }
            // Then every 60s indefinitely
            while (true) {
                delay(60_000)
                sweepMissingProfiles()
            }
        }

        // Periodic ephemeral relay cleanup
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                relayPool.cleanupEphemeralRelays()
            }
        }

        // Periodic relay list refresh (every 30 minutes)
        viewModelScope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                fetchRelayListsForFollows()
            }
        }

        // Set listRepo owner
        getUserPubkey()?.let { listRepo.setOwner(it) }

        subscribeFeed()
        fetchRelayListsForFollows()
        fetchProfilesForFollows()
    }

    /**
     * Await a specific EOSE signal by subscription ID. One-shot, no lingering collector.
     */
    private suspend fun awaitEose(targetSubId: String) {
        relayPool.eoseSignals.first { it == targetSubId }
    }

    /**
     * Await EOSE with a timeout. Returns true if EOSE was received, false on timeout.
     */
    private suspend fun awaitEoseWithTimeout(targetSubId: String, timeoutMs: Long = 15_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            relayPool.eoseSignals.first { it == targetSubId }
            true
        } ?: false
    }

    /**
     * Process a single relay event. Runs on [processingDispatcher] (off main thread).
     */
    private fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        if (subscriptionId == "notif") {
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                notifRepo.addEvent(event, myPubkey)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    addToPendingProfiles(event.pubkey)
                }
                // For zaps, also fetch the zapper profile (P tag)
                if (event.kind == 9735) {
                    val zapperPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId.startsWith("quote-")) {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 && eventRepo.getProfileData(event.pubkey) == null) {
                addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId.startsWith("reply-count-")) {
            if (event.kind == 1) {
                val targetId = Nip10.getReplyTarget(event)
                if (targetId != null) eventRepo.addReplyCount(targetId)
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId == "zaps") {
            if (event.kind == 9735) eventRepo.addEvent(event)
        } else {
            if (event.kind == 10002) {
                relayListRepo.updateFromEvent(event)
            }
            if (event.kind == Nip51.KIND_MUTE_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    muteRepo.loadFromEvent(event)
                }
            }
            if (event.kind == Blossom.KIND_SERVER_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    blossomRepo.updateFromEvent(event)
                }
            }
            if (event.kind == Nip51.KIND_FOLLOW_SET) {
                listRepo.updateFromEvent(event)
            }
            eventRepo.addEvent(event)
            if (event.kind == 1) eventRepo.addEventRelay(event.id, relayUrl)
            when (event.kind) {
                1 -> {
                    fetchQuotedEvents(event)
                    addToPendingReplyCounts(event.id)
                    addToPendingZapCounts(event.id)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        addToPendingProfiles(event.pubkey)
                    }
                }
                3 -> {
                    val myPubkey = getUserPubkey()
                    if (myPubkey != null && event.pubkey == myPubkey) {
                        contactRepo.updateFromEvent(event)
                    }
                }
            }
        }
    }

    // --- Batching helpers that accumulate without launching per-event coroutines ---

    /**
     * Add pubkey to pending profile batch. Flushes when batch reaches 20 or after delay.
     * No per-event coroutine launch — just adds to set and schedules a single flush.
     */
    fun queueProfileFetch(pubkey: String) = addToPendingProfiles(pubkey)

    private fun addToPendingProfiles(pubkey: String) {
        synchronized(pendingProfilePubkeys) {
            if (profileRepo.has(pubkey)) return
            val attempts = profileAttempts[pubkey] ?: 0
            if (attempts >= MAX_PROFILE_ATTEMPTS) return
            if (pubkey in pendingProfilePubkeys) return
            pendingProfilePubkeys.add(pubkey)
            val shouldFlushNow = pendingProfilePubkeys.size >= 20
            if (shouldFlushNow) {
                profileBatchJob?.cancel()
                flushProfileBatch()
            } else if (profileBatchJob == null || profileBatchJob?.isActive != true) {
                profileBatchJob = viewModelScope.launch(processingDispatcher) {
                    delay(100)
                    synchronized(pendingProfilePubkeys) { flushProfileBatch() }
                }
            }
        }
    }

    private fun addToPendingReplyCounts(eventId: String) {
        synchronized(pendingReplyCountIds) {
            pendingReplyCountIds.add(eventId)
            val shouldFlushNow = pendingReplyCountIds.size >= 20
            if (shouldFlushNow) {
                replyCountBatchJob?.cancel()
                flushReplyCountBatch()
            } else if (replyCountBatchJob == null || replyCountBatchJob?.isActive != true) {
                replyCountBatchJob = viewModelScope.launch(processingDispatcher) {
                    delay(500)
                    synchronized(pendingReplyCountIds) { flushReplyCountBatch() }
                }
            }
        }
    }

    private fun addToPendingZapCounts(eventId: String) {
        synchronized(pendingZapCountIds) {
            pendingZapCountIds.add(eventId)
            val shouldFlushNow = pendingZapCountIds.size >= 20
            if (shouldFlushNow) {
                zapCountBatchJob?.cancel()
                flushZapCountBatch()
            } else if (zapCountBatchJob == null || zapCountBatchJob?.isActive != true) {
                zapCountBatchJob = viewModelScope.launch(processingDispatcher) {
                    delay(500)
                    synchronized(pendingZapCountIds) { flushZapCountBatch() }
                }
            }
        }
    }

    private fun fetchProfilesForFollows() {
        val authors = contactRepo.getFollowList().map { it.pubkey }
        if (authors.isEmpty()) return
        val missing = authors.filter { !profileRepo.has(it) }
        if (missing.isEmpty()) return
        // Mark as attempted so addToPendingProfiles doesn't duplicate
        missing.forEach { profileAttempts[it] = (profileAttempts[it] ?: 0) + 1 }
        // Batch into groups of 20
        missing.chunked(20).forEachIndexed { index, batch ->
            val subId = "follow-profiles-$index"
            outboxRouter.requestProfiles(subId, batch)
            viewModelScope.launch {
                awaitEoseWithTimeout(subId)
                relayPool.closeOnAllRelays(subId)
            }
        }
    }

    private fun fetchRelayListsForFollows() {
        val authors = contactRepo.getFollowList().map { it.pubkey }
        if (authors.isEmpty()) return
        outboxRouter.requestMissingRelayLists(authors)
    }

    fun setFeedType(type: FeedType) {
        _feedType.value = type
        eventRepo.clearFeed()
        resubscribeFeed()
    }

    fun setSelectedRelay(url: String) {
        _selectedRelay.value = url
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearFeed()
            resubscribeFeed()
        }
    }

    fun getRelayUrls(): List<String> = relayPool.getRelayUrls()

    /**
     * Called when the app returns to the foreground. Re-establishes all subscriptions
     * including outbox relay routing, since relay servers drop subscriptions when idle.
     */
    fun onAppResume() {
        if (!relaysInitialized) return
        subscribeFeed()
        fetchRelayListsForFollows()
    }

    private fun subscribeFeed() {
        resubscribeFeed()

        // Subscribe to own data + persistent subs
        val myPubkey = getUserPubkey()
        if (myPubkey != null) {
            // Combined one-shot sub for own profile, contacts, mute list, blossom list
            val selfDataFilters = listOf(
                Filter(kinds = listOf(0), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(3), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Nip51.KIND_MUTE_LIST), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Blossom.KIND_SERVER_LIST), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(myPubkey), limit = 50)
            )
            relayPool.sendToAll(ClientMessage.req("self-data", selfDataFilters))
            viewModelScope.launch {
                awaitEoseWithTimeout("self-data")
                relayPool.closeOnAllRelays("self-data")
            }

            // Persistent: gift wraps for DMs
            val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
            relayPool.sendToAll(ClientMessage.req("dms", dmFilter))

            // Persistent: notifications (reactions, replies, zaps mentioning me)
            val notifFilter = Filter(
                kinds = listOf(1, 7, 9735),
                pTags = listOf(myPubkey),
                limit = 100
            )
            relayPool.sendToAll(ClientMessage.req("notif", notifFilter))
        }
    }

    private var feedEoseJob: Job? = null

    private fun resubscribeFeed() {
        // Close old feed subscription on all relays (including ephemeral)
        relayPool.closeOnAllRelays(feedSubId)
        // Close old reaction and zap subs
        for (subId in activeReactionSubIds) relayPool.closeOnAllRelays(subId)
        activeReactionSubIds.clear()
        for (subId in activeZapSubIds) relayPool.closeOnAllRelays(subId)
        activeZapSubIds.clear()
        relayPool.clearSeenEvents()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        when (_feedType.value) {
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: return
                val authors = list.members.toList()
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), limit = 100)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.FOLLOWS -> {
                val authors = contactRepo.getFollowList().map { it.pubkey }
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), limit = 100)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value ?: return
                val filter = Filter(kinds = listOf(1, 6), limit = 100)
                val msg = ClientMessage.req(feedSubId, filter)
                relayPool.sendToRelay(url, msg)
            }
        }

        // After initial batch loads, enable new-note counting and fetch scoped reactions/zaps
        feedEoseJob = viewModelScope.launch {
            awaitEose(feedSubId)
            eventRepo.countNewNotes = true
            _initialLoadDone.value = true

            // Subscribe to reactions and zaps scoped to feed event IDs
            subscribeReactionsForFeed()
            subscribeZapsForFeed()

            // Sweep for any missing profiles after initial load
            withContext(processingDispatcher) {
                sweepMissingProfiles()
            }
        }
    }

    /**
     * Subscribe to reactions scoped to the event IDs currently in the feed.
     */
    private fun subscribeReactionsForFeed() {
        // Close old reaction subs
        for (subId in activeReactionSubIds) relayPool.closeOnAllRelays(subId)
        activeReactionSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        val eventIds = feedEvents.map { it.id }
        // Batch into chunks to avoid oversized filters
        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = if (index == 0) "reactions" else "reactions-$index"
            activeReactionSubIds.add(subId)
            val reactFilter = Filter(kinds = listOf(7), eTags = batch)
            val reactMsg = ClientMessage.req(subId, reactFilter)
            relayPool.sendToAll(reactMsg)
        }
    }

    /**
     * Subscribe to zap receipts scoped to the event IDs currently in the feed.
     */
    private fun subscribeZapsForFeed() {
        // Close old zap subs
        for (subId in activeZapSubIds) relayPool.closeOnAllRelays(subId)
        activeZapSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        val eventIds = feedEvents.map { it.id }
        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = if (index == 0) "zaps" else "zaps-$index"
            activeZapSubIds.add(subId)
            val zapFilter = Filter(kinds = listOf(9735), eTags = batch)
            val zapMsg = ClientMessage.req(subId, zapFilter)
            relayPool.sendToAll(zapMsg)
        }
    }

    fun toggleFollow(pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val currentList = contactRepo.getFollowList()
        val newList = if (contactRepo.isFollowing(pubkey)) {
            Nip02.removeFollow(currentList, pubkey)
        } else {
            Nip02.addFollow(currentList, pubkey)
        }
        val tags = Nip02.buildFollowTags(newList)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = 3,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        contactRepo.updateFromEvent(event)
    }

    fun blockUser(pubkey: String) {
        muteRepo.blockUser(pubkey)
        eventRepo.purgeUser(pubkey)
        publishMuteList()
    }

    fun unblockUser(pubkey: String) {
        muteRepo.unblockUser(pubkey)
        publishMuteList()
    }

    fun updateMutedWords() {
        publishMuteList()
    }

    private fun publishMuteList() {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildMuteListTags(muteRepo.getBlockedPubkeys(), muteRepo.getMutedWords())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_MUTE_LIST,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
    }

    fun sendRepost(event: NostrEvent) {
        val keypair = keyRepo.getKeypair() ?: return
        viewModelScope.launch {
            try {
                val tags = com.wisp.app.nostr.Nip18.buildRepostTags(event)
                val repostEvent = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 6,
                    content = event.toJson(),
                    tags = tags
                )
                val msg = ClientMessage.event(repostEvent)
                relayPool.sendToWriteRelays(msg)
                eventRepo.addEvent(repostEvent)
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(event: NostrEvent, content: String = "+") {
        val keypair = keyRepo.getKeypair() ?: return
        viewModelScope.launch {
            try {
                val tags = com.wisp.app.nostr.Nip25.buildReactionTags(event)
                val reactionEvent = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 7,
                    content = content,
                    tags = tags
                )
                val msg = ClientMessage.event(reactionEvent)
                relayPool.sendToWriteRelays(msg)
                eventRepo.addEvent(reactionEvent)
            } catch (_: Exception) {}
        }
    }

    private val nostrNoteUriRegex = Regex("""nostr:(note1|nevent1)[a-z0-9]+""")

    private fun fetchQuotedEvents(event: NostrEvent) {
        val ids = mutableSetOf<String>()
        // From q tags
        event.tags.filter { it.size >= 2 && it[0] == "q" }.forEach { ids.add(it[1]) }
        // From nostr: URIs in content
        for (match in nostrNoteUriRegex.findAll(event.content)) {
            val decoded = Nip19.decodeNostrUri(match.value)
            if (decoded is NostrUriData.NoteRef) ids.add(decoded.eventId)
        }
        val toFetch = ids.filter { id -> eventRepo.getEvent(id) == null && id !in pendingQuoteFetches }
        if (toFetch.isEmpty()) return
        pendingQuoteFetches.addAll(toFetch)
        val subId = "quote-${toFetch.hashCode()}"
        relayPool.sendToAll(ClientMessage.req(subId, Filter(ids = toFetch)))
        // One-shot EOSE handler with timeout — no lingering collector
        viewModelScope.launch {
            awaitEoseWithTimeout(subId)
            relayPool.closeOnAllRelays(subId)
            toFetch.forEach { pendingQuoteFetches.remove(it) }
        }
    }

    private fun flushReplyCountBatch() {
        if (pendingReplyCountIds.isEmpty()) return
        val subId = "reply-count-${replyCountBatchCounter++}"
        val eventIds = pendingReplyCountIds.toList()
        pendingReplyCountIds.clear()
        val filter = Filter(kinds = listOf(1), eTags = eventIds)
        val msg = ClientMessage.req(subId, filter)
        relayPool.sendToAll(msg)
        viewModelScope.launch {
            awaitEoseWithTimeout(subId)
            relayPool.closeOnAllRelays(subId)
        }
    }

    private fun flushZapCountBatch() {
        if (pendingZapCountIds.isEmpty()) return
        val subId = "zap-count-${zapCountBatchCounter++}"
        val eventIds = pendingZapCountIds.toList()
        pendingZapCountIds.clear()
        val filter = Filter(kinds = listOf(9735), eTags = eventIds)
        val msg = ClientMessage.req(subId, filter)
        relayPool.sendToAll(msg)
        viewModelScope.launch {
            awaitEoseWithTimeout(subId)
            relayPool.closeOnAllRelays(subId)
        }
    }

    /** Must be called while holding pendingProfilePubkeys lock */
    private fun flushProfileBatch() {
        if (pendingProfilePubkeys.isEmpty()) return
        val subId = "meta-batch-${metaBatchCounter++}"
        val pubkeys = pendingProfilePubkeys.toList()
        pendingProfilePubkeys.clear()

        // Increment attempt counts
        pubkeys.forEach { profileAttempts[it] = (profileAttempts[it] ?: 0) + 1 }

        // Attempt 1: outbox-routed, Attempt 2+: broadcast to all relays
        val maxAttempt = pubkeys.maxOf { profileAttempts[it] ?: 1 }
        if (maxAttempt <= 1) {
            outboxRouter.requestProfiles(subId, pubkeys)
        } else {
            val filter = Filter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size)
            relayPool.sendToAll(ClientMessage.req(subId, filter))
        }

        // One-shot EOSE handler with timeout, then re-queue still-missing pubkeys
        viewModelScope.launch(processingDispatcher) {
            awaitEoseWithTimeout(subId)
            relayPool.closeOnAllRelays(subId)
            // Wait briefly for late-arriving events from other relays
            delay(3_000)
            // Re-queue any that are still missing
            for (pk in pubkeys) {
                if (!profileRepo.has(pk)) {
                    addToPendingProfiles(pk)
                }
            }
        }
    }

    /**
     * Sweep for missing profiles and quoted events. Runs on [processingDispatcher].
     */
    private fun sweepMissingProfiles() {
        val currentFeed = eventRepo.feed.value
        for (event in currentFeed) {
            if (eventRepo.getProfileData(event.pubkey) == null) {
                addToPendingProfiles(event.pubkey)
            }
            // Fetch profile for reposter if missing
            val reposter = eventRepo.getRepostAuthor(event.id)
            if (reposter != null && eventRepo.getProfileData(reposter) == null) {
                addToPendingProfiles(reposter)
            }
            // Re-attempt missing quoted events
            if (event.kind == 1) {
                fetchQuotedEvents(event)
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }

        when (_feedType.value) {
            FeedType.FOLLOWS -> {
                val authors = contactRepo.getFollowList().map { it.pubkey }
                if (authors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors("loadmore", authors, templateFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value
                if (url != null) {
                    val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                    relayPool.sendToRelay(url, ClientMessage.req("loadmore", filter))
                } else { isLoadingMore = false; return }
            }
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: run { isLoadingMore = false; return }
                val authors = list.members.toList()
                if (authors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors("loadmore", authors, templateFilter)
            }
        }

        viewModelScope.launch {
            awaitEoseWithTimeout("loadmore")
            relayPool.closeOnAllRelays("loadmore")
            isLoadingMore = false
        }
    }

    fun initNwc() {
        if (nwcRepo.hasConnection()) {
            nwcRepo.connect()
        }
    }

    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "") {
        val profileData = eventRepo.getProfileData(event.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        viewModelScope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = event.pubkey,
                eventId = event.id,
                amountMsats = amountMsats,
                message = message
            )
            _zapInProgress.value = _zapInProgress.value - event.id
            result.fold(
                onSuccess = {
                    eventRepo.addZapSats(event.id, amountMsats / 1000)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    _zapError.tryEmit(e.message ?: "Zap failed")
                }
            )
        }
    }

    fun setSelectedList(followSet: com.wisp.app.nostr.FollowSet) {
        listRepo.selectList(followSet)
        if (_feedType.value == FeedType.LIST) {
            eventRepo.clearFeed()
            resubscribeFeed()
        }
    }

    fun createList(name: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val dTag = name.trim().lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        val tags = Nip51.buildFollowSetTags(dTag, emptySet())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun addToList(dTag: String, pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members + pubkey
        val tags = Nip51.buildFollowSetTags(dTag, newMembers)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun removeFromList(dTag: String, pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members - pubkey
        val tags = Nip51.buildFollowSetTags(dTag, newMembers)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun deleteList(dTag: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildFollowSetTags(dTag, emptySet())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
        // Deselect if this was the selected list
        val sel = listRepo.selectedList.value
        if (sel != null && sel.dTag == dTag) {
            listRepo.selectList(null)
        }
    }

    fun fetchUserLists(pubkey: String) {
        val subId = "user-lists-${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(pubkey), limit = 50)
        relayPool.sendToAll(ClientMessage.req(subId, filter))
        viewModelScope.launch {
            awaitEoseWithTimeout(subId)
            relayPool.closeOnAllRelays(subId)
        }
    }

    fun refreshRelays() {
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.updateRelays(relays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())
        subscribeFeed()
    }

    override fun onCleared() {
        super.onCleared()
        nwcRepo.disconnect()
        relayPool.disconnectAll()
    }
}
