package cooking.zap.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.relay.Relay
import cooking.zap.app.relay.RelayLifecycleManager
import cooking.zap.app.relay.OutboxRouter
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayHealthTracker
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.RelayScoreBoard
import cooking.zap.app.relay.ScoredRelay
import cooking.zap.app.relay.SubscriptionManager
import cooking.zap.app.repo.BlossomRepository
import cooking.zap.app.repo.BookmarkRepository
import cooking.zap.app.repo.BookmarkSetRepository
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.DmRepository
import cooking.zap.app.repo.GroupRepository
import cooking.zap.app.db.EventPersistence
import cooking.zap.app.db.WispObjectBox
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.ExtendedNetworkRepository
import cooking.zap.app.repo.SocialGraphDb
import cooking.zap.app.repo.Nip05Repository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.InterestRepository
import cooking.zap.app.repo.ListRepository
import cooking.zap.app.repo.MetadataFetcher
import cooking.zap.app.repo.DeletedEventsRepository
import cooking.zap.app.repo.LiveStreamRepository
import cooking.zap.app.repo.MuteRepository
import cooking.zap.app.repo.NotificationRepository
import cooking.zap.app.repo.PinRepository
import cooking.zap.app.repo.ProfileRepository
import cooking.zap.app.repo.NwcRepository
import cooking.zap.app.repo.SparkRepository
import cooking.zap.app.repo.WalletMode
import cooking.zap.app.repo.WalletModeRepository
import cooking.zap.app.repo.WalletProvider
import cooking.zap.app.repo.CustomEmojiRepository
import cooking.zap.app.repo.InterfacePreferences
import cooking.zap.app.repo.PowPreferences
import cooking.zap.app.repo.SafetyPreferences
import cooking.zap.app.repo.ZapPreferences
import cooking.zap.app.repo.RelayHintStore
import cooking.zap.app.repo.RelayInfoRepository
import cooking.zap.app.repo.TranslationRepository
import cooking.zap.app.repo.RelayListRepository
import cooking.zap.app.repo.RelaySetRepository
import cooking.zap.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.RelaySet
import android.content.Context
import cooking.zap.app.nostr.Filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOR_YOU, FOLLOWS, EXTENDED_FOLLOWS, RELAY, LIST, TRENDING }

enum class TrendingMode { NOTES, USERS }

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

const val TRENDING_USERS_RELAY_URL = "wss://feeds.nostrarchives.com/users/upandcoming"

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

    /** True when the account has a local private key (i.e. not a remote/NIP-07 signer).
     *  DIP-03 private zaps require local signing for both ephemeral derivation and
     *  ECDH decryption, so this gates the UI toggle. */
    val hasLocalKeypair: Boolean = keyRepo.getKeypair() != null

    var signer: NostrSigner? = null
        private set

    /**
     * Transient hand-off for "Save" from a Cheffy structured recipe reply
     * (concern 2.3c): the raw recipe markdown to pre-fill [RecipeComposeScreen].
     * Set on Save, consumed **once** by the compose route (read-then-null), so an
     * abandoned hand-off can't leak into a later FAB-launched empty compose. Not
     * persisted (no draft store — v1 deliberately has none).
     */
    var pendingComposeMarkdown: String? = null

    fun setSigner(s: NostrSigner) {
        signer = s
        zapSender.signer = s
        registerAuthSigner()
    }

    fun clearSigner() {
        signer = null
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

    val relayPool = RelayPool(
        prefs = app.getSharedPreferences("relay_auth_prefs_$pubkeyHex", android.content.Context.MODE_PRIVATE)
    )
    val healthTracker = RelayHealthTracker(app, pubkeyHex)
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app, pubkeyHex)
    val nip05Repo = Nip05Repository()
    val relayHintStore = RelayHintStore(app)
    val deletedEventsRepo = DeletedEventsRepository(app, pubkeyHex)
    val eventPersistence: EventPersistence? = if (WispObjectBox.isInitialized) {
        EventPersistence(pubkeyHex)
    } else null
    val dmPersistence: cooking.zap.app.db.DmPersistence? = if (WispObjectBox.isInitialized) {
        cooking.zap.app.db.DmPersistence()
    } else null
    val eventRepo = EventRepository(profileRepo, muteRepo, relayHintStore).also {
        it.currentUserPubkey = pubkeyHex
        it.deletedEventsRepo = deletedEventsRepo
        it.eventPersistence = eventPersistence
        it.keyRepo = keyRepo
    }
    val contactRepo = ContactRepository(app, pubkeyHex).also {
        eventRepo.contactRepo = it
    }
    val listRepo = ListRepository(app, pubkeyHex)
    val dmRepo = DmRepository(app, pubkeyHex, dmPersistence)
    val groupRepo = GroupRepository(app, pubkeyHex)
    val liveStreamRepo = LiveStreamRepository()
    val notifRepo = NotificationRepository(app, pubkeyHex, muteRepo, eventRepo)
    val relayListRepo = RelayListRepository(app)
    val bookmarkRepo = BookmarkRepository(app, pubkeyHex)
    val bookmarkSetRepo = BookmarkSetRepository(app, pubkeyHex)
    val relaySetRepo = RelaySetRepository(app, pubkeyHex)
    val pinRepo = PinRepository(app, pubkeyHex)
    val blossomRepo = BlossomRepository(app, pubkeyHex)
    val interestRepo = InterestRepository(app, pubkeyHex, deletedEventsRepo)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo, pubkeyHex)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayHintStore, relayScoreBoard)
    val subManager = SubscriptionManager(relayPool)
    val lifecycleManager = RelayLifecycleManager(
        context = app,
        relayPool = relayPool,
        scope = viewModelScope,
        onReconnected = { force ->
            Log.d("RLC", "[FeedVM] onReconnected(force=$force) feedType=${feedSub.feedType.value} selectedRelay=${feedSub.selectedRelay.value} feedSize=${eventRepo.feed.value.size}")
            feedSub.subscribeFeed()
            val myPubkey = getUserPubkey()
            if (myPubkey != null) startup.subscribeDmsAndNotifications(myPubkey)
            if (force) {
                startup.fetchRelayListsForFollows()
                // After a long pause, NIP-51 lists (bookmarks, hashtag sets, media servers, etc.)
                // may be stale. Re-fetch self-data so they're up to date.
                startup.refreshSelfData()
            }
            onGroupReconnect?.invoke()
        }
    )
    val socialGraphDb = SocialGraphDb(app, pubkeyHex)
    val extendedNetworkRepo = ExtendedNetworkRepository(
        app, contactRepo, muteRepo, relayListRepo, relayPool, subManager, relayScoreBoard, pubkeyHex, socialGraphDb
    )
    val safetyPrefs = SafetyPreferences(app, pubkeyHex)
    val spamAuthorCache = cooking.zap.app.repo.SpamAuthorCache()
    @Volatile
    var nspamClassifier: cooking.zap.app.ml.NSpamClassifier? = null
        private set

    init {
        eventRepo.safetyPrefs = safetyPrefs
        eventRepo.extendedNetworkRepo = extendedNetworkRepo
        notifRepo.spamAuthorCache = spamAuthorCache
        notifRepo.safetyPrefs = safetyPrefs
        notifRepo.contactRepo = contactRepo
        notifRepo.extendedNetworkRepo = extendedNetworkRepo
        viewModelScope.launch(Dispatchers.Default) {
            nspamClassifier = try {
                val weights = cooking.zap.app.ml.NSpamWeights.loadFromAssets(app)
                cooking.zap.app.ml.NSpamClassifier(weights)
            } catch (e: Exception) {
                Log.e("FeedVM", "Failed to load nspam weights", e)
                null
            }
            notifRepo.spamClassifier = nspamClassifier
        }
    }
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

    // Recipe reads (kind 30023 #t zapcooking/nostrcooking) target the
    // Reads a widened relay union (articles ∪ indexers ∪ DEFAULTS read ∪ the
    // user's kind-10002 read relays) — see RecipeRepository.readRelays(). Owns
    // the recipe feed flow shared by the home feed + recipe-detail screens.
    val recipeRepo = cooking.zap.app.repo.RecipeRepository(
        relayPool, eventRepo, subManager, viewModelScope, processingDispatcher,
        // Widen the recipe read union with the signed-in user's own kind-10002
        // READ relays (coverage), de-duped inside the repo.
        userReadRelaysProvider = { pubkeyHex?.let { relayListRepo.getReadRelays(it) } ?: emptyList() },
    )

    /** Read-only Recipe Packs listing repository (PR A). */
    val recipePackRepo = cooking.zap.app.repo.RecipePackRepository(
        relayPool = relayPool,
        outboxRouter = outboxRouter,
        eventRepo = eventRepo,
        subManager = subManager,
        scope = viewModelScope,
        processingContext = processingDispatcher,
        userReadRelaysProvider = { pubkeyHex?.let { relayListRepo.getReadRelays(it) } ?: emptyList() },
        userPubkeyProvider = { getUserPubkey() },
    )

    /**
     * A14 canonical recipe bookmarks — the kind-30001 `nostrcooking-bookmarks`
     * list shared with the web client (separate from generic note bookmarks).
     */
    val recipeBookmarkRepo = cooking.zap.app.repo.RecipeBookmarkRepository(
        relayPool = relayPool,
        outboxRouter = outboxRouter,
        eventRepo = eventRepo,
        subManager = subManager,
        scope = viewModelScope,
        processingContext = processingDispatcher,
        userReadRelaysProvider = { pubkeyHex?.let { relayListRepo.getReadRelays(it) } ?: emptyList() },
        userPubkeyProvider = { getUserPubkey() },
        signerProvider = { signer },
    )

    /** zap.cooking backend client (membership today; Phase 2 AI endpoints). */
    val zapCookingApi = cooking.zap.app.api.ZapCookingApi()

    /** Recipe create spine — sign + publish kind-30023 (Sous Chef Save, concern 2.2). */
    val recipePublisher = cooking.zap.app.repo.RecipePublisher(relayPool, eventRepo, blossomRepo)

    /** Nourish health scores — auth'd read from the Pantry relay (concern 2.4a). */
    val nourishRepo = cooking.zap.app.repo.NourishRepository(relayPool)

    val interfacePrefs = InterfacePreferences(app)
    val nwcRepo = NwcRepository(app, relayPool, pubkeyHex)
    val sparkRepo = SparkRepository(app, pubkeyHex)
    val walletModeRepo = WalletModeRepository(app, pubkeyHex)

    val activeWalletProvider: WalletProvider
        get() = when (walletModeRepo.getMode()) {
            WalletMode.SPARK -> sparkRepo
            else -> nwcRepo
        }

    val zapSender = ZapSender(keyRepo, { activeWalletProvider }, relayPool, relayListRepo, HttpClientFactory.createRelayClient(), interfacePrefs)
    val powManager = PowManager(powPrefs, relayPool, outboxRouter, eventRepo, viewModelScope)

    // -- Manager classes --
    val feedSub: FeedSubscriptionManager = FeedSubscriptionManager(
        relayPool, outboxRouter, subManager, eventRepo, contactRepo, listRepo, notifRepo,
        extendedNetworkRepo, interestRepo, keyRepo, healthTracker, relayScoreBoard, profileRepo,
        metadataFetcher, viewModelScope, processingDispatcher, pubkeyHex,
        getApplication<Application>().getSharedPreferences("wisp_feed", android.content.Context.MODE_PRIVATE)
    )

    val eventRouter: EventRouter = EventRouter(
        relayPool, eventRepo, contactRepo, muteRepo, notifRepo, listRepo, bookmarkRepo,
        recipeBookmarkRepo,
        bookmarkSetRepo, pinRepo, blossomRepo, customEmojiRepo, relayListRepo, interestRepo, relaySetRepo,
        relayScoreBoard, relayHintStore, keyRepo, dmRepo, extendedNetworkRepo, groupRepo, liveStreamRepo, metadataFetcher,
        getUserPubkey = { getUserPubkey() },
        getSigner = { signer },
        getFeedSubId = { feedSub.feedSubId },
        getRelayFeedSubId = { feedSub.relayFeedSubId },
        getIsTrendingFeed = { feedSub.feedType.value == FeedType.TRENDING },
        onRelayFeedEventReceived = { feedSub.onRelayFeedEventReceived() }
    )

    val socialActions: SocialActionManager = SocialActionManager(
        relayPool, outboxRouter, eventRepo, contactRepo, muteRepo, notifRepo, dmRepo,
        pinRepo, deletedEventsRepo, { activeWalletProvider }, customEmojiRepo, zapSender, powPrefs, interfacePrefs,
        relayListRepo, viewModelScope,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val listCrud: ListCrudManager = ListCrudManager(
        relayPool, subManager, eventRepo, listRepo, interestRepo, bookmarkSetRepo, customEmojiRepo,
        metadataFetcher, outboxRouter, viewModelScope, processingDispatcher,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val startup: StartupCoordinator = StartupCoordinator(
        relayPool, outboxRouter, subManager, eventRepo, eventPersistence, contactRepo, muteRepo, notifRepo,
        listRepo, bookmarkRepo, bookmarkSetRepo, relaySetRepo, pinRepo, blossomRepo, deletedEventsRepo, interestRepo, customEmojiRepo,
        relayListRepo, relayScoreBoard, relayHintStore, healthTracker, keyRepo,
        extendedNetworkRepo, metadataFetcher, profileRepo, relayInfoRepo, nip05Repo,
        nwcRepo, sparkRepo, walletModeRepo, dmRepo, liveStreamRepo, zapPrefs, lifecycleManager, eventRouter, feedSub,
        viewModelScope, processingDispatcher, pubkeyHex,
        getUserPubkey = { getUserPubkey() },
        registerAuthSigner = { registerAuthSigner() },
        fetchEmojiSets = { listCrud.fetchEmojiSets() },
        getSigner = { signer },
        migrateRecipeBookmarks = { migrateRecipeBookmarksIfNeeded() }
    )

    // -- Global online count from nostrarchives live-metrics --
    private val _globalOnlineCount = MutableStateFlow<Int?>(null)
    val globalOnlineCount: StateFlow<Int?> = _globalOnlineCount
    private var liveMetricsSocket: okhttp3.WebSocket? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            connectLiveMetrics()
        }
    }

    private fun connectLiveMetrics() {
        val client = HttpClientFactory.createRelayClient()
        val req = okhttp3.Request.Builder()
            .url("wss://api.nostrarchives.com/v1/ws/live-metrics")
            .build()
        liveMetricsSocket = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        as? kotlinx.serialization.json.JsonObject ?: return
                    val online = obj["online"]?.jsonPrimitive?.intOrNull ?: return
                    _globalOnlineCount.value = online
                } catch (_: Exception) {}
            }
        })
    }

    // -- Exposed state --
    val feed: StateFlow<List<NostrEvent>> = combine(
        feedSub.feedType, eventRepo.feed, eventRepo.relayFeed
    ) { type, main, relay ->
        if (type == FeedType.RELAY || type == FeedType.TRENDING) relay else main
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liveNowStreams: StateFlow<List<cooking.zap.app.repo.LiveStream>> = liveStreamRepo.liveStreams
        .map { streams ->
            streams.values
                .filter { it.chatters.isNotEmpty() }
                .sortedByDescending { it.chatters.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    val trendingMode: StateFlow<TrendingMode> = feedSub.trendingMode
    val trendingUsers: StateFlow<List<cooking.zap.app.nostr.ProfileData>> = feedSub.trendingUsers
    val trendingUsersLoading: StateFlow<Boolean> = feedSub.trendingUsersLoading
    val feedContentFilter: StateFlow<FeedContentFilter> = feedSub.feedContentFilter
    val selectedList: StateFlow<cooking.zap.app.nostr.FollowSet?> = listRepo.selectedList
    val zapInProgress: StateFlow<Set<String>> = socialActions.zapInProgress
    val zapSuccess: SharedFlow<String> = socialActions.zapSuccess
    val zapError: SharedFlow<String> = socialActions.zapError
    val reactionSent: SharedFlow<Unit> = socialActions.reactionSent

    suspend fun payInvoice(bolt11: String): Boolean =
        activeWalletProvider.payInvoice(bolt11).isSuccess
    val pendingFirstFollow: StateFlow<String?> = socialActions.pendingFirstFollow
    val firstFollowCheckDone: StateFlow<Boolean> = socialActions.firstFollowCheckDone

    fun getUserPubkey(): String? = keyRepo.getPubkeyHex()
    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()
    fun translateEvent(eventId: String, content: String) = translationRepo.translate(eventId, content)
    fun queueProfileFetch(pubkey: String) = metadataFetcher.queueProfileFetch(pubkey)
    fun forceProfileFetch(pubkey: String) = metadataFetcher.forceProfileFetch(pubkey)
    fun markLoadingComplete() = feedSub.markLoadingComplete()

    // -- Startup delegates --
    fun initRelays() = startup.initRelays()
    fun resetForAccountSwitch() {
        startup.resetForAccountSwitch()
        groupRepo.clear()
        liveStreamRepo.clear()
        recipeBookmarkRepo.reset()
    }
    fun reloadForNewAccount() {
        safetyPrefs.reload(getUserPubkey())
        startup.reloadForNewAccount()
        groupRepo.reload(getUserPubkey())
        recipeBookmarkRepo.load(getUserPubkey())
    }

    /** Cache-first paint + relay refresh of the canonical recipe-bookmark list. */
    fun loadRecipeBookmarks() {
        recipeBookmarkRepo.paintFromCache()
        recipeBookmarkRepo.load()
    }

    /**
     * Toggle a recipe's bookmark in the canonical kind-30001 list by coordinate.
     * Resolves the event by id (recipe surfaces pass the recipe event id); a
     * no-op for non-recipe events or read-only accounts.
     */
    fun toggleRecipeBookmark(eventId: String) {
        val event = eventRepo.getEvent(eventId) ?: return
        // Off the Main dispatcher — toggle() reads ObjectBox and signs the event.
        viewModelScope.launch(processingDispatcher) { recipeBookmarkRepo.toggle(event) }
    }

    /**
     * A14 PR 2 — one-time, one-shot background migration of legacy kind-10003
     * recipe bookmarks into the canonical kind-30001 list, so existing Android
     * bookmarks sync to web. Idempotent: guarded by a per-user persisted flag
     * (mirrors KeyRepository.migrateRemoveRemoteSigner). ADD-ONLY — the legacy
     * 10003 list is never mutated (PR 1's read-union dedups by coordinate, so no
     * double-display and no bookmark can be stranded).
     */
    fun migrateRecipeBookmarksIfNeeded() {
        val s = signer ?: return
        val prefs = getApplication<Application>().getSharedPreferences(
            "wisp_recipe_bookmark_migration_${s.pubkeyHex}", android.content.Context.MODE_PRIVATE
        )
        if (prefs.getBoolean("recipe_bookmarks_migrated_v1", false)) return
        viewModelScope.launch(processingDispatcher) {
            try {
                // Let a freshly-synced 10003 list land before reading legacy ids;
                // persisted ids (the common case) are already available.
                kotlinx.coroutines.delay(2_000)
                val legacyIds = bookmarkRepo.getBookmarkedIds()
                if (legacyIds.isNotEmpty()) {
                    val outcome = recipeBookmarkRepo.migrateLegacyBookmarks(legacyIds)
                    if (outcome.added.isNotEmpty()) {
                        Log.d("FeedVM", "Recipe bookmark migration: added ${outcome.added.size} canonical coordinate(s)")
                    }
                    // Don't burn the one-shot flag if relays were unreachable for
                    // un-cached ids — retry on a later launch so nothing is stranded.
                    if (!outcome.complete) {
                        Log.d("FeedVM", "Recipe bookmark migration incomplete (relays unreachable); will retry next launch")
                        return@launch
                    }
                }
                prefs.edit().putBoolean("recipe_bookmarks_migrated_v1", true).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the flag unset so the migration retries on a later launch.
                Log.w("FeedVM", "Recipe bookmark migration failed; will retry next launch", e)
            }
        }
    }
    /** Called after relay reconnect to re-subscribe notified group channels. */
    var onGroupReconnect: (() -> Unit)? = null
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
    fun onVisibleRangeChanged(first: Int, last: Int) = feedSub.onViewportChanged(first, last)
    fun setTrendingMetric(metric: TrendingMetric) = feedSub.setTrendingMetric(metric)
    fun setTrendingTimeframe(timeframe: TrendingTimeframe) = feedSub.setTrendingTimeframe(timeframe)
    fun setTrendingMode(mode: TrendingMode) = feedSub.setTrendingMode(mode)
    fun setFeedContentFilter(filter: FeedContentFilter) = feedSub.setFeedContentFilter(filter)
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
        // Bare domain — try wss:// first, fall back to ws://
        val wssUrl = "wss://$input"
        if (tryConnect(wssUrl)) return wssUrl
        val wsUrl = "ws://$input"
        if (tryConnect(wsUrl)) return wsUrl
        return null
    }

    private suspend fun tryConnect(url: String): Boolean {
        val client = HttpClientFactory.createRelayClient()
        val relay = Relay(RelayConfig(url, read = true, write = false), client)
        relay.autoReconnect = false
        return try {
            relay.connect()
            val connected = relay.awaitConnected(timeoutMs = 4_000L)
            relay.disconnect()
            connected
        } catch (e: Exception) {
            relay.disconnect()
            false
        }
    }

    // -- Social action delegates --
    fun toggleFollow(pubkey: String) = socialActions.toggleFollow(pubkey)
    fun confirmFirstFollow() = socialActions.confirmFirstFollow()
    fun dismissFirstFollow() = socialActions.dismissFirstFollow()
    fun blockUser(pubkey: String) = socialActions.blockUser(pubkey)
    fun unblockUser(pubkey: String) = socialActions.unblockUser(pubkey)
    fun updateMutedWords() = socialActions.updateMutedWords()
    fun sendRepost(event: NostrEvent) = socialActions.sendRepost(event)
    fun sendReaction(event: NostrEvent, content: String = "+") = socialActions.toggleReaction(event, content)
    fun toggleReaction(event: NostrEvent, emoji: String) = socialActions.toggleReaction(event, emoji)
    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false, isPrivate: Boolean = false, extraRelayHints: List<String> = emptyList(), recipientOverride: String? = null, eventATag: String? = null) = socialActions.sendZap(event, amountMsats, message, isAnonymous, isPrivate, extraRelayHints, recipientOverride, eventATag)
    fun togglePin(eventId: String) = socialActions.togglePin(eventId)
    fun deleteEvent(eventId: String, kind: Int) = socialActions.deleteEvent(eventId, kind)
    fun followAll(pubkeys: Set<String>) = socialActions.followAll(pubkeys)
    fun muteThread(rootEventId: String) = socialActions.muteThread(rootEventId)
    fun publishPollVote(pollEventId: String, optionIds: List<String>) = socialActions.publishPollVote(pollEventId, optionIds)
    fun sendZapPollVote(pollEvent: NostrEvent, optionIndex: Int, amountMsats: Long, message: String = "", isAnonymous: Boolean = false) =
        socialActions.sendZapPollVote(pollEvent, optionIndex, amountMsats, message, isAnonymous)

    /** Publish a NIP-38 user status (kind 30315). Empty string clears the status. */
    fun publishUserStatus(status: String) {
        val pubkey = pubkeyHex ?: return
        // Optimistic local update so UI feels instant (before signer check so the
        // UI always responds, even if the signer is momentarily unavailable after
        // process-death recovery).
        eventRepo.setUserStatus(pubkey, status.ifBlank { null })
        val s = signer ?: return
        viewModelScope.launch {
            val tags = mutableListOf(listOf("d", "general"))
            val event = s.signEvent(kind = 30315, content = status, tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            eventRepo.addEvent(event)
        }
    }

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
        val filter = cooking.zap.app.nostr.Filter(ids = missing)
        relayPool.sendToTopRelays(cooking.zap.app.nostr.ClientMessage.req(subId, filter))
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

    private fun publishBookmarkList(s: cooking.zap.app.nostr.NostrSigner) {
        val ids = bookmarkRepo.getBookmarkedIds()
        val hints = eventRepo.getRelayHintsForEvents(ids)
        val tags = cooking.zap.app.nostr.Nip51.buildBookmarkListTags(
            ids,
            bookmarkRepo.getCoordinates(),
            bookmarkRepo.getHashtags(),
            relayHints = hints
        )
        viewModelScope.launch {
            val event = s.signEvent(
                kind = cooking.zap.app.nostr.Nip51.KIND_BOOKMARK_LIST,
                content = "",
                tags = tags
            )
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(cooking.zap.app.nostr.ClientMessage.event(event))
        }
    }
    fun createEmojiSet(name: String, emojis: List<cooking.zap.app.nostr.CustomEmoji>) = listCrud.createEmojiSet(name, emojis)
    fun updateEmojiSet(dTag: String, title: String, emojis: List<cooking.zap.app.nostr.CustomEmoji>) = listCrud.updateEmojiSet(dTag, title, emojis)
    fun deleteEmojiSet(dTag: String) = listCrud.deleteEmojiSet(dTag)
    fun publishUserEmojiList(emojis: List<cooking.zap.app.nostr.CustomEmoji>, setRefs: List<String>) = listCrud.publishUserEmojiList(emojis, setRefs)
    fun addSetToEmojiList(pubkey: String, dTag: String) = listCrud.addSetToEmojiList(pubkey, dTag)
    fun removeSetFromEmojiList(pubkey: String, dTag: String) = listCrud.removeSetFromEmojiList(pubkey, dTag)

    // -- Interest set CRUD delegates --
    fun followHashtag(tag: String, dTag: String) = listCrud.followHashtag(tag, dTag)
    fun followHashtags(tags: Set<String>, dTag: String, setTitle: String? = null) =
        listCrud.followHashtags(tags, dTag, setTitle)
    fun unfollowHashtag(tag: String, dTag: String) = listCrud.unfollowHashtag(tag, dTag)
    fun createInterestSet(name: String) = listCrud.createInterestSet(name)
    fun renameInterestSet(dTag: String, newName: String) = listCrud.renameInterestSet(dTag, newName)
    fun deleteInterestSet(dTag: String) = listCrud.deleteInterestSet(dTag)

    private val _interestSetsFetched = MutableStateFlow(false)
    val interestSetsFetched: StateFlow<Boolean> = _interestSetsFetched

    private val _hashtagPickerRequested = MutableStateFlow(false)
    val hashtagPickerRequested: StateFlow<Boolean> = _hashtagPickerRequested
    fun requestHashtagPicker() { _hashtagPickerRequested.value = true }
    fun clearHashtagPickerRequest() { _hashtagPickerRequested.value = false }

    /**
     * Fetch interest sets (kind 30015) from relays if none are cached locally.
     * Called when entering hashtag feed to ensure we have up-to-date data.
     */
    suspend fun fetchInterestSetsIfMissing() {
        if (interestRepo.sets.value.isNotEmpty()) {
            _interestSetsFetched.value = true
            return
        }
        val myPubkey = getUserPubkey() ?: run {
            _interestSetsFetched.value = true
            return
        }

        val subId = "interest_fetch_${myPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_INTEREST_SET),
            authors = listOf(myPubkey),
            limit = 50
        )

        // Query own write (outbox) relays first, with fallback to all connected relays
        outboxRouter.subscribeToUserWriteRelays(subId, myPubkey, filter)
        // Also try indexer relays as safety net
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }

        // Wait for events or timeout
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        relayPool.closeOnAllRelays(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        _interestSetsFetched.value = true
    }

    fun setSelectedList(followSet: cooking.zap.app.nostr.FollowSet) {
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
        sparkRepo.disconnect()
        relayPool.disconnectAll()
        liveMetricsSocket?.close(1000, null)
        notifRepo.shutdown()
    }
}
