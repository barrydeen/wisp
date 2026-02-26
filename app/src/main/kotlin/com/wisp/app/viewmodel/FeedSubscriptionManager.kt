package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.ConsoleLogType
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.nostr.Nip57
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Manages feed subscription lifecycle, feed type switching, engagement subscriptions,
 * relay feed status monitoring, and load-more pagination.
 * Extracted from FeedViewModel to reduce its size.
 */
class FeedSubscriptionManager(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val subManager: SubscriptionManager,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val notifRepo: NotificationRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val keyRepo: KeyRepository,
    private val healthTracker: RelayHealthTracker,
    private val relayScoreBoard: RelayScoreBoard,
    private val profileRepo: ProfileRepository,
    private val metadataFetcher: MetadataFetcher,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val pubkeyHex: String?
) {
    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType

    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    private val _relayFeedStatus = MutableStateFlow<RelayFeedStatus>(RelayFeedStatus.Idle)
    val relayFeedStatus: StateFlow<RelayFeedStatus> = _relayFeedStatus

    val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone

    // Mutable for StartupCoordinator to write loading progress
    val _initLoadingState = MutableStateFlow<InitLoadingState>(InitLoadingState.SearchingProfile)
    val initLoadingState: StateFlow<InitLoadingState> = _initLoadingState

    private val _loadingScreenComplete = MutableStateFlow(false)
    val loadingScreenComplete: StateFlow<Boolean> = _loadingScreenComplete

    var feedSubId = "feed"
        private set
    val activeEngagementSubIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var feedEoseJob: Job? = null
    private var relayStatusMonitorJob: Job? = null
    private var isLoadingMore = false

    fun markLoadingComplete() { _loadingScreenComplete.value = true }

    /** Resolve indexer relays: user's search relays (kind 10007) with default fallback. */
    private fun getIndexerRelays(): List<String> {
        val userSearchRelays = keyRepo.getSearchRelays()
        return userSearchRelays.ifEmpty { RelayConfig.DEFAULT_INDEXER_RELAYS }
    }

    /** Blocked + bad relay URLs combined for outbox routing exclusion. */
    private fun getExcludedRelayUrls(): Set<String> =
        relayPool.getBlockedUrls() + healthTracker.getBadRelays()

    fun applyAuthorFilterForFeedType(type: FeedType) {
        eventRepo.setAuthorFilter(when (type) {
            FeedType.FOLLOWS -> contactRepo.getFollowList().map { it.pubkey }.toSet()
            FeedType.LIST -> listRepo.selectedList.value?.members
            else -> null  // EXTENDED_FOLLOWS and RELAY show everything
        })
    }

    fun setFeedType(type: FeedType) {
        val prev = _feedType.value
        Log.d("RLC", "[FeedSub] setFeedType $prev → $type feedSize=${eventRepo.feed.value.size}")
        _feedType.value = type
        applyAuthorFilterForFeedType(type)
        when (type) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                if (prev == FeedType.LIST || prev == FeedType.RELAY) {
                    Log.d("RLC", "[FeedSub] switching from $prev to $type — clearing feed and resubscribing")
                    eventRepo.clearFeed()
                    resubscribeFeed()
                } else {
                    Log.d("RLC", "[FeedSub] setFeedType $prev → $type — filter-only switch, no resubscribe needed, feedSize=${eventRepo.feed.value.size}")
                }
            }
            FeedType.RELAY, FeedType.LIST -> {
                eventRepo.clearFeed()
                resubscribeFeed()
            }
        }
    }

    fun setSelectedRelay(url: String) {
        _selectedRelay.value = url
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearFeed()
            resubscribeFeed()
        }
    }

    fun retryRelayFeed() {
        val url = _selectedRelay.value ?: return
        healthTracker.clearBadRelay(url)
        eventRepo.clearFeed()
        _relayFeedStatus.value = RelayFeedStatus.Connecting
        resubscribeFeed()
    }

    fun subscribeFeed() {
        resubscribeFeed()
    }

    fun resubscribeFeed() {
        Log.d("RLC", "[FeedSub] resubscribeFeed() feedType=${_feedType.value} connectedCount=${relayPool.connectedCount.value}")
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        // Always request the full 24h window. Relying on newestTimestamp from the current
        // feed caused a race condition: premature subscribeFeed() calls (from followWatcherJob,
        // connectivity changes, or lifecycle callbacks) would receive partial events, then the
        // proper startup subscribeFeed() would use those events' timestamps as `since`, missing
        // the full window. seenEventIds + feedIds dedup handles re-received events cheaply.
        val sinceTimestamp = System.currentTimeMillis() / 1000 - 60 * 60 * 24
        Log.d("RLC", "[FeedSub] resubscribeFeed: since=$sinceTimestamp (24h window)")
        val indexerRelays = getIndexerRelays()
        val excludedUrls = getExcludedRelayUrls()
        val targetedRelays: Set<String> = when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                relayStatusMonitorJob?.cancel()
                _relayFeedStatus.value = RelayFeedStatus.Idle
                val cache = extendedNetworkRepo.cachedNetwork.value
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = if (cache != null) {
                    (listOfNotNull(pubkeyHex) + firstDegree + cache.qualifiedPubkeys).distinct()
                } else {
                    listOfNotNull(pubkeyHex) + firstDegree
                }
                if (allAuthors.isEmpty()) {
                    Log.d("RLC", "[FeedSub] resubscribeFeed: no authors, returning")
                    return
                }
                Log.d("RLC", "[FeedSub] resubscribeFeed: ${allAuthors.size} authors, ${indexerRelays.size} indexers, ${excludedUrls.size} excluded")
                val notesFilter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                outboxRouter.subscribeByAuthors(
                    feedSubId, allAuthors, notesFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value ?: return
                startRelayStatusMonitor(url)
                val status = _relayFeedStatus.value
                if (status is RelayFeedStatus.Cooldown || status is RelayFeedStatus.BadRelay) {
                    return
                }
                val filter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                val msg = ClientMessage.req(feedSubId, filter)
                val sent = relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)
                if (!sent) {
                    _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to relay")
                    return
                }
                setOf(url)
            }
            FeedType.LIST -> {
                relayStatusMonitorJob?.cancel()
                _relayFeedStatus.value = RelayFeedStatus.Idle
                val list = listRepo.selectedList.value ?: return
                val authors = list.members.toList()
                if (authors.isEmpty()) return

                // Lists are small (5-50 authors) so use a 7-day window instead of 24h.
                // Infrequent posters in curated lists would otherwise produce a nearly empty feed.
                val listSince = System.currentTimeMillis() / 1000 - 60 * 60 * 24 * 7

                // Pre-fetch relay lists + profiles for list members before subscribing.
                // Without this, authors not in the follow list have no cached kind 10002,
                // so subscribeByAuthors routes them to fallback (pinned relays only).
                val prefetchSubId = outboxRouter.requestRelayListsAndProfiles(authors, profileRepo, subId = "list-prefetch")
                if (prefetchSubId != null) {
                    // Track in feedEoseJob so repeated resubscribeFeed() calls cancel this.
                    feedEoseJob = scope.launch {
                        // Wait for multiple EOSEs — a single EOSE from a fast empty relay
                        // would make us proceed before relays with actual data respond.
                        val connected = relayPool.connectedCount.value
                        val prefetchTarget = maxOf(2, (connected * 0.2).toInt())
                        Log.d("RLC", "[FeedSub] list prefetch: awaiting $prefetchTarget EOSEs (connected=$connected)")
                        subManager.awaitEoseCount(prefetchSubId, prefetchTarget, timeoutMs = 5000)
                        subManager.closeSubscription(prefetchSubId)
                        Log.d("RLC", "[FeedSub] list relay-list prefetch done, now subscribing feed")
                        val notesFilter = Filter(kinds = listOf(1, 6), since = listSince)
                        val targeted = outboxRouter.subscribeByAuthors(
                            feedSubId, authors, notesFilter,
                            indexerRelays = indexerRelays, blockedUrls = excludedUrls
                        )
                        val feedEoseTarget = maxOf(3, (connected * 0.3).toInt()).coerceIn(1, targeted.size)
                        Log.d("RLC", "[FeedSub] LIST awaiting $feedEoseTarget/$connected EOSEs")
                        subManager.awaitEoseCount(feedSubId, feedEoseTarget)
                        Log.d("RLC", "[FeedSub] LIST EOSE received, feed loaded")
                        _initialLoadDone.value = true
                        _initLoadingState.value = InitLoadingState.Done
                        eventRepo.countNewNotes = true
                        subscribeEngagementForFeed()
                        subscribeNotifEngagement()
                        withContext(processingContext) {
                            metadataFetcher.sweepMissingProfiles()
                        }
                    }
                    return
                }

                val notesFilter = Filter(kinds = listOf(1, 6), since = listSince)
                outboxRouter.subscribeByAuthors(
                    feedSubId, authors, notesFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
        }

        // Use connected relay count (not total targeted) for the EOSE threshold.
        // Many pool relays are dead (DNS failures, SSL errors, etc.) and will never
        // send EOSE. Basing the threshold on total targeted relays (e.g. 38/59) makes
        // it unreachable, causing the 15s timeout to fire every time with a sparse feed.
        // Wait for 3 EOSEs or 30% of connected relays, whichever is higher — this is
        // achievable when a few key relays (damus.io, primal.net) are connected.
        val connected = relayPool.connectedCount.value
        Log.d("RLC", "[FeedSub] resubscribeFeed() sent to ${targetedRelays.size} relays (connected=$connected), awaiting EOSE...")
        feedEoseJob = scope.launch {
            val eoseTarget = maxOf(3, (connected * 0.3).toInt()).coerceIn(1, targetedRelays.size)
            Log.d("RLC", "[FeedSub] awaiting $eoseTarget/$connected EOSEs for feedSubId=$feedSubId")
            subManager.awaitEoseCount(feedSubId, eoseTarget)
            Log.d("RLC", "[FeedSub] EOSE received, feed loaded")
            _initialLoadDone.value = true
            _initLoadingState.value = InitLoadingState.Done
            onRelayFeedEose()

            eventRepo.countNewNotes = true
            subscribeEngagementForFeed()
            subscribeNotifEngagement()

            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }

        val indexerRelays = getIndexerRelays()
        val excludedUrls = getExcludedRelayUrls()
        when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                val cache = extendedNetworkRepo.cachedNetwork.value
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = if (cache != null) {
                    (listOfNotNull(pubkeyHex) + firstDegree + cache.qualifiedPubkeys).distinct()
                } else {
                    listOfNotNull(pubkeyHex) + firstDegree
                }
                if (allAuthors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors(
                    "loadmore", allAuthors, templateFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value
                if (url != null) {
                    val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                    relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("loadmore", filter), skipBadCheck = true)
                } else { isLoadingMore = false; return }
            }
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: run { isLoadingMore = false; return }
                val authors = list.members.toList()
                if (authors.isEmpty()) { isLoadingMore = false; return }

                // Ensure relay lists are cached before load-more routing
                val prefetchSubId = outboxRouter.requestMissingRelayLists(authors, subId = "list-prefetch-more")
                if (prefetchSubId != null) {
                    scope.launch {
                        val connected = relayPool.connectedCount.value
                        val prefetchTarget = maxOf(2, (connected * 0.2).toInt())
                        subManager.awaitEoseCount(prefetchSubId, prefetchTarget, timeoutMs = 5000)
                        subManager.closeSubscription(prefetchSubId)
                        val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1)
                        outboxRouter.subscribeByAuthors(
                            "loadmore", authors, templateFilter,
                            indexerRelays = indexerRelays, blockedUrls = excludedUrls
                        )
                        val feedSizeBefore = eventRepo.feed.value.size
                        subManager.awaitEoseWithTimeout("loadmore")
                        subManager.closeSubscription("loadmore")
                        if (eventRepo.feed.value.size > feedSizeBefore) {
                            subscribeEngagementForFeed()
                        }
                        isLoadingMore = false
                    }
                    return
                }

                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1)
                outboxRouter.subscribeByAuthors(
                    "loadmore", authors, templateFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
        }

        scope.launch {
            val feedSizeBefore = eventRepo.feed.value.size
            subManager.awaitEoseWithTimeout("loadmore")
            subManager.closeSubscription("loadmore")

            if (eventRepo.feed.value.size > feedSizeBefore) {
                subscribeEngagementForFeed()
            }

            isLoadingMore = false
        }
    }

    fun pauseEngagement() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
    }

    fun resumeEngagement() {
        if (activeEngagementSubIds.isEmpty()) {
            subscribeEngagementForFeed()
        }
    }

    // -- Relay status monitoring --

    private fun startRelayStatusMonitor(url: String) {
        relayStatusMonitorJob?.cancel()

        val cooldownRemaining = relayPool.getRelayCooldownRemaining(url)
        if (cooldownRemaining > 0) {
            _relayFeedStatus.value = RelayFeedStatus.Cooldown(cooldownRemaining)
            relayStatusMonitorJob = scope.launch {
                var remaining = cooldownRemaining
                while (remaining > 0) {
                    _relayFeedStatus.value = RelayFeedStatus.Cooldown(remaining)
                    delay(1000)
                    remaining = relayPool.getRelayCooldownRemaining(url)
                }
                _relayFeedStatus.value = RelayFeedStatus.Idle
                eventRepo.clearFeed()
                resubscribeFeed()
            }
            return
        }

        if (healthTracker.isBad(url)) {
            _relayFeedStatus.value = RelayFeedStatus.BadRelay("Marked unreliable by health tracker")
            return
        }

        _relayFeedStatus.value = if (relayPool.isRelayConnected(url)) {
            RelayFeedStatus.Subscribing
        } else {
            RelayFeedStatus.Connecting
        }

        relayStatusMonitorJob = scope.launch {
            launch {
                relayPool.consoleLog.collectLatest { entries ->
                    val latest = entries.lastOrNull { it.relayUrl == url } ?: return@collectLatest
                    val currentStatus = _relayFeedStatus.value
                    if (currentStatus is RelayFeedStatus.Connecting ||
                        currentStatus is RelayFeedStatus.Subscribing) {
                        when (latest.type) {
                            ConsoleLogType.CONN_FAILURE -> {
                                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed(
                                    latest.message ?: "Connection failed"
                                )
                            }
                            ConsoleLogType.NOTICE -> {
                                val msg = latest.message?.lowercase() ?: ""
                                if ("rate" in msg || "throttle" in msg || "slow down" in msg || "too many" in msg) {
                                    _relayFeedStatus.value = RelayFeedStatus.RateLimited
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            launch {
                relayPool.connectedCount.collectLatest {
                    val connected = relayPool.isRelayConnected(url)
                    val currentStatus = _relayFeedStatus.value
                    if (connected && currentStatus is RelayFeedStatus.Connecting) {
                        _relayFeedStatus.value = RelayFeedStatus.Subscribing
                    } else if (!connected && (currentStatus is RelayFeedStatus.Streaming ||
                                currentStatus is RelayFeedStatus.Subscribing)) {
                        _relayFeedStatus.value = RelayFeedStatus.Disconnected
                    }
                }
            }

            launch {
                delay(15_000)
                val currentStatus = _relayFeedStatus.value
                if (currentStatus is RelayFeedStatus.Connecting ||
                    currentStatus is RelayFeedStatus.Subscribing) {
                    val isPersistent = relayPool.getRelayUrls().contains(url)
                    Log.d("RLC", "[FeedSub] relay feed TIMEOUT for $url (was $currentStatus, persistent=$isPersistent) — closing sub")
                    _relayFeedStatus.value = RelayFeedStatus.TimedOut
                    relayPool.closeOnAllRelays(feedSubId)
                    if (!isPersistent) relayPool.disconnectRelay(url)
                }
            }
        }
    }

    private fun onRelayFeedEose() {
        if (_feedType.value != FeedType.RELAY) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Connecting || status is RelayFeedStatus.Subscribing) {
            _relayFeedStatus.value = if (eventRepo.feed.value.isEmpty()) {
                RelayFeedStatus.NoEvents
            } else {
                RelayFeedStatus.Streaming
            }
        }
    }

    /** Mark status as Streaming when events start arriving. Called by EventRouter. */
    fun onRelayFeedEventReceived() {
        if (_feedType.value != FeedType.RELAY) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Subscribing || status is RelayFeedStatus.Connecting) {
            _relayFeedStatus.value = RelayFeedStatus.Streaming
        }
    }

    // -- Engagement subscriptions --

    fun subscribeEngagementForFeed() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        subscribeEngagementForEvents(feedEvents, "engage")
    }

    private fun subscribeEngagementForEvents(events: List<NostrEvent>, prefix: String) {
        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (event in events) {
            eventsByAuthor.getOrPut(event.pubkey) { mutableListOf() }.add(event.id)
        }
        val safetyNet = relayScoreBoard.getScoredRelays().take(5).map { it.url }
        outboxRouter.subscribeEngagementByAuthors(prefix, eventsByAuthor, activeEngagementSubIds, safetyNet)
    }

    fun subscribeNotifEngagement() {
        val eventIds = notifRepo.getAllPostCardEventIds()
        if (eventIds.isEmpty()) return
        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (id in eventIds) {
            val event = eventRepo.getEvent(id)
            val author = event?.pubkey ?: "fallback"
            eventsByAuthor.getOrPut(author) { mutableListOf() }.add(id)
        }
        val safetyNet = relayScoreBoard.getScoredRelays().take(5).map { it.url }
        outboxRouter.subscribeEngagementByAuthors("engage-notif", eventsByAuthor, activeEngagementSubIds, safetyNet)

        val zapSubId = "engage-notif-zap"
        activeEngagementSubIds.add(zapSubId)
        relayPool.sendToReadRelays(
            ClientMessage.req(zapSubId, Filter(kinds = listOf(9735), eTags = eventIds))
        )
    }

    /** Reset state for account switch. */
    fun reset() {
        feedEoseJob?.cancel()
        relayStatusMonitorJob?.cancel()
        _relayFeedStatus.value = RelayFeedStatus.Idle
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        _loadingScreenComplete.value = false
        _initialLoadDone.value = false
        _initLoadingState.value = InitLoadingState.SearchingProfile
        _selectedRelay.value = null
        isLoadingMore = false
    }
}
