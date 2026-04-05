package com.wisp.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import com.wisp.app.ui.screen.BroadcastStatusBar
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.RemoteSigner
import com.wisp.app.nostr.SignerIntentBridge
import com.wisp.app.nostr.SignResult
import com.wisp.app.repo.SigningMode
import android.content.Context
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.wisp.app.ui.component.HapticHelper
import com.wisp.app.ui.component.NotifBlipSound
import com.wisp.app.ui.component.BottomTab
import com.wisp.app.ui.component.WispBottomBar
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.ui.component.pendingEmojiReactCallback
import com.wisp.app.ui.component.AuthApprovalDialog
import com.wisp.app.ui.screen.BlossomServersScreen
import com.wisp.app.ui.screen.AuthScreen
import com.wisp.app.ui.screen.SplashScreen
import com.wisp.app.ui.screen.ComposeScreen
import com.wisp.app.ui.screen.ContactPickerScreen
import com.wisp.app.ui.screen.DmConversationScreen
import com.wisp.app.ui.screen.DmListScreen
import com.wisp.app.ui.screen.DraftsScreen
import com.wisp.app.ui.screen.FeedScreen
import com.wisp.app.ui.screen.ProfileEditScreen
import com.wisp.app.ui.screen.RelayScreen
import com.wisp.app.ui.screen.ThreadScreen
import com.wisp.app.ui.screen.NotificationsScreen
import com.wisp.app.ui.screen.SafetyScreen
import com.wisp.app.ui.screen.UserProfileScreen
import com.wisp.app.ui.screen.ConsoleScreen
import com.wisp.app.ui.screen.RelayHealthScreen
import com.wisp.app.ui.screen.CustomEmojiScreen
import com.wisp.app.ui.screen.SearchScreen
import com.wisp.app.ui.screen.SocialGraphScreen
import com.wisp.app.ui.screen.BookmarkSetScreen
import com.wisp.app.ui.screen.ArticleScreen
import com.wisp.app.ui.screen.BookmarksScreen
import com.wisp.app.ui.screen.HashtagFeedScreen
import com.wisp.app.ui.screen.KeysScreen
import com.wisp.app.ui.screen.ListScreen
import com.wisp.app.ui.screen.LiveStreamScreen
import com.wisp.app.ui.screen.ExistingUserOnboardingScreen
import com.wisp.app.ui.screen.LoadingScreen
import com.wisp.app.ui.screen.ListsHubScreen
import com.wisp.app.ui.screen.InterfaceScreen
import com.wisp.app.ui.screen.PowSettingsScreen
import com.wisp.app.ui.screen.OnboardingScreen
import com.wisp.app.ui.component.AddNoteToListDialog
import com.wisp.app.ui.component.CrashReportDialog
import androidx.media3.exoplayer.ExoPlayer
import com.wisp.app.ui.component.FloatingVideoPlayer
import com.wisp.app.ui.component.PipController
import com.wisp.app.ui.component.FullScreenVideoPlayer
import com.wisp.app.ui.screen.OnboardingSuggestionsScreen
import com.wisp.app.ui.screen.RelayDetailScreen
import com.wisp.app.ui.screen.WalletScreen
import com.wisp.app.viewmodel.BlossomServersViewModel
import com.wisp.app.viewmodel.ArticleViewModel
import com.wisp.app.viewmodel.AuthViewModel
import com.wisp.app.viewmodel.ComposeViewModel
import com.wisp.app.viewmodel.DmConversationViewModel
import com.wisp.app.viewmodel.DmListViewModel
import com.wisp.app.viewmodel.FeedType
import com.wisp.app.viewmodel.FeedViewModel
import com.wisp.app.viewmodel.ProfileViewModel
import com.wisp.app.viewmodel.RelayViewModel
import com.wisp.app.viewmodel.ThreadViewModel
import com.wisp.app.viewmodel.UserProfileViewModel
import com.wisp.app.viewmodel.NotificationFilter
import com.wisp.app.viewmodel.NotificationsViewModel
import com.wisp.app.viewmodel.ConsoleViewModel
import com.wisp.app.viewmodel.RelayHealthViewModel
import com.wisp.app.viewmodel.DraftsViewModel
import com.wisp.app.viewmodel.SearchViewModel
import com.wisp.app.viewmodel.HashtagFeedViewModel
import com.wisp.app.viewmodel.OnboardingViewModel
import com.wisp.app.viewmodel.PowStatus
import com.wisp.app.viewmodel.SplashViewModel
import com.wisp.app.viewmodel.LiveStreamViewModel
import com.wisp.app.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
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
    const val ONBOARDING_PROFILE = "onboarding/profile"
    const val ONBOARDING_SUGGESTIONS = "onboarding/suggestions"
    const val RELAY_DETAIL = "relay_detail/{relayUrl}"
    const val CUSTOM_EMOJIS = "custom_emojis"
    const val HASHTAG_FEED = "hashtag/{tag}"
    const val HASHTAG_SET_FEED = "hashtag_set/{name}/{tags}"
    const val EXISTING_USER_ONBOARDING = "onboarding/existing"
    const val DRAFTS = "drafts"
    const val SOCIAL_GRAPH = "social_graph"
    const val POW_SETTINGS = "pow_settings"
    const val INTERFACE_SETTINGS = "interface_settings"
    const val RELAY_HEALTH = "relay_health"
    const val ARTICLE = "article/{kind}/{author}/{dTag}"
    const val LIVE_STREAM = "live_stream/{hostPubkey}/{dTag}?relayHint={relayHint}"
}

private fun NavController.navigateSafe(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route, builder)
    }
}

@Composable
fun WispNavHost(
    deepLinkUri: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    accentColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFF9800),
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
    val groupListViewModel: com.wisp.app.viewmodel.GroupListViewModel = viewModel()
    val blossomServersViewModel: BlossomServersViewModel = viewModel()
    val appContext = LocalContext.current.applicationContext
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
                    appContext.contentResolver
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
    val splashViewModel: SplashViewModel = viewModel()

    relayViewModel.relayPool = feedViewModel.relayPool

    // Unified signer — handles both LOCAL (nsec) and REMOTE (NIP-55) modes
    // Reactive: recomposes on login, logout, and account switch
    val context = LocalContext.current
    val signingMode by authViewModel.signingModeFlow.collectAsState()
    val npub by authViewModel.npub.collectAsState()
    val activeSigner = remember(signingMode, npub) {
        when (signingMode) {
            SigningMode.REMOTE -> {
                val pubkey = authViewModel.keyRepo.getPubkeyHex() ?: ""
                val pkg = authViewModel.keyRepo.getSignerPackage() ?: ""
                RemoteSigner(pubkey, context.contentResolver, pkg)
            }
            SigningMode.LOCAL -> {
                authViewModel.keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) }
            }
            SigningMode.READ_ONLY -> null
            null -> null
        }
    }

    // Push signer into FeedViewModel when it becomes available
    LaunchedEffect(activeSigner) {
        activeSigner?.let { feedViewModel.setSigner(it) }
    }

    // NIP-55 intent-based signing fallback — launches signer UI when ContentResolver fails
    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            val signature = data?.getStringExtra("signature") ?: data?.getStringExtra("result") ?: ""
            val event = data?.getStringExtra("event")
            SignerIntentBridge.deliverResult(SignResult.Success(signature, event))
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
        navController.navigateSafe(Routes.LOADING) {
            popUpTo(0) { inclusive = true }
        }
    }

    val onAddAccount: () -> Unit = {
        authViewModel.isAddingAccount = true
        feedViewModel.resetForAccountSwitch()
        walletViewModel.suspendForAccountSwitch()  // disconnect only, preserve credentials
        navController.navigateSafe(Routes.SPLASH) {
            popUpTo(0) { inclusive = true }
        }
    }

    // Tor state
    val torPrefs = remember { context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE) }
    val torStatus by com.wisp.app.relay.TorManager.status.collectAsState()
    val isTorEnabled = torStatus != com.wisp.app.relay.TorStatus.DISABLED

    // Auto-start Tor if previously enabled
    val torScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (torPrefs.getBoolean("tor_enabled", false)) {
            com.wisp.app.relay.TorManager.start()
        }
    }
    // When Tor finishes connecting/disconnecting, swap the relay pool client.
    // Skip the initial DISABLED state on first composition — only react to changes.
    var torStatusInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(torStatus) {
        if (!torStatusInitialized) {
            torStatusInitialized = true
            return@LaunchedEffect
        }
        if (torStatus == com.wisp.app.relay.TorStatus.CONNECTED ||
            torStatus == com.wisp.app.relay.TorStatus.DISABLED) {
            if (authViewModel.isLoggedIn) {
                feedViewModel.lifecycleManager.onTorSwitch(
                    savedConfigs = feedViewModel.keyRepo.getRelays(),
                    savedDmUrls = feedViewModel.keyRepo.getDmRelays()
                )
            }
        }
    }
    val onToggleTor: (Boolean) -> Unit = { enabled ->
        torPrefs.edit().putBoolean("tor_enabled", enabled).apply()
        torScope.launch {
            if (enabled) {
                com.wisp.app.relay.TorManager.start()
            } else {
                com.wisp.app.relay.TorManager.stop()
            }
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
        composeViewModel.init(feedViewModel.profileRepo, feedViewModel.contactRepo, feedViewModel.relayPool, feedViewModel.eventRepo, feedViewModel.eventPersistence)
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
        val parsed = Nip19.decodeNostrUri(uri) ?: return@remember null
        when (parsed) {
            is NostrUriData.ProfileRef -> "profile/${parsed.pubkey}"
            is NostrUriData.NoteRef -> "thread/${parsed.eventId}"
            is NostrUriData.AddressRef -> {
                if (parsed.kind == 30023 && parsed.author != null) {
                    "article/${parsed.kind}/${parsed.author}/${parsed.dTag}"
                } else null
            }
        }
    }

    // Handle deep links when app is already past loading (onNewIntent)
    LaunchedEffect(deepLinkUri) {
        val route = deepLinkRoute ?: return@LaunchedEffect
        if (!authViewModel.isLoggedIn) return@LaunchedEffect
        val currentDest = navController.currentDestination?.route
        // Only navigate directly if we're past the loading/auth screens
        if (currentDest != null && currentDest !in setOf(Routes.LOADING, Routes.AUTH, Routes.SPLASH, Routes.ONBOARDING_PROFILE)) {
            onDeepLinkConsumed()
            navController.navigateSafe(route)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val nonAppRoutes = setOf(Routes.SPLASH, Routes.AUTH, Routes.LOADING, Routes.ONBOARDING_PROFILE, Routes.ONBOARDING_SUGGESTIONS, Routes.EXISTING_USER_ONBOARDING)
    val hideBottomBarRoutes = nonAppRoutes + Routes.DM_CONVERSATION + Routes.DM_CONVERSATION_GROUP + Routes.CONTACT_PICKER + Routes.GROUP_ROOM + Routes.GROUP_DETAIL + Routes.LIVE_STREAM
    val socialGraphDiscoveryState by feedViewModel.extendedNetworkRepo.discoveryState.collectAsState()
    val socialGraphComputing = currentRoute == Routes.SOCIAL_GRAPH && (
        socialGraphDiscoveryState is com.wisp.app.repo.DiscoveryState.FetchingFollowLists ||
        socialGraphDiscoveryState is com.wisp.app.repo.DiscoveryState.BuildingGraph ||
        socialGraphDiscoveryState is com.wisp.app.repo.DiscoveryState.ComputingNetwork ||
        socialGraphDiscoveryState is com.wisp.app.repo.DiscoveryState.Filtering ||
        socialGraphDiscoveryState is com.wisp.app.repo.DiscoveryState.FetchingRelayLists)
    val showBottomBar by remember(currentRoute, socialGraphComputing) {
        derivedStateOf { currentRoute != null && currentRoute !in hideBottomBarRoutes && !socialGraphComputing }
    }

    // After process death, Navigation restores the last screen but the ViewModel
    // is fresh (no relay connections, empty feed). Redirect to LOADING to re-fetch.
    val loadingComplete by feedViewModel.loadingScreenComplete.collectAsState()
    if (currentRoute != null && currentRoute !in nonAppRoutes && !loadingComplete) {
        LaunchedEffect(Unit) {
            feedViewModel.initRelays()
            navController.navigateSafe(Routes.LOADING) {
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
            isReplyAnimating = true
            kotlinx.coroutines.delay(1000)
            isReplyAnimating = false
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
        notificationsViewModel.notifReceived.collect {
            if (!currentNotifSoundEnabled) return@collect
            val enabled = notificationsViewModel.enabledTypes.value
            val anyGenericEnabled = NotificationFilter.REACTIONS in enabled ||
                NotificationFilter.REPOSTS in enabled ||
                NotificationFilter.MENTIONS in enabled
            if (anyGenericEnabled) {
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
            isReplyAnimating = true
            kotlinx.coroutines.delay(1000)
            isReplyAnimating = false
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

    // NIP-42 AUTH approval dialog — shown when a DM delivery relay requests authentication
    val pendingAuth by feedViewModel.relayPool.pendingAuthRequest.collectAsState()
    pendingAuth?.let { request ->
        AuthApprovalDialog(
            relayUrl = request.relayUrl,
            onAllow = { feedViewModel.relayPool.approveAuth(request) },
            onDeny = { feedViewModel.relayPool.denyAuth(request) }
        )
    }

    // Prevent the system back button from ever closing the app.
    // On FEED: consume the press (nowhere to go).
    // On any other app screen: pop the back stack, falling back to FEED if empty.
    val isAppRoute = currentRoute != null && currentRoute !in nonAppRoutes
    BackHandler(enabled = isAppRoute) {
        if (currentRoute == Routes.FEED) {
            // Already on home — do nothing
        } else {
            val popped = navController.popBackStack()
            if (!popped) {
                navController.navigateSafe(Routes.FEED) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                WispBottomBar(
                    currentRoute = currentRoute,
                    hasUnreadHome = newNoteCount > 0,
                    hasUnreadMessages = hasUnreadDms,
                    hasUnreadNotifications = hasUnreadNotifications,
                    isZapAnimating = isZapAnimating,
                    isReplyAnimating = isReplyAnimating,
                    notifSoundEnabled = notifSoundEnabled,
                    onTabSelected = { tab ->
                        if (currentRoute == tab.route) {
                            scrollToTopTrigger++
                        } else {
                            if (tab == BottomTab.WALLET) walletViewModel.navigateHome()
                            navController.navigateSafe(tab.route) {
                                popUpTo(Routes.FEED) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    }
                )
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
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                viewModel = splashViewModel,
                isTorEnabled = isTorEnabled,
                torStatus = torStatus,
                onToggleTor = onToggleTor,
                onSignUp = {
                    if (authViewModel.signUp()) {
                        navController.navigateSafe(Routes.ONBOARDING_PROFILE) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }
                },
                onLogIn = {
                    navController.navigateSafe(Routes.AUTH)
                }
            )
        }

        composable(Routes.AUTH) {
            AuthScreen(
                viewModel = authViewModel,
                isTorEnabled = isTorEnabled,
                torStatus = torStatus,
                onToggleTor = onToggleTor,
                showSignUp = false,
                onAuthenticated = { isNewAccount ->
                    val wasAddingAccount = authViewModel.isAddingAccount
                    authViewModel.isAddingAccount = false

                    if (isNewAccount) {
                        // New key generation always goes through full onboarding
                        navController.navigateSafe(Routes.ONBOARDING_PROFILE)
                    } else if (wasAddingAccount && authViewModel.keyRepo.isOnboardingComplete()) {
                        // Adding an existing account that already completed onboarding — skip straight to loading
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        navController.navigateSafe(Routes.LOADING) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    } else if (authViewModel.keyRepo.isReadOnly()) {
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        feedViewModel.initRelays()
                        authViewModel.keyRepo.markOnboardingComplete()
                        navController.navigateSafe(Routes.LOADING) {
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
                        navController.navigateSafe(Routes.EXISTING_USER_ONBOARDING) {
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
                        // Navigate to Feed first (as backstack root), then to the deep link target
                        navController.navigateSafe(Routes.FEED) {
                            popUpTo(Routes.LOADING) { inclusive = true }
                        }
                        navController.navigateSafe(target)
                    } else {
                        navController.navigateSafe(Routes.FEED) {
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
                    navController.navigateSafe(Routes.FEED) {
                        popUpTo(Routes.EXISTING_USER_ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FEED) {
            FeedScreen(
                viewModel = feedViewModel,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                isTorEnabled = isTorEnabled,
                torStatus = torStatus,
                onToggleTor = onToggleTor,
                scrollToTopTrigger = scrollToTopTrigger,
                onCompose = {
                    replyTarget = null
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onRelays = {
                    navController.navigateSafe(Routes.RELAYS)
                },
                onProfileEdit = {
                    val pubkey = feedViewModel.getUserPubkey()
                    if (pubkey != null) {
                        navController.navigateSafe("profile/$pubkey")
                    }
                },
                onProfileClick = { pubkey ->
                    navController.navigateSafe("profile/$pubkey")
                },
                onDms = {
                    navController.navigateSafe(Routes.DM_LIST)
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onNoteClick = { event ->
                    navController.navigateSafe("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigateSafe("thread/$eventId")
                },
                accounts = accounts,
                onSwitchAccount = onSwitchAccount,
                onAddAccount = onAddAccount,
                onLogout = {
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
                        navController.navigateSafe(Routes.LOADING) {
                            popUpTo(0) { inclusive = true }
                        }
                    } else {
                        navController.navigateSafe(Routes.SPLASH) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onMediaServers = {
                    navController.navigateSafe(Routes.BLOSSOM_SERVERS)
                },
                onWallet = {
                    navController.navigateSafe(Routes.WALLET)
                },
                onLists = {
                    navController.navigateSafe(Routes.LISTS_HUB)
                },
                onDrafts = {
                    navController.navigateSafe(Routes.DRAFTS)
                },
                onSafety = {
                    navController.navigateSafe(Routes.SAFETY)
                },
                onSearch = {
                    navController.navigateSafe(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSocialGraph = {
                    navController.navigateSafe(Routes.SOCIAL_GRAPH)
                },
                onCustomEmojis = {
                    navController.navigateSafe(Routes.CUSTOM_EMOJIS)
                },
                onConsole = {
                    navController.navigateSafe(Routes.CONSOLE)
                },
                onRelayHealth = {
                    navController.navigateSafe(Routes.RELAY_HEALTH)
                },
                onKeys = {
                    navController.navigateSafe(Routes.KEYS)
                },
                onPowSettings = {
                    navController.navigateSafe(Routes.POW_SETTINGS)
                },
                onInterfaceSettings = {
                    navController.navigateSafe(Routes.INTERFACE_SETTINGS)
                },
                onAddToList = { eventId -> addToListEventId = eventId },
                onRelayDetail = { url ->
                    navController.navigateSafe("relay_detail/${java.net.URLEncoder.encode(url, "UTF-8")}")
                },
                onHashtagClick = { tag ->
                    navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onViewSetFeed = { set ->
                    val encodedName = java.net.URLEncoder.encode(set.name, "UTF-8")
                    val encodedTags = java.net.URLEncoder.encode(set.hashtags.joinToString(","), "UTF-8")
                    navController.navigateSafe("hashtag_set/$encodedName/$encodedTags")
                },
                onArticleClick = { kind, author, dTag ->
                    navController.navigateSafe("article/$kind/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}")
                },
                onGroupRoom = { relayUrl, groupId ->
                    val encoded = android.util.Base64.encodeToString(
                        relayUrl.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    )
                    navController.navigateSafe("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigateSafe(route)
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
                draftsViewModel.loadDrafts(feedViewModel.relayPool, activeSigner)
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
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onDeleteDraft = { dTag ->
                    draftsViewModel.deleteDraft(dTag, feedViewModel.relayPool, activeSigner)
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
            val profileUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onRepost = { event ->
                    feedViewModel.sendRepost(event)
                },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                eventRepo = feedViewModel.eventRepo,
                onNavigateToProfile = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                isOwnProfile = isOwnProfile,
                onEditProfile = {
                    profileViewModel.loadCurrentProfile(feedViewModel.eventRepo, feedViewModel.relayPool)
                    navController.navigateSafe(Routes.PROFILE_EDIT)
                },
                isBlocked = pubkey in isBlockedState,
                onBlockUser = {
                    feedViewModel.blockUser(pubkey)
                    navController.popBackStack()
                },
                onUnblockUser = { feedViewModel.unblockUser(pubkey) },
                onNoteClick = { event -> navController.navigateSafe("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onZap = { event, amountMsats, message, isAnonymous, isPrivate -> feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate) },
                userPubkey = feedViewModel.getUserPubkey(),
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onWallet = { navController.navigateSafe(Routes.WALLET) },
                zapSuccess = feedViewModel.zapSuccess,
                zapError = feedViewModel.zapError,
                zapInProgressIds = profileZapInProgress,
                canPrivateZap = feedViewModel.relayPool.hasDmRelays() && feedViewModel.relayListRepo.hasDmRelays(pubkey),
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
                onSendDm = if (!isOwnProfile) {{ navController.navigateSafe("dm/$pubkey") }} else null,
                onZapProfile = if (!isOwnProfile) { { amountMsats, message, isAnonymous ->
                    feedViewModel.socialActions.sendZapToPubkey(pubkey, amountMsats, message, isAnonymous)
                } } else null,
                signer = activeSigner,
                translationRepo = feedViewModel.translationRepo,
                onArticleClick = { kind, articleAuthor, articleDTag ->
                    navController.navigateSafe("article/$kind/$articleAuthor/${java.net.URLEncoder.encode(articleDTag, "UTF-8")}")
                },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                resolvedEmojis = profileResolvedEmojis,
                unicodeEmojis = profileUnicodeEmojis,
                onOpenEmojiLibrary = { showProfileEmojiLibrary = true },
                onSearchAuthor = {
                    val authorProfile = userProfileViewModel.profile.value
                        ?: ProfileData(pubkey = pubkey, name = null, displayName = null, about = null, picture = null, nip05 = null, banner = null, lud16 = null, updatedAt = 0)
                    searchViewModel.prepareAuthorSearch(authorProfile)
                    navController.navigateSafe(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigateSafe("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigateSafe(route)
                },
                fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                isEmojiSetAdded = { pk, dTag ->
                    val ref = com.wisp.app.nostr.Nip30.buildSetReference(pk, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                }
            )
            if (showProfileEmojiLibrary) {
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = profileUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = userHasDmRelays && recipientHasDmRelays
                )
            }
            SearchScreen(
                viewModel = searchViewModel,
                relayPool = feedViewModel.relayPool,
                eventRepo = feedViewModel.eventRepo,
                muteRepo = feedViewModel.muteRepo,
                contactRepo = feedViewModel.contactRepo,
                onProfileClick = { pubkey ->
                    navController.navigateSafe("profile/$pubkey")
                },
                onNoteClick = { event ->
                    navController.navigateSafe("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigateSafe("thread/$eventId")
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
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
                    navController.navigateSafe(Routes.COMPOSE)
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
                    navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onBlockUser = { pubkey ->
                    feedViewModel.blockUser(pubkey)
                },
                userPubkey = feedViewModel.getUserPubkey(),
                listedIds = searchListedIds,
                onAddToList = { eventId -> addToListEventId = eventId },
                onDeleteEvent = { eventId, kind -> feedViewModel.deleteEvent(eventId, kind) },
                translationRepo = feedViewModel.translationRepo,
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                isEmojiSetAdded = { pubkey, dTag ->
                    val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                },
                nip05Repo = feedViewModel.nip05Repo
            )
        }

        composable(Routes.DM_LIST) {
            LaunchedEffect(Unit) {
                feedViewModel.refreshDmsAndNotifications()
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
                        navController.navigateSafe("dm/group/${convo.conversationKey.replace(",", "~")}")
                    } else {
                        navController.navigateSafe("dm/${convo.peerPubkey}")
                    }
                },
                onNewGroupDm = {
                    navController.navigateSafe(Routes.CONTACT_PICKER)
                },
                onGroupRoom = { relayUrl, groupId ->
                    val encoded = android.util.Base64.encodeToString(
                        relayUrl.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    )
                    navController.navigateSafe("group_room/$encoded/${android.net.Uri.encode(groupId)}")
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
                    navController.navigateSafe("dm/group/${conversationKey.replace(",", "~")}")
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
                feedViewModel.refreshDmsAndNotifications()
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
            val dmUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                onProfileClick = { pk -> navController.navigateSafe("profile/$pk") },
                onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                peerPubkey = pubkey,
                signer = activeSigner,
                socialActionManager = feedViewModel.socialActions,
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                noteActions = remember {
                    com.wisp.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = com.wisp.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        }
                    )
                },
                resolvedEmojis = dmResolvedEmojis,
                unicodeEmojis = dmUnicodeEmojis,
                onOpenEmojiLibrary = { showDmEmojiLibrary = true }
            )
            if (showDmEmojiLibrary) {
                val dmSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = dmSheetUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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
                feedViewModel.refreshDmsAndNotifications()
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
            val dmGroupUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                onProfileClick = { pk -> navController.navigateSafe("profile/$pk") },
                onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                peerPubkey = participantList.firstOrNull() ?: "",
                participants = participantList,
                signer = activeSigner,
                socialActionManager = feedViewModel.socialActions,
                isWalletConnected = feedViewModel.activeWalletProvider.hasConnection(),
                onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                noteActions = remember {
                    com.wisp.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = com.wisp.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        }
                    )
                },
                resolvedEmojis = dmGroupResolvedEmojis,
                unicodeEmojis = dmGroupUnicodeEmojis,
                onOpenEmojiLibrary = { showDmGroupEmojiLibrary = true }
            )
            if (showDmGroupEmojiLibrary) {
                val dmGroupSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = dmGroupSheetUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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
            val groupRoomViewModel: com.wisp.app.viewmodel.GroupRoomViewModel = viewModel()
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
            var groupRoomZapTarget by remember { mutableStateOf<com.wisp.app.nostr.NostrEvent?>(null) }
            var showGroupRoomEmojiLibrary by remember { mutableStateOf(false) }
            val groupRoomResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val groupRoomUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                    onDismiss = { groupRoomZapTarget = null },
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        val event = groupRoomZapTarget ?: return@ZapDialog
                        groupRoomZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
                    },
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = feedViewModel.relayPool.hasDmRelays() && recipientHasDmRelays
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
            com.wisp.app.ui.screen.GroupRoomScreen(
                viewModel = groupRoomViewModel,
                initialRoom = initialRoom,
                scrollToMessageId = scrollToMessageId,
                relayPool = feedViewModel.relayPool,
                eventRepo = feedViewModel.eventRepo,
                signer = activeSigner,
                onBack = { navController.popBackStack() },
                onProfileClick = { pk -> navController.navigateSafe("profile/$pk") },
                onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                onGroupDetail = { navController.navigateSafe("group_detail/$encodedRelay/${android.net.Uri.encode(groupId)}") },
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
                    groupRoomZapTarget = com.wisp.app.nostr.NostrEvent(
                        id = msgId,
                        pubkey = senderPubkey,
                        created_at = 0L,
                        kind = com.wisp.app.nostr.Nip29.KIND_CHAT_MESSAGE,
                        tags = listOf(listOf("h", groupId)),
                        content = "",
                        sig = ""
                    )
                    // Subscribe to zap receipt on the group relay too, in case the
                    // LNURL provider publishes it there
                    feedViewModel.relayPool.sendToRelayOrEphemeral(
                        relayUrl,
                        com.wisp.app.nostr.ClientMessage.req(
                            subscriptionId = "zap-rcpt-grp-${msgId.take(12)}",
                            filter = com.wisp.app.nostr.Filter(kinds = listOf(9735), eTags = listOf(msgId))
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
                    com.wisp.app.ui.component.NoteActions(
                        nip05Repo = feedViewModel.nip05Repo,
                        onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                        onAddEmojiSet = { pk, dTag -> feedViewModel.addSetToEmojiList(pk, dTag) },
                        onRemoveEmojiSet = { pk, dTag -> feedViewModel.removeSetFromEmojiList(pk, dTag) },
                        isEmojiSetAdded = { pk, dTag ->
                            val ref = com.wisp.app.nostr.Nip30.buildSetReference(pk, dTag)
                            feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                        }
                    )
                }
            )
            if (showGroupRoomEmojiLibrary) {
                val groupRoomSheetUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = groupRoomSheetUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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
            com.wisp.app.ui.screen.GroupDetailScreen(
                groupId = groupId,
                relayUrl = relayUrl,
                groupListViewModel = groupListViewModel,
                eventRepo = feedViewModel.eventRepo,
                myPubkey = feedViewModel.getUserPubkey(),
                signer = activeSigner,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                onLeave = {
                    navController.navigateSafe(Routes.DM_LIST) {
                        popUpTo(Routes.DM_LIST) { inclusive = false }
                    }
                },
                onDelete = {
                    navController.navigateSafe(Routes.DM_LIST) {
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
                    relayHintStore = feedViewModel.relayHintStore
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = threadUserHasDmRelays && threadRecipientHasDmRelays
                )
            }
            val threadSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val threadBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val threadListedIds = remember(threadSetListedIds, threadBookmarkedIds) { threadSetListedIds + threadBookmarkedIds }
            val threadPinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            val threadResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val threadUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onProfileClick = { pubkey ->
                    navController.navigateSafe("profile/$pubkey")
                },
                onNoteClick = { event ->
                    navController.navigateSafe("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigateSafe("thread/$eventId")
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
                    navController.navigateSafe(Routes.COMPOSE)
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
                    navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onRelayClick = { url ->
                    feedViewModel.setSelectedRelay(url)
                    feedViewModel.setFeedType(FeedType.RELAY)
                    navController.popBackStack(Routes.FEED, inclusive = false)
                },
                onArticleClick = { kind, author, dTag ->
                    navController.navigateSafe("article/$kind/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}")
                },
                translationRepo = feedViewModel.translationRepo,
                resolvedEmojis = threadResolvedEmojis,
                unicodeEmojis = threadUnicodeEmojis,
                onOpenEmojiLibrary = { showThreadEmojiLibrary = true },
                onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigateSafe("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onLiveStreamClick = { hostPubkey, dTag, relayHint ->
                    val route = buildString {
                        append("live_stream/$hostPubkey/${android.net.Uri.encode(dTag)}")
                        if (relayHint != null) append("?relayHint=${android.net.Uri.encode(relayHint)}")
                    }
                    navController.navigateSafe(route)
                },
                fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                isEmojiSetAdded = { pubkey, dTag ->
                    val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                }
            )

            if (showThreadEmojiLibrary) {
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = threadUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = hashtagUserHasDmRelays && hashtagRecipientHasDmRelays
                )
            }

            val hashtagNoteActions = remember {
                com.wisp.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onZap = { event -> hashtagZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { clickedTag ->
                        navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}")
                    },
                    onRelayClick = { url ->
                        feedViewModel.setSelectedRelay(url)
                        feedViewModel.setFeedType(FeedType.RELAY)
                        navController.popBackStack(Routes.FEED, inclusive = false)
                    },
                    onArticleClick = { kind, author, dTag ->
                        navController.navigateSafe("article/$kind/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}")
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigateSafe("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    }
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = setFeedUserHasDmRelays && setFeedRecipientHasDmRelays
                )
            }

            val setFeedNoteActions = remember {
                com.wisp.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onZap = { event -> setFeedZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { clickedTag ->
                        navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}")
                    },
                    onRelayClick = { url ->
                        feedViewModel.setSelectedRelay(url)
                        feedViewModel.setFeedType(FeedType.RELAY)
                        navController.popBackStack(Routes.FEED, inclusive = false)
                    },
                    onArticleClick = { kind, author, dTag ->
                        navController.navigateSafe("article/$kind/$author/${java.net.URLEncoder.encode(dTag, "UTF-8")}")
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigateSafe("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    }
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
            var articleZapTarget by remember { mutableStateOf<com.wisp.app.nostr.NostrEvent?>(null) }
            val articleZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var articleZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isNwcConnected = feedViewModel.activeWalletProvider.hasConnection()
            val articleSetListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val articleBookmarkedIds by feedViewModel.bookmarkRepo.bookmarkedIds.collectAsState()
            val articleListedIds = remember(articleSetListedIds, articleBookmarkedIds) { articleSetListedIds + articleBookmarkedIds }
            val articlePinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            val articleResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val articleUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = articleUserHasDmRelays && articleRecipientHasDmRelays
                )
            }

            val articleNoteActions = remember {
                com.wisp.app.ui.component.NoteActions(
                    onReply = { event ->
                        replyTarget = event
                        quoteTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                    onRepost = { event -> feedViewModel.sendRepost(event) },
                    onQuote = { event ->
                        quoteTarget = event
                        replyTarget = null
                        composeViewModel.clear()
                        navController.navigateSafe(Routes.COMPOSE)
                    },
                    onZap = { event -> articleZapTarget = event },
                    onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                    onNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                    onAddToList = { eventId -> addToListEventId = eventId },
                    onFollowAuthor = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                    onBlockAuthor = { pubkey -> feedViewModel.blockUser(pubkey) },
                    onPin = { eventId -> feedViewModel.togglePin(eventId) },
                    isFollowing = { pubkey -> feedViewModel.contactRepo.isFollowing(pubkey) },
                    userPubkey = feedViewModel.getUserPubkey(),
                    nip05Repo = feedViewModel.nip05Repo,
                    onHashtagClick = { tag ->
                        navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                    },
                    onArticleClick = { k, a, d ->
                        navController.navigateSafe("article/$k/$a/${java.net.URLEncoder.encode(d, "UTF-8")}")
                    },
                    onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                    onPollVote = { pollId, optionIds -> feedViewModel.publishPollVote(pollId, optionIds) },
                    onGroupRoom = { relayUrl, groupId ->
                        val encoded = android.util.Base64.encodeToString(
                            relayUrl.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        )
                        navController.navigateSafe("group_room/$encoded/${android.net.Uri.encode(groupId)}")
                    },
                    groupMetadataProvider = { relayUrl, groupId -> feedViewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
                    fetchGroupPreview = { relayUrl, groupId -> groupListViewModel.fetchGroupPreview(relayUrl, groupId) },
                    onAddEmojiSet = { pubkey, dTag -> feedViewModel.addSetToEmojiList(pubkey, dTag) },
                    onRemoveEmojiSet = { pubkey, dTag -> feedViewModel.removeSetFromEmojiList(pubkey, dTag) },
                    isEmojiSetAdded = { pubkey, dTag ->
                        val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                        feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                    }
                )
            }

            ArticleScreen(
                viewModel = articleViewModel,
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                onHashtagClick = { tag ->
                    navController.navigateSafe("hashtag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
                },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onRepost = { event -> feedViewModel.sendRepost(event) },
                onQuote = { event ->
                    quoteTarget = event
                    replyTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
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
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = articleUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
                    },
                    onDismiss = { showArticleEmojiLibrary = false; pendingEmojiReactCallback = null }
                )
            }
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
                    feedViewModel.contactRepo, naddrHints
                )
            }
            DisposableEffect(hostPubkey, dTag) {
                onDispose { liveStreamViewModel.cleanup(feedViewModel.relayPool) }
            }
            val liveResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val liveUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = feedViewModel.relayPool.hasDmRelays() && recipientHasDmRelays
                )
            }
            val streamActivityEventId = remember(hostPubkey, dTag) {
                feedViewModel.eventRepo.findAddressableEvent(
                    com.wisp.app.nostr.Nip53.KIND_LIVE_ACTIVITY, hostPubkey, dTag
                )?.id
            }
            LiveStreamScreen(
                viewModel = liveStreamViewModel,
                eventRepo = feedViewModel.eventRepo,
                signer = activeSigner,
                myPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
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
                        com.wisp.app.nostr.Nip53.KIND_LIVE_ACTIVITY, hostPubkey, dTag
                    )
                    if (activityEvent != null) {
                        liveZapTarget = activityEvent
                        val activity = liveStreamViewModel.activity.value
                        liveZapRecipientOverride = activity?.streamerPubkey ?: activity?.hostPubkey
                        liveZapATag = com.wisp.app.nostr.Nip53.aTagValue(hostPubkey, dTag)
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
                onBack = { navController.popBackStack() },
                onChanged = { feedViewModel.updateMutedWords() }
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
            val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
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
                    navController.navigateSafe("relay_detail/${java.net.URLEncoder.encode(url, "UTF-8")}")
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
                    navController.navigateSafe("profile/$pubkey")
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
                onOperatorClick = if (operatorPubkey != null) {{ navController.navigateSafe("profile/$operatorPubkey") }} else null,
                onToggleFavorite = { feedViewModel.toggleFavoriteRelay(relayUrl) },
                onAddToRelaySet = { dTag -> feedViewModel.addRelayToSet(relayUrl, dTag) },
                onCreateRelaySet = { name ->
                    feedViewModel.createRelaySet(name, setOf(relayUrl))
                }
            )
        }

        composable(Routes.KEYS) {
            KeysScreen(
                keyRepository = authViewModel.keyRepo,
                onBack = { navController.popBackStack() }
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
                onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
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
                        navController.navigateSafe(Routes.FEED) {
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
                    navController.navigateSafe("list/${list.pubkey}/${list.dTag}")
                },
                onBookmarkSetDetail = { set ->
                    navController.navigateSafe("bookmark_set/${set.pubkey}/${set.dTag}")
                },
                onBookmarksClick = { navController.navigateSafe(Routes.BOOKMARKS) },
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
                onNoteClick = { event -> navController.navigateSafe("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                onRemoveBookmark = { eventId -> feedViewModel.removeBookmark(eventId) },
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) },
                translationRepo = feedViewModel.translationRepo,
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
                onNoteClick = { event -> navController.navigateSafe("thread/${event.id}") },
                onQuotedNoteClick = { eventId -> navController.navigateSafe("thread/$eventId") },
                onReply = { event ->
                    replyTarget = event
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigateSafe(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onProfileClick = { pubkey -> navController.navigateSafe("profile/$pubkey") },
                onRemoveFromSet = if (isOwnList) { eventId ->
                    feedViewModel.removeNoteFromBookmarkSet(dTag, eventId)
                } else null,
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) },
                translationRepo = feedViewModel.translationRepo
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
                            navController.navigateSafe(Routes.ONBOARDING_SUGGESTIONS) {
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
                onboardingViewModel.loadSuggestions(feedViewModel.relayPool)
            }
            val activeNow by onboardingViewModel.activeNow.collectAsState()
            val creators by onboardingViewModel.creators.collectAsState()
            val news by onboardingViewModel.news.collectAsState()
            val selectedPubkeys by onboardingViewModel.selectedPubkeys.collectAsState()
            val scope = rememberCoroutineScope()

            OnboardingSuggestionsScreen(
                activeNow = activeNow,
                creators = creators,
                news = news,
                selectedPubkeys = selectedPubkeys,
                onToggleFollowAll = { section -> onboardingViewModel.toggleFollowAll(section) },
                onTogglePubkey = { pubkey -> onboardingViewModel.togglePubkey(pubkey) },
                totalSelected = selectedPubkeys.size,
                onContinue = {
                    scope.launch {
                        feedViewModel.setFeedType(FeedType.FOLLOWS)
                        feedViewModel.reloadForNewAccount()
                        relayViewModel.reload()
                        blossomServersViewModel.reload()
                        composeViewModel.reloadBlossomRepo()
                        walletViewModel.refreshState()
                        // finishOnboarding must run after reloadForNewAccount so contactRepo
                        // is already switched to the new pubkey-specific prefs file.
                        // Otherwise the follow list is saved to the null-keyed prefs and
                        // wiped when reloadForNewAccount reloads to the correct prefs.
                        onboardingViewModel.finishOnboarding(
                            relayPool = feedViewModel.relayPool,
                            contactRepo = feedViewModel.contactRepo,
                            selectedPubkeys = selectedPubkeys,
                            signer = activeSigner
                        )
                        feedViewModel.initRelays()
                        navController.navigateSafe(Routes.LOADING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSkip = {
                    authViewModel.keyRepo.markOnboardingComplete()
                    feedViewModel.setFeedType(FeedType.EXTENDED_FOLLOWS)
                    feedViewModel.reloadForNewAccount()
                    relayViewModel.reload()
                    blossomServersViewModel.reload()
                    composeViewModel.reloadBlossomRepo()
                    walletViewModel.refreshState()
                    navController.navigateSafe(Routes.FEED) {
                        popUpTo(0) { inclusive = true }
                    }
                    scope.launch { feedViewModel.initRelays() }
                }
            )
        }

        composable(Routes.NOTIFICATIONS) {
            DisposableEffect(Unit) {
                feedViewModel.notifRepo.isViewing = true
                onDispose { feedViewModel.notifRepo.isViewing = false }
            }
            LaunchedEffect(Unit) {
                feedViewModel.refreshDmsAndNotifications()
                notificationsViewModel.markRead()
            }

            val notifReplyScope = rememberCoroutineScope()
            val notifInterfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) },
                    canPrivateZap = notifUserHasDmRelays && notifRecipientHasDmRelays
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
                    onGoToWallet = { navController.navigateSafe(Routes.WALLET) }
                )
            }

            val notifResolvedEmojis by feedViewModel.customEmojiRepo.resolvedEmojis.collectAsState()
            val notifUnicodeEmojis by feedViewModel.customEmojiRepo.unicodeEmojis.collectAsState()

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
                    navController.navigateSafe("thread/$eventId")
                },
                onProfileClick = { pubkey ->
                    navController.navigateSafe("profile/$pubkey")
                },
                onRefresh = { feedViewModel.refreshDmsAndNotifications() },
                onSendReply = { replyToEvent, content ->
                    val signer = activeSigner ?: return@NotificationsScreen
                    notifReplyScope.launch {
                        val hint = feedViewModel.outboxRouter?.getRelayHint(replyToEvent.pubkey) ?: ""
                        val tags = com.wisp.app.nostr.Nip10.buildReplyTags(replyToEvent, hint) +
                            com.wisp.app.nostr.Nip30.buildEmojiTagsForContent(content, notifResolvedEmojis) +
                            if (notifInterfacePrefs.isClientTagEnabled()) listOf(listOf("client", "Wisp")) else emptyList()

                        if (feedViewModel.powPrefs.isNotePowEnabled()) {
                            feedViewModel.powManager.submitNote(
                                signer = signer,
                                content = content,
                                tags = tags,
                                kind = 1,
                                replyToPubkey = replyToEvent.pubkey,
                                onPublished = {
                                    feedViewModel.eventRepo.addReplyCount(replyToEvent.id, "pow-pending")
                                    val rootId = com.wisp.app.nostr.Nip10.getRootId(replyToEvent)
                                    if (rootId != null && rootId != replyToEvent.id) {
                                        feedViewModel.eventRepo.addReplyCount(rootId, "pow-pending")
                                    }
                                }
                            )
                        } else {
                            val event = signer.signEvent(kind = 1, content = content, tags = tags)
                            val msg = com.wisp.app.nostr.ClientMessage.event(event)
                            if (feedViewModel.outboxRouter != null) {
                                feedViewModel.outboxRouter!!.publishToInbox(msg, replyToEvent.pubkey)
                            } else {
                                feedViewModel.relayPool.sendToWriteRelays(msg)
                            }
                            feedViewModel.eventRepo.cacheEvent(event)
                            feedViewModel.eventRepo.addReplyCount(replyToEvent.id, event.id)
                            val rootId = com.wisp.app.nostr.Nip10.getRootId(replyToEvent)
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
                    navController.navigateSafe(Routes.COMPOSE)
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
                    navController.navigateSafe(Routes.COMPOSE)
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
                        navController.navigateSafe("dm/group/${conversationKey.replace(",", "~")}")
                    } else {
                        navController.navigateSafe("dm/$conversationKey")
                    }
                },
                onPayInvoice = { bolt11 -> feedViewModel.payInvoice(bolt11) },
                onGroupRoom = { relayUrl, groupId ->
                    val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                    navController.navigateSafe("group_room/$encodedRelay/${android.net.Uri.encode(groupId)}")
                },
                onGroupNotificationClick = { groupChatId, messageId ->
                    val relayUrl = feedViewModel.groupRepo.getRelayForGroup(groupChatId)
                    if (relayUrl != null) {
                        val encodedRelay = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relayUrl.toByteArray())
                        navController.navigateSafe("group_room/$encodedRelay/${android.net.Uri.encode(groupChatId)}?scrollTo=${android.net.Uri.encode(messageId)}")
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
                    val ref = com.wisp.app.nostr.Nip30.buildSetReference(pk, dTag)
                    feedViewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
                }
            )

            if (showNotifEmojiLibrary) {
                com.wisp.app.ui.component.EmojiLibrarySheet(
                    currentEmojis = notifUnicodeEmojis,
                    onAddEmojis = { emojis ->
                        emojis.forEach { feedViewModel.customEmojiRepo.addUnicodeEmoji(it) }
                        if (emojis.isNotEmpty()) pendingEmojiReactCallback?.invoke(emojis.first())
                        pendingEmojiReactCallback = null
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

    BroadcastStatusBar(
        broadcastState = broadcastState,
        powStatus = powStatus,
        onCancelMining = { feedViewModel.powManager.cancel() },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )
    } // Box

    } // Scaffold
}
