package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.db.EventPersistence
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
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
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.RelaySetRepository
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.nostr.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val eventPersistence: EventPersistence?,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val listRepo: ListRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val relaySetRepo: RelaySetRepository,
    private val pinRepo: PinRepository,
    private val blossomRepo: BlossomRepository,
    private val interestRepo: InterestRepository,
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
    private val zapPrefs: ZapPreferences,
    private val lifecycleManager: RelayLifecycleManager,
    private val eventRouter: EventRouter,
    private val feedSub: FeedSubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val pubkeyHex: String?,
    private val getUserPubkey: () -> String?,
    private val registerAuthSigner: () -> Unit,
    private val fetchMissingEmojiSets: () -> Unit,
    private val getSigner: () -> NostrSigner?
) {
    private var eventProcessingJob: Job? = null
    private var metadataSweepJob: Job? = null
    private var ephemeralCleanupJob: Job? = null
    private var relayListRefreshJob: Job? = null
    private var followWatcherJob: Job? = null
    private var authCompletedJob: Job? = null
    private var notifRefreshJob: Job? = null
    private var startupJob: Job? = null

    var relaysInitialized = false
        private set

    fun resetForAccountSwitch() {
        // Cancel all background jobs
        eventProcessingJob?.cancel()
        metadataSweepJob?.cancel()
        ephemeralCleanupJob?.cancel()
        relayListRefreshJob?.cancel()
        followWatcherJob?.cancel()
        authCompletedJob?.cancel()
        notifRefreshJob?.cancel()
        startupJob?.cancel()
        feedSub.reset()

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
        relaySetRepo.clear()
        pinRepo.clear()
        listRepo.clear()
        blossomRepo.clear()
        interestRepo.clear()
        extendedNetworkRepo.clear()
        relayScoreBoard.clear()
        relayHintStore.clear()
        healthTracker.clear()
        relayListRepo.clear()
        nip05Repo.clear()
        relayPool.clearSeenEvents()
        eventRouter.clearSelfDataTimestamps()

        // Reset state
        relaysInitialized = false
    }

    fun reloadForNewAccount() {
        val newPubkey = getUserPubkey()

        // Clear stale data from previous account
        notifRepo.clear()
        eventRepo.clearAll()
        dmRepo.clear()

        // Reload per-account prefs for new pubkey
        eventRepo.currentUserPubkey = newPubkey
        keyRepo.reloadPrefs(newPubkey)
        contactRepo.reload(newPubkey)
        muteRepo.reload(newPubkey)
        bookmarkRepo.reload(newPubkey)
        bookmarkSetRepo.reload(newPubkey)
        relaySetRepo.reload(newPubkey)
        pinRepo.reload(newPubkey)
        listRepo.reload(newPubkey)
        blossomRepo.reload(newPubkey)
        interestRepo.reload(newPubkey)
        nwcRepo.reload(newPubkey)
        relayScoreBoard.reload(newPubkey)
        healthTracker.reload(newPubkey)
        extendedNetworkRepo.reload(newPubkey)
        customEmojiRepo.reload(newPubkey)
        zapPrefs.reload(newPubkey)
    }

    fun initRelays() {
        Log.d("StartupCoord", "initRelays() called, relaysInitialized=$relaysInitialized")
        if (relaysInitialized) { Log.d("StartupCoord", "initRelays: already initialized, returning"); return }
        relaysInitialized = true

        // Prune old events from ObjectBox in background
        eventPersistence?.let { persistence ->
            scope.launch(processingContext) { persistence.prune() }
        }

        relayPool.healthTracker = healthTracker
        relayPool.appIsActive = true
        healthTracker.onBadRelaysChanged = { recomputeAndMergeRelays() }
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val pinnedRelays = keyRepo.getRelays()
        // Merge pinned relays with cached scored relays immediately so the pool
        // starts with the full relay set instead of just pinned (5-10).
        // RelayScoreBoard rebuilds from persisted RelayListRepository data on init.
        val pinnedUrls = pinnedRelays.map { it.url }.toSet()
        relayPool.setPinnedRelays(pinnedUrls)
        val cachedScored = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val initialRelays = pinnedRelays + cachedScored
        relayPool.updateRelays(initialRelays)
        val dmRelays = keyRepo.getDmRelays()
        relayPool.updateDmRelays(dmRelays)
        eventRepo.dmRelayUrls = dmRelays.toSet()

        scope.launch {
            relayInfoRepo.prefetchAll(initialRelays.map { it.url })
        }

        // Main event processing loop — runs on Default dispatcher to keep UI thread free
        eventProcessingJob = scope.launch(processingContext) {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                eventRouter.processRelayEvent(event, relayUrl, subscriptionId)
            }
        }

        // Profile sweep — eager burst at startup for fast profile coverage,
        // then relaxed periodic sweep as a safety net.
        metadataSweepJob = scope.launch(processingContext) {
            // Eager: sweep at 3s, 8s, 15s after startup
            delay(3_000)
            metadataFetcher.sweepMissingProfiles()
            delay(5_000)  // t=8s
            metadataFetcher.sweepMissingProfiles()
            delay(7_000)  // t=15s
            metadataFetcher.sweepMissingProfiles()
            // Relax — by now profiles should be loaded
            while (true) {
                delay(120_000)
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

        // Periodic DM + notification subscription refresh (every 3 minutes)
        // Relays can silently drop subscriptions server-side while the WebSocket stays alive.
        notifRefreshJob = scope.launch {
            while (true) {
                delay(3 * 60 * 1000L)
                val pk = pubkeyHex ?: continue
                subscribeDmsAndNotifications(pk)
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
            relaySetRepo.setOwner(it)
        }

        // Unified startup: cold start shows profile discovery UI, warm start skips to feed.
        // Cold start = first login or cache invalidated; warm start = returning with valid cache.
        startupJob = scope.launch {
            val cachedFollows = contactRepo.getFollowList()
            val isColdStart = cachedFollows.isEmpty() || relayScoreBoard.needsRecompute()
            val relayCount = relayPool.getRelayUrls().size

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
                // Use actual relay count to avoid waiting for impossible minCount
                relayPool.awaitAnyConnected(minCount = minOf(3, relayCount), timeoutMs = 5_000)
                subscribeSelfData()
                fetchMissingEmojiSets()

                // Show profile if we didn't have it cached but now have it from self-data
                if (cachedProfile == null) {
                    val profile = myPubkey?.let { profileRepo.get(it) }
                    if (profile != null) {
                        feedSub._initLoadingState.value = InitLoadingState.FoundProfile(profile.displayString, profile.picture)
                        delay(400)
                    }
                }

                // Phase 1b: Relay list fetch + compute routing
                follows = contactRepo.getFollowList().map { it.pubkey }

                if (relayScoreBoard.needsRecompute() && follows.isNotEmpty()) {
                    feedSub._initLoadingState.value = InitLoadingState.FindingFriends(0, follows.size)

                    val subscriptionSent = fetchRelayListsForFollows(includeProfiles = true)
                    if (subscriptionSent) {
                        val target = (follows.size * 0.8).toInt()
                        val deadline = System.currentTimeMillis() + 7_000
                        while (System.currentTimeMillis() < deadline) {
                            val covered = follows.size - relayListRepo.getMissingPubkeys(follows).size
                            feedSub._initLoadingState.value = InitLoadingState.FindingFriends(covered, follows.size)
                            if (covered >= target) break
                            delay(200)
                        }
                        subManager.closeSubscription("relay-lists")
                    }

                    recomputeAndMergeRelays()
                    val newRelayCount = relayPool.getRelayUrls().size
                    relayPool.awaitAnyConnected(minCount = minOf(3, newRelayCount), timeoutMs = 5_000)
                } else {
                    val scored = relayScoreBoard.getScoredRelays()
                    Log.d("StartupCoord", "init: scoreboard cache valid (${scored.size} relays, ${follows.size} follows), skipping recompute")
                }
            } else {
                // Warm start: connect + background self-data refresh, no discovery UI
                Log.d("StartupCoord", "init: warm start (${cachedFollows.size} cached follows, scoreboard valid)")
                feedSub._initLoadingState.value = InitLoadingState.WarmLoading

                relayPool.awaitAnyConnected(minCount = minOf(3, relayCount), timeoutMs = 5_000)
                // Fire-and-forget self-data refresh
                launch {
                    subscribeSelfData()
                    fetchMissingEmojiSets()
                }

                follows = cachedFollows.map { it.pubkey }
            }

            // Subscribe feed with current follow list and relay routing.
            Log.d("StartupCoord", "Subscribing feed, feedType=${feedSub.feedType.value}")
            feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
            feedSub._initLoadingState.value = InitLoadingState.Subscribing
            feedSub.subscribeFeed()
            Log.d("StartupCoord", "subscribeFeed called")

            // Safety retry: if the follow list arrived late (after the initial subscribeFeed
            // call which early-returns on empty authors), wait and retry once.
            if (follows.isEmpty()) {
                launch {
                    val arrived = withTimeoutOrNull(8_000) {
                        contactRepo.followList.first { it.isNotEmpty() }
                    }
                    if (arrived != null) {
                        Log.d("StartupCoord", "Follow list arrived late (${arrived.size} follows), resubscribing feed")
                        feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
                        feedSub.resubscribeFeed()
                    }
                }
            }

            // Background: fetch relay lists for any new follows (non-blocking)
            if (!relayScoreBoard.needsRecompute()) {
                fetchRelayListsForFollows()
            }
        }
    }

    /**
     * Fetches self-data (follow list, relay lists, mutes, etc.) and **awaits** EOSE
     * so the caller has fresh data before proceeding to build the feed.
     * DM and notification subscriptions are fire-and-forget (not feed-blocking).
     */
    /**
     * Fetches self-data (follow list, relay lists, mutes, etc.).
     * Instead of waiting for full EOSE (up to 15s), we proceed as soon as the
     * follow list (kind 3) arrives since that's the gate for the next phase.
     * Remaining self-data continues streaming in the background.
     */
    private suspend fun subscribeSelfData() {
        val myPubkey = getUserPubkey() ?: return

        val selfDataFilters = listOf(
            Filter(kinds = listOf(0), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(3), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(10002), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(10050, 10007, 10006, Nip51.KIND_FAVORITE_RELAYS), authors = listOf(myPubkey), limit = 4),
            Filter(kinds = listOf(Nip51.KIND_MUTE_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_PIN_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_BOOKMARK_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Blossom.KIND_SERVER_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip51.KIND_BOOKMARK_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip51.KIND_RELAY_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip51.KIND_INTEREST_SET), authors = listOf(myPubkey), limit = 50),
            Filter(kinds = listOf(Nip30.KIND_USER_EMOJI_LIST), authors = listOf(myPubkey), limit = 1),
            Filter(kinds = listOf(Nip30.KIND_EMOJI_SET), authors = listOf(myPubkey), limit = 50)
        )
        // Send to all indexer relays (ephemeral if not already connected) —
        // these are the most reliable sources for user metadata on first launch.
        val indexerRelays = RelayConfig.DEFAULT_INDEXER_RELAYS
        val reqMsg = ClientMessage.req("self-data", selfDataFilters)
        for (url in indexerRelays) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg)
        }

        // Wait for indexer relays to EOSE so we get the newest kind 3 (follow list).
        // contactRepo.updateFromEvent already keeps the newest by created_at, so collecting
        // from several relays ensures we don't proceed with a stale follow list from
        // a single fast relay.
        val eoseCount = subManager.awaitEoseCount("self-data", expectedCount = indexerRelays.size, timeoutMs = 4_000)

        // EOSE and events travel on separate SharedFlows, so the kind 3 event may still
        // be buffered in relayEvents waiting for the processing loop on Dispatchers.Default.
        // Wait reactively for the follow list to arrive rather than polling — this handles
        // both slow event processing and cases where EOSE times out but events still arrive.
        if (contactRepo.getFollowList().isEmpty()) {
            withTimeoutOrNull(5_000) {
                contactRepo.followList.first { it.isNotEmpty() }
            }
        }

        val gotFollowList = contactRepo.getFollowList().isNotEmpty()
        Log.d("StartupCoord", "subscribeSelfData: eoseCount=$eoseCount")
        Log.d("StartupCoord", "subscribeSelfData: gotFollowList=$gotFollowList, follows=${contactRepo.getFollowList().size}")

        // Close subscription in background after a short grace period for remaining data
        scope.launch {
            delay(3_000)
            subManager.closeSubscription("self-data")
        }

        // Cache the user's avatar locally for instant loading screen display.
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

        // Apply default DM relays if the user has none set (e.g. new account or never configured).
        // This must be a suspend call — kind 10050 events from the self-data subscription may
        // still be buffered in EventRouter's processing loop even after EOSE, so we wait
        // reactively for them to arrive before concluding the user has no DM relays.
        applyDefaultDmRelaysIfEmpty(myPubkey)

        // DMs and notifications are not feed-blocking — fire and forget
        subscribeDmsAndNotifications(myPubkey)
    }

    /**
     * If the user has no DM relays after fetching self-data, apply defaults and broadcast.
     *
     * The self-data subscription already requests kind 10050 from indexer relays, but events
     * are processed asynchronously by EventRouter. We must wait for that processing to
     * complete before concluding the user has no DM relays. Additionally, we query the
     * user's write relays (outbox) as a second source in case indexers don't have it.
     */
    private suspend fun applyDefaultDmRelaysIfEmpty(myPubkey: String) {
        // Fast path: already have DM relays from local storage (previous session) or
        // from EventRouter processing the self-data response.
        if (keyRepo.getDmRelays().isNotEmpty()) {
            Log.d("StartupCoord", "DM relays already set: ${keyRepo.getDmRelays()}")
            return
        }

        // The kind 10050 event may still be buffered in EventRouter's processing loop
        // even though EOSE arrived. Also query the user's write relays as a fallback
        // source — indexer relays may not have the latest kind 10050.
        val writeRelayUrls = relayPool.getWriteRelayUrls()
        if (writeRelayUrls.isNotEmpty()) {
            val dmRelayFilter = Filter(kinds = listOf(Nip51.KIND_DM_RELAYS), authors = listOf(myPubkey), limit = 1)
            val reqMsg = ClientMessage.req("dm-relay-check", listOf(dmRelayFilter))
            for (url in writeRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, reqMsg)
            }
        }

        // Wait reactively for DM relays to arrive from either source.
        val arrived = withTimeoutOrNull(8_000) {
            keyRepo.dmRelaysFlow.first { it.isNotEmpty() }
        }

        // Clean up the fallback subscription
        subManager.closeSubscription("dm-relay-check")

        if (arrived != null) {
            Log.d("StartupCoord", "DM relays arrived during wait: $arrived")
            return
        }

        // After thorough checking, user genuinely has no DM relays — apply defaults.
        // Double-check one more time in case of a late arrival.
        if (keyRepo.getDmRelays().isNotEmpty()) {
            Log.d("StartupCoord", "DM relays arrived just before default application: ${keyRepo.getDmRelays()}")
            return
        }

        val defaults = RelayConfig.DEFAULT_DM_RELAYS
        keyRepo.saveDmRelays(defaults)
        relayPool.updateDmRelays(defaults)
        Log.d("StartupCoord", "Applied default DM relays: $defaults")

        // Publish kind 10050 so other clients and relays know our DM relays
        val s = getSigner() ?: return
        scope.launch {
            val tags = Nip51.buildRelaySetTags(defaults)
            val event = s.signEvent(kind = Nip51.KIND_DM_RELAYS, content = "", tags = tags)
            val msg = ClientMessage.event(event)
            relayPool.sendToWriteRelays(msg)
            relayPool.sendToDmRelays(msg)
            for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                relayPool.sendToRelayOrEphemeral(url, msg)
            }
            Log.d("StartupCoord", "Published default DM relay list (kind ${Nip51.KIND_DM_RELAYS})")
        }
    }

    /**
     * Subscribe to DMs and notifications. Extracted so it can be re-called on force reconnect
     * when all relay subscriptions have been torn down.
     */
    fun subscribeDmsAndNotifications(myPubkey: String) {
        notifRepo.soundEligibleAfter = System.currentTimeMillis() / 1000
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
            limit = 300
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

        // Fetch private zap receipts from DM relays
        if (relayPool.hasDmRelays()) {
            val dmZapFilter = Filter(
                kinds = listOf(9735),
                pTags = listOf(myPubkey),
                limit = 100
            )
            relayPool.sendToDmRelays(ClientMessage.req("notif", dmZapFilter))
        }

        // Fetch the user's own recent notes upfront so notification referenced events
        // (reactions, zaps, reposts all point at our events) are in cache before
        // engagement subscriptions start.
        val selfNotesMsg = ClientMessage.req(
            "self-notes",
            Filter(kinds = listOf(1), authors = listOf(myPubkey), limit = 200)
        )
        relayPool.sendToWriteRelays(selfNotesMsg)
        relayPool.sendToReadRelays(selfNotesMsg)

        scope.launch {
            subManager.awaitEoseWithTimeout("notif")
            // Wait for self-notes to arrive before engagement so referenced events are cached
            subManager.awaitEoseWithTimeout("self-notes", timeoutMs = 5_000)
            subManager.closeSubscription("self-notes")

            refreshNotifRepliesEtag(myPubkey)

            feedSub.subscribeNotifEngagement()
        }
    }

    /**
     * Subscribe for replies via e-tags on our own posts.
     * Catches replies where the replier's client omits the p-tag.
     * Extracted so it can be re-called after publishing a note without
     * re-running the full DM/notification setup.
     */
    fun refreshNotifRepliesEtag(myPubkey: String? = pubkeyHex) {
        val pk = myPubkey ?: return
        val myEventIds = eventRepo.getRecentEventIdsByAuthor(pk, limit = 100)
        if (myEventIds.isEmpty()) return

        val filters = myEventIds.chunked(OutboxRouter.MAX_ETAGS_PER_FILTER).map { chunk ->
            Filter(kinds = listOf(1), eTags = chunk, limit = 200)
        }
        val replyReqMsg = if (filters.size == 1) ClientMessage.req("notif-replies-etag", filters[0])
        else ClientMessage.req("notif-replies-etag", filters)

        relayPool.sendToReadRelays(replyReqMsg)
        // Replies often land on the relay where the original note was posted
        val readUrls2 = relayPool.getReadRelayUrls().toSet()
        val writeNotInRead = relayPool.getWriteRelayUrls().filter { it !in readUrls2 }
        for (url in writeNotInRead) {
            relayPool.sendToRelay(url, replyReqMsg)
        }
        val topScored2 = relayScoreBoard.getScoredRelays()
            .take(5)
            .map { it.url }
            .filter { it !in readUrls2 && it !in writeNotInRead.toSet() }
        for (url in topScored2) {
            relayPool.sendToRelay(url, replyReqMsg)
        }
        // Also subscribe for quotes of our posts via #q tags
        val quoteFilters = myEventIds.chunked(OutboxRouter.MAX_ETAGS_PER_FILTER).map { chunk ->
            Filter(kinds = listOf(1), qTags = chunk, limit = 200)
        }
        val quoteReqMsg = if (quoteFilters.size == 1) ClientMessage.req("notif-quotes-qtag", quoteFilters[0])
        else ClientMessage.req("notif-quotes-qtag", quoteFilters)
        relayPool.sendToReadRelays(quoteReqMsg)
        for (url in writeNotInRead) relayPool.sendToRelay(url, quoteReqMsg)
        for (url in topScored2) relayPool.sendToRelay(url, quoteReqMsg)

        scope.launch {
            subManager.awaitEoseWithTimeout("notif-replies-etag", timeoutMs = 5_000)
            subManager.awaitEoseWithTimeout("notif-quotes-qtag", timeoutMs = 5_000)
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
        relayPool.setPinnedRelays(pinnedUrls)
        val scoredConfigs = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val baseUrls = pinnedUrls + scoredConfigs.map { it.url }.toSet()
        val extendedConfigs = extendedNetworkRepo.getRelayConfigs()
            .filter { it.url !in baseUrls }

        relayPool.updateRelays(pinnedRelays + scoredConfigs + extendedConfigs)
    }

    /**
     * Integrate an already-computed extended network into the relay pool and feed.
     * Called after SocialGraphScreen finishes discovery.
     */
    fun integrateExtendedNetwork() {
        scope.launch {
            val extConfigs = extendedNetworkRepo.getRelayConfigs()
            if (extConfigs.isNotEmpty()) {
                rebuildRelayPool()
                val poolSize = relayPool.getRelayUrls().size
                relayPool.awaitAnyConnected(minCount = poolSize * 3 / 10, timeoutMs = 3_000)
                feedSub.applyAuthorFilterForFeedType(feedSub.feedType.value)
                feedSub.resubscribeFeed()
            }
        }
    }

    /** Called by Activity lifecycle — delegates to RelayLifecycleManager. */
    fun onAppPause() {
        Log.d("RLC", "[Startup] onAppPause — feedType=${feedSub.feedType.value} connectedCount=${relayPool.connectedCount.value}")
        notifRepo.appIsActive = false
        lifecycleManager.onAppPause()
    }

    /** Called by Activity lifecycle — delegates to RelayLifecycleManager. */
    fun onAppResume(pausedMs: Long) {
        Log.d("RLC", "[Startup] onAppResume — paused ${pausedMs/1000}s, feedType=${feedSub.feedType.value} connectedCount=${relayPool.connectedCount.value}")
        notifRepo.appIsActive = true
        lifecycleManager.onAppResume(pausedMs)
    }

    fun refreshDmsAndNotifications() {
        val pk = pubkeyHex ?: return
        subscribeDmsAndNotifications(pk)
    }

    fun refreshRelays() {
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.setPinnedRelays(relays.map { it.url }.toSet())
        relayPool.updateRelays(relays)
        val dmRelays = keyRepo.getDmRelays()
        relayPool.updateDmRelays(dmRelays)
        eventRepo.dmRelayUrls = dmRelays.toSet()
        feedSub.subscribeFeed()
    }
}
