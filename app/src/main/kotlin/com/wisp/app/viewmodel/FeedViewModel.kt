package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
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
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.SocialGraphDb
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class FeedType { FOLLOWS, EXTENDED_FOLLOWS, RELAY, LIST }

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

    var signer: NostrSigner? = keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) }
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
    val eventRepo = EventRepository(profileRepo, muteRepo, relayHintStore).also {
        it.currentUserPubkey = pubkeyHex
        it.deletedEventsRepo = deletedEventsRepo
    }
    val contactRepo = ContactRepository(app, pubkeyHex)
    val listRepo = ListRepository(app, pubkeyHex)
    val dmRepo = DmRepository(app, pubkeyHex)
    val notifRepo = NotificationRepository(app, pubkeyHex)
    val relayListRepo = RelayListRepository(app)
    val bookmarkRepo = BookmarkRepository(app, pubkeyHex)
    val bookmarkSetRepo = BookmarkSetRepository(app, pubkeyHex)
    val pinRepo = PinRepository(app, pubkeyHex)
    val blossomRepo = BlossomRepository(app, pubkeyHex)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo, pubkeyHex)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayHintStore)
    val subManager = SubscriptionManager(relayPool)
    val lifecycleManager = RelayLifecycleManager(
        context = app,
        relayPool = relayPool,
        scope = viewModelScope,
        onReconnected = { force ->
            Log.d("RLC", "[FeedVM] onReconnected(force=$force) feedType=${feedSub.feedType.value} selectedRelay=${feedSub.selectedRelay.value} feedSize=${eventRepo.feed.value.size}")
            feedSub.subscribeFeed()
            if (force) {
                startup.fetchRelayListsForFollows()
                val myPubkey = getUserPubkey()
                if (myPubkey != null) startup.subscribeDmsAndNotifications(myPubkey)
            }
        }
    )
    val socialGraphDb = SocialGraphDb(app)
    val extendedNetworkRepo = ExtendedNetworkRepository(
        app, contactRepo, muteRepo, relayListRepo, relayPool, subManager, relayScoreBoard, pubkeyHex, socialGraphDb
    )
    val customEmojiRepo = CustomEmojiRepository(app, pubkeyHex)
    val zapPrefs = ZapPreferences(app, pubkeyHex)
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

    val nwcRepo = NwcRepository(app, relayPool, pubkeyHex)
    val zapSender = ZapSender(keyRepo, nwcRepo, relayPool, relayListRepo, Relay.createClient())

    // -- Manager classes --
    val feedSub: FeedSubscriptionManager = FeedSubscriptionManager(
        relayPool, outboxRouter, subManager, eventRepo, contactRepo, listRepo, notifRepo,
        extendedNetworkRepo, keyRepo, healthTracker, metadataFetcher,
        viewModelScope, processingDispatcher, pubkeyHex
    )

    val eventRouter: EventRouter = EventRouter(
        relayPool, eventRepo, contactRepo, muteRepo, notifRepo, listRepo, bookmarkRepo,
        bookmarkSetRepo, pinRepo, blossomRepo, customEmojiRepo, relayListRepo,
        relayScoreBoard, relayHintStore, keyRepo, extendedNetworkRepo, metadataFetcher,
        getUserPubkey = { getUserPubkey() },
        getSigner = { signer },
        getFeedSubId = { feedSub.feedSubId },
        onRelayFeedEventReceived = { feedSub.onRelayFeedEventReceived() }
    )

    val socialActions: SocialActionManager = SocialActionManager(
        relayPool, outboxRouter, eventRepo, contactRepo, muteRepo, notifRepo, dmRepo,
        pinRepo, deletedEventsRepo, nwcRepo, customEmojiRepo, zapSender, viewModelScope,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val listCrud: ListCrudManager = ListCrudManager(
        relayPool, subManager, eventRepo, listRepo, bookmarkSetRepo, customEmojiRepo,
        metadataFetcher, viewModelScope, processingDispatcher,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val startup: StartupCoordinator = StartupCoordinator(
        relayPool, outboxRouter, subManager, eventRepo, contactRepo, muteRepo, notifRepo,
        listRepo, bookmarkRepo, bookmarkSetRepo, pinRepo, blossomRepo, customEmojiRepo,
        relayListRepo, relayScoreBoard, relayHintStore, healthTracker, keyRepo,
        extendedNetworkRepo, metadataFetcher, profileRepo, relayInfoRepo, nip05Repo,
        nwcRepo, dmRepo, zapPrefs, lifecycleManager, eventRouter, feedSub,
        viewModelScope, processingDispatcher, pubkeyHex,
        getUserPubkey = { getUserPubkey() },
        registerAuthSigner = { registerAuthSigner() },
        fetchMissingEmojiSets = { listCrud.fetchMissingEmojiSets() }
    )

    // -- Exposed state --
    val feed: StateFlow<List<NostrEvent>> = eventRepo.feed
    val newNoteCount: StateFlow<Int> = eventRepo.newNoteCount
    val initialLoadDone: StateFlow<Boolean> = feedSub.initialLoadDone
    val initLoadingState: StateFlow<InitLoadingState> = feedSub.initLoadingState
    val feedType: StateFlow<FeedType> = feedSub.feedType
    val selectedRelay: StateFlow<String?> = feedSub.selectedRelay
    val relayFeedStatus: StateFlow<RelayFeedStatus> = feedSub.relayFeedStatus
    val loadingScreenComplete: StateFlow<Boolean> = feedSub.loadingScreenComplete
    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList
    val zapInProgress: StateFlow<Set<String>> = socialActions.zapInProgress
    val zapSuccess: SharedFlow<String> = socialActions.zapSuccess
    val zapError: SharedFlow<String> = socialActions.zapError

    fun getUserPubkey(): String? = keyRepo.getPubkeyHex()
    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()
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

    // -- Feed subscription delegates --
    fun setFeedType(type: FeedType) = feedSub.setFeedType(type)
    fun setSelectedRelay(url: String) = feedSub.setSelectedRelay(url)
    fun retryRelayFeed() = feedSub.retryRelayFeed()
    fun loadMore() = feedSub.loadMore()
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

    // -- Social action delegates --
    fun toggleFollow(pubkey: String) = socialActions.toggleFollow(pubkey)
    fun blockUser(pubkey: String) = socialActions.blockUser(pubkey)
    fun unblockUser(pubkey: String) = socialActions.unblockUser(pubkey)
    fun updateMutedWords() = socialActions.updateMutedWords()
    fun sendRepost(event: NostrEvent) = socialActions.sendRepost(event)
    fun sendReaction(event: NostrEvent, content: String = "+") = socialActions.toggleReaction(event, content)
    fun toggleReaction(event: NostrEvent, emoji: String) = socialActions.toggleReaction(event, emoji)
    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "") = socialActions.sendZap(event, amountMsats, message)
    fun togglePin(eventId: String) = socialActions.togglePin(eventId)
    fun deleteEvent(eventId: String, kind: Int) = socialActions.deleteEvent(eventId, kind)
    fun followAll(pubkeys: Set<String>) = socialActions.followAll(pubkeys)

    // -- List CRUD delegates --
    fun createList(name: String) = listCrud.createList(name)
    fun addToList(dTag: String, pubkey: String) = listCrud.addToList(dTag, pubkey)
    fun removeFromList(dTag: String, pubkey: String) = listCrud.removeFromList(dTag, pubkey)
    fun deleteList(dTag: String) = listCrud.deleteList(dTag)
    fun fetchUserLists(pubkey: String) = listCrud.fetchUserLists(pubkey)
    fun createBookmarkSet(name: String) = listCrud.createBookmarkSet(name)
    fun addNoteToBookmarkSet(dTag: String, eventId: String) = listCrud.addNoteToBookmarkSet(dTag, eventId)
    fun removeNoteFromBookmarkSet(dTag: String, eventId: String) = listCrud.removeNoteFromBookmarkSet(dTag, eventId)
    fun deleteBookmarkSet(dTag: String) = listCrud.deleteBookmarkSet(dTag)
    fun fetchBookmarkSetEvents(dTag: String) = listCrud.fetchBookmarkSetEvents(dTag)
    fun createEmojiSet(name: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.createEmojiSet(name, emojis)
    fun updateEmojiSet(dTag: String, title: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.updateEmojiSet(dTag, title, emojis)
    fun deleteEmojiSet(dTag: String) = listCrud.deleteEmojiSet(dTag)
    fun publishUserEmojiList(emojis: List<com.wisp.app.nostr.CustomEmoji>, setRefs: List<String>) = listCrud.publishUserEmojiList(emojis, setRefs)
    fun addSetToEmojiList(pubkey: String, dTag: String) = listCrud.addSetToEmojiList(pubkey, dTag)
    fun removeSetFromEmojiList(pubkey: String, dTag: String) = listCrud.removeSetFromEmojiList(pubkey, dTag)

    fun setSelectedList(followSet: com.wisp.app.nostr.FollowSet) {
        listRepo.selectList(followSet)
        outboxRouter.requestMissingRelayLists(followSet.members.toList())
    }

    override fun onCleared() {
        super.onCleared()
        nwcRepo.disconnect()
        relayPool.disconnectAll()
    }
}
