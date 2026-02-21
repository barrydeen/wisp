package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.Relay
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.ScoredRelay
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.ReactionPreferences
import com.wisp.app.repo.ZapPreferences
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOLLOWS, EXTENDED_FOLLOWS, RELAY, LIST }

sealed class InitLoadingState {
    data object Idle : InitLoadingState()
    data class Connecting(val connected: Int, val total: Int) : InitLoadingState()
    data class FoundFollows(val count: Int) : InitLoadingState()
    data class FetchingRelayLists(val found: Int, val total: Int) : InitLoadingState()
    data class ComputingRouting(val relayCount: Int, val coveredAuthors: Int) : InitLoadingState()
    data object Subscribing : InitLoadingState()
    data object Done : InitLoadingState()
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    // KeyRepo first — needed to derive pubkeyHex for all per-account repos
    val keyRepo = KeyRepository(app)
    private val pubkeyHex: String? = keyRepo.getKeypair()?.pubkey?.toHex()

    val relayPool = RelayPool()
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app, pubkeyHex)
    val nip05Repo = Nip05Repository()
    val eventRepo = EventRepository(profileRepo, muteRepo).also { it.currentUserPubkey = pubkeyHex }
    val contactRepo = ContactRepository(app, pubkeyHex)
    val listRepo = ListRepository(app, pubkeyHex)
    val dmRepo = DmRepository()
    val notifRepo = NotificationRepository(app, pubkeyHex)
    val relayListRepo = RelayListRepository(app)
    val bookmarkRepo = BookmarkRepository(app, pubkeyHex)
    val pinRepo = PinRepository(app, pubkeyHex)
    val blossomRepo = BlossomRepository(app, pubkeyHex)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo, pubkeyHex)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayScoreBoard)
    val subManager = SubscriptionManager(relayPool)
    val extendedNetworkRepo = ExtendedNetworkRepository(
        app, contactRepo, muteRepo, relayListRepo, relayPool, subManager, relayScoreBoard, pubkeyHex
    )
    val reactionPrefs = ReactionPreferences(app, pubkeyHex)
    val zapPrefs = ZapPreferences(app, pubkeyHex)
    private val processingDispatcher = Dispatchers.Default

    val metadataFetcher = MetadataFetcher(
        relayPool, outboxRouter, subManager, profileRepo, eventRepo,
        viewModelScope, processingDispatcher
    ).also { eventRepo.metadataFetcher = it }

    private var feedSubId = "feed"
    private var isLoadingMore = false
    private val activeEngagementSubIds = mutableListOf<String>()
    private var loadMoreCount = 0

    val nwcRepo = NwcRepository(app, relayPool, pubkeyHex)
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _initLoadingState = MutableStateFlow<InitLoadingState>(InitLoadingState.Idle)
    val initLoadingState: StateFlow<InitLoadingState> = _initLoadingState

    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType


    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList

    fun getUserPubkey(): String? = keyRepo.getKeypair()?.pubkey?.toHex()

    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()

    fun queueProfileFetch(pubkey: String) = metadataFetcher.queueProfileFetch(pubkey)

    private var relaysInitialized = false

    fun resetForAccountSwitch() {
        // Cancel feed subscriptions
        feedEoseJob?.cancel()
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        // Disconnect relays and NWC
        relayPool.disconnectAll()
        nwcRepo.disconnect()

        // Clear all repos
        metadataFetcher.clear()
        eventRepo.clearAll()
        dmRepo.clear()
        notifRepo.clear()
        contactRepo.clear()
        muteRepo.clear()
        bookmarkRepo.clear()
        pinRepo.clear()
        listRepo.clear()
        blossomRepo.clear()
        extendedNetworkRepo.clear()
        relayScoreBoard.clear()
        relayPool.clearSeenEvents()

        // Reset state
        relaysInitialized = false
        _initialLoadDone.value = false
        _feedType.value = FeedType.FOLLOWS
        _selectedRelay.value = null
        isLoadingMore = false
    }

    fun reloadForNewAccount() {
        val newPubkey = getUserPubkey()

        // Reload per-account prefs for new pubkey
        eventRepo.currentUserPubkey = newPubkey
        keyRepo.reloadPrefs(newPubkey)
        contactRepo.reload(newPubkey)
        muteRepo.reload(newPubkey)
        bookmarkRepo.reload(newPubkey)
        pinRepo.reload(newPubkey)
        listRepo.reload(newPubkey)
        blossomRepo.reload(newPubkey)
        nwcRepo.reload(newPubkey)
        relayScoreBoard.reload(newPubkey)
        extendedNetworkRepo.reload(newPubkey)
        reactionPrefs.reload(newPubkey)
        zapPrefs.reload(newPubkey)
    }

    fun initRelays() {
        if (relaysInitialized) return
        relaysInitialized = true
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val pinnedRelays = keyRepo.getRelays()
        // Merge pinned relays with cached scored relays immediately so the pool
        // starts with the full relay set (40-50) instead of just pinned (5).
        // RelayScoreBoard rebuilds from persisted RelayListRepository data on init.
        val pinnedUrls = pinnedRelays.map { it.url }.toSet()
        val cachedScored = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val initialRelays = pinnedRelays + cachedScored
        relayPool.updateRelays(initialRelays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())

        viewModelScope.launch { relayInfoRepo.prefetchAll(initialRelays.map { it.url }) }

        // Main event processing loop — runs on Default dispatcher to keep UI thread free
        viewModelScope.launch(processingDispatcher) {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                processRelayEvent(event, relayUrl, subscriptionId)
            }
        }

        // Periodic profile & quote sweep — runs on Default dispatcher
        viewModelScope.launch(processingDispatcher) {
            delay(5_000)
            metadataFetcher.sweepMissingProfiles()
            repeat(7) {
                delay(15_000)
                metadataFetcher.sweepMissingProfiles()
            }
            while (true) {
                delay(60_000)
                metadataFetcher.sweepMissingProfiles()
            }
        }

        // Periodic ephemeral relay cleanup + seen event trimming
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                relayPool.cleanupEphemeralRelays()
                eventRepo.trimSeenEvents()
            }
        }

        // Periodic relay list refresh (every 30 minutes)
        viewModelScope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                fetchRelayListsForFollows()
                delay(15_000)
                recomputeAndMergeRelays()
            }
        }

        // Incrementally update scoreboard when follow list changes, then re-subscribe feed
        viewModelScope.launch {
            var previousFollows = contactRepo.getFollowList().map { it.pubkey }.toSet()
            contactRepo.followList.drop(1).collectLatest { entries ->
                val currentFollows = entries.map { it.pubkey }.toSet()
                val added = currentFollows - previousFollows
                val removed = previousFollows - currentFollows
                previousFollows = currentFollows

                for (pubkey in removed) relayScoreBoard.removeAuthor(pubkey)
                for (pubkey in added) {
                    // Fetch relay list for new follow so we can route to them
                    outboxRouter.requestMissingRelayLists(listOf(pubkey))
                    delay(500) // brief wait for relay list to arrive
                    relayScoreBoard.addAuthor(pubkey)
                }

                if ((added.isNotEmpty() || removed.isNotEmpty()) &&
                    (_feedType.value == FeedType.FOLLOWS || _feedType.value == FeedType.EXTENDED_FOLLOWS)) {
                    rebuildRelayPool()
                    resubscribeFeed()
                }
            }
        }

        // NIP-42 AUTH: sign challenges with our keypair
        keyRepo.getKeypair()?.let { kp ->
            relayPool.setAuthSigner { relayUrl, challenge ->
                NostrEvent.create(
                    privkey = kp.privkey,
                    pubkey = kp.pubkey,
                    kind = 22242,
                    content = "",
                    tags = listOf(
                        listOf("relay", relayUrl),
                        listOf("challenge", challenge)
                    )
                )
            }
        }

        // Re-send DM subscription to relays after AUTH completes
        viewModelScope.launch {
            relayPool.authCompleted.collect { relayUrl ->
                val myPubkey = getUserPubkey() ?: return@collect
                val dmRelayUrls = relayPool.getDmRelayUrls()
                if (relayUrl in dmRelayUrls || relayUrl in relayPool.getRelayUrls()) {
                    val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
                    relayPool.sendToRelay(relayUrl, ClientMessage.req("dms", dmFilter))
                }
            }
        }

        getUserPubkey()?.let { listRepo.setOwner(it) }

        // Sequential startup: self-data → relay lists → scoreboard → feed.
        // If the cached scoreboard matches the current follow list, skip the expensive
        // relay-list fetch + recompute and go straight to subscribing the feed.
        viewModelScope.launch {
            val totalRelays = relayPool.getRelayUrls().size
            _initLoadingState.value = InitLoadingState.Connecting(0, totalRelays)

            relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
            _initLoadingState.value = InitLoadingState.Connecting(relayPool.connectedCount.value, totalRelays)
            subscribeSelfData()

            val follows = contactRepo.getFollowList().map { it.pubkey }

            if (!relayScoreBoard.needsRecompute()) {
                // Cached scoreboard is valid — skip relay-list fetch
                val scored = relayScoreBoard.getScoredRelays()
                Log.d("FeedViewModel", "init: scoreboard cache valid (${scored.size} relays, ${follows.size} follows), skipping recompute")
                _initLoadingState.value = InitLoadingState.Subscribing
            } else {
                // Follow list changed or first launch — full rebuild
                _initLoadingState.value = InitLoadingState.FoundFollows(follows.size)
                delay(400) // brief pause so the user sees the follow count

                val subscriptionSent = fetchRelayListsForFollows()
                if (subscriptionSent) {
                    val target = (follows.size * 0.9).toInt()
                    // Poll coverage, updating UI each tick
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline) {
                        val covered = follows.size - relayListRepo.getMissingPubkeys(follows).size
                        _initLoadingState.value = InitLoadingState.FetchingRelayLists(covered, follows.size)
                        if (covered >= target) break
                        delay(200)
                    }
                    subManager.closeSubscription("relay-lists")
                }

                recomputeAndMergeRelays()
                val scored = relayScoreBoard.getScoredRelays()
                val coveredAuthors = scored.sumOf { it.authors.size }
                _initLoadingState.value = InitLoadingState.ComputingRouting(scored.size, coveredAuthors)
                delay(500) // brief pause so the user sees the routing result

                relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                _initLoadingState.value = InitLoadingState.Subscribing
            }

            subscribeFeed()
            metadataFetcher.fetchProfilesForFollows(follows)

            // Let the "Subscribing" state show briefly, then clear
            delay(300)
            _initLoadingState.value = InitLoadingState.Done

            // Background: fetch relay lists for any new follows (non-blocking)
            if (!relayScoreBoard.needsRecompute()) {
                fetchRelayListsForFollows()
            }
        }
    }

    private fun subscribeSelfData() {
        val myPubkey = getUserPubkey() ?: return

        val selfDataFilters = listOf(
            Filter(kinds = listOf(0), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(3), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(10002), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(10050, 10007, 10006), authors = listOf(myPubkey), limit = 3),
            Filter(kinds = listOf(Nip51.KIND_MUTE_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_PIN_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_BOOKMARK_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Blossom.KIND_SERVER_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(myPubkey), limit = 50)
        )
        relayPool.sendToAll(ClientMessage.req("self-data", selfDataFilters))
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout("self-data")
            subManager.closeSubscription("self-data")
        }

        val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
        val dmReqMsg = ClientMessage.req("dms", dmFilter)
        relayPool.sendToAll(dmReqMsg)
        relayPool.sendToDmRelays(dmReqMsg)
        // Track EOSE for initial DM load but keep subscription open for streaming
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout("dms")
            Log.d("FeedViewModel", "DM initial load complete")
        }

        val notifFilter = Filter(
            kinds = listOf(1, 7, 9735),
            pTags = listOf(myPubkey),
            limit = 100
        )
        relayPool.sendToReadRelays(ClientMessage.req("notif", notifFilter))
    }

    private fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        if (subscriptionId == "notif") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                when (event.kind) {
                    7, 9735 -> eventRepo.addEvent(event)
                    1 -> {
                        eventRepo.cacheEvent(event)
                        val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                        if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
                    }
                    else -> eventRepo.cacheEvent(event)
                }
                notifRepo.addEvent(event, myPubkey)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
                if (event.kind == 9735) {
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId.startsWith("quote-")) {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 && eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId.startsWith("reply-count-")) {
            if (event.kind == 1) {
                val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId.startsWith("zap-rcpt-")) {
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
                val zapperPubkey = Nip57.getZapperPubkey(event)
                if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                    metadataFetcher.addToPendingProfiles(zapperPubkey)
                }
            }
        } else if (subscriptionId.startsWith("thread-reactions")) {
            when (event.kind) {
                7, 6 -> eventRepo.addEvent(event)
                9735 -> {
                    eventRepo.addEvent(event)
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId.startsWith("engage") || subscriptionId.startsWith("user-engage")) {
            when (event.kind) {
                7 -> eventRepo.addEvent(event)
                9735 -> {
                    eventRepo.addEvent(event)
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
                1 -> {
                    val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                    if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
                }
            }
        } else if (subscriptionId.startsWith("extnet-k3-")) {
            // Extended network discovery: kind 3 follow lists — route to repo, NOT feed
            if (event.kind == 3) extendedNetworkRepo.processFollowListEvent(event)
        } else if (subscriptionId.startsWith("extnet-rl-")) {
            // Extended network discovery: relay lists — update relay list cache
            if (event.kind == 10002) relayListRepo.updateFromEvent(event)
        } else if (subscriptionId.startsWith("onb-")) {
            // Onboarding suggestion fetches — only cache kind 0 profiles, don't add to feed
            if (event.kind == 0) eventRepo.cacheEvent(event)
        } else {
            if (event.kind == 10002) {
                relayListRepo.updateFromEvent(event)
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    val relays = Nip65.parseRelayList(event)
                    if (relays.isNotEmpty()) {
                        keyRepo.saveRelays(relays)
                        relayPool.updateRelays(relays)
                    }
                }
            }
            if (event.kind == Nip51.KIND_DM_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveDmRelays(urls)
                    relayPool.updateDmRelays(urls)
                }
            }
            if (event.kind == Nip51.KIND_SEARCH_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    keyRepo.saveSearchRelays(Nip51.parseRelaySet(event))
                }
            }
            if (event.kind == Nip51.KIND_BLOCKED_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveBlockedRelays(urls)
                    relayPool.updateBlockedUrls(urls)
                }
            }
            if (event.kind == Nip51.KIND_MUTE_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) muteRepo.loadFromEvent(event)
            }
            if (event.kind == Nip51.KIND_BOOKMARK_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) bookmarkRepo.loadFromEvent(event)
            }
            if (event.kind == Nip51.KIND_PIN_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) pinRepo.loadFromEvent(event)
            }
            if (event.kind == Blossom.KIND_SERVER_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) blossomRepo.updateFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FOLLOW_SET) listRepo.updateFromEvent(event)

            // Only add to feed for feed-related subscriptions;
            // other subs (user profile, bookmarks, threads) just cache
            val isFeedSub = subscriptionId == feedSubId ||
                subscriptionId == "loadmore" ||
                subscriptionId == "feed-backfill"
            if (isFeedSub) {
                eventRepo.addEvent(event)
                if (event.kind == 1) eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
            } else {
                eventRepo.cacheEvent(event)
            }
            // Always handle follow list updates (from self-data subscription)
            if (event.kind == 3) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) contactRepo.updateFromEvent(event)
            }
        }
    }

    /** @return true if a "relay-lists" subscription was sent (callers should await EOSE). */
    private fun fetchRelayListsForFollows(): Boolean {
        val authors = contactRepo.getFollowList().map { it.pubkey }
        if (authors.isEmpty()) {
            Log.d("FeedViewModel", "fetchRelayListsForFollows: follow list empty")
            return false
        }
        val sent = outboxRouter.requestMissingRelayLists(authors) != null
        Log.d("FeedViewModel", "fetchRelayListsForFollows: ${authors.size} follows, subscription sent=$sent")
        return sent
    }

    /**
     * Poll until [target] of [follows] have relay lists cached, or [timeoutMs] elapses.
     * Events arrive concurrently from multiple relays on the processing dispatcher,
     * so we just check coverage periodically rather than counting EOSE signals.
     */
    private suspend fun awaitRelayListCoverage(
        follows: List<String>,
        target: Int,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val covered = follows.size - relayListRepo.getMissingPubkeys(follows).size
            if (covered >= target) return
            delay(200)
        }
    }

    private fun recomputeAndMergeRelays() {
        relayScoreBoard.recompute()
        if (!relayScoreBoard.hasScoredRelays()) return
        rebuildRelayPool()
    }

    /**
     * Rebuild the persistent relay pool from pinned + scored + extended network relays.
     * When extended follows is active, the pool expands to include relays needed for
     * the 2nd-degree network. When switching back, those relays are removed.
     */
    private fun rebuildRelayPool() {
        val pinnedRelays = keyRepo.getRelays()
        val pinnedUrls = pinnedRelays.map { it.url }.toSet()
        val scoredConfigs = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val baseUrls = pinnedUrls + scoredConfigs.map { it.url }.toSet()

        val extendedConfigs = if (_feedType.value == FeedType.EXTENDED_FOLLOWS) {
            extendedNetworkRepo.getRelayConfigs().filter { it.url !in baseUrls }
        } else {
            emptyList()
        }

        relayPool.updateRelays(pinnedRelays + scoredConfigs + extendedConfigs)
    }

    fun setFeedType(type: FeedType) {
        val wasExtended = _feedType.value == FeedType.EXTENDED_FOLLOWS
        _feedType.value = type
        val isExtended = type == FeedType.EXTENDED_FOLLOWS
        // Expand or shrink the persistent pool when toggling extended follows
        if (wasExtended != isExtended) rebuildRelayPool()
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

    fun getScoredRelays(): List<ScoredRelay> = relayScoreBoard.getScoredRelays()

    /**
     * Returns relay URL → pubkey coverage count for the current feed type.
     * Combines scoreboard (follows) + extended network counts when applicable.
     */
    fun getRelayCoverageCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        // Always include scoreboard counts (covers follows)
        for ((url, count) in relayScoreBoard.getCoverageCounts()) {
            counts[url] = (counts[url] ?: 0) + count
        }
        // Add extended network counts when that feed is active
        if (_feedType.value == FeedType.EXTENDED_FOLLOWS) {
            for ((url, count) in extendedNetworkRepo.getCoverageCounts()) {
                counts[url] = (counts[url] ?: 0) + count
            }
        }
        return counts
    }

    /**
     * Probe whether a relay is reachable by attempting a WebSocket connection.
     * Tries wss:// first, then ws://. Returns the working URL or null.
     */
    suspend fun probeRelay(domain: String): String? {
        val wssUrl = "wss://$domain"
        if (tryConnect(wssUrl)) return wssUrl
        val wsUrl = "ws://$domain"
        if (tryConnect(wsUrl)) return wsUrl
        return null
    }

    private suspend fun tryConnect(url: String): Boolean {
        val client = Relay.createClient()
        val relay = Relay(RelayConfig(url, read = true, write = false), client)
        relay.autoReconnect = false
        return try {
            relay.connect()
            val connected = relay.awaitConnected(timeoutMs = 4000)
            relay.disconnect()
            connected
        } catch (_: Exception) {
            relay.disconnect()
            false
        }
    }

    fun onAppResume(pausedMs: Long = 0L) {
        if (!relaysInitialized) return
        val forceThresholdMs = 30_000L
        if (pausedMs >= forceThresholdMs) {
            Log.d("FeedViewModel", "Long pause (${pausedMs / 1000}s) — force reconnecting all relays")
            relayPool.forceReconnectAll()
            viewModelScope.launch {
                relayPool.awaitAnyConnected(minCount = 3)
                subscribeFeed()
                fetchRelayListsForFollows()
            }
        } else {
            Log.d("FeedViewModel", "Short pause (${pausedMs / 1000}s) — lightweight reconnect")
            relayPool.reconnectAll()
            viewModelScope.launch {
                relayPool.awaitAnyConnected()
                subscribeFeed()
            }
        }
    }

    /**
     * Full feed refresh: disconnect all relays, reconnect, rebuild outbox
     * relay list, and resubscribe everything as if freshly logged in.
     */
    fun refreshFeed() {
        if (_isRefreshing.value) return

        // Cancel active subscriptions
        feedEoseJob?.cancel()
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        // Clear feed state
        eventRepo.clearFeed()
        relayPool.clearSeenEvents()
        _initialLoadDone.value = false
        isLoadingMore = false

        // Disconnect all relays
        relayPool.disconnectAll()

        _isRefreshing.value = true

        viewModelScope.launch {
            // Rebuild relay config from saved settings
            relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
            val pinnedRelays = keyRepo.getRelays()
            val pinnedUrls = pinnedRelays.map { it.url }.toSet()
            val cachedScored = relayScoreBoard.getScoredRelayConfigs()
                .filter { it.url !in pinnedUrls }
            relayPool.updateRelays(pinnedRelays + cachedScored)
            relayPool.updateDmRelays(keyRepo.getDmRelays())

            // Wait for relays to connect
            relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)

            subscribeSelfData()

            // Wait until 90% of follows have relay list coverage before subscribing feed
            val subscriptionSent = fetchRelayListsForFollows()
            if (subscriptionSent) {
                val follows = contactRepo.getFollowList().map { it.pubkey }
                val target = (follows.size * 0.9).toInt()
                awaitRelayListCoverage(follows, target, timeoutMs = 10_000)
                subManager.closeSubscription("relay-lists")
                recomputeAndMergeRelays()
                relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
            }

            subscribeFeed()
            metadataFetcher.fetchProfilesForFollows(contactRepo.getFollowList().map { it.pubkey })

            _isRefreshing.value = false
        }
    }

    private fun subscribeFeed() {
        resubscribeFeed()
    }

    private var feedEoseJob: Job? = null

    private fun resubscribeFeed() {
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        relayPool.clearSeenEvents()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        // Use a `since` timestamp so every relay queries the same time window, producing
        // deterministic results across refreshes. The limit still caps per-relay response
        // size. After EOSE the subscription stays open for live streaming of new events.
        val sinceTimestamp = System.currentTimeMillis() / 1000 - 60 * 60 // 1 hour ago
        val targetedRelays: Set<String> = when (_feedType.value) {
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: return
                val authors = list.members.toList()
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.FOLLOWS -> {
                val authors = contactRepo.getFollowList().map { it.pubkey }
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.EXTENDED_FOLLOWS -> {
                val cache = extendedNetworkRepo.cachedNetwork.value ?: return
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = (firstDegree + cache.qualifiedPubkeys).distinct()
                if (allAuthors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                outboxRouter.subscribeByAuthors(feedSubId, allAuthors, notesFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value ?: return
                val filter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                val msg = ClientMessage.req(feedSubId, filter)
                relayPool.sendToRelayOrEphemeral(url, msg)
                setOf(url)
            }
        }

        feedEoseJob = viewModelScope.launch {
            subManager.awaitEoseCount(feedSubId, targetedRelays.size.coerceAtLeast(1))
            _initialLoadDone.value = true

            eventRepo.countNewNotes = true
            subscribeEngagementForFeed()

            withContext(processingDispatcher) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    private fun subscribeEngagementForFeed() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        subscribeEngagementForEvents(feedEvents, "engage")
    }

    private fun subscribeEngagementForEvents(events: List<NostrEvent>, prefix: String) {
        // Group event IDs by author so engagement queries go to each author's inbox relays
        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (event in events) {
            eventsByAuthor.getOrPut(event.pubkey) { mutableListOf() }.add(event.id)
        }
        outboxRouter.subscribeEngagementByAuthors(prefix, eventsByAuthor, activeEngagementSubIds)
    }

    /**
     * Opens a subscription for zap receipts (kind 9735) targeting [eventId].
     * Kept open for 30s to catch the receipt whenever the LNURL provider publishes it.
     * Returns the subscription ID so the caller can close it early on failure.
     */
    private fun subscribeZapReceipt(eventId: String): String {
        val subId = "zap-rcpt-${eventId.take(12)}"
        val filter = Filter(kinds = listOf(9735), eTags = listOf(eventId))
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            relayPool.closeOnAllRelays(subId)
        }
        return subId
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
        notifRepo.purgeUser(pubkey)
        dmRepo.purgeUser(pubkey)
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
                outboxRouter.publishToInbox(msg, event.pubkey)
                eventRepo.markUserRepost(event.id)
                eventRepo.addEvent(repostEvent)
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(event: NostrEvent, content: String = "+") {
        toggleReaction(event, content)
    }

    fun toggleReaction(event: NostrEvent, emoji: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()
        val existingEventId = eventRepo.getUserReactionEventId(event.id, myPubkey, emoji)

        viewModelScope.launch {
            try {
                if (existingEventId != null) {
                    // Delete the existing reaction via NIP-09
                    val tags = com.wisp.app.nostr.Nip09.buildDeletionTags(existingEventId, 7)
                    val deletionEvent = NostrEvent.create(
                        privkey = keypair.privkey,
                        pubkey = keypair.pubkey,
                        kind = 5,
                        content = "",
                        tags = tags
                    )
                    relayPool.sendToWriteRelays(ClientMessage.event(deletionEvent))
                    eventRepo.removeReaction(event.id, myPubkey, emoji)
                } else {
                    // Create new reaction
                    val tags = com.wisp.app.nostr.Nip25.buildReactionTags(event)
                    val reactionEvent = NostrEvent.create(
                        privkey = keypair.privkey,
                        pubkey = keypair.pubkey,
                        kind = 7,
                        content = emoji,
                        tags = tags
                    )
                    val msg = ClientMessage.event(reactionEvent)
                    outboxRouter.publishToInbox(msg, event.pubkey)
                    eventRepo.addEvent(reactionEvent)
                }
            } catch (_: Exception) {}
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
            FeedType.EXTENDED_FOLLOWS -> {
                val cache = extendedNetworkRepo.cachedNetwork.value
                    ?: run { isLoadingMore = false; return }
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = (firstDegree + cache.qualifiedPubkeys).distinct()
                if (allAuthors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors("loadmore", allAuthors, templateFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value
                if (url != null) {
                    val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                    relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("loadmore", filter))
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
            val feedSizeBefore = eventRepo.feed.value.size
            subManager.awaitEoseWithTimeout("loadmore")
            subManager.closeSubscription("loadmore")

            // Subscribe engagement for newly loaded events
            val currentFeed = eventRepo.feed.value
            if (currentFeed.size > feedSizeBefore) {
                val newEvents = currentFeed.drop(feedSizeBefore)
                if (newEvents.isNotEmpty()) {
                    loadMoreCount++
                    val prefix = "engage-more-$loadMoreCount"
                    subscribeEngagementForEvents(newEvents, prefix)
                    // Close these engagement subs after EOSE to free subscription capacity
                    val engageSubsCopy = activeEngagementSubIds.filter { it.startsWith(prefix) }
                    launch {
                        subManager.awaitEoseWithTimeout(prefix)
                        for (subId in engageSubsCopy) {
                            relayPool.closeOnAllRelays(subId)
                            activeEngagementSubIds.remove(subId)
                        }
                    }
                }
            }

            isLoadingMore = false
        }
    }

    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "") {
        val profileData = eventRepo.getProfileData(event.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        // Reconnect NWC relay if credentials exist but relay disconnected
        if (nwcRepo.hasConnection() && !nwcRepo.isConnected.value) {
            nwcRepo.connect()
        }
        viewModelScope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            // Open receipt subscription BEFORE paying so we catch the 9735
            // even if the LNURL provider publishes it before NWC confirms
            val receiptSubId = subscribeZapReceipt(event.id)
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
                    val myPubkey = getUserPubkey() ?: ""
                    eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    _zapError.tryEmit(e.message ?: "Zap failed")
                    // Close receipt subscription on failure
                    relayPool.closeOnAllRelays(receiptSubId)
                }
            )
        }
    }

    fun startNetworkDiscovery() {
        viewModelScope.launch {
            extendedNetworkRepo.discoverNetwork()
        }
    }

    fun isExtendedNetworkReady(): Boolean = extendedNetworkRepo.isNetworkReady()

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
        val sel = listRepo.selectedList.value
        if (sel != null && sel.dTag == dTag) {
            listRepo.selectList(null)
        }
    }

    fun toggleBookmark(eventId: String) {
        if (bookmarkRepo.isBookmarked(eventId)) {
            bookmarkRepo.removeBookmark(eventId)
        } else {
            bookmarkRepo.addBookmark(eventId)
        }
        publishBookmarkList()
    }

    private fun publishBookmarkList() {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildBookmarkListTags(
            bookmarkRepo.getBookmarkedIds(),
            bookmarkRepo.getCoordinates(),
            bookmarkRepo.getHashtags()
        )
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_BOOKMARK_LIST,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
    }

    fun togglePin(eventId: String) {
        if (pinRepo.isPinned(eventId)) {
            pinRepo.unpinEvent(eventId)
        } else {
            pinRepo.pinEvent(eventId)
        }
        publishPinList()
    }

    private fun publishPinList() {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildPinListTags(pinRepo.getPinnedIds())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_PIN_LIST,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
    }

    fun followAll(pubkeys: Set<String>) {
        val keypair = keyRepo.getKeypair() ?: return
        var currentList = contactRepo.getFollowList()
        for (pk in pubkeys) {
            if (!contactRepo.isFollowing(pk)) {
                currentList = Nip02.addFollow(currentList, pk)
            }
        }
        val tags = Nip02.buildFollowTags(currentList)
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

    fun fetchBookmarkedEvents() {
        val ids = bookmarkRepo.getBookmarkedIds().toList()
        if (ids.isEmpty()) return
        // Skip IDs already in cache
        val missing = ids.filter { eventRepo.getEvent(it) == null }
        if (missing.isEmpty()) return
        val subId = "fetch-bookmarks"
        val filter = Filter(ids = missing)
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            withContext(processingDispatcher) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun fetchUserLists(pubkey: String) {
        val subId = "user-lists-${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(pubkey), limit = 50)
        relayPool.sendToAll(ClientMessage.req(subId, filter))
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }

    /**
     * Pause feed engagement subscriptions to free subscription capacity for thread loading.
     */
    fun pauseEngagement() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
    }

    /**
     * Resume engagement subscriptions after returning from a thread.
     */
    fun resumeEngagement() {
        if (activeEngagementSubIds.isEmpty()) {
            subscribeEngagementForFeed()
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
