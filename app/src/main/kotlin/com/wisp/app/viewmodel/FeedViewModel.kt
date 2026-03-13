package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.TorManager
import com.wisp.app.relay.Relay
import com.wisp.app.relay.RelayLifecycleManager
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.ScoredRelay
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.db.EventPersistence
import com.wisp.app.db.WispObjectBox
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.SocialGraphDb
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.RelaySetRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.RelaySet
import android.content.Context
import com.wisp.app.nostr.Filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOLLOWS, EXTENDED_FOLLOWS, RELAY, LIST, TRENDING }

enum class TrendingMetric(val slug: String, val label: String) {
    REACTIONS("reactions", "Reactions"),
    REPLIES("replies", "Replies"),
    REPOSTS("reposts", "Reposts"),
    ZAPS("zaps", "Zaps")
}

enum class TrendingTimeframe(val slug: String, val label: String) {
    TODAY("today", "Today"),
    WEEK("7d", "7d"),
    MONTH("30d", "30d"),
    YEAR("1y", "1y"),
    ALL("all", "All")
}

fun buildTrendingRelayUrl(metric: TrendingMetric, timeframe: TrendingTimeframe): String =
    "wss://feeds.nostrarchives.com/notes/trending/${metric.slug}/${timeframe.slug}"

sealed interface RelayFeedStatus {
    data object Idle : RelayFeedStatus
    data object Connecting : RelayFeedStatus
    data object Subscribing : RelayFeedStatus
    data object Streaming : RelayFeedStatus
    data object NoEvents : RelayFeedStatus
    data object TimedOut : RelayFeedStatus
    data object RateLimited : RelayFeedStatus
    data class BadRelay(val reason: String) : RelayFeedStatus
    data class Cooldown(val remainingSeconds: Int) : RelayFeedStatus
    data class ConnectionFailed(val message: String) : RelayFeedStatus
    data object Disconnected : RelayFeedStatus
}

sealed class InitLoadingState {
    // Cold-start only (skipped when cached):
    data object SearchingProfile : InitLoadingState()
    data class FoundProfile(val name: String, val picture: String?) : InitLoadingState()
    data class FindingFriends(val found: Int, val total: Int) : InitLoadingState()

    // Common path (both cold and warm):
    data class DiscoveringNetwork(val fetched: Int, val total: Int) : InitLoadingState()
    data class ExpandingRelays(val relayCount: Int) : InitLoadingState()

    // Warm-start path (no progress bar, rotating text):
    data object WarmLoading : InitLoadingState()

    data object Subscribing : InitLoadingState()
    data object Done : InitLoadingState()
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    // -- Infrastructure --
    val keyRepo = KeyRepository(app)
    private val pubkeyHex: String? = keyRepo.getPubkeyHex()

    var signer: NostrSigner? = null
        private set

    fun setSigner(s: NostrSigner) {
        signer = s
        zapSender.signer = s
        registerAuthSigner()
    }

    private fun registerAuthSigner() {
        val s = signer ?: return
        relayPool.setAuthSigner { relayUrl, challenge ->
            s.signEvent(
                kind = 22242,
                content = "",
                tags = listOf(
                    listOf("relay", relayUrl),
                    listOf("challenge", challenge)
                )
            )
        }
    }

    val relayPool = RelayPool()
    val healthTracker = RelayHealthTracker(app, pubkeyHex)
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app, pubkeyHex)
    val nip05Repo = Nip05Repository()
    val relayHintStore = RelayHintStore(app)
    val deletedEventsRepo = DeletedEventsRepository(app, pubkeyHex)
    val eventPersistence: EventPersistence? = if (WispObjectBox.isInitialized) {
        EventPersistence(pubkeyHex)
    } else null
    val eventRepo = EventRepository(profileRepo, muteRepo, relayHintStore).also {
        it.currentUserPubkey = pubkeyHex
        it.deletedEventsRepo = deletedEventsRepo
        it.eventPersistence = eventPersistence
    }
    val contactRepo = ContactRepository(app, pubkeyHex)
    val listRepo = ListRepository(app, pubkeyHex)
    val dmRepo = DmRepository(app, pubkeyHex)
    val notifRepo = NotificationRepository(app, pubkeyHex, muteRepo, eventRepo)
    val relayListRepo = RelayListRepository(app)
    val bookmarkRepo = BookmarkRepository(app, pubkeyHex)
    val bookmarkSetRepo = BookmarkSetRepository(app, pubkeyHex)
    val relaySetRepo = RelaySetRepository(app, pubkeyHex)
    val pinRepo = PinRepository(app, pubkeyHex)
    val blossomRepo = BlossomRepository(app, pubkeyHex)
    val interestRepo = InterestRepository(app, pubkeyHex)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo, pubkeyHex)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayHintStore, relayScoreBoard)
    val subManager = SubscriptionManager(relayPool)
    val lifecycleManager = RelayLifecycleManager(
        context = app,
        relayPool = relayPool,
        scope = viewModelScope,
        onPreReconnect = {
            // Start the trending relay connection early so it connects in parallel
            // with persistent relays, not sequentially after them.
            if (feedSub.feedType.value == FeedType.TRENDING) {
                val url = buildTrendingRelayUrl(feedSub.trendingMetric.value, feedSub.trendingTimeframe.value)
                relayPool.preConnectEphemeral(url)
            }
        },
        onReconnected = { force ->
            Log.d("RLC", "[FeedVM] onReconnected(force=$force) feedType=${feedSub.feedType.value} selectedRelay=${feedSub.selectedRelay.value} feedSize=${eventRepo.feed.value.size}")
            feedSub.subscribeFeed()
            val myPubkey = getUserPubkey()
            if (myPubkey != null) startup.subscribeDmsAndNotifications(myPubkey)
            if (force) {
                startup.fetchRelayListsForFollows()
            }
        }
    )
    val socialGraphDb = SocialGraphDb(app)
    val extendedNetworkRepo = ExtendedNetworkRepository(
        app, contactRepo, muteRepo, relayListRepo, relayPool, subManager, relayScoreBoard, pubkeyHex, socialGraphDb
    )
    val customEmojiRepo = CustomEmojiRepository(app, pubkeyHex)
    val translationRepo = TranslationRepository()
    val zapPrefs = ZapPreferences(app, pubkeyHex)
    val powPrefs = PowPreferences(app)
    private val processingDispatcher = Dispatchers.Default

    val metadataFetcher = MetadataFetcher(
        relayPool, outboxRouter, subManager, profileRepo, eventRepo,
        viewModelScope, processingDispatcher
    ).also {
        eventRepo.metadataFetcher = it
        it.quoteRelayProvider = {
            relayScoreBoard.getScoredRelays().take(5).map { sr -> sr.url }
        }
    }

    val interfacePrefs = InterfacePreferences(app)
    val nwcRepo = NwcRepository(app, relayPool, pubkeyHex)
    val zapSender = ZapSender(keyRepo, nwcRepo, relayPool, relayListRepo, HttpClientFactory.createRelayClient(), interfacePrefs)
    val powManager = PowManager(powPrefs, relayPool, outboxRouter, eventRepo, viewModelScope)

    // -- Manager classes --
    val feedSub: FeedSubscriptionManager = FeedSubscriptionManager(
        relayPool, outboxRouter, subManager, eventRepo, contactRepo, listRepo, notifRepo,
        extendedNetworkRepo, keyRepo, healthTracker, relayScoreBoard, profileRepo,
        metadataFetcher, viewModelScope, processingDispatcher, pubkeyHex
    )

    val eventRouter: EventRouter = EventRouter(
        relayPool, eventRepo, contactRepo, muteRepo, notifRepo, listRepo, bookmarkRepo,
        bookmarkSetRepo, pinRepo, blossomRepo, customEmojiRepo, relayListRepo, interestRepo, relaySetRepo,
        relayScoreBoard, relayHintStore, keyRepo, dmRepo, extendedNetworkRepo, metadataFetcher,
        getUserPubkey = { getUserPubkey() },
        getSigner = { signer },
        getFeedSubId = { feedSub.feedSubId },
        getRelayFeedSubId = { feedSub.relayFeedSubId },
        getIsTrendingFeed = { feedSub.feedType.value == FeedType.TRENDING },
        onRelayFeedEventReceived = { feedSub.onRelayFeedEventReceived() }
    )

    val socialActions: SocialActionManager = SocialActionManager(
        relayPool, outboxRouter, eventRepo, contactRepo, muteRepo, notifRepo, dmRepo,
        pinRepo, deletedEventsRepo, nwcRepo, customEmojiRepo, zapSender, powPrefs, interfacePrefs, viewModelScope,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val listCrud: ListCrudManager = ListCrudManager(
        relayPool, subManager, eventRepo, listRepo, interestRepo, bookmarkSetRepo, customEmojiRepo,
        metadataFetcher, viewModelScope, processingDispatcher,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val startup: StartupCoordinator = StartupCoordinator(
        relayPool, outboxRouter, subManager, eventRepo, eventPersistence, contactRepo, muteRepo, notifRepo,
        listRepo, bookmarkRepo, bookmarkSetRepo, relaySetRepo, pinRepo, blossomRepo, interestRepo, customEmojiRepo,
        relayListRepo, relayScoreBoard, relayHintStore, healthTracker, keyRepo,
        extendedNetworkRepo, metadataFetcher, profileRepo, relayInfoRepo, nip05Repo,
        nwcRepo, dmRepo, zapPrefs, lifecycleManager, eventRouter, feedSub,
        viewModelScope, processingDispatcher, pubkeyHex,
        getUserPubkey = { getUserPubkey() },
        registerAuthSigner = { registerAuthSigner() },
        fetchMissingEmojiSets = { listCrud.fetchMissingEmojiSets() },
        getSigner = { signer }
    )

    // -- Exposed state --
    val feed: StateFlow<List<NostrEvent>> = combine(
        feedSub.feedType, eventRepo.feed, eventRepo.relayFeed
    ) { type, main, relay ->
        if (type == FeedType.RELAY || type == FeedType.TRENDING) relay else main
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val newNoteCount: StateFlow<Int> = eventRepo.newNoteCount

    // -- New notes button visibility --
    private val settingsPrefs = app.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
    private val _newNotesButtonHidden = MutableStateFlow(
        settingsPrefs.getBoolean("new_notes_button_hidden", false)
    )
    val newNotesButtonHidden: StateFlow<Boolean> = _newNotesButtonHidden

    fun hideNewNotesButton(permanent: Boolean) {
        _newNotesButtonHidden.value = true
        if (permanent) {
            settingsPrefs.edit().putBoolean("new_notes_button_hidden", true).apply()
        }
    }

    fun showNewNotesButton() {
        _newNotesButtonHidden.value = false
        settingsPrefs.edit().putBoolean("new_notes_button_hidden", false).apply()
    }

    val initialLoadDone: StateFlow<Boolean> = feedSub.initialLoadDone
    val initLoadingState: StateFlow<InitLoadingState> = feedSub.initLoadingState
    val feedType: StateFlow<FeedType> = feedSub.feedType
    val selectedRelay: StateFlow<String?> = feedSub.selectedRelay
    val selectedRelaySet: StateFlow<RelaySet?> = feedSub.selectedRelaySet
    val isRefreshing: StateFlow<Boolean> = feedSub.isRefreshing
    val relayFeedStatus: StateFlow<RelayFeedStatus> = feedSub.relayFeedStatus
    val loadingScreenComplete: StateFlow<Boolean> = feedSub.loadingScreenComplete
    val trendingMetric: StateFlow<TrendingMetric> = feedSub.trendingMetric
    val trendingTimeframe: StateFlow<TrendingTimeframe> = feedSub.trendingTimeframe
    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList
    val zapInProgress: StateFlow<Set<String>> = socialActions.zapInProgress
    val zapSuccess: SharedFlow<String> = socialActions.zapSuccess
    val zapError: SharedFlow<String> = socialActions.zapError
    val reactionSent: SharedFlow<Unit> = socialActions.reactionSent

    fun getUserPubkey(): String? = keyRepo.getPubkeyHex()
    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()
    fun translateEvent(eventId: String, content: String) = translationRepo.translate(eventId, content)
    fun queueProfileFetch(pubkey: String) = metadataFetcher.queueProfileFetch(pubkey)
    fun forceProfileFetch(pubkey: String) = metadataFetcher.forceProfileFetch(pubkey)
    fun markLoadingComplete() = feedSub.markLoadingComplete()

    // -- Startup delegates --
    fun initRelays() = startup.initRelays()
    fun resetForAccountSwitch() = startup.resetForAccountSwitch()
    fun reloadForNewAccount() = startup.reloadForNewAccount()
    fun onAppPause() = startup.onAppPause()
    fun onAppResume(pausedMs: Long) = startup.onAppResume(pausedMs)
    fun refreshRelays() = startup.refreshRelays()
    fun refreshDmsAndNotifications() = startup.refreshDmsAndNotifications()
    fun integrateExtendedNetwork() = startup.integrateExtendedNetwork()
    fun refreshNotifRepliesEtag() = startup.refreshNotifRepliesEtag()

    // -- Feed subscription delegates --
    fun setFeedType(type: FeedType) = feedSub.setFeedType(type)
    fun setSelectedRelay(url: String) = feedSub.setSelectedRelay(url)
    fun refreshFeed() = feedSub.refreshFeed()
    fun retryRelayFeed() = feedSub.retryRelayFeed()
    fun loadMore() = feedSub.loadMore()
    fun setTrendingMetric(metric: TrendingMetric) = feedSub.setTrendingMetric(metric)
    fun setTrendingTimeframe(timeframe: TrendingTimeframe) = feedSub.setTrendingTimeframe(timeframe)
    fun pauseEngagement() = feedSub.pauseEngagement()
    fun resumeEngagement() = feedSub.resumeEngagement()

    // -- Relay info delegates --
    fun getRelayUrls(): List<String> = relayPool.getRelayUrls()
    fun getScoredRelays(): List<ScoredRelay> = relayScoreBoard.getScoredRelays()

    fun getRelayCoverageCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((url, count) in relayScoreBoard.getCoverageCounts()) {
            counts[url] = (counts[url] ?: 0) + count
        }
        for ((url, count) in extendedNetworkRepo.getCoverageCounts()) {
            counts[url] = (counts[url] ?: 0) + count
        }
        return counts
    }

    suspend fun probeRelay(input: String): String? {
        // If the caller already provided a full URL, try only that
        if (input.startsWith("ws://") || input.startsWith("wss://")) {
            return if (tryConnect(input)) input else null
        }
        // Bare domain — try both protocols
        val isOnion = input.contains(".onion")
        // .onion relays typically use ws:// (TLS is redundant over Tor), try that first
        if (isOnion) {
            val wsUrl = "ws://$input"
            if (tryConnect(wsUrl)) return wsUrl
            val wssUrl = "wss://$input"
            if (tryConnect(wssUrl)) return wssUrl
        } else {
            val wssUrl = "wss://$input"
            if (tryConnect(wssUrl)) return wssUrl
            val wsUrl = "ws://$input"
            if (tryConnect(wsUrl)) return wsUrl
        }
        return null
    }

    private suspend fun tryConnect(url: String): Boolean {
        val isTor = TorManager.isEnabled()
        val timeoutMs = if (isTor) 15_000L else 4_000L
        val client = HttpClientFactory.createRelayClient()
        val relay = Relay(RelayConfig(url, read = true, write = false), client)
        relay.autoReconnect = false
        return try {
            relay.connect()
            val connected = relay.awaitConnected(timeoutMs = timeoutMs)
            relay.disconnect()
            connected
        } catch (e: Exception) {
            relay.disconnect()
            false
        }
    }

    // -- Social action delegates --
    fun toggleFollow(pubkey: String) = socialActions.toggleFollow(pubkey)
    fun blockUser(pubkey: String) = socialActions.blockUser(pubkey)
    fun unblockUser(pubkey: String) = socialActions.unblockUser(pubkey)
    fun updateMutedWords() = socialActions.updateMutedWords()
    fun sendRepost(event: NostrEvent) = socialActions.sendRepost(event)
    fun sendReaction(event: NostrEvent, content: String = "+") = socialActions.toggleReaction(event, content)
    fun toggleReaction(event: NostrEvent, emoji: String) = socialActions.toggleReaction(event, emoji)
    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false, isPrivate: Boolean = false) = socialActions.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
    fun togglePin(eventId: String) = socialActions.togglePin(eventId)
    fun deleteEvent(eventId: String, kind: Int) = socialActions.deleteEvent(eventId, kind)
    fun followAll(pubkeys: Set<String>) = socialActions.followAll(pubkeys)
    fun muteThread(rootEventId: String) = socialActions.muteThread(rootEventId)

    // -- List CRUD delegates --
    fun createList(name: String, isPrivate: Boolean = false) = listCrud.createList(name, isPrivate)
    fun addToList(dTag: String, pubkey: String) = listCrud.addToList(dTag, pubkey)
    fun removeFromList(dTag: String, pubkey: String) = listCrud.removeFromList(dTag, pubkey)
    fun deleteList(dTag: String) = listCrud.deleteList(dTag)
    fun fetchUserLists(pubkey: String) = listCrud.fetchUserLists(pubkey)
    fun createBookmarkSet(name: String, isPrivate: Boolean = false) = listCrud.createBookmarkSet(name, isPrivate)
    fun addNoteToBookmarkSet(dTag: String, eventId: String) = listCrud.addNoteToBookmarkSet(dTag, eventId)
    fun removeNoteFromBookmarkSet(dTag: String, eventId: String) = listCrud.removeNoteFromBookmarkSet(dTag, eventId)
    fun deleteBookmarkSet(dTag: String) = listCrud.deleteBookmarkSet(dTag)
    fun fetchBookmarkSetEvents(dTag: String) = listCrud.fetchBookmarkSetEvents(dTag)

    fun fetchBookmarkEvents() {
        val ids = bookmarkRepo.getBookmarkedIds().toList()
        if (ids.isEmpty()) return
        val missing = ids.filter { eventRepo.getEvent(it) == null }
        if (missing.isEmpty()) return
        val subId = "fetch-bookmarks"
        val filter = com.wisp.app.nostr.Filter(ids = missing)
        relayPool.sendToTopRelays(com.wisp.app.nostr.ClientMessage.req(subId, filter))
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            eventRepo.bumpEventCacheVersion()
            kotlinx.coroutines.withContext(processingDispatcher) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun toggleBookmark(eventId: String) {
        val s = signer ?: return
        if (bookmarkRepo.isBookmarked(eventId)) {
            bookmarkRepo.removeBookmark(eventId)
        } else {
            bookmarkRepo.addBookmark(eventId)
        }
        publishBookmarkList(s)
    }

    fun removeBookmark(eventId: String) {
        val s = signer ?: return
        bookmarkRepo.removeBookmark(eventId)
        publishBookmarkList(s)
    }

    private fun publishBookmarkList(s: com.wisp.app.nostr.NostrSigner) {
        val ids = bookmarkRepo.getBookmarkedIds()
        val hints = eventRepo.getRelayHintsForEvents(ids)
        val tags = com.wisp.app.nostr.Nip51.buildBookmarkListTags(
            ids,
            bookmarkRepo.getCoordinates(),
            bookmarkRepo.getHashtags(),
            relayHints = hints
        )
        viewModelScope.launch {
            val event = s.signEvent(
                kind = com.wisp.app.nostr.Nip51.KIND_BOOKMARK_LIST,
                content = "",
                tags = tags
            )
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(com.wisp.app.nostr.ClientMessage.event(event))
        }
    }
    fun createEmojiSet(name: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.createEmojiSet(name, emojis)
    fun updateEmojiSet(dTag: String, title: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.updateEmojiSet(dTag, title, emojis)
    fun deleteEmojiSet(dTag: String) = listCrud.deleteEmojiSet(dTag)
    fun publishUserEmojiList(emojis: List<com.wisp.app.nostr.CustomEmoji>, setRefs: List<String>) = listCrud.publishUserEmojiList(emojis, setRefs)
    fun addSetToEmojiList(pubkey: String, dTag: String) = listCrud.addSetToEmojiList(pubkey, dTag)
    fun removeSetFromEmojiList(pubkey: String, dTag: String) = listCrud.removeSetFromEmojiList(pubkey, dTag)

    // -- Interest set CRUD delegates --
    fun followHashtag(tag: String, dTag: String) = listCrud.followHashtag(tag, dTag)
    fun unfollowHashtag(tag: String, dTag: String) = listCrud.unfollowHashtag(tag, dTag)
    fun createInterestSet(name: String) = listCrud.createInterestSet(name)
    fun renameInterestSet(dTag: String, newName: String) = listCrud.renameInterestSet(dTag, newName)
    fun deleteInterestSet(dTag: String) = listCrud.deleteInterestSet(dTag)

    fun setSelectedList(followSet: com.wisp.app.nostr.FollowSet) {
        listRepo.selectList(followSet)
    }

    // -- Relay set delegates --
    fun setSelectedRelaySet(relaySet: RelaySet) = feedSub.setSelectedRelaySet(relaySet)

    fun toggleFavoriteRelay(url: String) {
        val isFav = relaySetRepo.isFavorite(url)
        val updated = if (isFav) relaySetRepo.removeFavorite(url) else relaySetRepo.addFavorite(url)
        publishFavoriteRelays(updated)
    }

    fun addRelayToSet(url: String, dTag: String) {
        val updated = relaySetRepo.addRelayToSet(url, dTag) ?: return
        publishRelaySet(updated)
    }

    fun removeRelayFromSet(url: String, dTag: String) {
        val updated = relaySetRepo.removeRelayFromSet(url, dTag) ?: return
        publishRelaySet(updated)
    }

    fun createRelaySet(name: String, relays: Set<String> = emptySet()) {
        val set = relaySetRepo.createRelaySet(name, relays) ?: return
        publishRelaySet(set)
    }

    fun removeRelaySet(dTag: String) {
        relaySetRepo.removeRelaySet(dTag)
    }

    private fun publishFavoriteRelays(urls: List<String>) {
        val s = signer ?: return
        viewModelScope.launch {
            val tags = Nip51.buildRelaySetTags(urls)
            val event = s.signEvent(
                kind = Nip51.KIND_FAVORITE_RELAYS,
                content = "",
                tags = tags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    private fun publishRelaySet(relaySet: RelaySet) {
        val s = signer ?: return
        viewModelScope.launch {
            val tags = Nip51.buildRelaySetNamedTags(relaySet.dTag, relaySet.relays, relaySet.name)
            val event = s.signEvent(
                kind = Nip51.KIND_RELAY_SET,
                content = "",
                tags = tags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    /**
     * Fetch DM relays (kind 10050) for a pubkey if not already cached.
     * Returns true if the pubkey has DM relays (from cache or fresh fetch).
     */
    suspend fun fetchDmRelaysIfMissing(pubkey: String): Boolean {
        if (relayListRepo.hasDmRelays(pubkey)) return true

        val subId = "zap_dm_relay_${pubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(pubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        val result = withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        if (result != null) {
            relayListRepo.updateDmRelaysFromEvent(result.event)
        }

        return relayListRepo.hasDmRelays(pubkey)
    }

    override fun onCleared() {
        super.onCleared()
        nwcRepo.disconnect()
        relayPool.disconnectAll()
    }
}
