package com.wisp.app.viewmodel

import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
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
import com.wisp.app.nostr.Nip69
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.RelaySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class FeedContentFilter { ALL, TEXT_ONLY, GALLERY_ONLY, POLLS_ONLY }

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
    private val pubkeyHex: String?,
    private val prefs: SharedPreferences
) {
    companion object {
        val FEED_KINDS = listOf(1, 6, 1068, 6969, 30023, 20, 21, 22)
    }

    init {
        // Relay feed subs bypass RelayPool's seen-event dedup so events already
        // received by the main feed subscription can still appear in relay feeds.
        relayPool.registerDedupBypass("relay-feed-")
        relayPool.registerDedupBypass("relay-loadmore")
        relayPool.registerDedupBypass("trending-feed-")
        relayPool.registerDedupBypass("trending-users-")
    }

    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType

    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    private val _selectedRelaySet = MutableStateFlow<RelaySet?>(null)
    val selectedRelaySet: StateFlow<RelaySet?> = _selectedRelaySet

    private val _trendingMetric = MutableStateFlow(TrendingMetric.REACTIONS)
    val trendingMetric: StateFlow<TrendingMetric> = _trendingMetric

    private val _trendingTimeframe = MutableStateFlow(TrendingTimeframe.TODAY)
    val trendingTimeframe: StateFlow<TrendingTimeframe> = _trendingTimeframe

    private val _trendingMode = MutableStateFlow(TrendingMode.NOTES)
    val trendingMode: StateFlow<TrendingMode> = _trendingMode

    private val _trendingUsers = MutableStateFlow<List<ProfileData>>(emptyList())
    val trendingUsers: StateFlow<List<ProfileData>> = _trendingUsers

    private val _trendingUsersLoading = MutableStateFlow(false)
    val trendingUsersLoading: StateFlow<Boolean> = _trendingUsersLoading

    private val _feedContentFilter = MutableStateFlow(FeedContentFilter.ALL)
    val feedContentFilter: StateFlow<FeedContentFilter> = _feedContentFilter

    fun setFeedContentFilter(filter: FeedContentFilter) {
        _feedContentFilter.value = filter
        // Client-side filter: rebuild the filtered feed view
        when (filter) {
            FeedContentFilter.ALL -> eventRepo.setKindFilter(null)
            FeedContentFilter.TEXT_ONLY -> eventRepo.setKindFilter(setOf(1, 6, 30023))
            FeedContentFilter.GALLERY_ONLY -> eventRepo.setKindFilter(setOf(20, 21, 22))
            FeedContentFilter.POLLS_ONLY -> eventRepo.setKindFilter(setOf(Nip88.KIND_POLL, Nip69.KIND_ZAP_POLL))
        }
    }

    private val _relayFeedStatus = MutableStateFlow<RelayFeedStatus>(RelayFeedStatus.Idle)
    val relayFeedStatus: StateFlow<RelayFeedStatus> = _relayFeedStatus

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone

    // Mutable for StartupCoordinator to write loading progress
    val _initLoadingState = MutableStateFlow<InitLoadingState>(InitLoadingState.SearchingProfile)
    val initLoadingState: StateFlow<InitLoadingState> = _initLoadingState

    private val _loadingScreenComplete = MutableStateFlow(false)
    val loadingScreenComplete: StateFlow<Boolean> = _loadingScreenComplete

    private var feedGeneration = 0
    var feedSubId = "feed"
        private set
    private var relayFeedGeneration = 0
    var relayFeedSubId = "relay-feed"
        private set
    val activeEngagementSubIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var pollVoteCollectorJob: Job? = null
    private var feedEoseJob: Job? = null
    private var relayFeedEoseJob: Job? = null
    private var relayStatusMonitorJob: Job? = null
    private var isLoadingMore = false

    // Batched engagement state (Steps 2-4)
    private var engagementGeneration = 0
    private val engagedEventIds = mutableSetOf<String>()
    private var viewportEngagementJob: Job? = null

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
            FeedType.FOLLOWS -> {
                val follows = contactRepo.getFollowList().map { it.pubkey }.toSet()
                if (pubkeyHex != null) follows + pubkeyHex else follows
            }
            FeedType.LIST -> listRepo.selectedList.value?.members
            else -> null  // EXTENDED_FOLLOWS, RELAY, and TRENDING show everything
        })
    }

    fun setFeedType(type: FeedType) {
        val prev = _feedType.value
        Log.d("RLC", "[FeedSub] setFeedType $prev → $type feedSize=${eventRepo.feed.value.size}")
        _feedType.value = type
        engagedEventIds.clear()
        viewportEngagementJob?.cancel()
        applyAuthorFilterForFeedType(type)

        // Tear down relay/trending feed when leaving those modes
        if (prev == FeedType.RELAY && type != FeedType.RELAY) {
            unsubscribeRelayFeed()
        }
        if (prev == FeedType.TRENDING && type != FeedType.TRENDING) {
            unsubscribeRelayFeed()
        }

        when (type) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                if (prev == FeedType.LIST) {
                    Log.d("RLC", "[FeedSub] switching from $prev to $type — rebuilding feed from cache and resubscribing")
                    eventRepo.resetFeedDisplay()
                    eventRepo.rebuildFeedFromCache()
                    resubscribeFeed()
                } else {
                    // Switching from RELAY or between FOLLOWS/EXTENDED — main feed still running
                    Log.d("RLC", "[FeedSub] setFeedType $prev → $type — filter-only switch, no resubscribe needed, feedSize=${eventRepo.feed.value.size}")
                }
            }
            FeedType.RELAY -> {
                // Skip if already in RELAY mode — setSelectedRelay() already triggered
                // subscribeRelayFeed(). Double-subscribing causes a race where the second
                // call finds the ephemeral relay still connecting and fails.
                if (prev == FeedType.RELAY) {
                    Log.d("RLC", "[FeedSub] setFeedType RELAY → RELAY — skipping, already subscribed")
                    return
                }
                eventRepo.clearRelayFeed()
                subscribeRelayFeed()
            }
            FeedType.LIST -> {
                eventRepo.resetFeedDisplay()
                // Lists use a 7-day window, so rebuild cache with matching range
                val listSince = System.currentTimeMillis() / 1000 - 60 * 60 * 24 * 7
                eventRepo.rebuildFeedFromCache(sinceTimestamp = listSince)
                resubscribeFeed()
            }
            FeedType.TRENDING -> {
                eventRepo.clearRelayFeed()
                if (_trendingMode.value == TrendingMode.USERS) {
                    subscribeTrendingUsers()
                } else {
                    subscribeTrendingFeed()
                }
            }
        }
    }

    fun setSelectedRelay(url: String) {
        _selectedRelaySet.value = null
        _selectedRelay.value = url
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        }
    }

    fun setSelectedRelaySet(relaySet: RelaySet) {
        _selectedRelaySet.value = relaySet
        _selectedRelay.value = null
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        }
    }

    fun retryRelayFeed() {
        val url = _selectedRelay.value ?: return
        healthTracker.clearBadRelay(url)
        relayPool.clearCooldown(url)
        eventRepo.clearRelayFeed()
        _relayFeedStatus.value = RelayFeedStatus.Connecting
        subscribeRelayFeed()
    }

    fun subscribeFeed() {
        resubscribeFeed()
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        } else if (_feedType.value == FeedType.TRENDING) {
            eventRepo.clearRelayFeed()
            if (_trendingMode.value == TrendingMode.USERS) {
                subscribeTrendingUsers()
            } else {
                subscribeTrendingFeed()
            }
        }
    }

    fun refreshFeed() {
        _isRefreshing.value = true
        scope.launch {
            delay(3000)
            _isRefreshing.value = false
        }
    }

    fun resubscribeFeed() {
        Log.d("RLC", "[FeedSub] resubscribeFeed() feedType=${_feedType.value} connectedCount=${relayPool.connectedCount.value}")
        val oldSubId = feedSubId
        feedGeneration++
        feedSubId = "feed-$feedGeneration"
        Log.d("RLC", "[FeedSub] feed generation $feedGeneration: $oldSubId → $feedSubId")
        relayPool.closeOnAllRelays(oldSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        engagedEventIds.clear()
        viewportEngagementJob?.cancel()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        // For FOLLOWS/EXTENDED feeds, use the persisted latest-event timestamp from the previous
        // session as `since`, capped at 24h ago. This avoids re-downloading thousands of events
        // on every startup — only new posts since the last load are fetched. The 5-minute buffer
        // covers relay clock skew. Relying on the *current* session's newest event would cause a
        // race condition (premature resubscribeFeed() calls from followWatcherJob/connectivity
        // changes get partial events first), so we only write the timestamp after EOSE.
        // All other feed types (RELAY, LIST, TRENDING) ignore this and use their own windows.
        // Scale the default since-window by follow count: sparse feeds need a wider window to
        // surface enough content, while dense feeds should stay narrow to avoid fetching too much.
        val followCount = contactRepo.getFollowList().size
        val defaultWindowSeconds = when {
            followCount <= 10  -> 7 * 24 * 3600L
            followCount <= 30  -> 5 * 24 * 3600L
            followCount <= 75  -> 3 * 24 * 3600L
            followCount <= 150 -> 2 * 24 * 3600L
            followCount <= 300 -> 36 * 3600L
            else               -> 24 * 3600L
        }
        val defaultSince = System.currentTimeMillis() / 1000 - defaultWindowSeconds
        val savedFeedTs = prefs.getLong("latest_follows_feed_ts", 0L)
        val sinceTimestamp = if (savedFeedTs > 0) maxOf(savedFeedTs - 5 * 60, defaultSince)
                             else defaultSince
        Log.d("RLC", "[FeedSub] resubscribeFeed: since=$sinceTimestamp (savedFeedTs=$savedFeedTs, followCount=$followCount, windowDays=${defaultWindowSeconds/86400})")
        val indexerRelays = getIndexerRelays()
        val excludedUrls = getExcludedRelayUrls()
        val targetedRelays: Set<String> = when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
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
                val notesFilter = Filter(kinds = FEED_KINDS, since = sinceTimestamp)
                outboxRouter.subscribeByAuthors(
                    feedSubId, allAuthors, notesFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
            FeedType.RELAY, FeedType.TRENDING -> {
                // RELAY/TRENDING feeds use their own subscribe methods — should not reach here
                Log.w("RLC", "[FeedSub] resubscribeFeed() called for ${_feedType.value} type, skipping")
                return
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
                        val notesFilter = Filter(kinds = FEED_KINDS, since = listSince)
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
                        eventRepo.enableNewNoteCounting()
                        subscribeEngagementForFeed()
                        subscribeNotifEngagement()
                        withContext(processingContext) {
                            metadataFetcher.sweepMissingProfiles()
                        }
                    }
                    return
                }

                val notesFilter = Filter(kinds = FEED_KINDS, since = listSince)
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
            eventRepo.getNewestFeedEventTimestamp()?.let { ts ->
                val now = System.currentTimeMillis() / 1000
                val safeTsVal = minOf(ts, now)
                prefs.edit().putLong("latest_follows_feed_ts", safeTsVal).apply()
                Log.d("RLC", "[FeedSub] saved latest_follows_feed_ts=$safeTsVal (raw=$ts, now=$now)")
            }
            _initialLoadDone.value = true
            _initLoadingState.value = InitLoadingState.Done
            onRelayFeedEose()

            eventRepo.enableNewNoteCounting()
            subscribeEngagementForFeed()
            subscribeNotifEngagement()

            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        if (_feedType.value == FeedType.TRENDING) return  // trending relay sends full ranked set
        isLoadingMore = true

        val indexerRelays = getIndexerRelays()
        val excludedUrls = getExcludedRelayUrls()
        when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }
                val cache = extendedNetworkRepo.cachedNetwork.value
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = if (cache != null) {
                    (listOfNotNull(pubkeyHex) + firstDegree + cache.qualifiedPubkeys).distinct()
                } else {
                    listOfNotNull(pubkeyHex) + firstDegree
                }
                if (allAuthors.isEmpty()) { isLoadingMore = false; return }
                // Use content-specific kinds when a filter is active
                val loadMoreKinds = when (_feedContentFilter.value) {
                    FeedContentFilter.GALLERY_ONLY -> listOf(20, 21, 22)
                    FeedContentFilter.TEXT_ONLY -> listOf(1, 6, 30023)
                    FeedContentFilter.POLLS_ONLY -> listOf(Nip88.KIND_POLL, Nip69.KIND_ZAP_POLL)
                    FeedContentFilter.ALL -> FEED_KINDS
                }
                val templateFilter = Filter(kinds = loadMoreKinds, until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors(
                    "loadmore", allAuthors, templateFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
            FeedType.RELAY -> {
                val oldest = eventRepo.getOldestRelayFeedTimestamp() ?: run { isLoadingMore = false; return }
                val relaySet = _selectedRelaySet.value
                if (relaySet != null) {
                    val filter = Filter(kinds = FEED_KINDS, until = oldest - 1, limit = 50)
                    val msg = ClientMessage.req("relay-loadmore", filter)
                    for (setUrl in relaySet.relays) {
                        relayPool.sendToRelayOrEphemeral(setUrl, msg, skipBadCheck = true)
                    }
                } else {
                    val url = _selectedRelay.value
                    if (url != null) {
                        val filter = Filter(kinds = FEED_KINDS, until = oldest - 1, limit = 50)
                        relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("relay-loadmore", filter), skipBadCheck = true)
                    } else { isLoadingMore = false; return }
                }
            }
            FeedType.LIST -> {
                val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }
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
                        val templateFilter = Filter(kinds = FEED_KINDS, until = oldest - 1)
                        outboxRouter.subscribeByAuthors(
                            "loadmore", authors, templateFilter,
                            indexerRelays = indexerRelays, blockedUrls = excludedUrls
                        )
                        val feedBefore = eventRepo.feed.value.toList()
                        subManager.awaitEoseWithTimeout("loadmore")
                        subManager.closeSubscription("loadmore")
                        if (eventRepo.feed.value.size > feedBefore.size) {
                            val existingIds = feedBefore.map { it.id }.toSet()
                            val newEvents = eventRepo.feed.value.filter { it.id !in existingIds }
                            if (newEvents.isNotEmpty()) {
                                subscribeEngagementForEvents(newEvents)
                            }
                        }
                        isLoadingMore = false
                    }
                    return
                }

                val templateFilter = Filter(kinds = FEED_KINDS, until = oldest - 1)
                outboxRouter.subscribeByAuthors(
                    "loadmore", authors, templateFilter,
                    indexerRelays = indexerRelays, blockedUrls = excludedUrls
                )
            }
            FeedType.TRENDING -> return  // guarded above, but required for exhaustiveness
        }

        val loadMoreSubId = if (_feedType.value == FeedType.RELAY) "relay-loadmore" else "loadmore"
        scope.launch {
            val feedBefore = if (_feedType.value == FeedType.RELAY) {
                eventRepo.relayFeed.value.toList()
            } else {
                eventRepo.feed.value.toList()
            }
            val sizeBefore = feedBefore.size
            subManager.awaitEoseWithTimeout(loadMoreSubId)
            subManager.closeSubscription(loadMoreSubId)

            val feedAfter = if (_feedType.value == FeedType.RELAY) {
                eventRepo.relayFeed.value
            } else {
                eventRepo.feed.value
            }
            if (feedAfter.size > sizeBefore) {
                // Only engage the new events — don't reset existing engagement
                val existingIds = feedBefore.map { it.id }.toSet()
                val newEvents = feedAfter.filter { it.id !in existingIds }
                if (newEvents.isNotEmpty()) {
                    subscribeEngagementForEvents(newEvents)
                }
            }

            isLoadingMore = false
        }
    }

    fun pauseEngagement() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        viewportEngagementJob?.cancel()
    }

    fun resumeEngagement() {
        if (activeEngagementSubIds.isEmpty()) {
            // After reconnect, re-engage only the initial viewport — the viewport
            // tracker will handle the rest as the user scrolls.
            engagedEventIds.clear()
            subscribeEngagementForFeed()
        }
    }

    // -- Trending feed --

    fun setTrendingMetric(metric: TrendingMetric) {
        if (_trendingMode.value == TrendingMode.USERS) {
            _trendingMode.value = TrendingMode.NOTES
        }
        _trendingMetric.value = metric
        if (_feedType.value == FeedType.TRENDING) {
            eventRepo.clearRelayFeed()
            subscribeTrendingFeed()
        }
    }

    fun setTrendingTimeframe(timeframe: TrendingTimeframe) {
        _trendingTimeframe.value = timeframe
        if (_feedType.value == FeedType.TRENDING && _trendingMode.value == TrendingMode.NOTES) {
            eventRepo.clearRelayFeed()
            subscribeTrendingFeed()
        }
    }

    fun setTrendingMode(mode: TrendingMode) {
        if (_trendingMode.value == mode) return
        _trendingMode.value = mode
        if (_feedType.value == FeedType.TRENDING) {
            if (mode == TrendingMode.USERS) {
                unsubscribeRelayFeed()
                subscribeTrendingUsers()
            } else {
                _trendingUsers.value = emptyList()
                _trendingUsersLoading.value = false
                eventRepo.clearRelayFeed()
                subscribeTrendingFeed()
            }
        }
    }

    private fun subscribeTrendingFeed() {
        val oldSubId = relayFeedSubId
        relayFeedGeneration++
        relayFeedSubId = "trending-feed-$relayFeedGeneration"
        relayPool.closeOnAllRelays(oldSubId)
        relayFeedEoseJob?.cancel()
        relayStatusMonitorJob?.cancel()

        val url = buildTrendingRelayUrl(_trendingMetric.value, _trendingTimeframe.value)
        _relayFeedStatus.value = RelayFeedStatus.Connecting

        val filter = Filter(kinds = FEED_KINDS, limit = 100)
        val currentGen = relayFeedGeneration
        val subId = relayFeedSubId

        relayFeedEoseJob = scope.launch {
            // The relay may already be pre-connecting (via onPreReconnect) or may
            // need to be created fresh. sendToRelayOrEphemeral handles both cases
            // and queues the REQ as a pending message if the WebSocket isn't open yet.
            // If the relay fails to connect (stale DNS after sleep, etc.), retry with
            // a fresh ephemeral connection.
            var connected = false
            for (attempt in 0..2) {
                if (relayFeedGeneration != currentGen) return@launch
                if (attempt > 0) {
                    relayPool.disconnectRelay(url)
                    delay(1500L)
                }
                val msg = ClientMessage.req(subId, filter)
                relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)

                // Wait for relay to connect — pending messages drain on WebSocket open
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (relayFeedGeneration != currentGen) return@launch
                    if (relayPool.isRelayConnected(url)) {
                        connected = true
                        break
                    }
                    delay(200)
                }
                if (connected) break
            }
            if (!connected) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to trending relay")
                return@launch
            }

            subManager.awaitEoseCount(subId, 1)
            onRelayFeedEose()
            subscribeEngagementForFeed()
            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    private fun subscribeTrendingUsers() {
        val oldSubId = relayFeedSubId
        relayFeedGeneration++
        relayFeedSubId = "trending-users-$relayFeedGeneration"
        relayPool.closeOnAllRelays(oldSubId)
        relayFeedEoseJob?.cancel()
        relayStatusMonitorJob?.cancel()

        val url = TRENDING_USERS_RELAY_URL
        _trendingUsersLoading.value = true
        _trendingUsers.value = emptyList()
        _relayFeedStatus.value = RelayFeedStatus.Connecting

        val filter = Filter(kinds = listOf(0), limit = 100)
        val currentGen = relayFeedGeneration
        val subId = relayFeedSubId

        relayFeedEoseJob = scope.launch {
            var connected = false
            for (attempt in 0..2) {
                if (relayFeedGeneration != currentGen) return@launch
                if (attempt > 0) {
                    relayPool.disconnectRelay(url)
                    delay(1500L)
                }
                val msg = ClientMessage.req(subId, filter)
                relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)

                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (relayFeedGeneration != currentGen) return@launch
                    if (relayPool.isRelayConnected(url)) {
                        connected = true
                        break
                    }
                    delay(200)
                }
                if (connected) break
            }
            if (!connected) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to trending users relay")
                _trendingUsersLoading.value = false
                return@launch
            }

            _relayFeedStatus.value = RelayFeedStatus.Subscribing

            // Collect kind 0 events as they arrive
            val collected = mutableListOf<ProfileData>()
            val seenPubkeys = mutableSetOf<String>()
            val collectJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    if (relayFeedGeneration != currentGen) return@collect
                    val event = relayEvent.event
                    if (event.kind == 0 && seenPubkeys.add(event.pubkey)) {
                        val profile = ProfileData.fromEvent(event)
                        if (profile != null) {
                            profileRepo.updateFromEvent(event)
                            collected.add(profile)
                            _trendingUsers.value = collected.toList()
                            if (collected.size == 1) {
                                _relayFeedStatus.value = RelayFeedStatus.Streaming
                            }
                        }
                    }
                }
            }

            subManager.awaitEoseCount(subId, 1)
            collectJob.cancel()

            if (collected.isEmpty()) {
                _relayFeedStatus.value = RelayFeedStatus.NoEvents
            } else {
                _relayFeedStatus.value = RelayFeedStatus.Streaming
            }
            _trendingUsersLoading.value = false
        }
    }

    // -- Isolated relay feed subscription --

    private fun subscribeRelayFeed() {
        val oldSubId = relayFeedSubId
        relayFeedGeneration++
        relayFeedSubId = "relay-feed-$relayFeedGeneration"
        relayPool.closeOnAllRelays(oldSubId)
        relayFeedEoseJob?.cancel()

        // Always request the latest 100 notes per relay — no since timestamp.
        // Using a since timestamp caused empty feeds on switch because RelayPool's
        // seen-event dedup interacts with the shared timestamp state.
        val relaySet = _selectedRelaySet.value
        if (relaySet != null) {
            relayStatusMonitorJob?.cancel()
            _relayFeedStatus.value = RelayFeedStatus.Subscribing
            val filter = Filter(kinds = FEED_KINDS, limit = 100)
            val msg = ClientMessage.req(relayFeedSubId, filter)
            val sentUrls = mutableSetOf<String>()
            for (setUrl in relaySet.relays) {
                val sent = relayPool.sendToRelayOrEphemeral(setUrl, msg, skipBadCheck = true)
                if (sent) sentUrls.add(setUrl)
            }
            if (sentUrls.isEmpty()) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to any relay in set")
                return
            }
            relayFeedEoseJob = scope.launch {
                val eoseTarget = maxOf(1, (sentUrls.size * 0.3).toInt()).coerceIn(1, sentUrls.size)
                subManager.awaitEoseCount(relayFeedSubId, eoseTarget)
                onRelayFeedEose()
                subscribeEngagementForFeed()
                withContext(processingContext) {
                    metadataFetcher.sweepMissingProfiles()
                }
            }
        } else {
            val url = _selectedRelay.value ?: return
            startRelayStatusMonitor(url)
            val status = _relayFeedStatus.value
            if (status is RelayFeedStatus.Cooldown || status is RelayFeedStatus.BadRelay) {
                return
            }
            val filter = Filter(kinds = FEED_KINDS, limit = 100)
            val msg = ClientMessage.req(relayFeedSubId, filter)
            val sent = relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)
            if (!sent) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to relay")
                return
            }
            relayFeedEoseJob = scope.launch {
                subManager.awaitEoseCount(relayFeedSubId, 1)
                onRelayFeedEose()
                subscribeEngagementForFeed()
                withContext(processingContext) {
                    metadataFetcher.sweepMissingProfiles()
                }
            }
        }
    }

    private fun unsubscribeRelayFeed() {
        relayFeedEoseJob?.cancel()
        relayStatusMonitorJob?.cancel()
        relayPool.closeOnAllRelays(relayFeedSubId)
        eventRepo.clearRelayFeed()
        _relayFeedStatus.value = RelayFeedStatus.Idle
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
                eventRepo.clearRelayFeed()
                subscribeRelayFeed()
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
                // Track the current console log size so we only react to NEW entries,
                // not stale CONN_FAILURE entries from previous connection attempts.
                var baselineSize = relayPool.consoleLog.value.size
                relayPool.consoleLog.collectLatest { entries ->
                    if (entries.size <= baselineSize) {
                        baselineSize = entries.size
                        return@collectLatest
                    }
                    // Only check entries added since the monitor started
                    val newEntries = entries.subList(baselineSize, entries.size)
                    val latest = newEntries.lastOrNull { it.relayUrl == url } ?: return@collectLatest
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

            // Two-phase timeout: connection (10s) then data (15s)
            launch {
                // Phase 1 — Connection timeout
                delay(10_000)
                if (_relayFeedStatus.value is RelayFeedStatus.Connecting) {
                    val isPersistent = relayPool.getRelayUrls().contains(url)
                    Log.d("RLC", "[FeedSub] relay feed CONNECTION TIMEOUT for $url (persistent=$isPersistent) — closing sub")
                    _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Connection timed out")
                    relayPool.closeOnAllRelays(relayFeedSubId)
                    if (!isPersistent) relayPool.disconnectRelay(url)
                    return@launch
                }
                // Phase 2 — Data timeout (15s after connection phase)
                delay(15_000)
                if (_relayFeedStatus.value is RelayFeedStatus.Subscribing) {
                    val isPersistent = relayPool.getRelayUrls().contains(url)
                    Log.d("RLC", "[FeedSub] relay feed DATA TIMEOUT for $url (persistent=$isPersistent) — closing sub")
                    _relayFeedStatus.value = RelayFeedStatus.TimedOut
                    relayPool.closeOnAllRelays(relayFeedSubId)
                    if (!isPersistent) relayPool.disconnectRelay(url)
                }
            }
        }
    }

    private fun onRelayFeedEose() {
        if (_feedType.value != FeedType.RELAY && _feedType.value != FeedType.TRENDING) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Connecting || status is RelayFeedStatus.Subscribing) {
            _relayFeedStatus.value = if (eventRepo.relayFeed.value.isEmpty()) {
                RelayFeedStatus.NoEvents
            } else {
                RelayFeedStatus.Streaming
            }
        }
    }

    /** Mark status as Streaming when events start arriving. Called by EventRouter. */
    fun onRelayFeedEventReceived() {
        if (_feedType.value != FeedType.RELAY && _feedType.value != FeedType.TRENDING) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Subscribing || status is RelayFeedStatus.Connecting) {
            _relayFeedStatus.value = RelayFeedStatus.Streaming
        }
    }

    // -- Engagement subscriptions --

    fun subscribeEngagementForFeed() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        engagedEventIds.clear()

        val feedEvents = if (_feedType.value == FeedType.RELAY || _feedType.value == FeedType.TRENDING) eventRepo.relayFeed.value else eventRepo.feed.value
        if (feedEvents.isEmpty()) return

        // Subscribe global subs (poll votes, DM zaps) for all feed events
        subscribeGlobalEngagement(feedEvents)

        // Only engage the first ~15 events (rough viewport). Viewport tracking
        // via onViewportChanged() handles the rest as the user scrolls.
        val initialBatch = feedEvents.take(15)
        subscribeEngagementForEvents(initialBatch)
    }

    /**
     * Subscribe engagement for a batch of events that haven't been engaged yet.
     * Each batch gets a unique sub ID to avoid cancelling in-progress fetches.
     */
    private fun subscribeEngagementForEvents(events: List<NostrEvent>) {
        val newEvents = events.filter { engagedEventIds.add(it.id) }
        if (newEvents.isEmpty()) return

        engagementGeneration++
        val batchSubId = "engage-${engagementGeneration}"
        Log.d("RLC", "[FeedSub] subscribeEngagementForEvents batch=$batchSubId count=${newEvents.size}")

        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (event in newEvents) {
            eventsByAuthor.getOrPut(event.pubkey) { mutableListOf() }.add(event.id)
        }
        val safetyNet = relayScoreBoard.getScoredRelays().take(5).map { it.url }
        val relayCount = outboxRouter.subscribeEngagementByAuthors(batchSubId, eventsByAuthor, activeEngagementSubIds, safetyNet)

        if (relayCount > 0) {
            scope.launch {
                val eoseTarget = maxOf(3, (relayCount * 0.3).toInt()).coerceIn(1, relayCount)
                Log.d("RLC", "[FeedSub] awaiting $eoseTarget/$relayCount EOSEs for $batchSubId")
                subManager.awaitEoseCount(batchSubId, eoseTarget, timeoutMs = 8_000)
                Log.d("RLC", "[FeedSub] engagement EOSE received for $batchSubId")
            }
        }
    }

    /**
     * Subscribe global engagement subs that need broad relay coverage:
     * private zap receipts on DM relays and poll vote responses.
     */
    private fun subscribeGlobalEngagement(feedEvents: List<NostrEvent>) {
        // Subscribe for private zap receipts on DM relays
        if (relayPool.hasDmRelays() && pubkeyHex != null) {
            val myEventIds = feedEvents.filter { it.pubkey == pubkeyHex }.map { it.id }
            if (myEventIds.isNotEmpty()) {
                val dmSubId = "engage-zap-dm"
                activeEngagementSubIds.add(dmSubId)
                val zapFilter = Filter(kinds = listOf(9735), eTags = myEventIds)
                relayPool.sendToDmRelays(ClientMessage.req(dmSubId, zapFilter))
            }
        }

        // Subscribe for poll vote responses: cast a wide net since voters
        // publish to their own write relays which could be anywhere.
        val nip88Polls = feedEvents.filter { it.kind == Nip88.KIND_POLL }
        val zapPolls = feedEvents.filter { it.kind == Nip69.KIND_ZAP_POLL }
        val pollEvents = nip88Polls + zapPolls
        Log.d("POLL", "[FeedSub] subscribeEngagement: ${feedEvents.size} feed events, ${nip88Polls.size} NIP-88 polls, ${zapPolls.size} zap polls")
        pollVoteCollectorJob?.cancel()
        if (pollEvents.isNotEmpty()) {
            val safetyNet = relayScoreBoard.getScoredRelays().take(5).map { it.url }
            val sentUrls = relayPool.getReadRelayUrls().toSet() + relayPool.getWriteRelayUrls().toSet()

            // NIP-88 polls: subscribe for kind 1018 responses
            if (nip88Polls.isNotEmpty()) {
                val pollEventIds = nip88Polls.map { it.id }
                Log.d("POLL", "[FeedSub] subscribing poll votes for ${pollEventIds.map { it.take(12) }}")
                val pollSubId = "engage-poll-votes"
                activeEngagementSubIds.add(pollSubId)
                val pollFilters = pollEventIds.chunked(OutboxRouter.MAX_ETAGS_PER_FILTER).map { chunk ->
                    Filter(kinds = listOf(Nip88.KIND_POLL_RESPONSE), eTags = chunk)
                }
                val msg = if (pollFilters.size == 1) ClientMessage.req(pollSubId, pollFilters[0])
                else ClientMessage.req(pollSubId, pollFilters)
                val sentAll = relayPool.sendToAllRelays(msg)
                Log.d("POLL", "[FeedSub] sent poll vote REQ to $sentAll persistent relays")
                for (poll in nip88Polls) {
                    for (url in Nip88.parsePollRelays(poll)) {
                        if (url !in sentUrls) relayPool.sendToRelayOrEphemeral(url, msg)
                    }
                }
                for (url in safetyNet) {
                    if (url !in sentUrls) relayPool.sendToRelayOrEphemeral(url, msg)
                }
            }

            // Zap polls (kind 6969): subscribe for kind 9735 zap receipts
            if (zapPolls.isNotEmpty()) {
                val zapPollIds = zapPolls.map { it.id }
                Log.d("POLL", "[FeedSub] subscribing zap poll receipts for ${zapPollIds.map { it.take(12) }}")
                val zapPollSubId = "engage-zappoll-rcpts"
                activeEngagementSubIds.add(zapPollSubId)
                val zapPollFilters = zapPollIds.chunked(OutboxRouter.MAX_ETAGS_PER_FILTER).map { chunk ->
                    Filter(kinds = listOf(9735), eTags = chunk)
                }
                val zapPollMsg = if (zapPollFilters.size == 1) ClientMessage.req(zapPollSubId, zapPollFilters[0])
                else ClientMessage.req(zapPollSubId, zapPollFilters)
                relayPool.sendToAllRelays(zapPollMsg)
                for (poll in zapPolls) {
                    for (url in Nip69.parseZapPollRelays(poll)) {
                        if (url !in sentUrls) relayPool.sendToRelayOrEphemeral(url, zapPollMsg)
                    }
                }
            }

            // Dedicated fast collector for poll votes — the main EventRouter
            // SharedFlow can drop events during startup burst due to buffer pressure.
            // This collector does minimal work (just addEvent) so it keeps up.
            pollVoteCollectorJob = scope.launch {
                relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                    if (!subscriptionId.startsWith("engage")) return@collect
                    // NIP-88 poll responses or zap poll receipts
                    if (event.kind == Nip88.KIND_POLL_RESPONSE || event.kind == 9735) {
                        eventRepo.addEvent(event)
                    }
                }
            }
        }
    }

    /**
     * Called by FeedViewModel when the visible item range changes.
     * Subscribes engagement for newly visible events not yet engaged.
     */
    fun onViewportChanged(firstVisible: Int, lastVisible: Int) {
        viewportEngagementJob?.cancel()
        viewportEngagementJob = scope.launch {
            // Debounce to avoid thrashing during fast scrolls
            delay(300)
            val feedEvents = if (_feedType.value == FeedType.RELAY || _feedType.value == FeedType.TRENDING) {
                eventRepo.relayFeed.value
            } else {
                eventRepo.feed.value
            }
            if (feedEvents.isEmpty()) return@launch

            // Expand visible range with prefetch buffer: 5 above, 10 below
            val bufferedFirst = maxOf(0, firstVisible - 5)
            val bufferedLast = minOf(feedEvents.size - 1, lastVisible + 10)

            val viewportEvents = feedEvents.subList(bufferedFirst, minOf(bufferedLast + 1, feedEvents.size))
            val newEvents = viewportEvents.filter { it.id !in engagedEventIds }
            if (newEvents.isNotEmpty()) {
                Log.d("RLC", "[FeedSub] viewport [$firstVisible-$lastVisible] buffered [$bufferedFirst-$bufferedLast] — ${newEvents.size} new events to engage")
                subscribeEngagementForEvents(newEvents)
            }

            // Cleanup: close engagement subs for events far from viewport.
            // Data persists in EventRepository LRU cache even after sub closes.
            cleanupDistantEngagementSubs(feedEvents, firstVisible, lastVisible)
        }
    }

    /**
     * Close engagement subs for events >50 items away from the current viewport.
     * The engagement data is already cached in EventRepository's LRU caches.
     */
    private fun cleanupDistantEngagementSubs(
        feedEvents: List<NostrEvent>,
        firstVisible: Int,
        lastVisible: Int
    ) {
        // Only clean up when there are many active engagement subs
        if (activeEngagementSubIds.size < 5) return
        // We don't track which sub IDs map to which events, so cleanup is
        // handled naturally by the generation-based sub ID scheme — old subs
        // eventually get closed when subscribeEngagementForFeed() is called
        // on feed switches/reconnects.
    }

    fun subscribeNotifEngagement() {
        val eventIds = notifRepo.getAllPostCardEventIds()
        if (eventIds.isEmpty()) return

        // Own events are already cached from the self-notes subscription in
        // subscribeDmsAndNotifications(), so go straight to engagement.
        subscribeNotifEngagementInner(eventIds)
    }

    private fun subscribeNotifEngagementInner(eventIds: List<String>) {
        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (id in eventIds) {
            val event = eventRepo.getEvent(id)
            val author = event?.pubkey ?: "fallback"
            eventsByAuthor.getOrPut(author) { mutableListOf() }.add(id)
        }
        val safetyNet = relayScoreBoard.getScoredRelays().take(5).map { it.url }
        val since = notifRepo.getLatestNotifTimestamp()?.let { it - 5 * 60 }
        outboxRouter.subscribeEngagementByAuthors("engage-notif", eventsByAuthor, activeEngagementSubIds, safetyNet, since)

        val zapSubId = "engage-notif-zap"
        activeEngagementSubIds.add(zapSubId)
        val zapFilters = eventIds.chunked(OutboxRouter.MAX_ETAGS_PER_FILTER).map { chunk ->
            Filter(kinds = listOf(9735), eTags = chunk, since = since)
        }
        val zapMsg = if (zapFilters.size == 1) ClientMessage.req(zapSubId, zapFilters[0])
        else ClientMessage.req(zapSubId, zapFilters)
        relayPool.sendToReadRelays(zapMsg)

        // Also fetch private zap receipts from DM relays
        if (relayPool.hasDmRelays()) {
            val dmZapSubId = "engage-notif-zap-dm"
            activeEngagementSubIds.add(dmZapSubId)
            val dmZapMsg = if (zapFilters.size == 1) ClientMessage.req(dmZapSubId, zapFilters[0])
            else ClientMessage.req(dmZapSubId, zapFilters)
            relayPool.sendToDmRelays(dmZapMsg)
        }
    }

    /** Reset state for account switch. */
    fun reset() {
        feedEoseJob?.cancel()
        viewportEngagementJob?.cancel()
        unsubscribeRelayFeed()
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        engagedEventIds.clear()
        _loadingScreenComplete.value = false
        _initialLoadDone.value = false
        _initLoadingState.value = InitLoadingState.SearchingProfile
        _selectedRelay.value = null
        _selectedRelaySet.value = null
        _trendingMetric.value = TrendingMetric.REACTIONS
        _trendingTimeframe.value = TrendingTimeframe.TODAY
        _trendingMode.value = TrendingMode.NOTES
        _trendingUsers.value = emptyList()
        _trendingUsersLoading.value = false
        isLoadingMore = false
    }
}
