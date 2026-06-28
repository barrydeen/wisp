package cooking.zap.app

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import cooking.zap.app.ui.screen.BroadcastStatusBar
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.NostrUriData
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.ProfileData
import android.app.Activity
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.nostr.RemoteSigner
import cooking.zap.app.nostr.SignResult
import cooking.zap.app.nostr.SignerIntentBridge
import cooking.zap.app.repo.SigningMode
import android.content.Context
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import cooking.zap.app.ui.component.HapticHelper
import cooking.zap.app.ui.component.NotifBlipSound
import cooking.zap.app.ui.component.BottomTab
import cooking.zap.app.ui.component.WispBottomBar
import cooking.zap.app.ui.component.WispDrawerContent
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import cooking.zap.app.ui.component.ZapDialog
import cooking.zap.app.ui.component.pendingEmojiReactCallback
import cooking.zap.app.ui.component.AuthApprovalDialog
import cooking.zap.app.ui.screen.BlossomServersScreen
import cooking.zap.app.ui.screen.AuthScreen
import cooking.zap.app.ui.screen.SplashScreen
import cooking.zap.app.ui.screen.ComposeScreen
import cooking.zap.app.ui.screen.ContactPickerScreen
import cooking.zap.app.ui.screen.DmConversationScreen
import cooking.zap.app.ui.screen.DmListScreen
import cooking.zap.app.ui.screen.DraftsScreen
import cooking.zap.app.ui.screen.FeedScreen
import cooking.zap.app.ui.screen.ProfileEditScreen
import cooking.zap.app.ui.screen.RelayScreen
import cooking.zap.app.ui.screen.ThreadScreen
import cooking.zap.app.ui.screen.NotificationsScreen
import cooking.zap.app.ui.screen.SafetyScreen
import cooking.zap.app.ui.screen.UserProfileScreen
import cooking.zap.app.ui.screen.ConsoleScreen
import cooking.zap.app.ui.screen.RelayHealthScreen
import cooking.zap.app.ui.screen.CustomEmojiScreen
import cooking.zap.app.ui.screen.SearchScreen
import cooking.zap.app.ui.screen.SocialGraphScreen
import cooking.zap.app.ui.screen.BookmarkSetScreen
import cooking.zap.app.ui.screen.ArticleScreen
import cooking.zap.app.ui.screen.RecipeDetailScreen
import cooking.zap.app.ui.screen.RecipeComposeScreen
import cooking.zap.app.ui.screen.RecipeFeedScreen
import cooking.zap.app.ui.screen.RecipePackDetailScreen
import cooking.zap.app.ui.screen.RecipeTagFeedScreen
import cooking.zap.app.ui.screen.OnlyFoodFeedScreen
import cooking.zap.app.ui.screen.CheffyScreen
import cooking.zap.app.ui.screen.NourishHubScreen
import cooking.zap.app.ui.screen.SousChefScreen
import cooking.zap.app.ui.screen.BookmarksScreen
import cooking.zap.app.ui.screen.HashtagFeedScreen
import cooking.zap.app.ui.screen.BackupKeyScreen
import cooking.zap.app.ui.screen.KeysScreen
import cooking.zap.app.ui.screen.ListScreen
import cooking.zap.app.ui.screen.LiveStreamScreen
import cooking.zap.app.ui.screen.ExistingUserOnboardingScreen
import cooking.zap.app.ui.screen.WatchOnlyOnboardingScreen
import cooking.zap.app.ui.screen.LoadingScreen
import cooking.zap.app.ui.screen.ListsHubScreen
import cooking.zap.app.ui.screen.InterfaceScreen
import cooking.zap.app.ui.screen.PowSettingsScreen
import cooking.zap.app.ui.screen.OnboardingScreen
import cooking.zap.app.ui.component.AddNoteToListDialog
import cooking.zap.app.ui.component.CrashReportDialog
import androidx.media3.exoplayer.ExoPlayer
import cooking.zap.app.ui.component.FloatingAudioPlayer
import cooking.zap.app.ui.component.FloatingVideoPlayer
import cooking.zap.app.ui.component.PipController
import cooking.zap.app.ui.component.FullScreenVideoPlayer
import cooking.zap.app.ui.component.FullScreenVideoState
import cooking.zap.app.ui.component.KeyBackupBanner
import cooking.zap.app.ui.component.NsecPasteWarningOverlay
import cooking.zap.app.ui.screen.OnboardingSuggestionsScreen
import cooking.zap.app.ui.screen.OnboardingTopicsScreen
import cooking.zap.app.ui.screen.OnboardingSaveRecipesScreen
import cooking.zap.app.ui.screen.OnboardingFirstPostScreen
import cooking.zap.app.ui.screen.RelayDetailScreen
import cooking.zap.app.ui.screen.WalletScreen
import cooking.zap.app.viewmodel.BlossomServersViewModel
import cooking.zap.app.viewmodel.ArticleViewModel
import cooking.zap.app.viewmodel.RecipeDetailViewModel
import cooking.zap.app.viewmodel.RecipeComposeViewModel
import cooking.zap.app.viewmodel.RecipeFeedViewModel
import cooking.zap.app.viewmodel.CookbookViewModel
import cooking.zap.app.viewmodel.RecipePackDetailViewModel
import cooking.zap.app.viewmodel.RecipePacksViewModel
import cooking.zap.app.viewmodel.RecipeTagFeedViewModel
import cooking.zap.app.viewmodel.OnlyFoodFeedViewModel
import cooking.zap.app.viewmodel.CheffyViewModel
import cooking.zap.app.viewmodel.SousChefViewModel
import cooking.zap.app.viewmodel.AuthViewModel
import cooking.zap.app.viewmodel.ComposeViewModel
import cooking.zap.app.viewmodel.DmConversationViewModel
import cooking.zap.app.viewmodel.DmListViewModel
import cooking.zap.app.viewmodel.FeedType
import cooking.zap.app.viewmodel.FeedViewModel
import cooking.zap.app.viewmodel.ProfileViewModel
import cooking.zap.app.viewmodel.RelayViewModel
import cooking.zap.app.viewmodel.ThreadViewModel
import cooking.zap.app.viewmodel.UserProfileViewModel
import cooking.zap.app.nostr.Nip88
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.RecipeTagCatalog
import cooking.zap.app.viewmodel.NotificationFilter
import cooking.zap.app.viewmodel.NotificationsViewModel
import cooking.zap.app.viewmodel.ConsoleViewModel
import cooking.zap.app.viewmodel.RelayHealthViewModel
import cooking.zap.app.viewmodel.DraftsViewModel
import cooking.zap.app.viewmodel.SearchViewModel
import cooking.zap.app.viewmodel.HashtagFeedViewModel
import cooking.zap.app.viewmodel.OnboardingViewModel
import cooking.zap.app.viewmodel.PowStatus
import cooking.zap.app.viewmodel.SplashViewModel
import cooking.zap.app.viewmodel.LiveStreamViewModel
import cooking.zap.app.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import cooking.zap.app.ui.util.LocalCanSign

object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
    const val GOOGLE_AUTH = "google_auth"
    const val FEED = "feed"
    const val COMPOSE = "compose"
    const val RELAYS = "relays"
    const val PROFILE_EDIT = "profile/edit"
    const val USER_PROFILE = "profile/{pubkey}"
    const val THREAD = "thread/{eventId}"
    const val DM_LIST = "dms"
    const val DM_CONVERSATION = "dm/{pubkey}"
    const val DM_CONVERSATION_GROUP = "dm/group/{conversationKey}"
    const val CONTACT_PICKER = "contact_picker"
    const val GROUP_ROOM = "group_room/{encodedRelay}/{groupId}?scrollTo={scrollTo}"
    const val GROUP_DETAIL = "group_detail/{encodedRelay}/{groupId}"
    const val NOTIFICATIONS = "notifications"
    const val BLOSSOM_SERVERS = "blossom_servers"
    const val WALLET = "wallet"
    const val SAFETY = "safety"
    const val SEARCH = "search"
    const val CONSOLE = "console"
    const val KEYS = "keys"
    const val LIST_DETAIL = "list/{pubkey}/{dTag}"
    const val LISTS_HUB = "lists"
    const val BOOKMARKS = "bookmarks"
    const val BOOKMARK_SET_DETAIL = "bookmark_set/{pubkey}/{dTag}"
    const val LOADING = "loading"
    const val BACKUP_KEY = "backup_key?reprompt={reprompt}"
    const val ONBOARDING_PROFILE = "onboarding/profile"
    const val ONBOARDING_SUGGESTIONS = "onboarding/suggestions"
    const val ONBOARDING_TOPICS = "onboarding/topics"
    const val ONBOARDING_SAVE_RECIPES = "onboarding/save-recipes"
    const val ONBOARDING_FIRST_POST = "onboarding/first-post"
    const val RELAY_DETAIL = "relay_detail/{relayUrl}"
    const val CUSTOM_EMOJIS = "custom_emojis"
    const val HASHTAG_FEED = "hashtag/{tag}"
    const val HASHTAG_SET_FEED = "hashtag_set/{name}/{tags}"
    const val EXISTING_USER_ONBOARDING = "onboarding/existing"
    const val WATCH_ONLY_ONBOARDING = "onboarding/watch-only"
    const val DRAFTS = "drafts"
    const val SOCIAL_GRAPH = "social_graph"
    const val POW_SETTINGS = "pow_settings"
    const val INTERFACE_SETTINGS = "interface_settings"
    const val RELAY_HEALTH = "relay_health"
    const val ARTICLE = "article/{kind}/{author}/{dTag}"
    const val LIVE_STREAM = "live_stream/{hostPubkey}/{dTag}?relayHint={relayHint}"
    const val RECIPE_DETAIL = "recipe/{author}/{dTag}"
    const val RECIPE_PACK_DETAIL = "recipe_pack/{author}/{dTag}"
    const val RECIPE_COLLECTION = "recipe_collection/{dTag}"
    const val RECIPE_TAG_FEED = "recipe_tag/{tag}"
    const val RECIPES = "recipes"
    const val NOURISH = "nourish?author={author}&dTag={dTag}"
    const val ONLY_FOOD = "onlyfood"
    const val SOUS_CHEF = "souschef"
    const val RECIPE_COMPOSE = "recipe_compose"
    const val CHEFFY = "cheffy"

    /**
     * Build a recipe-detail route, URL-encoding the d-tag. Recipe kind is the
     * constant 30023, so the route carries only author + d-tag. Real recipe
     * d-tags contain `(`, `)`, and even `/` (e.g.
     * "mai-tai-barcadi-(german/-deutsch)") — an un-encoded d-tag would break
     * the NavController path; the composable decodes it back with URLDecoder.
     */
    fun recipe(author: String, dTag: String): String =
        "recipe/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}"

    /** Build a recipe-pack detail route (stubbed in PR A, implemented in PR B). */
    fun recipePack(author: String, dTag: String): String =
        "recipe_pack/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}"

    /** Build a cookbook-collection detail route (the user's own kind-30001 list). */
    fun recipeCollection(dTag: String): String =
        "recipe_collection/${java.net.URLEncoder.encode(dTag, "UTF-8")}"

    /** Build a recipe-by-tag feed route, URL-encoding the slug-like tag. */
    fun recipeTag(tag: String): String =
        "recipe_tag/${java.net.URLEncoder.encode(tag.trim().lowercase(), "UTF-8")}"

    /** Build the Nourish hub route (optionally preloading a selected recipe). */
    fun nourish(author: String? = null, dTag: String? = null): String {
        if (author.isNullOrBlank() || dTag.isNullOrBlank()) return "nourish"
        return "nourish?author=$author&dTag=${java.net.URLEncoder.encode(dTag, "UTF-8")}"
    }

    /** The plain article route (kind 30023 long-form that isn't a recipe). */
    fun article(kind: Int, author: String, dTag: String): String =
        "article/$kind/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}"

    /**
     * Build the key-backup route. `reprompt=false` (creation flow) advances to
     * onboarding on dismiss; `reprompt=true` (banner / cold-launch nudge) sits on
     * top of the feed and pops back to it.
     */
    fun backupKey(reprompt: Boolean): String = "backup_key?reprompt=$reprompt"
}

/**
 * Open an addressable 30023 event as a **recipe** if it carries the recipe
 * tag, otherwise as a plain article. Detection reads the already-cached
 * event ([EventRepository.findAddressableEvent]) — in a feed tap the event is
 * normally present. **Cache-miss guard:** if the event was evicted between
 * render and tap (or it simply isn't a recipe), fall back to the article
 * route — never route to a recipe screen with no event behind it.
 */
fun NavController.openArticleOrRecipe(
    eventRepo: cooking.zap.app.repo.EventRepository,
    kind: Int,
    author: String,
    dTag: String,
) {
    val event = eventRepo.findAddressableEvent(kind, author, dTag)
    if (event != null && cooking.zap.app.nostr.RecipeParser.isRecipe(event)) {
        navigate(Routes.recipe(author, dTag))
    } else {
        navigate(Routes.article(kind, author, dTag))
    }
}

/**
 * Map a decoded NIP-19 entity to a navigation route, or null if there is no
 * route for it (e.g. an addressable event whose kind we don't render).
 */
fun NostrUriData.toRoute(): String? = when (this) {
    is NostrUriData.ProfileRef -> "profile/$pubkey"
    is NostrUriData.NoteRef -> "thread/$eventId"
    is NostrUriData.AddressRef ->
        if (kind == 30023 && author != null) "article/$kind/$author/$dTag" else null
}

@Composable
fun WispNavHost(
    deepLinkUri: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    accentColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFF5722),
    isLargeText: Boolean = false,
    onInterfaceChanged: () -> Unit = {}
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val feedViewModel: FeedViewModel = viewModel()
    val composeViewModel: ComposeViewModel = viewModel()
    val relayViewModel: RelayViewModel = viewModel()
    val profileViewModel: ProfileViewModel = viewModel()
    val dmListViewModel: DmListViewModel = viewModel()
    val groupListViewModel: cooking.zap.app.viewmodel.GroupListViewModel = viewModel()
    val blossomServersViewModel: BlossomServersViewModel = viewModel()
    val appContentResolver = LocalContext.current.contentResolver
    val walletViewModel: WalletViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return WalletViewModel(
                    feedViewModel.nwcRepo,
                    feedViewModel.sparkRepo,
                    feedViewModel.walletModeRepo,
                    feedViewModel.eventRepo,
                    feedViewModel.relayPool,
                    feedViewModel.keyRepo,
                    contentResolver = appContentResolver,
                ) as T
            }
        }
    )
    val notificationsViewModel: NotificationsViewModel = viewModel()
    val draftsViewModel: DraftsViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()
    val consoleViewModel: ConsoleViewModel = viewModel()
    val relayHealthViewModel: RelayHealthViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val topicOnboardingViewModel: cooking.zap.app.viewmodel.TopicOnboardingViewModel = viewModel()
    val recipeOnboardingViewModel: cooking.zap.app.viewmodel.RecipeOnboardingViewModel = viewModel()
    val splashViewModel: SplashViewModel = viewModel()

    relayViewModel.relayPool = feedViewModel.relayPool

    // Signer for the active account — reactive on login/logout/account-switch.
    val context = LocalContext.current
    val signingMode by authViewModel.signingModeFlow.collectAsState()
    val npub by authViewModel.npub.collectAsState()
    val activeSigner = remember(signingMode, npub) {
        when (signingMode) {
            SigningMode.LOCAL -> {
                authViewModel.keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) }
            }
            SigningMode.REMOTE -> {
                val pubkeyHex = authViewModel.keyRepo.getPubkeyHex()
                val pkg = authViewModel.keyRepo.getSignerPackage()
                if (pubkeyHex != null && pkg != null) RemoteSigner(pubkeyHex, context.contentResolver, pkg)
                else null
            }
            SigningMode.READ_ONLY -> null
            null -> null
        }
    }

    // NIP-55 intent-based signing fallback — launches signer UI when ContentResolver fails
    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            val signature = data?.getStringExtra("signature") ?: data?.getStringExtra("result") ?: ""
            val event = data?.getStringExtra("event")
            if (signature.isNotEmpty() || !event.isNullOrBlank()) {
                SignerIntentBridge.deliverResult(SignResult.Success(signature, event))
            } else {
                SignerIntentBridge.deliverResult(SignResult.Rejected)
            }
        } else {
            SignerIntentBridge.deliverResult(SignResult.Cancelled)
        }
    }

    val pendingSignRequest by SignerIntentBridge.pendingRequest.collectAsState()
    LaunchedEffect(pendingSignRequest) {
        pendingSignRequest?.let { request ->
            signerLauncher.launch(request.intent)
        }
    }

    // Push signer into FeedViewModel when it becomes available
    LaunchedEffect(activeSigner) {
        activeSigner?.let { feedViewModel.setSigner(it) }
    }

    var replyTarget by remember { mutableStateOf<NostrEvent?>(null) }
    var quoteTarget by remember { mutableStateOf<NostrEvent?>(null) }
    var scrollToTopTrigger by remember { mutableStateOf(0) }
    var addToListEventId by remember { mutableStateOf<String?>(null) }

    // Multi-account state
    val accounts by authViewModel.accountsFlow.collectAsState()
    var groupListInitKey by rememberSaveable { mutableStateOf(0) }

    val onSwitchAccount: (String) -> Unit = { pubkeyHex ->
        feedViewModel.clearSigner()
        feedViewModel.resetForAccountSwitch()
        walletViewModel.suspendForAccountSwitch()  // disconnect only, preserve credentials
        groupListViewModel.reset()
        authViewModel.switchAccount(pubkeyHex)
        feedViewModel.reloadForNewAccount()
        relayViewModel.reload()
        blossomServersViewModel.reload()
        composeViewModel.reloadBlossomRepo()
        walletViewModel.refreshState()
        groupListInitKey++
        // initRelays() is called by the LOADING composable's LaunchedEffect
        navController.navigate(Routes.LOADING) {
            popUpTo(0) { inclusive = true }
        }
    }

    val onAddAccount: () -> Unit = {
        authViewModel.isAddingAccount = true
        feedViewModel.resetForAccountSwitch()
        walletViewModel.suspendForAccountSwitch()  // disconnect only, preserve credentials
        navController.navigate(Routes.SPLASH) {
            popUpTo(0) { inclusive = true }
        }
    }

    val startDestination = rememberSaveable {
        when {
            !authViewModel.isLoggedIn -> Routes.SPLASH
            !authViewModel.keyRepo.isOnboardingComplete() -> Routes.ONBOARDING_PROFILE
            else -> Routes.LOADING
        }
    }

    // Initialize relays when logged in and onboarding is complete
    if (authViewModel.isLoggedIn && startDestination == Routes.LOADING) {
        LaunchedEffect(Unit) {
            feedViewModel.initRelays()
        }
    }

    // App lifecycle observer — handles relay pause/resume regardless of which screen is active.
    // Previously lived in FeedScreen's DisposableEffect, which meant pauses/resumes on
    // Notifications, DMs, or any other screen were silently ignored.
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(activity) {
        var pausedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    pausedAt = System.currentTimeMillis()
                    feedViewModel.onAppPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (pausedAt > 0L) {
                        val pausedMs = System.currentTimeMillis() - pausedAt
                        pausedAt = 0L
                        feedViewModel.onAppResume(pausedMs)
                    }
                }
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Initialize compose viewmodel with shared repos
    LaunchedEffect(Unit) {
        composeViewModel.init(
            feedViewModel.profileRepo,
            feedViewModel.contactRepo,
            feedViewModel.relayPool,
            feedViewModel.eventRepo,
            feedViewModel.eventPersistence,
            feedViewModel.dmRepo,
            feedViewModel.relayListRepo
        )
    }

    // Initialize DM list viewmodel with shared repo
    LaunchedEffect(Unit) {
        dmListViewModel.init(feedViewModel.dmRepo, feedViewModel.muteRepo)
    }

    // Initialize group list viewmodel with shared repo; key changes on account switch to re-init
    LaunchedEffect(groupListInitKey) {
        feedViewModel.onGroupReconnect = { groupListViewModel.resubscribeNotifiedGroups() }
        groupListViewModel.init(feedViewModel.groupRepo, feedViewModel.relayPool, feedViewModel.eventRepo,
            feedViewModel.notifRepo, feedViewModel.getUserPubkey())
    }

    // Initialize notifications viewmodel with shared repos
    LaunchedEffect(Unit) {
        notificationsViewModel.init(
            feedViewModel.notifRepo, feedViewModel.eventRepo, feedViewModel.contactRepo,
            feedViewModel.dmRepo, feedViewModel.relayPool, feedViewModel.relayListRepo,
            feedViewModel.powPrefs, feedViewModel.profileRepo, feedViewModel.eventPersistence
        )
    }

    // Resolve deep link URI to a navigation route
    val deepLinkRoute = remember(deepLinkUri) {
        val uri = deepLinkUri ?: return@remember null
        Nip19.decodeNostrUri(uri)?.toRoute()
    }

    // Handle deep links when app is already past loading (onNewIntent)
    LaunchedEffect(deepLinkUri) {
        val route = deepLinkRoute ?: return@LaunchedEffect
        if (!authViewModel.isLoggedIn) return@LaunchedEffect
        val currentDest = navController.currentDestination?.route
        // Only navigate directly if we're past the loading/auth screens
        if (currentDest != null && currentDest !in setOf(Routes.LOADING, Routes.AUTH, Routes.SPLASH, Routes.ONBOARDING_PROFILE)) {
            onDeepLinkConsumed()
            navController.navigate(route)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Active key-backup re-prompt: once per process, when we first land on the feed and
    // the deferred reminder is due (1st skip → next launch, then backing off), re-show
    // the backup screen on top of the feed. The passive banner handles ongoing nudging.
    var launchRepromptHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (!launchRepromptHandled && currentRoute == Routes.FEED) {
            launchRepromptHandled = true
            if (authViewModel.isLoggedIn && authViewModel.shouldRepromptKeyBackup()) {
                navController.navigate(Routes.backupKey(reprompt = true))
            }
        }
    }

    val nonAppRoutes = setOf(Routes.SPLASH, Routes.AUTH, Routes.GOOGLE_AUTH, Routes.LOADING, Routes.BACKUP_KEY, Routes.ONBOARDING_PROFILE, Routes.ONBOARDING_SUGGESTIONS, Routes.ONBOARDING_TOPICS, Routes.ONBOARDING_SAVE_RECIPES, Routes.ONBOARDING_FIRST_POST, Routes.EXISTING_USER_ONBOARDING, Routes.WATCH_ONLY_ONBOARDING)
    val hideBottomBarRoutes = nonAppRoutes + Routes.DM_CONVERSATION + Routes.DM_CONVERSATION_GROUP + Routes.CONTACT_PICKER + Routes.GROUP_ROOM + Routes.GROUP_DETAIL + Routes.LIVE_STREAM
    val socialGraphDiscoveryState by feedViewModel.extendedNetworkRepo.discoveryState.collectAsState()
    val socialGraphComputing = currentRoute == Routes.SOCIAL_GRAPH && (
        socialGraphDiscoveryState is cooking.zap.app.repo.DiscoveryState.FetchingFollowLists ||
        socialGraphDiscoveryState is cooking.zap.app.repo.DiscoveryState.BuildingGraph ||
        socialGraphDiscoveryState is cooking.zap.app.repo.DiscoveryState.ComputingNetwork ||
        socialGraphDiscoveryState is cooking.zap.app.repo.DiscoveryState.Filtering ||
        socialGraphDiscoveryState is cooking.zap.app.repo.DiscoveryState.FetchingRelayLists)
    val showBottomBar by remember(currentRoute, socialGraphComputing) {
        derivedStateOf { currentRoute != null && currentRoute !in hideBottomBarRoutes && !socialGraphComputing }
    }

    // After process death, Navigation restores the last screen but the ViewModel
    // is fresh (no relay connections, empty feed). Redirect to LOADING to re-fetch.
    val loadingComplete by feedViewModel.loadingScreenComplete.collectAsState()
    if (currentRoute != null && currentRoute !in nonAppRoutes && !loadingComplete) {
        LaunchedEffect(Unit) {
            feedViewModel.initRelays()
            navController.navigate(Routes.LOADING) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val newNoteCount by feedViewModel.newNoteCount.collectAsState()
    val hasUnreadDms by dmListViewModel.hasUnreadDms.collectAsState()
    val hasUnreadNotifications by notificationsViewModel.hasUnread.collectAsState()

    var isZapAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        notificationsViewModel.zapReceived.collect {
            isZapAnimating = true
            kotlinx.coroutines.delay(900)
            isZapAnimating = false
        }
    }

    var isReplyAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        notificationsViewModel.replyReceived.collect {
            if (NotificationFilter.REPLIES in notificationsViewModel.enabledTypes.value) {
                isReplyAnimating = true
                kotlinx.coroutines.delay(1000)
                isReplyAnimating = false
            }
        }
    }

    val notifPrefs = remember { context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE) }
    var notifSoundEnabled by rememberSaveable { mutableStateOf(notifPrefs.getBoolean("notif_sound_enabled", true)) }
    val notifBlipSound = remember { NotifBlipSound(context) }
    DisposableEffect(Unit) {
        onDispose { notifBlipSound.release() }
    }
    LaunchedEffect(Unit) { HapticHelper.init(context) }
    val currentNotifSoundEnabled by rememberUpdatedState(notifSoundEnabled)
    LaunchedEffect(Unit) {
        notificationsViewModel.notifReceived.collect { kind ->
            if (!currentNotifSoundEnabled) return@collect
            val enabled = notificationsViewModel.enabledTypes.value
            val filter = when (kind) {
                7 -> NotificationFilter.REACTIONS
                6, 16 -> NotificationFilter.REPOSTS
                1 -> NotificationFilter.MENTIONS
                Nip88.KIND_POLL_RESPONSE -> NotificationFilter.VOTES
                else -> null
            }
            if (filter == null || filter in enabled) {
                notifBlipSound.play()
                HapticHelper.blip()
            }
        }
    }
    LaunchedEffect(Unit) {
        notificationsViewModel.zapReceived.collect {
            if (currentNotifSoundEnabled && NotificationFilter.ZAPS in notificationsViewModel.enabledTypes.value) {
                HapticHelper.zapBuzz()
            }
        }
    }
    LaunchedEffect(Unit) {
        notificationsViewModel.replyReceived.collect {
            if (currentNotifSoundEnabled && NotificationFilter.REPLIES in notificationsViewModel.enabledTypes.value) {
                HapticHelper.pulse()
            }
        }
    }
    LaunchedEffect(Unit) {
        notificationsViewModel.dmReceived.collect {
            if (NotificationFilter.DMS in notificationsViewModel.enabledTypes.value) {
                isReplyAnimating = true
                kotlinx.coroutines.delay(1000)
                isReplyAnimating = false
            }
            if (currentNotifSoundEnabled && NotificationFilter.DMS in notificationsViewModel.enabledTypes.value) {
                HapticHelper.pulse()
            }
        }
    }
    // Background decryption of pending DM gift wraps (remote signer mode)
    val pendingDmCount by dmListViewModel.pendingDecryptCount.collectAsState()
    LaunchedEffect(pendingDmCount, activeSigner) {
        if (pendingDmCount > 0) {
            activeSigner?.let { dmListViewModel.decryptPending(it) }
        }
    }
    LaunchedEffect(Unit) {
        feedViewModel.reactionSent.collect { HapticHelper.blip() }
    }
    LaunchedEffect(Unit) {
        feedViewModel.zapSuccess.collect { HapticHelper.zapBuzz() }
    }
    LaunchedEffect(Unit) {
        var fired = false
        feedViewModel.relayPool.broadcastState.collect { state ->
            if (state != null && state.accepted > 0 && !fired) {
                fired = true
                HapticHelper.pulse()
            } else if (state == null) {
                fired = false
            }
        }
    }

    var lastInboxRefreshElapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    fun refreshInboxSubscriptionsIfStale() {
        val now = SystemClock.elapsedRealtime()
        // Inbox subscriptions already stay live in the background; avoid re-sending the
        // same REQs every time the user bounces between top-level tabs.
        if (now - lastInboxRefreshElapsedMs < 30_000L) return
        lastInboxRefreshElapsedMs = now
        feedViewModel.refreshDmsAndNotifications()
    }

    // Crash report dialog — check on launch if a crash log exists
    var showCrashDialog by remember { mutableStateOf(CrashHandler.hasCrashLog(context)) }
    if (showCrashDialog) {
        val crashLog = remember { CrashHandler.getCrashLog(context) }
        CrashReportDialog(
            crashLog = crashLog,
            onSend = {
                showCrashDialog = false
                CrashHandler.clearCrashLog(context)
                kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.Default) {
                    CrashHandler.sendCrashDm(authViewModel.keyRepo, feedViewModel.relayPool, crashLog)
                }
            },
            onDismiss = {
                showCrashDialog = false
                CrashHandler.clearCrashLog(context)
            }
        )
    }

    // Add Note to List dialog — shared across all screens
    if (addToListEventId != null) {
        val ownSets by feedViewModel.bookmarkSetRepo.ownSets.collectAsState()
        val bookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
        AddNoteToListDialog(
            eventId = addToListEventId!!,
            bookmarkSets = ownSets,
            isBookmarked = addToListEventId!! in bookmarkedIds,
            onToggleBookmark = { eventId -> feedViewModel.toggleBookmark(eventId) },
            onAddToSet = { dTag, eventId -> feedViewModel.addNoteToBookmarkSet(dTag, eventId) },
            onRemoveFromSet = { dTag, eventId -> feedViewModel.removeNoteFromBookmarkSet(dTag, eventId) },
            onCreateSet = { name -> feedViewModel.createBookmarkSet(name) },
            onDismiss = { addToListEventId = null }
        )
    }

    // NIP-42 AUTH approval dialog — shown when a tier-2 relay (DM delivery or joined chat relay)
    // requests authentication so the user can decide whether to reveal their pubkey.
    val pendingAuth by feedViewModel.relayPool.pendingAuthRequest.collectAsState()
    pendingAuth?.let { request ->
        AuthApprovalDialog(
            relayUrl = request.relayUrl,
            onAllow = { feedViewModel.relayPool.approveAuth(request) },
            onDeny = { feedViewModel.relayPool.denyAuth(request) }
        )
    }

    // Feed is the home anchor. On non-FEED app screens: pop the back stack,
    // falling back to FEED if empty. On FEED: let the system handle back
    // (minimizes the app). Recipes is still a root tab, but Feed is "home".
    val isAppRoute = currentRoute != null && currentRoute !in nonAppRoutes
    BackHandler(enabled = isAppRoute && currentRoute != Routes.FEED) {
        val popped = navController.popBackStack()
        if (!popped) {
            navController.navigate(Routes.FEED) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // ───── Hoisted navigation drawer ─────
    // Shared by the Recipes & Feed root tabs (and reachable from every screen
    // that opens it via onOpenDrawer). Lives here, not inside FeedScreen, so
    // Recipes — the default/home tab — can open the same drawer.
    val drawerScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val onOpenDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }
    // Edge-swipe-to-open is allowed only on the root tabs — never on sub-screens
    // (recipe detail, threads, DM/group rooms, settings, etc.).
    val rootTabRoutes = remember {
        setOf(Routes.RECIPES, Routes.FEED, Routes.WALLET, Routes.DM_LIST, Routes.NOTIFICATIONS)
    }
    val drawerPubkey = feedViewModel.getUserPubkey()
    val drawerProfileVersion by feedViewModel.eventRepo.profileVersion.collectAsState()
    val drawerProfile = drawerProfileVersion.let {
        drawerPubkey?.let { pk -> feedViewModel.eventRepo.getProfileData(pk) }
    }
    val drawerStatusVersion by feedViewModel.eventRepo.statusVersion.collectAsState()
    val drawerHasEmbeddedWallet =
        walletViewModel.walletMode.collectAsState().value == cooking.zap.app.repo.WalletMode.SPARK
    val closeDrawerAndNavigate: (String) -> Unit = { route ->
        drawerScope.launch { drawerState.close() }
        navController.navigate(route)
    }
    // Network diagnostics relocated from the Feed top-bar chips (PR 3): the
    // counts feed the Advanced "Network" rows, and the online-members sheet is
    // hosted here so it survives the chip removal.
    val drawerConnectedCount by feedViewModel.relayPool.connectedCount.collectAsState()
    val drawerOnlinePubkeys by feedViewModel.eventRepo.onlinePubkeys.collectAsState()
    val drawerGlobalOnlineCount by feedViewModel.globalOnlineCount.collectAsState()
    var showOnlineNowSheet by remember { mutableStateOf(false) }

    // Active account still needs to back up its key — drives the drawer dot + feed banner.
    val keyBackupNudge by authViewModel.keyBackupNudge.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute in rootTabRoutes,
        drawerContent = {
            WispDrawerContent(
                profile = drawerProfile,
                pubkey = drawerPubkey,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                accounts = accounts,
                onSwitchAccount = { pubkeyHex ->
                    drawerScope.launch { drawerState.close() }
                    onSwitchAccount(pubkeyHex)
                },
                onAddAccount = {
                    drawerScope.launch { drawerState.close() }
                    onAddAccount()
                },
                onProfile = {
                    drawerScope.launch { drawerState.close() }
                    drawerPubkey?.let { navController.navigate("profile/$it") }
                },
                onFeed = {
                    drawerScope.launch { drawerState.close() }
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.FEED) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onSearch = {
                    drawerScope.launch { drawerState.close() }
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onMessages = { closeDrawerAndNavigate(Routes.DM_LIST) },
                onWallet = { closeDrawerAndNavigate(Routes.WALLET) },
                onRecipes = {
                    drawerScope.launch { drawerState.close() }
                    navController.navigate(Routes.RECIPES) {
                        popUpTo(Routes.FEED) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onSousChef = { closeDrawerAndNavigate(Routes.SOUS_CHEF) },
                onCheffy = { closeDrawerAndNavigate(Routes.CHEFFY) },
                onOnlyFood = { closeDrawerAndNavigate(Routes.ONLY_FOOD) },
                onLists = { closeDrawerAndNavigate(Routes.LISTS_HUB) },
                onDrafts = { closeDrawerAndNavigate(Routes.DRAFTS) },
                onMediaServers = { closeDrawerAndNavigate(Routes.BLOSSOM_SERVERS) },
                onSocialGraph = { closeDrawerAndNavigate(Routes.SOCIAL_GRAPH) },
                onSafety = { closeDrawerAndNavigate(Routes.SAFETY) },
                onCustomEmojis = { closeDrawerAndNavigate(Routes.CUSTOM_EMOJIS) },
                onKeys = { closeDrawerAndNavigate(Routes.KEYS) },
                keyBackupNeeded = keyBackupNudge,
                onPowSettings = { closeDrawerAndNavigate(Routes.POW_SETTINGS) },
                onConsole = { closeDrawerAndNavigate(Routes.CONSOLE) },
                connectedRelayCount = drawerConnectedCount,
                onlineCount = drawerOnlinePubkeys.size,
                onNetworkStatus = { closeDrawerAndNavigate(Routes.RELAY_HEALTH) },
                onOnlineNow = {
                    // Close the drawer, THEN present the sheet in the same
                    // coroutine so the modal doesn't overlap the drawer scrim.
                    drawerScope.launch {
                        drawerState.close()
                        showOnlineNowSheet = true
                    }
                },
                onRelaySettings = { closeDrawerAndNavigate(Routes.RELAYS) },
                onInterfaceSettings = { closeDrawerAndNavigate(Routes.INTERFACE_SETTINGS) },
                onLogout = {
                    drawerScope.launch { drawerState.close() }
                    feedViewModel.clearSigner()
                    feedViewModel.resetForAccountSwitch()
                    walletViewModel.disconnectWallet()  // full clear — intentional logout
                    val hasRemaining = authViewModel.logOut()
                    if (hasRemaining) {
                        // logOut() already switched to the first remaining account
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        walletViewModel.refreshState()
                        // initRelays() triggered by LOADING composable LaunchedEffect
                        navController.navigate(Routes.LOADING) {
                            popUpTo(0) { inclusive = true }
                        }
                    } else {
                        // Full logout — reset UI preferences and refresh in-memory theme state
                        cooking.zap.app.repo.InterfacePreferences(context).reset()
                        onInterfaceChanged()
                        navController.navigate(Routes.SPLASH) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasEmbeddedWallet = drawerHasEmbeddedWallet,
                userStatus = drawerStatusVersion.let {
                    drawerPubkey?.let { pk -> feedViewModel.eventRepo.getUserStatus(pk) }
                },
                onUpdateStatus = { status -> feedViewModel.publishUserStatus(status) },
                onScanResult = { route ->
                    drawerScope.launch { drawerState.close() }
                    navController.navigate(route)
                }
            )
        }
    ) {

    // Relocated "Online Now" members sheet (was the Feed top-bar online chip).
    if (showOnlineNowSheet) {
        cooking.zap.app.ui.component.OnlineNowSheet(
            onlinePubkeys = drawerOnlinePubkeys,
            globalOnlineCount = drawerGlobalOnlineCount,
            profileProvider = { pk -> feedViewModel.eventRepo.getProfileData(pk) },
            onProfileClick = { pk ->
                showOnlineNowSheet = false
                navController.navigate("profile/$pk")
            },
            onDismiss = { showOnlineNowSheet = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                FloatingAudioPlayer()
                if (showBottomBar) {
                    WispBottomBar(
                        currentRoute = currentRoute,
                        hasUnreadHome = newNoteCount > 0,
                        hasUnreadMessages = hasUnreadDms,
                        hasUnreadNotifications = hasUnreadNotifications,
                        isZapAnimating = isZapAnimating,
                        isReplyAnimating = isReplyAnimating,
                        notifSoundEnabled = notifSoundEnabled,
                        isReadOnly = signingMode == SigningMode.READ_ONLY,
                        onTabSelected = { tab ->
                            if (currentRoute == tab.route) {
                                scrollToTopTrigger++
                            } else {
                                if (tab == BottomTab.WALLET) walletViewModel.navigateHome()
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.FEED) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->

    val broadcastState by feedViewModel.relayPool.broadcastState.collectAsState()
    val powStatus by feedViewModel.powManager.status.collectAsState()
    var pipFullScreenVideoUrl by remember { mutableStateOf<String?>(null) }
    var pipFullScreenStartPosition by remember { mutableLongStateOf(0L) }
    var pipFullScreenPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var pipFullScreenAspectRatio by remember { mutableStateOf(16f / 9f) }

    Box(modifier = Modifier.padding(innerPadding)) {
    CompositionLocalProvider(LocalCanSign provides (signingMode != SigningMode.READ_ONLY)) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                viewModel = splashViewModel,
                authViewModel = authViewModel,
                onAccountCreated = {
                    // New key generated → show the backup step before profile setup.
                    navController.navigate(Routes.backupKey(reprompt = false)) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onLoggedIn = {
                    feedViewModel.reloadForNewAccount()
                    relayViewModel.reload()
                    blossomServersViewModel.reload()
                    composeViewModel.reloadBlossomRepo()
                    feedViewModel.initRelays()
                    walletViewModel.refreshState()
                    authViewModel.keyRepo.markOnboardingComplete()
                    val target = if (authViewModel.keyRepo.isReadOnly())
                        Routes.LOADING
                    else
                        Routes.EXISTING_USER_ONBOARDING
                    navController.navigate(target) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onContinueWithGoogle = {
                    navController.navigate(Routes.GOOGLE_AUTH)
                },
                onCancel = if (authViewModel.isAddingAccount) {
                    {
                        authViewModel.isAddingAccount = false
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        navController.navigate(Routes.LOADING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                } else null
            )
        }

        composable(Routes.GOOGLE_AUTH) {
            val googleAuthViewModel: cooking.zap.app.viewmodel.GoogleAuthViewModel = viewModel()
            cooking.zap.app.ui.screen.GoogleAuthScreen(
                viewModel = googleAuthViewModel,
                onCancel = {
                    googleAuthViewModel.reset()
                    navController.popBackStack()
                },
                onDone = { isNewAccount ->
                    val wasAddingAccount = authViewModel.isAddingAccount
                    authViewModel.isAddingAccount = false
                    authViewModel.refreshAfterExternalLogin()

                    if (isNewAccount) {
                        navController.navigate(Routes.backupKey(reprompt = false)) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    } else if (wasAddingAccount && authViewModel.keyRepo.isOnboardingComplete()) {
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        navController.navigate(Routes.LOADING) {
                            popUpTo(Routes.GOOGLE_AUTH) { inclusive = true }
                        }
                    } else {
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        authViewModel.keyRepo.markOnboardingComplete()
                        navController.navigate(Routes.EXISTING_USER_ONBOARDING) {
                            popUpTo(Routes.GOOGLE_AUTH) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.AUTH) {
            AuthScreen(
                viewModel = authViewModel,
                showSignUp = false,
                onAuthenticated = { isNewAccount ->
                    val wasAddingAccount = authViewModel.isAddingAccount
                    authViewModel.isAddingAccount = false

                    if (isNewAccount) {
                        // New key generation: back up the key, then full onboarding.
                        navController.navigate(Routes.backupKey(reprompt = false))
                    } else if (wasAddingAccount && authViewModel.keyRepo.isOnboardingComplete()) {
                        // Adding an existing account that already completed onboarding — skip straight to loading
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        navController.navigate(Routes.LOADING) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    } else if (authViewModel.keyRepo.isReadOnly()) {
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        feedViewModel.initRelays()
                        authViewModel.keyRepo.markOnboardingComplete()
                        navController.navigate(Routes.WATCH_ONLY_ONBOARDING) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    } else {
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        // Start relay connections immediately so TCP/TLS handshakes
                        // run in parallel with the onboarding welcome animation
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        authViewModel.keyRepo.markOnboardingComplete()
                        navController.navigate(Routes.EXISTING_USER_ONBOARDING) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.LOADING) {
            // Ensure relays are initialized whenever the loading screen is shown —
            // covers both initial cold start and account switches (where initRelays()
            // is not called eagerly so old relay connections fully close first).
            LaunchedEffect(Unit) {
                feedViewModel.initRelays()
            }
            LoadingScreen(
                viewModel = feedViewModel,
                onReady = {
                    val target = deepLinkRoute
                    if (target != null) {
                        onDeepLinkConsumed()
                        // Navigate to Feed first (the home anchor), then to the deep link target
                        navController.navigate(Routes.FEED) {
                            popUpTo(Routes.LOADING) { inclusive = true }
                        }
                        navController.navigate(target)
                    } else {
                        navController.navigate(Routes.FEED) {
                            popUpTo(Routes.LOADING) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.EXISTING_USER_ONBOARDING) {
            ExistingUserOnboardingScreen(
                feedViewModel = feedViewModel,
                onReady = {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.EXISTING_USER_ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.WATCH_ONLY_ONBOARDING) {
            WatchOnlyOnboardingScreen(
                feedViewModel = feedViewModel,
                onReady = {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.WATCH_ONLY_ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FEED) {
            FeedScreen(
                viewModel = feedViewModel,
                onOpenDrawer = onOpenDrawer,
                scrollToTopTrigger = scrollToTopTrigger,
                onCompose = if (signingMode == SigningMode.READ_ONLY) null else {
                    {
                        replyTarget = null
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    }
                },
                onReply = if (signingMode == SigningMode.READ_ONLY) { _ -> } else { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onNoteClick = { event ->
                    navController.navigate("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onWallet = {
                    navController.navigate(Routes.WALLET)
                },
                onSearch = {
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSousChef = { navController.navigate(Routes.SOUS_CHEF) { launchSingleTop = true } },
                onCheffy = { navController.navigate(Routes.CHEFFY) { launchSingleTop = true } },
                onNourish = { navController.navigate(Routes.nourish()) { launchSingleTop = true } },
                onSocialGraph = {
                    navController.navigate(Routes.SOCIAL_GRAPH)
                },
                onAddToList = { eventId -> addToListEventId = eventId },
                onRelayDetail = { url ->
                    navController.navigate("relay_detail/${java.net.URLEncoder.encode(url, "UTF-8")}")
                },
                onHashtagClick = { tag ->
                    navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onViewSetFeed = { set ->
                    val encodedName = java.net.URLEncoder.encode(set.name, "UTF-8")
                    val encodedTags = java.net.URLEncoder.encode(set.hashtags.joinToString(","), "UTF-8")
                    navController.navigate("hashtag_set/$encodedName/$encodedTags")
                },
                onArticleClick = { kind, author, dTag ->
                    navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, author, dTag)
                },
                onGroupRoom = { relayUrl, groupId ->
                    val encoded = android.util.Base64.encodeToString(
                        relayUrl.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    )
                    navController.navigate("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigate(route)
                },
                fetchGroupPreview = { relayUrl, groupId ->
                    groupListViewModel.fetchGroupPreview(relayUrl, groupId)
                }
            )
        }

        composable(Routes.COMPOSE) {
            // Initialize PoW toggle from persisted preferences
            LaunchedEffect(Unit) {
                composeViewModel.initPowState(feedViewModel.powPrefs.isNotePowEnabled())
            }
            // Auto-save draft when leaving compose screen (back button, navigation, etc.)
            DisposableEffect(Unit) {
                onDispose {
                    if (composeViewModel.content.value.text.isNotBlank()) {
                        composeViewModel.saveDraft(feedViewModel.relayPool, replyTarget, activeSigner)
                    }
                }
            }
            ComposeScreen(
                viewModel = composeViewModel,
                relayPool = feedViewModel.relayPool,
                replyTo = replyTarget,
                quoteTo = quoteTarget,
                onBack = { navController.popBackStack() },
                onSaveDraft = {
                    composeViewModel.saveDraft(feedViewModel.relayPool, replyTarget, activeSigner)
                    navController.popBackStack()
                },
                outboxRouter = feedViewModel.outboxRouter,
                eventRepo = feedViewModel.eventRepo,
                profileRepo = feedViewModel.profileRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                signer = activeSigner,
                onNotePublished = { feedViewModel.refreshNotifRepliesEtag() },
                powManager = feedViewModel.powManager,
                powPrefs = feedViewModel.powPrefs,
                resolvedEmojis = feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState().value
            )
        }

        composable(Routes.DRAFTS) {
            LaunchedEffect(Unit) {
                draftsViewModel.loadDrafts(feedViewModel.relayPool, activeSigner, feedViewModel.deletedEventsRepo)
                draftsViewModel.loadScheduledPosts(feedViewModel.relayPool, activeSigner)
            }
            val profileVersion by feedViewModel.eventRepo.profileVersion.collectAsState()
            val userPubkey = feedViewModel.getUserPubkey()
            val userProfile = remember(userPubkey, profileVersion) {
                userPubkey?.let { feedViewModel.eventRepo.getProfileData(it) }
            }
            DraftsScreen(
                viewModel = draftsViewModel,
                onBack = { navController.popBackStack() },
                onDraftClick = { draft ->
                    // Extract reply target from draft tags if present
                    // Prefer "reply" marker, fall back to "root"
                    val replyTag = draft.tags
                        .firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }
                        ?: draft.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "root" }
                    if (replyTag != null) {
                        val eventId = replyTag[1]
                        val cached = feedViewModel.eventRepo.getEvent(eventId)
                        replyTarget = cached ?: run {
                            // Build a stub event from draft tags so reply context works
                            val peerPubkey = draft.tags
                                .firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: ""
                            NostrEvent(
                                id = eventId,
                                pubkey = peerPubkey,
                                created_at = 0L,
                                kind = 1,
                                tags = draft.tags.filter { it.firstOrNull() == "e" },
                                content = "",
                                sig = ""
                            )
                        }
                    } else {
                        replyTarget = null
                    }
                    quoteTarget = null
                    composeViewModel.loadDraft(draft)
                    navController.navigate(Routes.COMPOSE)
                },
                onDeleteDraft = { dTag ->
                    draftsViewModel.deleteDraft(dTag, feedViewModel.relayPool, activeSigner, feedViewModel.deletedEventsRepo)
                },
                onDeleteScheduled = { eventId ->
                    draftsViewModel.deleteScheduledPost(eventId, feedViewModel.relayPool, activeSigner)
                },
                userProfile = userProfile
            )
        }

        composable(Routes.RELAYS) {
            RelayScreen(
                viewModel = relayViewModel,
                relayPool = feedViewModel.relayPool,
                onBack = {
                    feedViewModel.refreshRelays()
                    navController.popBackStack()
                },
                signer = activeSigner
            )
        }

        composable(Routes.BLOSSOM_SERVERS) {
            LaunchedEffect(Unit) { blossomServersViewModel.refreshServers() }
            BlossomServersScreen(
                viewModel = blossomServersViewModel,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                signer = activeSigner
            )
        }

        composable(Routes.WALLET) {
            WalletScreen(
                viewModel = walletViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROFILE_EDIT) {
            ProfileEditScreen(
                viewModel = profileViewModel,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                signer = activeSigner
            )
        }

        composable(
            Routes.USER_PROFILE,
            arguments = listOf(navArgument("pubkey") { type = NavType.StringType })
        ) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            val isOwnProfile = pubkey == feedViewModel.getUserPubkey()
            val userProfileViewModel: UserProfileViewModel = viewModel()
            LaunchedEffect(pubkey) {
                userProfileViewModel.loadProfile(
                    pubkey = pubkey,
                    eventRepo = feedViewModel.eventRepo,
                    contactRepo = feedViewModel.contactRepo,
                    relayPool = feedViewModel.relayPool,
                    outboxRouter = feedViewModel.outboxRouter,
                    relayListRepo = feedViewModel.relayListRepo,
                    subManager = feedViewModel.subManager,
                    topRelayUrls = feedViewModel.getScoredRelays().take(5).map { it.url },
                    relayHintStore = feedViewModel.relayHintStore,
                    extendedNetworkRepo = feedViewModel.extendedNetworkRepo
                )
            }
            val isBlockedState by feedViewModel.muteRepo.blockedPubkeys.collectAsState()
            val profileSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val profileBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val profileListedIds = remember(profileSetListedIds, profileBookmarkedIds) { profileSetListedIds + profileBookmarkedIds }
            val profilePinnedIds by if (isOwnProfile) feedViewModel.pinRepo.pinnedIds.collectAsState() else userProfileViewModel.pinnedNoteIds.collectAsState()
            val profileZapInProgress by feedViewModel.zapInProgress.collectAsState()
            val profileResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val profileUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            var showProfileEmojiLibrary by remember { mutableStateOf(false) }
            UserProfileScreen(
                viewModel = userProfileViewModel,
                contactRepo = feedViewModel.contactRepo,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                eventRepo = feedViewModel.eventRepo,
                onNavigateToProfile = { pubkey -> navController.navigate("profile/$pubkey") },
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                isOwnProfile = isOwnProfile,
                onEditProfile = {
                    profileViewModel.loadCurrentProfile(feedViewModel.eventRepo, feedViewModel.relayPool)
                    navController.navigate(Routes.PROFILE_EDIT)
                },
                isBlocked = pubkey in isBlockedState,
                onBlockUser = {
                    feedViewModel.blockUser(pubkey)
                    navController.popBackStack()
                },
                onUnblockUser = { feedViewModel.unblockUser(pubkey) },
                onNoteClick = { event -> navController.navigate("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onZap = { event, amountMsats, message, isAnonymous, isPrivate -> feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate) },
                userPubkey = feedViewModel.getUserPubkey(),
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onWallet = { navController.navigate(Routes.WALLET) },
                zapSuccess = feedViewModel.zapSuccess,
                zapError = feedViewModel.zapError,
                zapInProgressIds = profileZapInProgress,
                canPrivateZap = feedViewModel.hasLocalKeypair && feedViewModel.relayPool.hasDmRelays() && feedViewModel.relayListRepo.hasDmRelays(pubkey),
                fetchDmRelays = { pk -> feedViewModel.fetchDmRelaysIfMissing(pk) && feedViewModel.relayPool.hasDmRelays() },
                ownLists = feedViewModel.listRepo.ownLists.collectAsState().value,
                onAddToList = { dTag, pk -> feedViewModel.addToList(dTag, pk) },
                onRemoveFromList = { dTag, pk -> feedViewModel.removeFromList(dTag, pk) },
                onCreateList = { name, isPrivate -> feedViewModel.createList(name, isPrivate) },
                profilePubkey = pubkey,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                nip05Repo = feedViewModel.nip05Repo,
                listedIds = profileListedIds,
                pinnedIds = profilePinnedIds,
                onTogglePin = { eventId -> feedViewModel.togglePin(eventId) },
                onDeleteEvent = { eventId, kind -> feedViewModel.deleteEvent(eventId, kind) },
                onAddNoteToList = { eventId -> addToListEventId = eventId },
                onSendDm = if (!isOwnProfile) {{ navController.navigate("dm/$pubkey") }} else null,
                onZapProfile = if (!isOwnProfile) { { amountMsats, message, isAnonymous ->
                    feedViewModel.socialActions.sendZapToPubkey(pubkey, amountMsats, message, isAnonymous)
                } } else null,
                signer = activeSigner,
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onArticleClick = { kind, articleAuthor, articleDTag ->
                    navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, articleAuthor, articleDTag)
                },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                resolvedEmojis = profileResolvedEmojis,
                unicodeEmojis = profileUnicodeEmojis,
                onOpenEmojiLibrary = { showProfileEmojiLibrary = true },
                onSearchAuthor = {
                    val authorProfile = userProfileViewModel.profile.value
                        ?: ProfileData(pubkey = pubkey, name = null, displayName = null, about = null, picture = null, nip05 = null, banner = null, lud16 = null, updatedAt = 0)
                    searchViewModel.prepareAuthorSearch(authorProfile)
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigate("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigate(route)
                },
                fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                isEmojiSetAdded = { pk, dTag ->
                    val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pk, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                },
                onMuteUser = if (!isOwnProfile) { { feedViewModel.blockUser(pubkey) } } else null
            )
            if (showProfileEmojiLibrary) {
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = profileUnicodeEmojis,
                    customEmojiMap = profileResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showProfileEmojiLibrary = false
                    },
                    onDismiss = { showProfileEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(Routes.SEARCH) {
            val searchSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val searchBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val searchListedIds = remember(searchSetListedIds, searchBookmarkedIds) { searchSetListedIds + searchBookmarkedIds }
            var searchZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val searchZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var searchZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    searchZapAnimatingIds = searchZapAnimatingIds + eventId
                    delay(1500)
                    searchZapAnimatingIds = searchZapAnimatingIds - eventId
                }
            }
            if (searchZapTarget != null) {
                val zapRecipient = searchZapTarget!!.pubkey
                val userHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var recipientHasDmRelays by remember(zapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(zapRecipient))
                }
                if (userHasDmRelays && !recipientHasDmRelays) {
                    LaunchedEffect(zapRecipient) {
                        recipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(zapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                    onDismiss = { searchZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = searchZapTarget ?: return@ZapDialog
                        searchZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && userHasDmRelays && recipientHasDmRelays
                )
            }
            SearchScreen(
                viewModel = searchViewModel,
                relayPool = feedViewModel.relayPool,
                eventRepo = feedViewModel.eventRepo,
                muteRepo = feedViewModel.muteRepo,
                contactRepo = feedViewModel.contactRepo,
                recipeRepo = feedViewModel.recipeRepo,
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onNoteClick = { event ->
                    navController.navigate("thread/${event.id}")
                },
                onRecipeClick = { author, dTag ->
                    navController.navigate(Routes.recipe(author, dTag))
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onZap = { event ->
                    searchZapTarget = event
                },
                zapInProgress = searchZapInProgress,
                zapAnimatingIds = searchZapAnimatingIds,
                onToggleFollow = { pubkey ->
                    feedViewModel.toggleFollow(pubkey)
                },
                onHashtagClick = { tag ->
                    navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onTagClick = { tag ->
                    navController.navigate(Routes.recipeTag(tag))
                },
                onBlockUser = { pubkey ->
                    feedViewModel.blockUser(pubkey)
                },
                userPubkey = feedViewModel.getUserPubkey(),
                listedIds = searchListedIds,
                onAddToList = { eventId -> addToListEventId = eventId },
                onDeleteEvent = { eventId, kind -> feedViewModel.deleteEvent(eventId, kind) },
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                isEmojiSetAdded = { pubkey, dTag ->
                    val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                },
                nip05Repo = feedViewModel.nip05Repo
            )
        }

        composable(Routes.DM_LIST) {
            LaunchedEffect(Unit) {
                refreshInboxSubscriptionsIfStale()
                // Decrypt pending gift wraps when signer is available
                activeSigner?.let { dmListViewModel.decryptPending(it) }
                dmListViewModel.markDmsRead()
                // Fetch profile metadata for all DM peers
                val allParticipants = dmListViewModel.conversationList.value.flatMap { it.participants }
                for (pubkey in allParticipants) {
                    feedViewModel.metadataFetcher.queueProfileFetch(pubkey)
                }
            }
            DmListScreen(
                viewModel = dmListViewModel,
                groupListViewModel = groupListViewModel,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                signer = activeSigner,
                onBack = null,
                onConversation = { convo ->
                    if (convo.isGroup) {
                        navController.navigate("dm/group/${convo.conversationKey.replace(",", "~")}")
                    } else {
                        navController.navigate("dm/${convo.peerPubkey}")
                    }
                },
                onNewGroupDm = {
                    navController.navigate(Routes.CONTACT_PICKER)
                },
                onGroupRoom = { relayUrl, groupId ->
                    val encoded = android.util.Base64.encodeToString(
                        relayUrl.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    )
                    navController.navigate("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                }
            )
        }

        composable(Routes.CONTACT_PICKER) {
            val userPubkey = feedViewModel.getUserPubkey() ?: return@composable
            ContactPickerScreen(
                viewModel = dmListViewModel,
                eventRepo = feedViewModel.eventRepo,
                contactRepo = feedViewModel.contactRepo,
                onBack = { navController.popBackStack() },
                onConfirm = { conversationKey ->
                    navController.popBackStack()
                    navController.navigate("dm/group/${conversationKey.replace(",", "~")}")
                },
                myPubkey = userPubkey
            )
        }

        composable(
            Routes.DM_CONVERSATION,
            arguments = listOf(navArgument("pubkey") { type = NavType.StringType })
        ) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            val dmConvoViewModel: DmConversationViewModel = viewModel()
            val userPubkey = feedViewModel.getUserPubkey()
            LaunchedEffect(pubkey) {
                refreshInboxSubscriptionsIfStale()
                dmConvoViewModel.init(
                    peerPubkeyHex = pubkey,
                    dmRepository = feedViewModel.dmRepo,
                    relayListRepository = feedViewModel.relayListRepo,
                    relayPool = feedViewModel.relayPool,
                    powPreferences = feedViewModel.powPrefs,
                    myPubkeyHex = userPubkey
                )
                activeSigner?.let { dmConvoViewModel.decryptPending(it, feedViewModel.muteRepo) }
            }
            val peerProfile = feedViewModel.eventRepo.getProfileData(pubkey)
            val userProfile = userPubkey?.let { feedViewModel.eventRepo.getProfileData(it) }
            val dmResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val dmUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            var showDmEmojiLibrary by remember { mutableStateOf(false) }
            DmConversationScreen(
                viewModel = dmConvoViewModel,
                relayPool = feedViewModel.relayPool,
                peerProfile = peerProfile,
                userProfile = userProfile,
                userPubkey = userPubkey,
                eventRepo = feedViewModel.eventRepo,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                onBack = { navController.popBackStack() },
                onProfileClick = { pk -> navController.navigate("profile/$pk") },
                onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                peerPubkey = pubkey,
                signer = activeSigner,
                socialActionManager = feedViewModel.socialActions,
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onGoToWallet = { navController.navigate(Routes.WALLET) },
                noteActions = remember {
                    cooking.zap.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        },
                        resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                        unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value },
                        onOpenEmojiLibrary = { showDmEmojiLibrary = true }
                    )
                },
                resolvedEmojis = dmResolvedEmojis,
                unicodeEmojis = dmUnicodeEmojis,
                onOpenEmojiLibrary = { showDmEmojiLibrary = true },
                onEmojiUsed = { feedViewModel.customEmojiRepo.recordEmojiUsage(it) }
            )
            if (showDmEmojiLibrary) {
                val dmSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                val dmSheetResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = dmSheetUnicodeEmojis,
                    customEmojiMap = dmSheetResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showDmEmojiLibrary = false
                    },
                    onDismiss = { showDmEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(
            Routes.DM_CONVERSATION_GROUP,
            arguments = listOf(navArgument("conversationKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedKey = backStackEntry.arguments?.getString("conversationKey") ?: return@composable
            val convKey = encodedKey.replace("~", ",")
            val participantList = convKey.split(",").filter { it != feedViewModel.getUserPubkey() }
            val dmConvoViewModel: DmConversationViewModel = viewModel()
            val userPubkey = feedViewModel.getUserPubkey()
            LaunchedEffect(convKey) {
                refreshInboxSubscriptionsIfStale()
                dmConvoViewModel.init(
                    peerPubkeyHex = participantList.firstOrNull() ?: "",
                    dmRepository = feedViewModel.dmRepo,
                    relayListRepository = feedViewModel.relayListRepo,
                    relayPool = feedViewModel.relayPool,
                    powPreferences = feedViewModel.powPrefs,
                    myPubkeyHex = userPubkey,
                    participantPubkeys = participantList
                )
                activeSigner?.let { dmConvoViewModel.decryptPending(it, feedViewModel.muteRepo) }
                for (pubkey in participantList) {
                    feedViewModel.metadataFetcher.queueProfileFetch(pubkey)
                }
            }
            val peerProfile = participantList.firstOrNull()?.let { feedViewModel.eventRepo.getProfileData(it) }
            val userProfile = userPubkey?.let { feedViewModel.eventRepo.getProfileData(it) }
            val dmGroupResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val dmGroupUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            var showDmGroupEmojiLibrary by remember { mutableStateOf(false) }
            DmConversationScreen(
                viewModel = dmConvoViewModel,
                relayPool = feedViewModel.relayPool,
                peerProfile = peerProfile,
                userProfile = userProfile,
                userPubkey = userPubkey,
                eventRepo = feedViewModel.eventRepo,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                onBack = { navController.popBackStack() },
                onProfileClick = { pk -> navController.navigate("profile/$pk") },
                onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                peerPubkey = participantList.firstOrNull() ?: "",
                participants = participantList,
                signer = activeSigner,
                socialActionManager = feedViewModel.socialActions,
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onGoToWallet = { navController.navigate(Routes.WALLET) },
                noteActions = remember {
                    cooking.zap.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        },
                        resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                        unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value },
                        onOpenEmojiLibrary = { showDmGroupEmojiLibrary = true }
                    )
                },
                resolvedEmojis = dmGroupResolvedEmojis,
                unicodeEmojis = dmGroupUnicodeEmojis,
                onOpenEmojiLibrary = { showDmGroupEmojiLibrary = true },
                onEmojiUsed = { feedViewModel.customEmojiRepo.recordEmojiUsage(it) }
            )
            if (showDmGroupEmojiLibrary) {
                val dmGroupSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                val dmGroupSheetResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = dmGroupSheetUnicodeEmojis,
                    customEmojiMap = dmGroupSheetResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showDmGroupEmojiLibrary = false
                    },
                    onDismiss = { showDmGroupEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(
            Routes.GROUP_ROOM,
            arguments = listOf(
                navArgument("encodedRelay") { type = NavType.StringType },
                navArgument("groupId") { type = NavType.StringType },
                navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val encodedRelay = backStackEntry.arguments?.getString("encodedRelay") ?: return@composable
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val scrollToMessageId = backStackEntry.arguments?.getString("scrollTo")
            val relayUrl = String(
                android.util.Base64.decode(encodedRelay, android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )
            val groupRoomViewModel: cooking.zap.app.viewmodel.GroupRoomViewModel = viewModel()
            // Pre-compute from current repo state so the first composition doesn't flash
            // the join screen for groups the user has already joined.
            val initialRoom = remember(relayUrl, groupId) {
                feedViewModel.groupRepo.getRoom(relayUrl, groupId)
            }
            LaunchedEffect(relayUrl, groupId) {
                groupRoomViewModel.init(groupId, relayUrl, feedViewModel.groupRepo, feedViewModel.relayPool)
                feedViewModel.metadataFetcher.queueProfileFetch(feedViewModel.getUserPubkey() ?: "")
                groupListViewModel.markGroupRead(relayUrl, groupId)
            }
            // Only subscribe eagerly if the group is already joined locally —
            // otherwise messages arrive before the room exists in the repo and get
            // silently dropped.  joinGroup/silentJoin handle their own subscriptions.
            DisposableEffect(relayUrl, groupId) {
                if (initialRoom != null) {
                    groupListViewModel.subscribeToGroup(relayUrl, groupId)
                }
                onDispose {
                    groupListViewModel.markGroupRead(relayUrl, groupId)
                    groupListViewModel.unsubscribeFromGroup(relayUrl, groupId)
                }
            }
            val groupRoomContext = LocalContext.current
            val groupRoomUploadScope = rememberCoroutineScope()
            var groupRoomUploadProgress by remember { mutableStateOf<String?>(null) }
            var groupRoomZapTarget by remember { mutableStateOf<cooking.zap.app.nostr.NostrEvent?>(null) }
            var groupRoomZapInitialSats by remember { mutableStateOf<Int?>(null) }
            var showGroupRoomEmojiLibrary by remember { mutableStateOf(false) }
            val groupRoomResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val groupRoomUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            val groupRoomZapVersion by feedViewModel.eventRepo.zapVersion.collectAsState()
            val groupRoomZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var groupRoomZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    groupRoomZapAnimatingIds = groupRoomZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    groupRoomZapAnimatingIds = groupRoomZapAnimatingIds - eventId
                }
            }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()

            if (groupRoomZapTarget != null) {
                val zapRecipient = groupRoomZapTarget!!.pubkey
                var recipientHasDmRelays by remember(zapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(zapRecipient))
                }
                if (feedViewModel.relayPool.hasDmRelays() && !recipientHasDmRelays) {
                    LaunchedEffect(zapRecipient) {
                        recipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(zapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = {
                        groupRoomZapTarget = null
                        groupRoomZapInitialSats = null
                    },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = groupRoomZapTarget ?: return@ZapDialog
                        groupRoomZapTarget = null
                        groupRoomZapInitialSats = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && feedViewModel.relayPool.hasDmRelays() && recipientHasDmRelays,
                    initialSatsHint = groupRoomZapInitialSats
                )
            }
            val groupRoomMediaLauncher = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
            ) { uris ->
                if (uris.isNotEmpty()) {
                    groupRoomUploadScope.launch {
                        val total = uris.size
                        for ((index, uri) in uris.withIndex()) {
                            try {
                                groupRoomUploadProgress = if (total > 1) "Uploading ${index + 1}/$total..." else "Uploading..."
                                val bytes = groupRoomContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: continue
                                val mimeType = groupRoomContext.contentResolver.getType(uri) ?: "application/octet-stream"
                                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                                val url = feedViewModel.blossomRepo.uploadMedia(bytes, mimeType, ext, activeSigner)
                                groupRoomViewModel.appendToText(url)
                            } catch (_: Exception) { }
                        }
                        groupRoomUploadProgress = null
                    }
                }
            }
            cooking.zap.app.ui.screen.GroupRoomScreen(
                viewModel = groupRoomViewModel,
                initialRoom = initialRoom,
                scrollToMessageId = scrollToMessageId,
                relayPool = feedViewModel.relayPool,
                eventRepo = feedViewModel.eventRepo,
                signer = activeSigner,
                onBack = { navController.popBackStack() },
                onProfileClick = { pk -> navController.navigate("profile/$pk") },
                onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                onGroupDetail = { navController.navigate("group_detail/$encodedRelay/${android.net.Uri.encode(groupId)}") },
                onJoin = { groupListViewModel.joinGroup(relayUrl, groupId, activeSigner) },
                onAlreadyMember = { groupListViewModel.silentJoin(relayUrl, groupId, activeSigner) },
                fetchGroupPreview = { rUrl, gId -> groupListViewModel.fetchGroupPreview(rUrl, gId) },
                onPickMedia = {
                    groupRoomMediaLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                },
                onUploadMedia = { uris, onUrl ->
                    groupRoomUploadScope.launch {
                        val total = uris.size
                        for ((index, uri) in uris.withIndex()) {
                            try {
                                groupRoomUploadProgress = if (total > 1) "Uploading ${index + 1}/$total..." else "Uploading..."
                                val bytes = groupRoomContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: continue
                                val mimeType = groupRoomContext.contentResolver.getType(uri) ?: "application/octet-stream"
                                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                                val url = feedViewModel.blossomRepo.uploadMedia(bytes, mimeType, ext, activeSigner)
                                onUrl(url)
                            } catch (_: Exception) { }
                        }
                        groupRoomUploadProgress = null
                    }
                },
                uploadProgress = groupRoomUploadProgress,
                myPubkey = feedViewModel.getUserPubkey(),
                onZap = { msgId, senderPubkey ->
                    groupRoomZapInitialSats = null
                    groupRoomZapTarget = cooking.zap.app.nostr.NostrEvent(
                        id = msgId,
                        pubkey = senderPubkey,
                        created_at = 0L,
                        kind = cooking.zap.app.nostr.Nip29.KIND_CHAT_MESSAGE,
                        tags = listOf(listOf("h", groupId)),
                        content = "",
                        sig = ""
                    )
                    feedViewModel.relayPool.sendToRelayOrEphemeral(
                        relayUrl,
                        cooking.zap.app.nostr.ClientMessage.req(
                            subscriptionId = "zap-rcpt-grp-${msgId.take(12)}",
                            filter = cooking.zap.app.nostr.Filter(kinds = listOf(9735), eTags = listOf(msgId))
                        ),
                        skipBadCheck = true
                    )
                },
                onZapPreset = { msgId, senderPubkey, sats ->
                    groupRoomZapInitialSats = sats
                    groupRoomZapTarget = cooking.zap.app.nostr.NostrEvent(
                        id = msgId,
                        pubkey = senderPubkey,
                        created_at = 0L,
                        kind = cooking.zap.app.nostr.Nip29.KIND_CHAT_MESSAGE,
                        tags = listOf(listOf("h", groupId)),
                        content = "",
                        sig = ""
                    )
                    feedViewModel.relayPool.sendToRelayOrEphemeral(
                        relayUrl,
                        cooking.zap.app.nostr.ClientMessage.req(
                            subscriptionId = "zap-rcpt-grp-${msgId.take(12)}",
                            filter = cooking.zap.app.nostr.Filter(kinds = listOf(9735), eTags = listOf(msgId))
                        ),
                        skipBadCheck = true
                    )
                },
                resolvedEmojis = groupRoomResolvedEmojis,
                unicodeEmojis = groupRoomUnicodeEmojis,
                zapVersion = groupRoomZapVersion,
                zapAnimatingIds = groupRoomZapAnimatingIds,
                zapInProgressIds = groupRoomZapInProgress,
                onOpenEmojiLibrary = { showGroupRoomEmojiLibrary = true },
                onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                noteActions = remember {
                    cooking.zap.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        },
                        resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                        unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value },
                        onOpenEmojiLibrary = { showGroupRoomEmojiLibrary = true }
                    )
                },
                onEmojiUsed = { feedViewModel.customEmojiRepo.recordEmojiUsage(it) },
                onRemoveUser = { pubkey ->
                    groupListViewModel.removeUser(relayUrl, groupId, pubkey, activeSigner)
                }
            )
            if (showGroupRoomEmojiLibrary) {
                val groupRoomSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                val groupRoomSheetResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = groupRoomSheetUnicodeEmojis,
                    customEmojiMap = groupRoomSheetResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showGroupRoomEmojiLibrary = false
                    },
                    onDismiss = { showGroupRoomEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(
            Routes.GROUP_DETAIL,
            arguments = listOf(
                navArgument("encodedRelay") { type = NavType.StringType },
                navArgument("groupId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedRelay = backStackEntry.arguments?.getString("encodedRelay") ?: return@composable
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val relayUrl = String(
                android.util.Base64.decode(encodedRelay, android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )
            val groupDetailContext = LocalContext.current
            val groupPictureUploadScope = rememberCoroutineScope()
            var pendingPictureCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
            val groupPictureLauncher = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                val callback = pendingPictureCallback ?: return@rememberLauncherForActivityResult
                pendingPictureCallback = null
                uri ?: run { callback(""); return@rememberLauncherForActivityResult }
                groupPictureUploadScope.launch {
                    try {
                        val bytes = groupDetailContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@launch
                        val mimeType = groupDetailContext.contentResolver.getType(uri) ?: "image/jpeg"
                        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                        val url = feedViewModel.blossomRepo.uploadMedia(bytes, mimeType, ext, activeSigner)
                        callback(url)
                    } catch (_: Exception) { callback("") }
                }
            }
            cooking.zap.app.ui.screen.GroupDetailScreen(
                groupId = groupId,
                relayUrl = relayUrl,
                groupListViewModel = groupListViewModel,
                eventRepo = feedViewModel.eventRepo,
                myPubkey = feedViewModel.getUserPubkey(),
                signer = activeSigner,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                onLeave = {
                    navController.navigate(Routes.DM_LIST) {
                        popUpTo(Routes.DM_LIST) { inclusive = false }
                    }
                },
                onDelete = {
                    navController.navigate(Routes.DM_LIST) {
                        popUpTo(Routes.DM_LIST) { inclusive = false }
                    }
                },
                onPickPicture = { callback ->
                    pendingPictureCallback = callback
                    groupPictureLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            )
        }

        composable(
            Routes.THREAD,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId") ?: return@composable
            val threadViewModel: ThreadViewModel = viewModel()
            LaunchedEffect(eventId) {
                feedViewModel.pauseEngagement()
                threadViewModel.loadThread(
                    eventId = eventId,
                    eventRepo = feedViewModel.eventRepo,
                    relayPool = feedViewModel.relayPool,
                    outboxRouter = feedViewModel.outboxRouter,
                    subManager = feedViewModel.subManager,
                    metadataFetcher = feedViewModel.metadataFetcher,
                    muteRepo = feedViewModel.muteRepo,
                    topRelayUrls = feedViewModel.getScoredRelays().take(5).map { it.url },
                    relayListRepo = feedViewModel.relayListRepo,
                    relayHintStore = feedViewModel.relayHintStore,
                    spamClassifier = feedViewModel.nspamClassifier,
                    spamAuthorCache = feedViewModel.spamAuthorCache,
                    safetyPrefs = feedViewModel.safetyPrefs,
                    contactRepo = feedViewModel.contactRepo
                )
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { feedViewModel.resumeEngagement() }
            }
            var threadZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val threadZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var threadZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            var showThreadEmojiLibrary by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    threadZapAnimatingIds = threadZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    threadZapAnimatingIds = threadZapAnimatingIds - eventId
                }
            }

            if (threadZapTarget != null) {
                val threadZapRecipient = threadZapTarget!!.pubkey
                val threadUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var threadRecipientHasDmRelays by remember(threadZapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(threadZapRecipient))
                }
                if (threadUserHasDmRelays && !threadRecipientHasDmRelays) {
                    LaunchedEffect(threadZapRecipient) {
                        threadRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(threadZapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { threadZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = threadZapTarget ?: return@ZapDialog
                        threadZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && threadUserHasDmRelays && threadRecipientHasDmRelays,
                    forcePrivate = threadZapTarget?.id?.let { feedViewModel.eventRepo.isPrivate(it) } == true
                )
            }
            val threadSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val threadBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val threadListedIds = remember(threadSetListedIds, threadBookmarkedIds) { threadSetListedIds + threadBookmarkedIds }
            val threadPinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            val threadResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val threadUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            ThreadScreen(
                viewModel = threadViewModel,
                eventRepo = feedViewModel.eventRepo,
                contactRepo = feedViewModel.contactRepo,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                nip05Repo = feedViewModel.nip05Repo,
                userPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onNoteClick = { event ->
                    navController.navigate("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onToggleFollow = { pubkey ->
                    feedViewModel.toggleFollow(pubkey)
                },
                onBlockUser = { pubkey ->
                    feedViewModel.blockUser(pubkey)
                },
                onZap = { event -> threadZapTarget = event },
                zapAnimatingIds = threadZapAnimatingIds,
                zapInProgressIds = threadZapInProgress,
                listedIds = threadListedIds,
                pinnedIds = threadPinnedIds,
                onTogglePin = { eventId -> feedViewModel.togglePin(eventId) },
                onDeleteEvent = { eventId, kind -> feedViewModel.deleteEvent(eventId, kind) },
                onAddToList = { eventId -> addToListEventId = eventId },
                onHashtagClick = { tag ->
                    navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onRelayClick = { url ->
                    feedViewModel.setSelectedRelay(url)
                    feedViewModel.setFeedType(FeedType.RELAY)
                    navController.popBackStack(Routes.FEED, inclusive = false)
                },
                onArticleClick = { kind, author, dTag ->
                    navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, author, dTag)
                },
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                resolvedEmojis = threadResolvedEmojis,
                unicodeEmojis = threadUnicodeEmojis,
                onOpenEmojiLibrary = { showThreadEmojiLibrary = true },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigate("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigate(route)
                },
                fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                isEmojiSetAdded = { pubkey, dTag ->
                    val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                },
                canPrivateZapFor = { event ->
                    feedViewModel.hasLocalKeypair &&
                        feedViewModel.relayPool.hasDmRelays() &&
                        feedViewModel.relayListRepo.hasDmRelays(event.pubkey)
                }
            )

            if (showThreadEmojiLibrary) {
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = threadUnicodeEmojis,
                    customEmojiMap = threadResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showThreadEmojiLibrary = false
                    },
                    onDismiss = { showThreadEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(
            Routes.HASHTAG_FEED,
            arguments = listOf(navArgument("tag") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedTag = backStackEntry.arguments?.getString("tag") ?: return@composable
            val tag = java.net.URLDecoder.decode(encodedTag, "UTF-8")
            val hashtagFeedViewModel: HashtagFeedViewModel = viewModel()
            LaunchedEffect(tag) {
                hashtagFeedViewModel.loadHashtag(
                    tag = tag,
                    relayPool = feedViewModel.relayPool,
                    eventRepo = feedViewModel.eventRepo,
                    muteRepo = feedViewModel.muteRepo,
                    outboxRouter = feedViewModel.outboxRouter,
                    relayScoreBoard = feedViewModel.relayScoreBoard
                )
            }
            var hashtagZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val hashtagZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var hashtagZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isHashtagNwcConnected = feedViewModel.activeWalletProvider.hasConnection()

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    hashtagZapAnimatingIds = hashtagZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    hashtagZapAnimatingIds = hashtagZapAnimatingIds - eventId
                }
            }

            if (hashtagZapTarget != null) {
                val hashtagZapRecipient = hashtagZapTarget!!.pubkey
                val hashtagUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var hashtagRecipientHasDmRelays by remember(hashtagZapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(hashtagZapRecipient))
                }
                if (hashtagUserHasDmRelays && !hashtagRecipientHasDmRelays) {
                    LaunchedEffect(hashtagZapRecipient) {
                        hashtagRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(hashtagZapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isHashtagNwcConnected,
                    onDismiss = { hashtagZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = hashtagZapTarget ?: return@ZapDialog
                        hashtagZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && hashtagUserHasDmRelays && hashtagRecipientHasDmRelays
                )
            }

            val hashtagNoteActions = remember {
                cooking.zap.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onZap = { event -> hashtagZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { clickedTag ->
                        navController.navigate("hashtag/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}")
                    },
                    onRelayClick = { url ->
                        feedViewModel.setSelectedRelay(url)
                        feedViewModel.setFeedType(FeedType.RELAY)
                        navController.popBackStack(Routes.FEED, inclusive = false)
                    },
                    onArticleClick = { kind, author, dTag ->
                        navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, author, dTag)
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigate("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    },
                    resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                    unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value }
                )
            }
            val interestSets by feedViewModel.interestRepo.sets.collectAsState()
            val interestSetsFetched by feedViewModel.interestSetsFetched.collectAsState()
            LaunchedEffect(Unit) {
                feedViewModel.fetchInterestSetsIfMissing()
            }
            HashtagFeedScreen(
                viewModel = hashtagFeedViewModel,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                noteActions = hashtagNoteActions,
                interestSets = interestSets,
                interestSetsLoaded = interestSetsFetched,
                onFollowHashtag = { dTag -> feedViewModel.followHashtag(tag, dTag) },
                onUnfollowHashtag = { dTag -> feedViewModel.unfollowHashtag(tag, dTag) },
                onCreateDefaultSet = {
                    feedViewModel.createInterestSet("Interests")
                    feedViewModel.followHashtag(tag, "interests")
                },
                nip05Repo = feedViewModel.nip05Repo,
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onHashtagPicker = {
                    feedViewModel.requestHashtagPicker()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                zapAnimatingIds = hashtagZapAnimatingIds,
                zapInProgressIds = hashtagZapInProgress
            )
        }

        composable(
            Routes.HASHTAG_SET_FEED,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("tags") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedName = backStackEntry.arguments?.getString("name") ?: return@composable
            val encodedTags = backStackEntry.arguments?.getString("tags") ?: return@composable
            val name = java.net.URLDecoder.decode(encodedName, "UTF-8")
            val tags = java.net.URLDecoder.decode(encodedTags, "UTF-8").split(",").filter { it.isNotBlank() }
            if (tags.isEmpty()) return@composable

            val hashtagFeedViewModel: HashtagFeedViewModel = viewModel()
            LaunchedEffect(tags) {
                hashtagFeedViewModel.loadHashtags(
                    tags = tags,
                    name = name,
                    relayPool = feedViewModel.relayPool,
                    eventRepo = feedViewModel.eventRepo,
                    muteRepo = feedViewModel.muteRepo,
                    outboxRouter = feedViewModel.outboxRouter,
                    relayScoreBoard = feedViewModel.relayScoreBoard
                )
            }
            var setFeedZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val setFeedZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var setFeedZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isSetFeedNwcConnected = feedViewModel.activeWalletProvider.hasConnection()

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    setFeedZapAnimatingIds = setFeedZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    setFeedZapAnimatingIds = setFeedZapAnimatingIds - eventId
                }
            }

            if (setFeedZapTarget != null) {
                val setFeedZapRecipient = setFeedZapTarget!!.pubkey
                val setFeedUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var setFeedRecipientHasDmRelays by remember(setFeedZapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(setFeedZapRecipient))
                }
                if (setFeedUserHasDmRelays && !setFeedRecipientHasDmRelays) {
                    LaunchedEffect(setFeedZapRecipient) {
                        setFeedRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(setFeedZapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isSetFeedNwcConnected,
                    onDismiss = { setFeedZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = setFeedZapTarget ?: return@ZapDialog
                        setFeedZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && setFeedUserHasDmRelays && setFeedRecipientHasDmRelays
                )
            }

            val setFeedNoteActions = remember {
                cooking.zap.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onZap = { event -> setFeedZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { clickedTag ->
                        navController.navigate("hashtag/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}")
                    },
                    onRelayClick = { url ->
                        feedViewModel.setSelectedRelay(url)
                        feedViewModel.setFeedType(FeedType.RELAY)
                        navController.popBackStack(Routes.FEED, inclusive = false)
                    },
                    onArticleClick = { kind, author, dTag ->
                        navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, author, dTag)
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigate("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    },
                    resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                    unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value }
                )
            }
            val interestSets by feedViewModel.interestRepo.sets.collectAsState()
            val interestSetsFetched by feedViewModel.interestSetsFetched.collectAsState()
            LaunchedEffect(Unit) {
                feedViewModel.fetchInterestSetsIfMissing()
            }
            HashtagFeedScreen(
                viewModel = hashtagFeedViewModel,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                noteActions = setFeedNoteActions,
                interestSets = interestSets,
                interestSetsLoaded = interestSetsFetched,
                onFollowHashtag = { dTag -> tags.forEach { feedViewModel.followHashtag(it, dTag) } },
                onUnfollowHashtag = { dTag -> tags.forEach { feedViewModel.unfollowHashtag(it, dTag) } },
                onCreateDefaultSet = {
                    feedViewModel.createInterestSet("Interests")
                    tags.forEach { feedViewModel.followHashtag(it, "interests") }
                },
                nip05Repo = feedViewModel.nip05Repo,
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onHashtagPicker = {
                    feedViewModel.requestHashtagPicker()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                zapAnimatingIds = setFeedZapAnimatingIds,
                zapInProgressIds = setFeedZapInProgress
            )
        }

        composable(
            Routes.ARTICLE,
            arguments = listOf(
                navArgument("kind") { type = NavType.IntType },
                navArgument("author") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val kind = backStackEntry.arguments?.getInt("kind") ?: return@composable
            val author = backStackEntry.arguments?.getString("author") ?: return@composable
            val dTag = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("dTag") ?: return@composable, "UTF-8"
            )
            val articleViewModel: ArticleViewModel = viewModel()
            LaunchedEffect(kind, author, dTag) {
                articleViewModel.loadArticle(kind, author, dTag, feedViewModel.eventRepo)
                val articleEvent = feedViewModel.eventRepo.findAddressableEvent(kind, author, dTag)
                articleViewModel.loadComments(
                    author = author,
                    dTag = dTag,
                    articleEventId = articleEvent?.id,
                    eventRepo = feedViewModel.eventRepo,
                    relayPool = feedViewModel.relayPool,
                    outboxRouter = feedViewModel.outboxRouter,
                    subManager = feedViewModel.subManager,
                    metadataFetcher = feedViewModel.metadataFetcher,
                    topRelayUrls = feedViewModel.getScoredRelays().take(5).map { it.url },
                    relayListRepo = feedViewModel.relayListRepo,
                    relayHintStore = feedViewModel.relayHintStore
                )
            }
            var articleZapTarget by remember { mutableStateOf<cooking.zap.app.nostr.NostrEvent?>(null) }
            val articleZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var articleZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            val articleSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val articleBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val articleListedIds = remember(articleSetListedIds, articleBookmarkedIds) { articleSetListedIds + articleBookmarkedIds }
            val articlePinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            val articleResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val articleUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            var showArticleEmojiLibrary by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    articleZapAnimatingIds = articleZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    articleZapAnimatingIds = articleZapAnimatingIds - eventId
                }
            }

            if (articleZapTarget != null) {
                val zapRecipient = articleZapTarget!!.pubkey
                val articleUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var articleRecipientHasDmRelays by remember(zapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(zapRecipient))
                }
                if (articleUserHasDmRelays && !articleRecipientHasDmRelays) {
                    LaunchedEffect(zapRecipient) {
                        articleRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(zapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { articleZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = articleZapTarget ?: return@ZapDialog
                        articleZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && articleUserHasDmRelays && articleRecipientHasDmRelays
                )
            }

            val articleNoteActions = remember {
                cooking.zap.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onZap = { event -> articleZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { tag ->
                        navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                    },
                    onArticleClick = { k, a, d ->
                        navController.openArticleOrRecipe(feedViewModel.eventRepo, k, a, d)
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigate("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    },
                    resolvedEmojisProvider = { feedViewModel.customEmojiRepo.resolvedEmojis.value },
                    unicodeEmojisProvider = { feedViewModel.customEmojiRepo.sortedUnicodeEmojis.value },
                    onOpenEmojiLibrary = { showArticleEmojiLibrary = true }
                )
            }

            ArticleScreen(
                viewModel = articleViewModel,
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onHashtagClick = { tag ->
                    navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onRepost = { event -> feedViewModel.sendRepost(event) },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onZap = { event -> articleZapTarget = event },
                onAddToList = { eventId -> addToListEventId = eventId },
                noteActions = articleNoteActions,
                zapAnimatingIds = articleZapAnimatingIds,
                zapInProgressIds = articleZapInProgress,
                listedIds = articleListedIds,
                pinnedIds = articlePinnedIds,
                userPubkey = feedViewModel.getUserPubkey(),
                resolvedEmojis = articleResolvedEmojis,
                unicodeEmojis = articleUnicodeEmojis,
                onOpenEmojiLibrary = { showArticleEmojiLibrary = true }
            )

            if (showArticleEmojiLibrary) {
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = articleUnicodeEmojis,
                    customEmojiMap = articleResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showArticleEmojiLibrary = false
                    },
                    onDismiss = { showArticleEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }

        composable(
            Routes.RECIPE_DETAIL,
            arguments = listOf(
                navArgument("author") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val author = backStackEntry.arguments?.getString("author") ?: return@composable
            val dTag = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("dTag") ?: return@composable, "UTF-8"
            )
            val recipeDetailViewModel: RecipeDetailViewModel = viewModel()
            LaunchedEffect(author, dTag) {
                recipeDetailViewModel.load(
                    author, dTag, feedViewModel.recipeRepo,
                    feedViewModel.nourishRepo, hasSigningKey = feedViewModel.signer != null,
                )
                // Hydrate the canonical recipe-bookmark list so the save state is fresh.
                feedViewModel.loadRecipeBookmarks()
            }

            var recipeZapTarget by remember { mutableStateOf<cooking.zap.app.nostr.NostrEvent?>(null) }
            val recipeZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var recipeZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            val recipeSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val recipeBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            // A14: canonical kind-30001 recipe bookmarks (a-coordinates), unioned with
            // legacy 10003 e-ids so old Android bookmarks still render filled.
            val recipeCanonicalBookmarks by feedViewModel.recipeBookmarkRepo.bookmarkedCoordinates.collectAsState()
            val recipeDetailEvent by recipeDetailViewModel.event.collectAsState()
            val recipeListedIds = remember(
                recipeSetListedIds, recipeBookmarkedIds, recipeCanonicalBookmarks, recipeDetailEvent
            ) {
                val base = recipeSetListedIds + recipeBookmarkedIds
                val ev = recipeDetailEvent
                val coord = ev?.let { feedViewModel.recipeBookmarkRepo.coordinateForEvent(it) }
                if (ev != null && coord != null && recipeCanonicalBookmarks.contains(coord)) base + ev.id else base
            }
            val recipeResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val recipeUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()

            // A14 PR 3a: long-press the bookmark → list chooser (multi-membership).
            val recipeCollections by feedViewModel.recipeBookmarkRepo.lists.collectAsState()
            var showRecipeListChooser by remember { mutableStateOf(false) }
            var recipeChooserEventId by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    recipeZapAnimatingIds = recipeZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    recipeZapAnimatingIds = recipeZapAnimatingIds - eventId
                }
            }

            if (recipeZapTarget != null) {
                val zapRecipient = recipeZapTarget!!.pubkey
                val recipeUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var recipeRecipientHasDmRelays by remember(zapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(zapRecipient))
                }
                if (recipeUserHasDmRelays && !recipeRecipientHasDmRelays) {
                    LaunchedEffect(zapRecipient) {
                        recipeRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(zapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { recipeZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = recipeZapTarget ?: return@ZapDialog
                        recipeZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && recipeUserHasDmRelays && recipeRecipientHasDmRelays
                )
            }

            val recipeShareContext = LocalContext.current

            RecipeDetailScreen(
                viewModel = recipeDetailViewModel,
                eventRepo = feedViewModel.eventRepo,
                onComputeNourish = {
                    recipeDetailViewModel.computeNourish(feedViewModel.zapCookingApi, feedViewModel.signer)
                },
                onOpenNourishHub = { recipe ->
                    navController.navigate(Routes.nourish(recipe.author, recipe.dTag))
                },
                onBack = { navController.popBackStack() },
                onShare = {
                    val event = recipeDetailEvent ?: return@RecipeDetailScreen
                    // Mirror PostCard's note share: never let a malformed event or a
                    // missing chooser target crash the screen.
                    try {
                        val text = cooking.zap.app.nostr.RecipeShare.shareText(event)
                            ?: return@RecipeDetailScreen
                        val title = cooking.zap.app.nostr.RecipeShare.titleFor(event)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        recipeShareContext.startActivity(
                            Intent.createChooser(intent, recipeShareContext.getString(R.string.btn_share))
                        )
                    } catch (_: Exception) {}
                },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onHashtagClick = { tag ->
                    navController.navigate("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onRepost = { event -> feedViewModel.sendRepost(event) },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onZap = { event -> recipeZapTarget = event },
                // A14: recipe bookmarks go to the canonical kind-30001 list by
                // coordinate (web-interop), not the generic note-bookmark dialog.
                onAddToList = { eventId -> feedViewModel.toggleRecipeBookmark(eventId) },
                onAddToListLongPress = { eventId ->
                    recipeChooserEventId = eventId
                    showRecipeListChooser = true
                },
                zapAnimatingIds = recipeZapAnimatingIds,
                zapInProgressIds = recipeZapInProgress,
                listedIds = recipeListedIds,
                userPubkey = feedViewModel.getUserPubkey(),
                resolvedEmojis = recipeResolvedEmojis,
                unicodeEmojis = recipeUnicodeEmojis,
                // Cook mode (onStartCooking) lands in 1.4 — null here, so no button renders.
                onStartCooking = null
            )

            if (showRecipeListChooser) {
                val chooserEvent = recipeChooserEventId?.let { feedViewModel.eventRepo.getEvent(it) }
                val chooserCoord = chooserEvent?.let {
                    feedViewModel.recipeBookmarkRepo.coordinateForEvent(it)
                }
                cooking.zap.app.ui.component.RecipeListChooserSheet(
                    lists = recipeCollections,
                    recipeCoordinate = chooserCoord,
                    onToggleList = { dTag ->
                        recipeChooserEventId?.let { feedViewModel.toggleRecipeInList(dTag, it) }
                    },
                    onCreateList = { name ->
                        recipeChooserEventId?.let { feedViewModel.createRecipeListAndSave(name, it) }
                    },
                    onDismiss = { showRecipeListChooser = false },
                )
            }
        }

        composable(Routes.RECIPES) {
            val recipeFeedViewModel: RecipeFeedViewModel = viewModel()
            val recipePacksViewModel: RecipePacksViewModel = viewModel()
            val cookbookViewModel: CookbookViewModel = viewModel()
            LaunchedEffect(Unit) { recipeFeedViewModel.load(feedViewModel.recipeRepo) }
            LaunchedEffect(Unit) {
                recipePacksViewModel.load(feedViewModel.recipePackRepo) { feedViewModel.getUserPubkey() }
            }
            LaunchedEffect(Unit) {
                cookbookViewModel.bind(
                    feedViewModel.recipeBookmarkRepo,
                    feedViewModel.recipeRepo,
                ) { feedViewModel.getUserPubkey() }
            }
            // Avatar for the nav icon — mirrors the Feed tab's avatar→drawer button.
            val recipesProfileVersion by feedViewModel.eventRepo.profileVersion.collectAsState()
            val recipesAvatarUrl = recipesProfileVersion.let {
                feedViewModel.getUserPubkey()?.let { pk -> feedViewModel.eventRepo.getProfileData(pk)?.picture }
            }
            RecipeFeedScreen(
                viewModel = recipeFeedViewModel,
                packsViewModel = recipePacksViewModel,
                cookbookViewModel = cookbookViewModel,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                onRecipeClick = { author, dTag -> navController.navigate(Routes.recipe(author, dTag)) },
                onPackClick = { author, dTag -> navController.navigate(Routes.recipePack(author, dTag)) },
                onCollectionClick = { dTag -> navController.navigate(Routes.recipeCollection(dTag)) },
                onTagClick = { tag -> navController.navigate(Routes.recipeTag(tag)) },
                onOpenDrawer = onOpenDrawer,
                onSearch = {
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(Routes.RECIPES) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSousChef = { navController.navigate(Routes.SOUS_CHEF) { launchSingleTop = true } },
                onCheffy = { navController.navigate(Routes.CHEFFY) { launchSingleTop = true } },
                onNourish = { navController.navigate(Routes.nourish()) { launchSingleTop = true } },
                userAvatarUrl = recipesAvatarUrl,
                // READ_ONLY can't sign → no compose entry point.
                onCreateRecipe = if (signingMode == SigningMode.READ_ONLY) null else {
                    { navController.navigate(Routes.RECIPE_COMPOSE) }
                },
            )
        }

        composable(
            Routes.RECIPE_PACK_DETAIL,
            arguments = listOf(
                navArgument("author") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val author = backStackEntry.arguments?.getString("author") ?: return@composable
            val encodedDTag = backStackEntry.arguments?.getString("dTag") ?: return@composable
            val dTag = java.net.URLDecoder.decode(encodedDTag, "UTF-8")
            val recipePackDetailViewModel: RecipePackDetailViewModel = viewModel()
            LaunchedEffect(author, dTag) {
                recipePackDetailViewModel.load(
                    author = author,
                    dTag = dTag,
                    packRepo = feedViewModel.recipePackRepo,
                    recipeRepo = feedViewModel.recipeRepo,
                )
            }
            RecipePackDetailScreen(
                viewModel = recipePackDetailViewModel,
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onRecipeClick = { recipeAuthor, recipeDTag ->
                    navController.navigate(Routes.recipe(recipeAuthor, recipeDTag))
                }
            )
        }

        // Cookbook-collection detail (A14 PR 3b-i). Reuses the pack-detail screen +
        // VM: the user's kind-30001 list (PR 3a) feeds the same coordinate resolution
        // and poster grid via RecipePackDetailViewModel.loadCollection().
        composable(
            Routes.RECIPE_COLLECTION,
            arguments = listOf(navArgument("dTag") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedDTag = backStackEntry.arguments?.getString("dTag") ?: return@composable
            val dTag = java.net.URLDecoder.decode(encodedDTag, "UTF-8")
            val collectionDetailViewModel: RecipePackDetailViewModel = viewModel()
            val cookbookLists by feedViewModel.recipeBookmarkRepo.lists.collectAsState()
            val listsLoading by feedViewModel.recipeBookmarkRepo.isLoading.collectAsState()
            val list = remember(cookbookLists, dTag) { cookbookLists.firstOrNull { it.dTag == dTag } }
            val currentList = list
            LaunchedEffect(currentList?.event?.id, listsLoading) {
                when {
                    currentList != null -> collectionDetailViewModel.loadCollection(currentList, feedViewModel.recipeRepo)
                    // Lists finished loading and the d-tag isn't among them (stale
                    // deep link / deleted list) — show not-found instead of spinning.
                    !listsLoading -> collectionDetailViewModel.markNotFound()
                }
            }
            RecipePackDetailScreen(
                viewModel = collectionDetailViewModel,
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onRecipeClick = { recipeAuthor, recipeDTag ->
                    navController.navigate(Routes.recipe(recipeAuthor, recipeDTag))
                }
            )
        }

        composable(
            Routes.RECIPE_TAG_FEED,
            arguments = listOf(navArgument("tag") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedTag = backStackEntry.arguments?.getString("tag") ?: return@composable
            val tag = java.net.URLDecoder.decode(encodedTag, "UTF-8").trim().lowercase()
            val tagFeedViewModel: RecipeTagFeedViewModel = viewModel()
            LaunchedEffect(tag) {
                tagFeedViewModel.load(tag, feedViewModel.recipeRepo)
            }
            RecipeTagFeedScreen(
                viewModel = tagFeedViewModel,
                tagInfo = RecipeTagCatalog.byTag(tag),
                onRecipeClick = { author, dTag -> navController.navigate(Routes.recipe(author, dTag)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.RECIPE_COMPOSE) {
            val recipeComposeViewModel: RecipeComposeViewModel = viewModel()
            val context = androidx.compose.ui.platform.LocalContext.current
            // Consume a pending Cheffy "Save" hand-off ONCE (read-then-null), so
            // recomposition/back-nav can't re-prefill and an abandoned hand-off
            // can't leak into a later FAB-launched empty compose (concern 2.3c).
            LaunchedEffect(Unit) {
                feedViewModel.pendingComposeMarkdown?.let { md ->
                    feedViewModel.pendingComposeMarkdown = null
                    recipeComposeViewModel.prefillFromMarkdown(md)
                }
            }
            RecipeComposeScreen(
                viewModel = recipeComposeViewModel,
                canSign = feedViewModel.signer != null,
                onPickImages = { uris ->
                    recipeComposeViewModel.addImages(
                        uris = uris,
                        contentResolver = context.contentResolver,
                        blossomRepo = feedViewModel.blossomRepo,
                        signer = feedViewModel.signer,
                    )
                },
                onPublish = {
                    recipeComposeViewModel.publish(
                        publisher = feedViewModel.recipePublisher,
                        signer = feedViewModel.signer,
                        clientTagEnabled = feedViewModel.interfacePrefs.isClientTagEnabled(),
                    )
                },
                onPublished = { author, dTag ->
                    navController.navigate(Routes.recipe(author, dTag)) {
                        // Replace the composer in the back stack — back returns to the feed.
                        popUpTo(Routes.RECIPE_COMPOSE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SOUS_CHEF) {
            val sousChefViewModel: SousChefViewModel = viewModel()
            SousChefScreen(
                viewModel = sousChefViewModel,
                onImport = { url -> sousChefViewModel.import(url, feedViewModel.zapCookingApi) },
                onSave = {
                    sousChefViewModel.save(
                        publisher = feedViewModel.recipePublisher,
                        signer = feedViewModel.signer,
                        clientTagEnabled = feedViewModel.interfacePrefs.isClientTagEnabled(),
                    )
                },
                onSaved = { author, dTag ->
                    navController.navigate(Routes.recipe(author, dTag)) {
                        // Replace the importer in the back stack — back returns to the feed.
                        popUpTo(Routes.SOUS_CHEF) { inclusive = true }
                    }
                },
                canSign = feedViewModel.signer != null,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CHEFFY) {
            val cheffyViewModel: CheffyViewModel = viewModel()
            CheffyScreen(
                viewModel = cheffyViewModel,
                canSign = feedViewModel.signer != null,
                onSend = { text, mode ->
                    cheffyViewModel.send(
                        content = text,
                        mode = mode,
                        api = feedViewModel.zapCookingApi,
                        pubkey = feedViewModel.getUserPubkey(),
                    )
                },
                onRetry = {
                    cheffyViewModel.retry(feedViewModel.zapCookingApi, feedViewModel.getUserPubkey())
                },
                onSaveRecipe = { content ->
                    // Transient hand-off: pre-fill the compose editor with Cheffy's
                    // recipe markdown (consumed once by the compose route). 2.3c.
                    feedViewModel.pendingComposeMarkdown = content
                    navController.navigate(Routes.RECIPE_COMPOSE)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.NOURISH,
            arguments = listOf(
                navArgument("author") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("dTag") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val author = backStackEntry.arguments?.getString("author")?.trim().orEmpty()
            val dTag = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("dTag")?.trim().orEmpty(), "UTF-8"
            )
            val selectedAuthor = author.takeIf { it.isNotBlank() }
            val selectedDTag = dTag.takeIf { it.isNotBlank() }
            val recipeDetailViewModel: RecipeDetailViewModel = viewModel()
            LaunchedEffect(selectedAuthor, selectedDTag) {
                if (!selectedAuthor.isNullOrBlank() && !selectedDTag.isNullOrBlank()) {
                    recipeDetailViewModel.load(
                        author = selectedAuthor,
                        dTag = selectedDTag,
                        recipeRepo = feedViewModel.recipeRepo,
                        nourishRepo = feedViewModel.nourishRepo,
                        hasSigningKey = feedViewModel.signer != null,
                    )
                }
            }
            NourishHubScreen(
                viewModel = recipeDetailViewModel,
                selectedAuthor = selectedAuthor,
                selectedDTag = selectedDTag,
                canSign = feedViewModel.signer != null,
                onComputeNourish = {
                    recipeDetailViewModel.computeNourish(feedViewModel.zapCookingApi, feedViewModel.signer)
                },
                onBack = { navController.popBackStack() },
                onBrowseRecipes = { navController.navigate(Routes.RECIPES) },
                onOpenSelectedRecipe = { authorHex, recipeDTag ->
                    navController.navigate(Routes.recipe(authorHex, recipeDTag))
                },
                onExploreRecipes = { navController.navigate(Routes.RECIPES) },
            )
        }

        composable(Routes.ONLY_FOOD) {
            val onlyFoodViewModel: OnlyFoodFeedViewModel = viewModel()
            LaunchedEffect(Unit) {
                onlyFoodViewModel.init(
                    relayPool = feedViewModel.relayPool,
                    eventRepo = feedViewModel.eventRepo,
                    muteRepo = feedViewModel.muteRepo,
                    contactRepo = feedViewModel.contactRepo,
                )
            }

            var onlyFoodZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val onlyFoodZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var onlyFoodZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isOnlyFoodNwcConnected = feedViewModel.activeWalletProvider.hasConnection()

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    onlyFoodZapAnimatingIds = onlyFoodZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    onlyFoodZapAnimatingIds = onlyFoodZapAnimatingIds - eventId
                }
            }

            if (onlyFoodZapTarget != null) {
                val onlyFoodZapRecipient = onlyFoodZapTarget!!.pubkey
                val onlyFoodUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var onlyFoodRecipientHasDmRelays by remember(onlyFoodZapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(onlyFoodZapRecipient))
                }
                if (onlyFoodUserHasDmRelays && !onlyFoodRecipientHasDmRelays) {
                    LaunchedEffect(onlyFoodZapRecipient) {
                        onlyFoodRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(onlyFoodZapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isOnlyFoodNwcConnected,
                    onDismiss = { onlyFoodZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = onlyFoodZapTarget ?: return@ZapDialog
                        onlyFoodZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && onlyFoodUserHasDmRelays && onlyFoodRecipientHasDmRelays
                )
            }

            val onlyFoodNoteActions = remember {
                cooking.zap.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigate(Routes.COMPOSE)
                    },
                    onZap = { event -> onlyFoodZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { clickedTag ->
                        navController.navigate("hashtag/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}")
                    },
                    onArticleClick = { kind, author, dTag ->
                        navController.openArticleOrRecipe(feedViewModel.eventRepo, kind, author, dTag)
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) }
                )
            }

            OnlyFoodFeedScreen(
                viewModel = onlyFoodViewModel,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                noteActions = onlyFoodNoteActions,
                onBack = { navController.popBackStack() },
                zapAnimatingIds = onlyFoodZapAnimatingIds,
                zapInProgressIds = onlyFoodZapInProgress
            )
        }

        composable(
            Routes.LIVE_STREAM,
            arguments = listOf(
                navArgument("hostPubkey") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType },
                navArgument("relayHint") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val hostPubkey = backStackEntry.arguments?.getString("hostPubkey") ?: return@composable
            val dTag = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("dTag") ?: return@composable, "UTF-8"
            )
            val relayHint = backStackEntry.arguments?.getString("relayHint")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            val naddrHints = listOfNotNull(relayHint)
            val liveStreamViewModel: LiveStreamViewModel = viewModel()
            LaunchedEffect(hostPubkey, dTag) {
                liveStreamViewModel.init(
                    hostPubkey, dTag, feedViewModel.liveStreamRepo,
                    feedViewModel.relayPool, feedViewModel.relayListRepo,
                    feedViewModel.outboxRouter, feedViewModel.subManager,
                    feedViewModel.contactRepo, feedViewModel.profileRepo, naddrHints
                )
            }
            DisposableEffect(hostPubkey, dTag) {
                onDispose { liveStreamViewModel.cleanup(feedViewModel.relayPool) }
            }
            val liveResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val liveUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()
            val liveZapVersion by feedViewModel.eventRepo.zapVersion.collectAsState()
            val liveZapInProgress by feedViewModel.zapInProgress.collectAsState()
            val liveStreamZapTotal by liveStreamViewModel.streamZapTotal.collectAsState()
            var liveZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            var liveZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            var liveZapRecipientOverride by remember { mutableStateOf<String?>(null) }
            var liveZapATag by remember { mutableStateOf<String?>(null) }
            var liveZapError by remember { mutableStateOf<String?>(null) }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    liveZapAnimatingIds = liveZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    liveZapAnimatingIds = liveZapAnimatingIds - eventId
                }
            }
            LaunchedEffect(Unit) {
                feedViewModel.zapError.collect { error ->
                    liveZapError = error
                }
            }
            if (liveZapError != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { liveZapError = null },
                    title = { androidx.compose.material3.Text("Zap Failed") },
                    text = { androidx.compose.material3.Text(liveZapError ?: "") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { liveZapError = null }) {
                            androidx.compose.material3.Text("OK")
                        }
                    }
                )
            }
            if (liveZapTarget != null) {
                val zapRecipient = liveZapRecipientOverride ?: liveZapTarget!!.pubkey
                var recipientHasDmRelays by remember(zapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(zapRecipient))
                }
                if (feedViewModel.relayPool.hasDmRelays() && !recipientHasDmRelays) {
                    LaunchedEffect(zapRecipient) {
                        recipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(zapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { liveZapTarget = null; liveZapRecipientOverride = null; liveZapATag = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = liveZapTarget ?: return@ZapDialog
                        val recipientPubkey = liveZapRecipientOverride
                        val aTag = liveZapATag
                        liveZapTarget = null
                        liveZapRecipientOverride = null
                        liveZapATag = null
                        // Include stream chat relays so the receipt arrives on relays
                        // the streamer and chat room are subscribed to
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate,
                            extraRelayHints = liveStreamViewModel.chatRelays,
                            recipientOverride = recipientPubkey,
                            eventATag = aTag)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    // DIP-03 needs a concrete note id for the ephemeral key
                    // derivation; live-stream zaps target an addressable event
                    // (a-tag) instead, so private zaps don't apply here.
                    canPrivateZap = false
                )
            }
            val streamActivityEventId = remember(hostPubkey, dTag) {
                feedViewModel.eventRepo.findAddressableEvent(
                    cooking.zap.app.nostr.Nip53.KIND_LIVE_ACTIVITY, hostPubkey, dTag
                )?.id
            }
            LiveStreamScreen(
                viewModel = liveStreamViewModel,
                eventRepo = feedViewModel.eventRepo,
                signer = activeSigner,
                myPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onFullScreenVideo = { url, positionMs ->
                    pipFullScreenVideoUrl = url
                    pipFullScreenStartPosition = positionMs
                    pipFullScreenPlayer = null
                    pipFullScreenAspectRatio = 16f / 9f
                },
                resolvedEmojis = liveResolvedEmojis,
                unicodeEmojis = liveUnicodeEmojis,
                onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                onEmojiUsed = { feedViewModel.customEmojiRepo.recordEmojiUsage(it) },
                onZap = { messageId, senderPubkey ->
                    // Create a minimal event for the zap sender to target
                    val chatEvent = feedViewModel.eventRepo.getEvent(messageId)
                    if (chatEvent != null) liveZapTarget = chatEvent
                },
                onZapStream = {
                    // Target the 30311 activity event for stream-level zaps.
                    // The event pubkey is the stream provider — the actual streamer
                    // is in the p-tag with role "Host", so override the recipient.
                    // Per NIP-57, use "a" tag (not "e") for parameterized replaceable events.
                    val activityEvent = feedViewModel.eventRepo.findAddressableEvent(
                        cooking.zap.app.nostr.Nip53.KIND_LIVE_ACTIVITY, hostPubkey, dTag
                    )
                    if (activityEvent != null) {
                        liveZapTarget = activityEvent
                        val activity = liveStreamViewModel.activity.value
                        liveZapRecipientOverride = activity?.streamerPubkey ?: activity?.hostPubkey
                        liveZapATag = cooking.zap.app.nostr.Nip53.aTagValue(hostPubkey, dTag)
                    }
                },
                streamZapTotal = liveStreamZapTotal,
                streamActivityEventId = streamActivityEventId,
                zapVersion = liveZapVersion,
                zapAnimatingIds = liveZapAnimatingIds,
                zapInProgressIds = liveZapInProgress
            )
        }

        composable(Routes.SAFETY) {
            SafetyScreen(
                muteRepo = feedViewModel.muteRepo,
                profileRepo = feedViewModel.profileRepo,
                profileVersion = feedViewModel.eventRepo.profileVersion,
                fetchProfile = { feedViewModel.forceProfileFetch(it) },
                onBack = { navController.popBackStack() },
                onChanged = { feedViewModel.updateMutedWords() },
                safetyPrefs = feedViewModel.safetyPrefs,
                cachedNetwork = feedViewModel.extendedNetworkRepo.cachedNetwork,
                isNetworkReady = { feedViewModel.extendedNetworkRepo.isNetworkReady() },
                onNavigateToSocialGraph = { navController.navigate(Routes.SOCIAL_GRAPH) },
                onWotToggled = { feedViewModel.eventRepo.rebuildFeedFromCache() }
            )
        }

        composable(Routes.POW_SETTINGS) {
            PowSettingsScreen(
                powPrefs = feedViewModel.powPrefs,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.INTERFACE_SETTINGS) {
            val context = LocalContext.current
            val app = context.applicationContext as android.app.Application
            val interfacePrefs = remember { cooking.zap.app.repo.InterfacePreferences(context) }
            InterfaceScreen(
                application = app,
                interfacePrefs = interfacePrefs,
                onBack = { navController.popBackStack() },
                onChanged = onInterfaceChanged
            )
        }

        composable(Routes.CUSTOM_EMOJIS) {
            val emojiUploadScope = androidx.compose.runtime.rememberCoroutineScope()
            CustomEmojiScreen(
                customEmojiRepo = feedViewModel.customEmojiRepo,
                onBack = { navController.popBackStack() },
                onCreateSet = { name, emojis -> feedViewModel.createEmojiSet(name, emojis) },
                onUpdateSet = { dTag, title, emojis -> feedViewModel.updateEmojiSet(dTag, title, emojis) },
                onDeleteSet = { dTag -> feedViewModel.deleteEmojiSet(dTag) },
                onPublishEmojiList = { emojis, refs -> feedViewModel.publishUserEmojiList(emojis, refs) },
                onAddSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                onRemoveSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                onUploadEmoji = { contentResolver, uri, onResult ->
                    emojiUploadScope.launch {
                        try {
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw Exception("Cannot read file")
                            val mimeType = contentResolver.getType(uri) ?: "image/png"
                            val ext = mimeType.substringAfter("/", "png")
                            val url = feedViewModel.blossomRepo.uploadMedia(bytes, mimeType, ext, feedViewModel.signer)
                            onResult(url)
                        } catch (e: Exception) {
                            android.util.Log.e("CustomEmoji", "Upload failed: ${e.message}")
                            onResult("")
                        }
                    }
                }
            )
        }

        composable(Routes.CONSOLE) {
            consoleViewModel.init(feedViewModel.relayPool)
            ConsoleScreen(
                viewModel = consoleViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.RELAY_HEALTH) {
            relayHealthViewModel.init(
                feedViewModel.relayPool,
                feedViewModel.healthTracker,
                feedViewModel.relayInfoRepo,
                feedViewModel.eventRepo,
                feedViewModel.relayScoreBoard
            )
            RelayHealthScreen(
                viewModel = relayHealthViewModel,
                onBack = { navController.popBackStack() },
                onRelayDetail = { url ->
                    navController.navigate("relay_detail/${java.net.URLEncoder.encode(url, "UTF-8")}")
                }
            )
        }

        composable(Routes.SOCIAL_GRAPH) {
            SocialGraphScreen(
                extendedNetworkRepo = feedViewModel.extendedNetworkRepo,
                profileRepo = feedViewModel.profileRepo,
                socialGraphDb = feedViewModel.socialGraphDb,
                userPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onNavigateToProfile = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onNetworkDiscovered = {
                    feedViewModel.integrateExtendedNetwork()
                }
            )
        }

        composable(
            Routes.RELAY_DETAIL,
            arguments = listOf(navArgument("relayUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("relayUrl") ?: return@composable
            val relayUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            val consoleLog by feedViewModel.relayPool.consoleLog.collectAsState()
            val profileVersion by feedViewModel.eventRepo.profileVersion.collectAsState()
            val operatorPubkey = feedViewModel.relayInfoRepo.getInfo(relayUrl)?.pubkey
            val operatorProfile = remember(operatorPubkey, profileVersion) {
                operatorPubkey?.let { feedViewModel.eventRepo.getProfileData(it) }
            }
            // Queue profile fetch for operator if needed
            LaunchedEffect(operatorPubkey) {
                if (operatorPubkey != null && feedViewModel.eventRepo.getProfileData(operatorPubkey) == null) {
                    feedViewModel.forceProfileFetch(operatorPubkey)
                }
            }
            val favoriteRelays by feedViewModel.relaySetRepo.favoriteRelays.collectAsState()
            val relaySets by feedViewModel.relaySetRepo.ownRelaySets.collectAsState()
            RelayDetailScreen(
                relayUrl = relayUrl,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                healthTracker = feedViewModel.healthTracker,
                consoleEntries = consoleLog,
                operatorProfile = operatorProfile,
                isFavorite = relayUrl in favoriteRelays,
                relaySets = relaySets,
                onBack = { navController.popBackStack() },
                onOperatorClick = if (operatorPubkey != null) {{ navController.navigate("profile/$operatorPubkey") }} else null,
                onToggleFavorite = { feedViewModel.toggleFavoriteRelay(relayUrl) },
                onAddToRelaySet = { dTag -> feedViewModel.addRelayToSet(relayUrl, dTag) },
                onCreateRelaySet = { name ->
                    feedViewModel.createRelaySet(name, setOf(relayUrl))
                }
            )
        }

        composable(Routes.KEYS) {
            val pubkeyHex = authViewModel.keyRepo.getPubkeyHex()
            val avatarUrl = pubkeyHex?.let { feedViewModel.eventRepo.getProfileData(it)?.picture }
            KeysScreen(
                keyRepository = authViewModel.keyRepo,
                onBack = { navController.popBackStack() },
                avatarUrl = avatarUrl
            )
        }

        composable(
            Routes.LIST_DETAIL,
            arguments = listOf(
                navArgument("pubkey") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val listPubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            val dTag = backStackEntry.arguments?.getString("dTag") ?: return@composable
            val isOwnList = listPubkey == feedViewModel.getUserPubkey()

            LaunchedEffect(listPubkey) {
                feedViewModel.fetchUserLists(listPubkey)
            }

            val ownLists by feedViewModel.listRepo.ownLists.collectAsState()
            val followSet = remember(ownLists, listPubkey, dTag) {
                feedViewModel.listRepo.getList(listPubkey, dTag)
            }

            ListScreen(
                followSet = followSet,
                eventRepo = feedViewModel.eventRepo,
                isOwnList = isOwnList,
                onBack = { navController.popBackStack() },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onRemoveMember = if (isOwnList) { pubkey ->
                    feedViewModel.removeFromList(dTag, pubkey)
                } else null,
                onAddMember = if (isOwnList) { pubkey ->
                    feedViewModel.addToList(dTag, pubkey)
                } else null,
                onUseAsFeed = {
                    followSet?.let {
                        feedViewModel.setSelectedList(it)
                        feedViewModel.setFeedType(FeedType.LIST)
                        navController.navigate(Routes.FEED) {
                            popUpTo(Routes.FEED) { inclusive = true }
                        }
                    }
                },
                onDeleteList = if (isOwnList) {{
                    feedViewModel.deleteList(dTag)
                    navController.popBackStack()
                }} else null,
                onFollowAll = if (!isOwnList) { members ->
                    feedViewModel.followAll(members)
                } else null,
                contactRepo = feedViewModel.contactRepo
            )
        }

        composable(Routes.LISTS_HUB) {
            ListsHubScreen(
                listRepo = feedViewModel.listRepo,
                bookmarkSetRepo = feedViewModel.bookmarkSetRepo,
                bookmarkRepo = feedViewModel.bookmarkRepo,
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onListDetail = { list ->
                    navController.navigate("list/${list.pubkey}/${list.dTag}")
                },
                onBookmarkSetDetail = { set ->
                    navController.navigate("bookmark_set/${set.pubkey}/${set.dTag}")
                },
                onBookmarksClick = { navController.navigate(Routes.BOOKMARKS) },
                onCreateList = { name, isPrivate -> feedViewModel.createList(name, isPrivate) },
                onCreateBookmarkSet = { name, isPrivate -> feedViewModel.createBookmarkSet(name, isPrivate) },
                onDeleteList = { dTag -> feedViewModel.deleteList(dTag) },
                onDeleteBookmarkSet = { dTag -> feedViewModel.deleteBookmarkSet(dTag) }
            )
        }

        composable(Routes.BOOKMARKS) {
            val bookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()

            LaunchedEffect(Unit) {
                feedViewModel.fetchBookmarkEvents()
            }

            BookmarksScreen(
                bookmarkedIds = bookmarkedIds,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onNoteClick = { event -> navController.navigate("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onRemoveBookmark = { eventId -> feedViewModel.removeBookmark(eventId) },
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) },
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) }
            )
        }

        composable(
            Routes.BOOKMARK_SET_DETAIL,
            arguments = listOf(
                navArgument("pubkey") { type = NavType.StringType },
                navArgument("dTag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val setPubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            val dTag = backStackEntry.arguments?.getString("dTag") ?: return@composable
            val isOwnList = setPubkey == feedViewModel.getUserPubkey()

            LaunchedEffect(dTag) {
                feedViewModel.fetchBookmarkSetEvents(dTag)
            }

            val ownSets by feedViewModel.bookmarkSetRepo.ownSets.collectAsState()
            val bookmarkSet = remember(ownSets, setPubkey, dTag) {
                feedViewModel.bookmarkSetRepo.getSet(setPubkey, dTag)
            }

            BookmarkSetScreen(
                bookmarkSet = bookmarkSet,
                eventRepo = feedViewModel.eventRepo,
                userPubkey = feedViewModel.getUserPubkey(),
                isOwnList = isOwnList,
                onBack = { navController.popBackStack() },
                onDeleteList = if (isOwnList) {{
                    feedViewModel.deleteBookmarkSet(dTag)
                    navController.popBackStack()
                }} else null,
                onNoteClick = { event -> navController.navigate("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigate("thread/$eventId") },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onRemoveFromSet = if (isOwnList) { eventId ->
                    feedViewModel.removeNoteFromBookmarkSet(dTag, eventId)
                } else null,
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) },
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate()
            )
        }

        composable(
            Routes.BACKUP_KEY,
            arguments = listOf(
                navArgument("reprompt") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val reprompt = backStackEntry.arguments?.getBoolean("reprompt") ?: false
            val backupPubkey = authViewModel.keyRepo.getPubkeyHex()
            val backupAvatar = backupPubkey?.let { feedViewModel.eventRepo.getProfileData(it)?.picture }
            // Creation flow → advance into onboarding. Re-prompt (banner / cold launch)
            // sits on top of the feed → pop back to it.
            val proceed: () -> Unit = {
                if (reprompt) {
                    navController.popBackStack()
                } else {
                    navController.navigate(Routes.ONBOARDING_PROFILE) {
                        popUpTo(Routes.BACKUP_KEY) { inclusive = true }
                    }
                }
            }
            BackHandler {
                // System back behaves like "Skip for now": keep the need alive, back off.
                authViewModel.recordKeyBackupSkip()
                proceed()
            }
            BackupKeyScreen(
                keyRepository = authViewModel.keyRepo,
                avatarUrl = backupAvatar,
                onSaved = {
                    authViewModel.markKeyBackedUp()
                    proceed()
                },
                onSkip = {
                    authViewModel.recordKeyBackupSkip()
                    proceed()
                },
                onBackupExported = { authViewModel.markKeyBackedUp() }
            )
        }

        composable(Routes.ONBOARDING_PROFILE) {
            val onBack: () -> Unit = {
                authViewModel.keyRepo.clearKeypair()
                navController.popBackStack()
            }
            val scope = rememberCoroutineScope()
            BackHandler(onBack = onBack)
            LaunchedEffect(Unit) {
                onboardingViewModel.startDiscovery(feedViewModel.sparkRepo, feedViewModel.walletModeRepo)
            }
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onContinue = {
                    scope.launch {
                        if (onboardingViewModel.finishProfile(
                                feedViewModel.relayPool,
                                sparkRepo = feedViewModel.sparkRepo,
                                walletModeRepo = feedViewModel.walletModeRepo,
                                signer = activeSigner
                            )) {
                            // Re-key all per-account state to the new pubkey now, BEFORE
                            // the Topics step publishes its interest set. reloadForNewAccount()
                            // runs clearAll() + switches contactRepo/interestRepo to the
                            // pubkey-scoped prefs; if it ran later, the topic/follow writes
                            // would land in the null-keyed prefs and be wiped on reload.
                            feedViewModel.reloadForNewAccount()
                            relayViewModel.reload()
                            blossomServersViewModel.reload()
                            composeViewModel.reloadBlossomRepo()
                            walletViewModel.refreshState()
                            navController.navigate(Routes.ONBOARDING_TOPICS) {
                                popUpTo(Routes.ONBOARDING_PROFILE) { inclusive = true }
                            }
                        }
                    }
                },
                onBack = onBack,
                signer = activeSigner
            )
        }

        composable(Routes.ONBOARDING_SUGGESTIONS) {
            LaunchedEffect(Unit) {
                onboardingViewModel.loadSuggestions(feedViewModel.relayPool, feedViewModel.extendedNetworkRepo)
            }
            val activeNow by onboardingViewModel.activeNow.collectAsState()
            val creators by onboardingViewModel.creators.collectAsState()
            val selectedPubkeys by onboardingViewModel.selectedPubkeys.collectAsState()
            val scope = rememberCoroutineScope()

            OnboardingSuggestionsScreen(
                activeNow = activeNow,
                creators = creators,
                selectedPubkeys = selectedPubkeys,
                onToggleFollowAll = { section -> onboardingViewModel.toggleFollowAll(section) },
                onTogglePubkey = { pubkey -> onboardingViewModel.togglePubkey(pubkey) },
                totalSelected = selectedPubkeys.size,
                onContinue = {
                    scope.launch {
                        // The account was already re-keyed at the Profile step, so
                        // contactRepo points at the correct pubkey-scoped prefs here.
                        // finishOnboarding publishes the kind-3 follow list and marks
                        // onboarding complete — keeping markOnboardingComplete() at the
                        // Creators step means a drop-off after this still completes.
                        onboardingViewModel.finishOnboarding(
                            relayPool = feedViewModel.relayPool,
                            contactRepo = feedViewModel.contactRepo,
                            selectedPubkeys = selectedPubkeys,
                            signer = activeSigner
                        )
                        navController.navigate(Routes.ONBOARDING_SAVE_RECIPES)
                    }
                },
                onSkip = {
                    scope.launch {
                        // Publish a kind 3 that at least follows the user themselves so
                        // their own posts land in their feed — finishOnboarding also
                        // marks onboarding complete.
                        onboardingViewModel.finishOnboarding(
                            relayPool = feedViewModel.relayPool,
                            contactRepo = feedViewModel.contactRepo,
                            selectedPubkeys = emptySet(),
                            signer = activeSigner
                        )
                        navController.navigate(Routes.ONBOARDING_SAVE_RECIPES)
                    }
                }
            )
        }

        composable(Routes.ONBOARDING_TOPICS) {
            OnboardingTopicsScreen(
                viewModel = topicOnboardingViewModel,
                onContinue = {
                    val selected = topicOnboardingViewModel.selectedTopics.value
                    if (selected.isNotEmpty()) {
                        // Publish a single kind 30015 with all selected tags. Per-tag
                        // followHashtag calls race on the same d-tag, so use the bulk
                        // API to produce one atomic event with a nice title.
                        feedViewModel.followHashtags(selected, dTag = "interests", setTitle = "Interests")
                    }
                    navController.navigate(Routes.ONBOARDING_SUGGESTIONS)
                },
                onSkip = {
                    navController.navigate(Routes.ONBOARDING_SUGGESTIONS)
                }
            )
        }

        composable(Routes.ONBOARDING_SAVE_RECIPES) {
            // Gate behind signing capability: RecipeBookmarkRepository.toggle needs a
            // signer, so a READ_ONLY account can't save. The new-key flow always has a
            // signer; watch-only never reaches here (separate path), but gate anyway so
            // the bookmark action can't render in a broken state — skip straight on.
            if (activeSigner == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.ONBOARDING_FIRST_POST) {
                        popUpTo(Routes.ONBOARDING_SAVE_RECIPES) { inclusive = true }
                    }
                }
            } else {
                LaunchedEffect(Unit) {
                    // Browse happens AFTER the Profile-step reloadForNewAccount()/clearAll(),
                    // so this fresh load isn't wiped. loadRecipeBookmarks() keys the
                    // bookmark repo to the new pubkey for correct saved-state + carry-forward.
                    recipeOnboardingViewModel.load(feedViewModel.recipeRepo)
                    feedViewModel.loadRecipeBookmarks()
                }
                val featured by recipeOnboardingViewModel.featured.collectAsState()
                val recipesLoading by recipeOnboardingViewModel.loading.collectAsState()
                val bookmarked by feedViewModel.recipeBookmarkRepo.bookmarkedCoordinates.collectAsState()

                OnboardingSaveRecipesScreen(
                    recipes = featured,
                    loading = recipesLoading,
                    bookmarkedCoordinates = bookmarked,
                    onToggleBookmark = { recipeId -> feedViewModel.toggleRecipeBookmark(recipeId) },
                    onContinue = {
                        recipeOnboardingViewModel.reset()
                        navController.navigate(Routes.ONBOARDING_FIRST_POST)
                    },
                    onSkip = {
                        recipeOnboardingViewModel.reset()
                        navController.navigate(Routes.ONBOARDING_FIRST_POST)
                    }
                )
            }
        }

        composable(Routes.ONBOARDING_FIRST_POST) {
            LaunchedEffect(Unit) {
                // Kick off the feed subscription while the user composes their intro.
                feedViewModel.initRelays()
            }
            OnboardingFirstPostScreen(
                viewModel = composeViewModel,
                relayPool = feedViewModel.relayPool,
                outboxRouter = feedViewModel.outboxRouter,
                signer = activeSigner,
                onPosted = {
                    topicOnboardingViewModel.reset()
                    navController.navigate(Routes.FEED) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onSkip = {
                    topicOnboardingViewModel.reset()
                    navController.navigate(Routes.FEED) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.NOTIFICATIONS) {
            DisposableEffect(Unit) {
                feedViewModel.notifRepo.isViewing = true
                onDispose { feedViewModel.notifRepo.isViewing = false }
            }
            LaunchedEffect(Unit) {
                refreshInboxSubscriptionsIfStale()
                notificationsViewModel.markRead()
            }

            val notifReplyScope = rememberCoroutineScope()
            val notifInterfacePrefs = remember { cooking.zap.app.repo.InterfacePreferences(context) }
            var notifZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            data class NotifDmZapInfo(val peerPubkey: String, val rumorId: String, val senderPubkey: String)
            var notifDmZapTarget by remember { mutableStateOf<NotifDmZapInfo?>(null) }
            var notifDmZapPendingSats by remember { mutableStateOf(0L) }
            var lastNotifDmZapSenderPubkey by remember { mutableStateOf<String?>(null) }
            var notifDmZapSatsMap by remember { mutableStateOf(mapOf<String, Long>()) }
            val notifZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var notifZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val notifSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val notifBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val notifListedIds = remember(notifSetListedIds, notifBookmarkedIds) { notifSetListedIds + notifBookmarkedIds }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            var showNotifEmojiLibrary by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    notifZapAnimatingIds = notifZapAnimatingIds + eventId
                    if (eventId == lastNotifDmZapSenderPubkey && notifDmZapPendingSats > 0) {
                        notifDmZapSatsMap = notifDmZapSatsMap +
                            (eventId to (notifDmZapSatsMap.getOrDefault(eventId, 0L) + notifDmZapPendingSats))
                        lastNotifDmZapSenderPubkey = null
                        notifDmZapPendingSats = 0L
                    }
                    kotlinx.coroutines.delay(1500)
                    notifZapAnimatingIds = notifZapAnimatingIds - eventId
                }
            }

            if (notifZapTarget != null) {
                val notifZapRecipient = notifZapTarget!!.pubkey
                val notifUserHasDmRelays = feedViewModel.relayPool.hasDmRelays()
                var notifRecipientHasDmRelays by remember(notifZapRecipient) {
                    mutableStateOf(feedViewModel.relayListRepo.hasDmRelays(notifZapRecipient))
                }
                if (notifUserHasDmRelays && !notifRecipientHasDmRelays) {
                    LaunchedEffect(notifZapRecipient) {
                        notifRecipientHasDmRelays = feedViewModel.fetchDmRelaysIfMissing(notifZapRecipient)
                    }
                }
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { notifZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = notifZapTarget ?: return@ZapDialog
                        notifZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) },
                    canPrivateZap = feedViewModel.hasLocalKeypair && notifUserHasDmRelays && notifRecipientHasDmRelays,
                    forcePrivate = notifZapTarget?.id?.let { feedViewModel.eventRepo.isPrivate(it) } == true
                )
            }

            if (notifDmZapTarget != null) {
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { notifDmZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, _ ->
                        val target = notifDmZapTarget ?: return@ZapDialog
                        notifDmZapTarget = null
                        notifDmZapPendingSats = amountMsats / 1000
                        lastNotifDmZapSenderPubkey = target.senderPubkey
                        feedViewModel.socialActions.sendZapToPubkey(
                            target.senderPubkey, amountMsats, message, isAnonymous,
                            rumorId = target.rumorId.ifEmpty { null }
                        )
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) }
                )
            }

            val notifResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val notifUnicodeEmojis by feedViewModel.customEmojiRepo.sortedUnicodeEmojis.collectAsState()

            NotificationsScreen(
                viewModel = notificationsViewModel,
                scrollToTopTrigger = scrollToTopTrigger,
                userPubkey = feedViewModel.getUserPubkey(),
                notifSoundEnabled = notifSoundEnabled,
                onToggleNotifSound = {
                    notifSoundEnabled = !notifSoundEnabled
                    notifPrefs.edit().putBoolean("notif_sound_enabled", notifSoundEnabled).apply()
                },
                onBack = { navController.popBackStack() },
                onNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onRefresh = { feedViewModel.refreshDmsAndNotifications() },
                onSendReply = { replyToEvent, content ->
                    val signer = activeSigner ?: return@NotificationsScreen
                    notifReplyScope.launch {
                        val hint = feedViewModel.outboxRouter?.getRelayHint(replyToEvent.pubkey) ?: ""
                        val tags = cooking.zap.app.nostr.Nip10.buildReplyTags(replyToEvent, hint) +
                            cooking.zap.app.nostr.Nip30.buildEmojiTagsForContent(content, notifResolvedEmojis) +
                            if (notifInterfacePrefs.isClientTagEnabled()) listOf(Nip89.clientTag()) else emptyList()

                        // If the parent is a private reply we received, keep the thread encrypted
                        // by gift-wrapping this reply too. Otherwise fall through to the public path.
                        if (feedViewModel.eventRepo.isPrivate(replyToEvent.id)) {
                            val difficulty = if (feedViewModel.powPrefs.isNotePowEnabled()) feedViewModel.powPrefs.getNoteDifficulty() else 0
                            cooking.zap.app.repo.PrivateReplyPublisher.send(
                                signer = signer,
                                relayPool = feedViewModel.relayPool,
                                dmRepo = feedViewModel.dmRepo,
                                relayListRepo = feedViewModel.relayListRepo,
                                eventRepo = feedViewModel.eventRepo,
                                replyTo = replyToEvent,
                                content = content,
                                baseTags = tags,
                                targetDifficulty = difficulty
                            )
                            return@launch
                        }

                        if (feedViewModel.powPrefs.isNotePowEnabled()) {
                            feedViewModel.powManager.submitNote(
                                signer = signer,
                                content = content,
                                tags = tags,
                                kind = 1,
                                replyToPubkey = replyToEvent.pubkey,
                                onPublished = {
                                    feedViewModel.eventRepo.addReplyCount(replyToEvent.id, "pow-pending")
                                    val rootId = cooking.zap.app.nostr.Nip10.getRootId(replyToEvent)
                                    if (rootId != null && rootId != replyToEvent.id) {
                                        feedViewModel.eventRepo.addReplyCount(rootId, "pow-pending")
                                    }
                                }
                            )
                        } else {
                            val event = signer.signEvent(kind = 1, content = content, tags = tags)
                            val msg = cooking.zap.app.nostr.ClientMessage.event(event)
                            if (feedViewModel.outboxRouter != null) {
                                feedViewModel.outboxRouter!!.publishToInbox(msg, replyToEvent.pubkey)
                            } else {
                                feedViewModel.relayPool.sendToWriteRelays(msg)
                            }
                            feedViewModel.eventRepo.cacheEvent(event)
                            feedViewModel.eventRepo.addReplyCount(replyToEvent.id, event.id)
                            val rootId = cooking.zap.app.nostr.Nip10.getRootId(replyToEvent)
                            if (rootId != null && rootId != replyToEvent.id) {
                                feedViewModel.eventRepo.addReplyCount(rootId, event.id)
                            }
                        }
                    }
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onZap = { event -> notifZapTarget = event },
                onFollowToggle = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) },
                onMuteThread = { rootEventId -> feedViewModel.muteThread(rootEventId) },
                onAddToList = { eventId -> addToListEventId = eventId },
                nip05Repo = feedViewModel.nip05Repo,
                isZapAnimating = { it in notifZapAnimatingIds },
                isZapInProgress = { it in notifZapInProgress },
                isInList = { it in notifListedIds },
                resolvedEmojis = notifResolvedEmojis,
                unicodeEmojis = notifUnicodeEmojis,
                onOpenEmojiLibrary = { showNotifEmojiLibrary = true },
                zapError = feedViewModel.zapError,
                translationRepo = feedViewModel.translationRepo,
                autoTranslate = feedViewModel.interfacePrefs.isAutoTranslate(),
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                onUploadMedia = { uris, onUrl ->
                    notifReplyScope.launch {
                        for (uri in uris) {
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val bytes = inputStream?.use { it.readBytes() } ?: continue
                                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                                val url = feedViewModel.blossomRepo.uploadMedia(bytes, mimeType, ext, activeSigner)
                                onUrl(url)
                            } catch (_: Exception) {}
                        }
                    }
                },
                onSendDm = { peerPubkey, content ->
                    notificationsViewModel.sendDm(peerPubkey, content, activeSigner)
                },
                onDmReact = { peerPubkey, rumorId, senderPubkey, emoji ->
                    notificationsViewModel.sendDmReaction(peerPubkey, rumorId, senderPubkey, emoji, activeSigner)
                },
                onDmZap = { peerPubkey, rumorId, senderPubkey ->
                    notifDmZapTarget = NotifDmZapInfo(peerPubkey, rumorId, senderPubkey)
                },
                dmZapSats = { senderPubkey -> notifDmZapSatsMap[senderPubkey] ?: 0L },
                onDmConversationClick = { conversationKey ->
                    if (conversationKey.contains(",")) {
                        navController.navigate("dm/group/${conversationKey.replace(",", "~")}")
                    } else {
                        navController.navigate("dm/$conversationKey")
                    }
                },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigate("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onGroupNotificationClick = { groupChatId, messageId ->
                    val relayUrl = feedViewModel.groupRepo.getRelayForGroup(groupChatId)
                    if (relayUrl != null) {
                        val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                        navController.navigate("group_room/$encodedRelay/${android.net.Uri.encode(groupChatId)}?scrollTo=${android.net.Uri.encode(messageId)}")
                    }
                },
                resolveGroupMessage = { groupChatId, messageId ->
                    val result = feedViewModel.groupRepo.findGroupMessage(groupChatId, messageId)
                    Triple(result?.content, result?.groupName, result?.emojiTags ?: emptyMap())
                },
                fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                isEmojiSetAdded = { pk, dTag ->
                    val ref = cooking.zap.app.nostr.Nip30.buildSetReference(pk, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                }
            )

            if (showNotifEmojiLibrary) {
                cooking.zap.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = notifUnicodeEmojis,
                    customEmojiMap = notifResolvedEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onCustomEmojiPick = { shortcode ->
                        pendingEmojiReactCallback?.invoke(":$shortcode:")
                        pendingEmojiReactCallback = null
                        showNotifEmojiLibrary = false
                    },
                    onDismiss = { showNotifEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
        }
    }

    FloatingVideoPlayer(
        onExpandToFullScreen = { url, positionMs, player, aspectRatio ->
            pipFullScreenPlayer = player
            pipFullScreenAspectRatio = aspectRatio
            pipFullScreenVideoUrl = url
            pipFullScreenStartPosition = positionMs
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 72.dp)
    )

    if (pipFullScreenVideoUrl != null) {
        FullScreenVideoPlayer(
            videoUrl = pipFullScreenVideoUrl!!,
            startPositionMs = pipFullScreenStartPosition,
            existingPlayer = pipFullScreenPlayer,
            onMinimizeToPip = { player, _ ->
                PipController.enterPip(pipFullScreenVideoUrl!!, player, pipFullScreenAspectRatio)
            },
            onDismiss = {
                pipFullScreenVideoUrl = null
                pipFullScreenPlayer = null
            }
        )
    }

    val inlineFullScreenRequest by FullScreenVideoState.request.collectAsState()
    if (inlineFullScreenRequest != null) {
        FullScreenVideoPlayer(
            videoUrl = inlineFullScreenRequest!!.url,
            startPositionMs = inlineFullScreenRequest!!.startPositionMs,
            onDismiss = { FullScreenVideoState.dismiss() }
        )
    }

    BroadcastStatusBar(
        broadcastState = broadcastState,
        powStatus = powStatus,
        onCancelMining = { feedViewModel.powManager.cancel() },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )
    // Persistent key-backup nudge — only on the feed, only while the active account
    // has an unconfirmed key backup. Collapse is per-session; it returns next launch.
    var keyBackupBannerCollapsed by remember { mutableStateOf(false) }
    KeyBackupBanner(
        visible = keyBackupNudge && currentRoute == Routes.FEED && !keyBackupBannerCollapsed,
        onBackup = { navController.navigate(Routes.backupKey(reprompt = true)) },
        onDismiss = { keyBackupBannerCollapsed = true },
        modifier = Modifier.align(Alignment.BottomCenter)
    )

    NsecPasteWarningOverlay()
    } // CompositionLocalProvider
    } // Box

    } // Scaffold
    } // ModalNavigationDrawer
}
