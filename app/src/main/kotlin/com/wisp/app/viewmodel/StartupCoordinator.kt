package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.relay.RelayLifecycleManager
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DiscoveryState
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.FeedCache
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.ZapPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Orchestrates startup sequencing (cold/warm paths), self-data fetching,
 * relay pool building, account switching, app lifecycle, and feed caching.
 * Extracted from FeedViewModel to reduce its size.
 */
class StartupCoordinator(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val subManager: SubscriptionManager,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val listRepo: ListRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val pinRepo: PinRepository,
    private val blossomRepo: BlossomRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val relayListRepo: RelayListRepository,
    private val relayScoreBoard: RelayScoreBoard,
    private val relayHintStore: RelayHintStore,
    private val healthTracker: RelayHealthTracker,
    private val keyRepo: KeyRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val metadataFetcher: MetadataFetcher,
    private val profileRepo: ProfileRepository,
    private val relayInfoRepo: RelayInfoRepository,
    private val nip05Repo: Nip05Repository,
    private val nwcRepo: NwcRepository,
    private val dmRepo: DmRepository,
    private val feedCache: FeedCache,
    private val zapPrefs: ZapPreferences,
    private val lifecycleManager: RelayLifecycleManager,
    private val eventRouter: EventRouter,
    private val feedSub: FeedSubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val pubkeyHex: String?,
    private val getUserPubkey: () -> String?,
    private val registerAuthSigner: () -> Unit,
    private val fetchMissingEmojiSets: () -> Unit
) {
    private var eventProcessingJob: Job? = null
    private var metadataSweepJob: Job? = null
    private var ephemeralCleanupJob: Job? = null
    private var relayListRefreshJob: Job? = null
    private var followWatcherJob: Job? = null
    private var authCompletedJob: Job? = null
    private var startupJob: Job? = null
    private var feedCacheSaveJob: Job? = null

    var relaysInitialized = false
        private set
    var hasCachedFeed = false
        private set

    /**
     * Attempt to load cached feed from disk. Returns true if cache had content.
     * Must be called before initRelays() so seeded events dedup against relay events.
     */
    fun loadCachedFeed(): Boolean {
        val ft = feedSub.feedType.value
        if (ft != FeedType.FOLLOWS && ft != FeedType.EXTENDED_FOLLOWS) return false
        if (!feedCache.exists()) return false
        val events = feedCache.load()
        if (events.isEmpty()) return false
        val follows = contactRepo.getFollowList().map { it.pubkey }.toSet()
        val filtered = if (follows.isNotEmpty()) events.filter { it.pubkey in follows } else events
        if (filtered.isEmpty()) return false
        eventRepo.seedFromCache(filtered)
        feedSub.applyAuthorFilterForFeedType(ft)
        feedSub.markLoadingComplete()
        feedSub._initialLoadDone.value = true
        hasCachedFeed = true
        return true
    }

    /** Save feed snapshot to disk on IO thread. */
    fun saveFeedCache() {
        feedCacheSaveJob?.cancel()
        feedCacheSaveJob = scope.launch(Dispatchers.IO) {
            val snapshot = eventRepo.getFeedSnapshot()
            if (snapshot.isNotEmpty()) feedCache.save(snapshot)
        }
    }

    fun resetForAccountSwitch() {
        // Cancel all background jobs
        eventProcessingJob?.cancel()
        metadataSweepJob?.cancel()
        ephemeralCleanupJob?.cancel()
        relayListRefreshJob?.cancel()
        followWatcherJob?.cancel()
        authCompletedJob?.cancel()
        startupJob?.cancel()
        feedCacheSaveJob?.cancel()
        feedSub.reset()
        feedCache.clear()

        // Stop lifecycle manager and disconnect relays
        lifecycleManager.stop()
        relayPool.disconnectAll()
        nwcRepo.disconnect()

        // Clear all repos
        metadataFetcher.clear()
        eventRepo.clearAll()
        customEmojiRepo.clear()
        dmRepo.clear()
        notifRepo.clear()
        contactRepo.clear()
        muteRepo.clear()
        bookmarkRepo.clear()
        bookmarkSetRepo.clear()
        pinRepo.clear()
        listRepo.clear()
        blossomRepo.clear()
        extendedNetworkRepo.clear()
        relayScoreBoard.clear()
        relayHintStore.clear()
        healthTracker.clear()
        relayListRepo.clear()
        nip05Repo.clear()
        relayPool.clearSeenEvents()

        // Reset state
        relaysInitialized = false
        hasCachedFeed = false
    }

    fun reloadForNewAccount() {
        val newPubkey = getUserPubkey()

        // Reload per-account prefs for new pubkey
        eventRepo.currentUserPubkey = newPubkey
        keyRepo.reloadPrefs(newPubkey)
        contactRepo.reload(newPubkey)
        muteRepo.reload(newPubkey)
        bookmarkRepo.reload(newPubkey)
        bookmarkSetRepo.reload(newPubkey)
        pinRepo.reload(newPubkey)
        listRepo.reload(newPubkey)
        blossomRepo.reload(newPubkey)
        nwcRepo.reload(newPubkey)
        relayScoreBoard.reload(newPubkey)
        healthTracker.reload(newPubkey)
        extendedNetworkRepo.reload(newPubkey)
        customEmojiRepo.reload(newPubkey)
        zapPrefs.reload(newPubkey)
    }

    fun initRelays() {
        if (relaysInitialized) return
        relaysInitialized = true
        relayPool.healthTracker = healthTracker
        relayPool.appIsActive = true
        healthTracker.onBadRelaysChanged = { recomputeAndMergeRelays() }
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

        scope.launch {
            if (hasCachedFeed) delay(5_000) // defer when feed is already showing
            relayInfoRepo.prefetchAll(initialRelays.map { it.url })
        }

        // Main event processing loop — runs on Default dispatcher to keep UI thread free
        eventProcessingJob = scope.launch(processingContext) {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                eventRouter.processRelayEvent(event, relayUrl, subscriptionId)
            }
        }

        // Periodic profile & quote sweep — safety net, not primary fetch mechanism.
        // Profiles are bootstrapped with relay lists before feed loads.
        metadataSweepJob = scope.launch(processingContext) {
            delay(15_000)
            metadataFetcher.sweepMissingProfiles()
            while (true) {
                delay(60_000)
                metadataFetcher.sweepMissingProfiles()
            }
        }

        // Periodic ephemeral relay cleanup + seen event trimming
        ephemeralCleanupJob = scope.launch {
            while (true) {
                delay(60_000)
                relayPool.cleanupEphemeralRelays()
                eventRepo.trimSeenEvents()
                relayHintStore.flush()
            }
        }

        // Periodic relay list refresh (every 30 minutes)
        relayListRefreshJob = scope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                fetchRelayListsForFollows()
                delay(15_000)
                recomputeAndMergeRelays()
            }
        }

        // Incrementally update scoreboard when follow list changes, then re-subscribe feed
        followWatcherJob = scope.launch {
            var previousFollows = contactRepo.getFollowList().map { it.pubkey }.toSet()
            contactRepo.followList.drop(1).collectLatest { entries ->
                val currentFollows = entries.map { it.pubkey }.toSet()
                val added = currentFollows - previousFollows
                val removed = previousFollows - currentFollows
                previousFollows = currentFollows

                for (pubkey in removed) relayScoreBoard.removeAuthor(pubkey)
                for (pubkey in added) {
                    outboxRouter.requestMissingRelayLists(listOf(pubkey))
                    delay(500)
                    relayScoreBoard.addAuthor(pubkey, excludeRelays = getExcludedRelayUrls())
                }

                if ((added.isNotEmpty() || removed.isNotEmpty()) &&
                    (feedSub.feedType.value == FeedType.FOLLOWS || feedSub.feedType.value == FeedType.EXTENDED_FOLLOWS)) {
                    rebuildRelayPool()
                    feedSub.resubscribeFeed()
                    feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
                }
            }
        }

        // NIP-42 AUTH: sign challenges via signer (local or remote)
        registerAuthSigner()

        // Re-send DM subscription to relays after AUTH completes
        authCompletedJob = scope.launch {
            relayPool.authCompleted.collect { relayUrl ->
                val myPubkey = getUserPubkey() ?: return@collect
                val dmRelayUrls = relayPool.getDmRelayUrls()
                if (relayUrl in dmRelayUrls || relayUrl in relayPool.getRelayUrls()) {
                    val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
                    relayPool.sendToRelay(relayUrl, ClientMessage.req("dms", dmFilter))
                }
            }
        }

        // Start network-aware lifecycle manager — handles connectivity changes
        // and works regardless of which screen is active.
        lifecycleManager.start()

        getUserPubkey()?.let {
            listRepo.setOwner(it)
            bookmarkSetRepo.setOwner(it)
        }

        // Unified startup: cold start shows profile discovery UI, warm start skips to feed.
        // Cold start = first login or cache invalidated; warm start = returning with valid cache.
        startupJob = scope.launch {
            val cachedFollows = contactRepo.getFollowList()
            val isColdStart = cachedFollows.isEmpty() || relayScoreBoard.needsRecompute()

            val follows: List<String>

            if (isColdStart) {
                Log.d("StartupCoord", "init: cold start (${cachedFollows.size} cached follows, needsRecompute=${relayScoreBoard.needsRecompute()})")

                // Show cached profile immediately if available (e.g. re-login same account)
                val myPubkey = getUserPubkey()
                val cachedProfile = myPubkey?.let { profileRepo.get(it) }
                if (cachedProfile != null) {
                    feedSub._initLoadingState.value = InitLoadingState.FoundProfile(cachedProfile.displayString, cachedProfile.picture)
                } else {
                    feedSub._initLoadingState.value = InitLoadingState.SearchingProfile
                }

                // Phase 1a: Connect + fetch self-data (profile, follow list, relay lists)
                relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                subscribeSelfData()
                fetchMissingEmojiSets()

                // Show profile if we didn't have it cached but now have it from self-data
                if (cachedProfile == null) {
                    val profile = myPubkey?.let { profileRepo.get(it) }
                    if (profile != null) {
                        feedSub._initLoadingState.value = InitLoadingState.FoundProfile(profile.displayString, profile.picture)
                        delay(1200)
                    }
                }

                // Phase 1b: Relay list fetch + compute routing
                follows = contactRepo.getFollowList().map { it.pubkey }

                if (relayScoreBoard.needsRecompute() && follows.isNotEmpty()) {
                    feedSub._initLoadingState.value = InitLoadingState.FindingFriends(0, follows.size)

                    val subscriptionSent = fetchRelayListsForFollows(includeProfiles = true)
                    if (subscriptionSent) {
                        val target = (follows.size * 0.9).toInt()
                        val deadline = System.currentTimeMillis() + 10_000
                        while (System.currentTimeMillis() < deadline) {
                            val covered = follows.size - relayListRepo.getMissingPubkeys(follows).size
                            feedSub._initLoadingState.value = InitLoadingState.FindingFriends(covered, follows.size)
                            if (covered >= target) break
                            delay(200)
                        }
                        subManager.closeSubscription("relay-lists")
                    }

                    recomputeAndMergeRelays()
                    relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                } else {
                    val scored = relayScoreBoard.getScoredRelays()
                    Log.d("StartupCoord", "init: scoreboard cache valid (${scored.size} relays, ${follows.size} follows), skipping recompute")
                }
            } else {
                // Warm start: connect + background self-data refresh, no discovery UI
                Log.d("StartupCoord", "init: warm start (${cachedFollows.size} cached follows, scoreboard valid)")
                feedSub._initLoadingState.value = InitLoadingState.WarmLoading

                if (hasCachedFeed) {
                    // Don't block — relays will connect in background, feed is already showing
                    launch {
                        relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                        subscribeSelfData()
                        fetchMissingEmojiSets()
                    }
                } else {
                    relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                    // Fire-and-forget self-data refresh
                    launch {
                        subscribeSelfData()
                        fetchMissingEmojiSets()
                    }
                }

                follows = cachedFollows.map { it.pubkey }
            }

            // Phase 2: Extended network discovery (common path)
            val extNetCache = extendedNetworkRepo.cachedNetwork.value
            val extNetCacheValid = extNetCache != null && !extendedNetworkRepo.isCacheStale(extNetCache)

            if (hasCachedFeed) {
                // Feed is already showing from disk cache.
                // Stagger background work so the UI isn't competing for CPU.

                // Phase A: Wait for at least 1 relay, then subscribe to new events only
                delay(300) // let Compose finish initial layout
                relayPool.awaitAnyConnected(minCount = 1, timeoutMs = 5_000)
                feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
                feedSub.resumeSubscribeFeed()

                // Phase B: Deferred heavy work — self-data, discovery, relay lists
                launch {
                    delay(3_000) // let feed subscription settle first
                    if (!extNetCacheValid && follows.isNotEmpty()) {
                        try { extendedNetworkRepo.discoverNetwork() } catch (_: Exception) {}
                        val extConfigs = extendedNetworkRepo.getRelayConfigs()
                        if (extConfigs.isNotEmpty()) rebuildRelayPool()
                    }
                    if (!relayScoreBoard.needsRecompute()) fetchRelayListsForFollows()
                    saveFeedCache()
                }

                // Periodic save timer
                launch {
                    while (true) {
                        delay(60_000)
                        saveFeedCache()
                    }
                }
            } else {
                if (!extNetCacheValid && follows.isNotEmpty()) {
                    val progressJob = launch {
                        extendedNetworkRepo.discoveryState.collect { ds ->
                            if (ds is DiscoveryState.FetchingFollowLists && isColdStart) {
                                feedSub._initLoadingState.value = InitLoadingState.DiscoveringNetwork(ds.fetched, ds.total)
                            }
                        }
                    }
                    try {
                        extendedNetworkRepo.discoverNetwork()
                    } catch (e: Exception) {
                        Log.e("StartupCoord", "Extended network discovery failed during init", e)
                    }
                    progressJob.cancel()
                }

                // Expand pool with extended relays
                val extConfigs = extendedNetworkRepo.getRelayConfigs()
                if (extConfigs.isNotEmpty()) {
                    if (isColdStart) feedSub._initLoadingState.value = InitLoadingState.ExpandingRelays(extConfigs.size)
                    rebuildRelayPool()
                    val poolSize = relayPool.getRelayUrls().size
                    val targetConnected = poolSize * 3 / 10
                    relayPool.awaitAnyConnected(minCount = targetConnected, timeoutMs = 3_000)
                }

                // Apply filter and subscribe
                feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
                feedSub._initLoadingState.value = InitLoadingState.Subscribing
                feedSub.subscribeFeed()

                // Don't set Done here — subscribeFeed() is non-blocking.
                // InitLoadingState.Done is set inside feedEoseJob after EOSE arrives.

                // Background: fetch relay lists for any new follows (non-blocking)
                if (!relayScoreBoard.needsRecompute()) {
                    fetchRelayListsForFollows()
                }
            }
        }
    }

    /**
     * Fetches self-data (follow list, relay lists, mutes, etc.) and **awaits** EOSE
     * so the caller has fresh data before proceeding to build the feed.
     * DM and notification subscriptions are fire-and-forget (not feed-blocking).
     */
    private suspend fun subscribeSelfData() {
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
            Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip51.KIND_BOOKMARK_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip30.KIND_USER_EMOJI_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip30.KIND_EMOJI_SET), authors = listOf(myPubkey), limit = 50)
        )
        relayPool.sendToAll(ClientMessage.req("self-data", selfDataFilters))

        // Await EOSE so follow list (kind 3) and relay list (kind 10002) are
        // available before the caller proceeds to build the feed.
        subManager.awaitEoseWithTimeout("self-data")
        subManager.closeSubscription("self-data")

        // Cache the user's avatar locally for instant loading screen display.
        // Re-download if the URL changed (compare with what we had before self-data fetch).
        val profile = profileRepo.get(myPubkey)
        if (profile?.picture != null) {
            val localFile = profileRepo.getLocalAvatar(myPubkey)
            val urlFile = File(profileRepo.avatarDir, "${myPubkey}.url")
            val cachedUrl = if (urlFile.exists()) urlFile.readText() else null
            if (localFile == null || cachedUrl != profile.picture) {
                scope.launch {
                    profileRepo.cacheAvatar(myPubkey, profile.picture)
                    urlFile.writeText(profile.picture)
                }
            }
        }

        // DMs and notifications are not feed-blocking — fire and forget
        subscribeDmsAndNotifications(myPubkey)
    }

    /**
     * Subscribe to DMs and notifications. Extracted so it can be re-called on force reconnect
     * when all relay subscriptions have been torn down.
     */
    fun subscribeDmsAndNotifications(myPubkey: String) {
        val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
        val dmReqMsg = ClientMessage.req("dms", dmFilter)
        relayPool.sendToAll(dmReqMsg)
        relayPool.sendToDmRelays(dmReqMsg)
        scope.launch {
            subManager.awaitEoseWithTimeout("dms")
            Log.d("StartupCoord", "DM subscription (re)established")
        }

        val notifFilter = Filter(
            kinds = listOf(1, 6, 7, 9735),
            pTags = listOf(myPubkey),
            limit = 100
        )
        val notifReqMsg = ClientMessage.req("notif", notifFilter)
        relayPool.sendToReadRelays(notifReqMsg)

        // Also send to top scored relays for broader coverage
        val readUrls = relayPool.getReadRelayUrls().toSet()
        val topScored = relayScoreBoard.getScoredRelays()
            .take(5)
            .map { it.url }
            .filter { it !in readUrls }
        for (url in topScored) {
            relayPool.sendToRelay(url, notifReqMsg)
        }
        scope.launch {
            subManager.awaitEoseWithTimeout("notif")
            feedSub.subscribeNotifEngagement()
        }
    }

    /**
     * Bootstrap follow data: fetch relay lists (kind 10002) AND profiles (kind 0)
     * for all follows in a single REQ.
     */
    fun fetchRelayListsForFollows(includeProfiles: Boolean = false): Boolean {
        val authors = contactRepo.getFollowList().map { it.pubkey }
        if (authors.isEmpty()) {
            Log.d("StartupCoord", "fetchRelayListsForFollows: follow list empty")
            return false
        }
        val sent = if (includeProfiles) {
            outboxRouter.requestRelayListsAndProfiles(authors, profileRepo) != null
        } else {
            outboxRouter.requestMissingRelayLists(authors) != null
        }
        Log.d("StartupCoord", "fetchRelayListsForFollows: ${authors.size} follows, includeProfiles=$includeProfiles, subscription sent=$sent")
        return sent
    }

    /** Blocked + bad relay URLs combined for outbox routing exclusion. */
    private fun getExcludedRelayUrls(): Set<String> =
        relayPool.getBlockedUrls() + healthTracker.getBadRelays()

    fun recomputeAndMergeRelays() {
        relayScoreBoard.recompute(excludeRelays = getExcludedRelayUrls())
        if (!relayScoreBoard.hasScoredRelays()) return
        rebuildRelayPool()
    }

    /**
     * Rebuild the persistent relay pool from pinned + scored + extended network relays.
     * Extended relays are always included so feed type switching is a cheap local filter.
     */
    fun rebuildRelayPool() {
        val pinnedRelays = keyRepo.getRelays()
        val pinnedUrls = pinnedRelays.map { it.url }.toSet()
        val scoredConfigs = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val baseUrls = pinnedUrls + scoredConfigs.map { it.url }.toSet()
        val extendedConfigs = extendedNetworkRepo.getRelayConfigs()
            .filter { it.url !in baseUrls }

        relayPool.updateRelays(pinnedRelays + scoredConfigs + extendedConfigs)
    }

    /** Called by Activity lifecycle — delegates to RelayLifecycleManager. */
    fun onAppPause() {
        Log.d("RLC", "[Startup] onAppPause — feedType=${feedSub.feedType.value} connectedCount=${relayPool.connectedCount.value}")
        notifRepo.appIsActive = false
        lifecycleManager.onAppPause()
        if (feedSub.feedType.value == FeedType.FOLLOWS || feedSub.feedType.value == FeedType.EXTENDED_FOLLOWS) {
            saveFeedCache()
        }
    }

    /** Called by Activity lifecycle — delegates to RelayLifecycleManager. */
    fun onAppResume(pausedMs: Long) {
        Log.d("RLC", "[Startup] onAppResume — paused ${pausedMs/1000}s, feedType=${feedSub.feedType.value} connectedCount=${relayPool.connectedCount.value}")
        notifRepo.appIsActive = true
        lifecycleManager.onAppResume(pausedMs)
    }

    fun refreshRelays() {
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.updateRelays(relays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())
        feedSub.subscribeFeed()
    }
}
