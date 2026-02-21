package com.wisp.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.WispBottomBar
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.ui.screen.BlossomServersScreen
import com.wisp.app.ui.screen.AuthScreen
import com.wisp.app.ui.screen.ComposeScreen
import com.wisp.app.ui.screen.DmConversationScreen
import com.wisp.app.ui.screen.DmListScreen
import com.wisp.app.ui.screen.FeedScreen
import com.wisp.app.ui.screen.ProfileEditScreen
import com.wisp.app.ui.screen.RelayScreen
import com.wisp.app.ui.screen.ThreadScreen
import com.wisp.app.ui.screen.NotificationsScreen
import com.wisp.app.ui.screen.SafetyScreen
import com.wisp.app.ui.screen.UserProfileScreen
import com.wisp.app.ui.screen.ConsoleScreen
import com.wisp.app.ui.screen.SearchScreen
import com.wisp.app.ui.screen.BookmarkSetScreen
import com.wisp.app.ui.screen.KeysScreen
import com.wisp.app.ui.screen.ListScreen
import com.wisp.app.ui.screen.ListsHubScreen
import com.wisp.app.ui.screen.OnboardingScreen
import com.wisp.app.ui.component.AddNoteToListDialog
import com.wisp.app.ui.screen.OnboardingSuggestionsScreen
import com.wisp.app.ui.screen.RelayDetailScreen
import com.wisp.app.ui.screen.WalletScreen
import com.wisp.app.viewmodel.BlossomServersViewModel
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
import com.wisp.app.viewmodel.NotificationsViewModel
import com.wisp.app.viewmodel.ConsoleViewModel
import com.wisp.app.viewmodel.SearchViewModel
import com.wisp.app.viewmodel.OnboardingViewModel
import com.wisp.app.viewmodel.WalletViewModel

object Routes {
    const val AUTH = "auth"
    const val FEED = "feed"
    const val COMPOSE = "compose"
    const val RELAYS = "relays"
    const val PROFILE_EDIT = "profile/edit"
    const val USER_PROFILE = "profile/{pubkey}"
    const val THREAD = "thread/{eventId}"
    const val DM_LIST = "dms"
    const val DM_CONVERSATION = "dm/{pubkey}"
    const val NOTIFICATIONS = "notifications"
    const val BLOSSOM_SERVERS = "blossom_servers"
    const val WALLET = "wallet"
    const val SAFETY = "safety"
    const val SEARCH = "search"
    const val CONSOLE = "console"
    const val KEYS = "keys"
    const val LIST_DETAIL = "list/{pubkey}/{dTag}"
    const val LISTS_HUB = "lists"
    const val BOOKMARK_SET_DETAIL = "bookmark_set/{pubkey}/{dTag}"
    const val ONBOARDING_PROFILE = "onboarding/profile"
    const val ONBOARDING_SUGGESTIONS = "onboarding/suggestions"
    const val RELAY_DETAIL = "relay_detail/{relayUrl}"
}

@Composable
fun WispNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val feedViewModel: FeedViewModel = viewModel()
    val composeViewModel: ComposeViewModel = viewModel()
    val relayViewModel: RelayViewModel = viewModel()
    val profileViewModel: ProfileViewModel = viewModel()
    val dmListViewModel: DmListViewModel = viewModel()
    val blossomServersViewModel: BlossomServersViewModel = viewModel()
    val walletViewModel: WalletViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return WalletViewModel(feedViewModel.nwcRepo) as T
            }
        }
    )
    val notificationsViewModel: NotificationsViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()
    val consoleViewModel: ConsoleViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()

    relayViewModel.relayPool = feedViewModel.relayPool

    var replyTarget by remember { mutableStateOf<NostrEvent?>(null) }
    var quoteTarget by remember { mutableStateOf<NostrEvent?>(null) }
    var scrollToTopTrigger by remember { mutableStateOf(0) }
    var addToListEventId by remember { mutableStateOf<String?>(null) }

    val startDestination = when {
        !authViewModel.isLoggedIn -> Routes.AUTH
        !authViewModel.keyRepo.isOnboardingComplete() -> Routes.ONBOARDING_PROFILE
        else -> Routes.FEED
    }

    // Initialize relays and NWC when logged in and onboarding is complete
    if (authViewModel.isLoggedIn && startDestination == Routes.FEED) {
        LaunchedEffect(Unit) {
            feedViewModel.initRelays()
        }
    }

    // Initialize DM list viewmodel with shared repo
    LaunchedEffect(Unit) {
        dmListViewModel.init(feedViewModel.dmRepo, feedViewModel.muteRepo)
    }

    // Initialize notifications viewmodel with shared repos
    LaunchedEffect(Unit) {
        notificationsViewModel.init(feedViewModel.notifRepo, feedViewModel.eventRepo, feedViewModel.contactRepo)
    }

    // Process incoming gift wraps for DMs
    LaunchedEffect(Unit) {
        feedViewModel.relayPool.relayEvents.collect { relayEvent ->
            if (relayEvent.event.kind == 1059) {
                dmListViewModel.processGiftWrap(relayEvent.event, relayEvent.relayUrl)
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val hideBottomBarRoutes = setOf(Routes.AUTH, Routes.ONBOARDING_PROFILE, Routes.ONBOARDING_SUGGESTIONS)
    val showBottomBar by remember(currentRoute) {
        derivedStateOf { currentRoute != null && currentRoute !in hideBottomBarRoutes }
    }

    val newNoteCount by feedViewModel.newNoteCount.collectAsState()
    val hasUnreadDms by dmListViewModel.hasUnreadDms.collectAsState()
    val hasUnreadNotifications by notificationsViewModel.hasUnread.collectAsState()

    // Add Note to List dialog — shared across all screens
    if (addToListEventId != null) {
        val ownSets by feedViewModel.bookmarkSetRepo.ownSets.collectAsState()
        AddNoteToListDialog(
            eventId = addToListEventId!!,
            bookmarkSets = ownSets,
            onAddToSet = { dTag, eventId -> feedViewModel.addNoteToBookmarkSet(dTag, eventId) },
            onRemoveFromSet = { dTag, eventId -> feedViewModel.removeNoteFromBookmarkSet(dTag, eventId) },
            onCreateSet = { name -> feedViewModel.createBookmarkSet(name) },
            onDismiss = { addToListEventId = null }
        )
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
                    onTabSelected = { tab ->
                        scrollToTopTrigger++
                        navController.navigate(tab.route) {
                            popUpTo(Routes.FEED) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding)
    ) {
        composable(Routes.AUTH) {
            AuthScreen(
                viewModel = authViewModel,
                onAuthenticated = { isNewAccount ->
                    if (isNewAccount) {
                        navController.navigate(Routes.ONBOARDING_PROFILE) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    } else {
                        feedViewModel.reloadForNewAccount()
                        feedViewModel.initRelays()
                        walletViewModel.refreshState()
                        authViewModel.keyRepo.markOnboardingComplete()
                        navController.navigate(Routes.FEED) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.FEED) {
            FeedScreen(
                viewModel = feedViewModel,
                scrollToTopTrigger = scrollToTopTrigger,
                onCompose = {
                    replyTarget = null
                    quoteTarget = null
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
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
                onRelays = {
                    navController.navigate(Routes.RELAYS)
                },
                onProfileEdit = {
                    val pubkey = feedViewModel.getUserPubkey()
                    if (pubkey != null) {
                        navController.navigate("profile/$pubkey")
                    }
                },
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onDms = {
                    navController.navigate(Routes.DM_LIST)
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
                onLogout = {
                    feedViewModel.resetForAccountSwitch()
                    walletViewModel.refreshState()
                    authViewModel.logOut()
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onMediaServers = {
                    navController.navigate(Routes.BLOSSOM_SERVERS)
                },
                onWallet = {
                    navController.navigate(Routes.WALLET)
                },
                onLists = {
                    navController.navigate(Routes.LISTS_HUB)
                },
                onSafety = {
                    navController.navigate(Routes.SAFETY)
                },
                onSearch = {
                    navController.navigate(Routes.SEARCH) {
                        popUpTo(Routes.FEED) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onConsole = {
                    navController.navigate(Routes.CONSOLE)
                },
                onKeys = {
                    navController.navigate(Routes.KEYS)
                },
                onAddToList = { eventId -> addToListEventId = eventId },
                onRelayDetail = { url ->
                    navController.navigate("relay_detail/${java.net.URLEncoder.encode(url, "UTF-8")}")
                }
            )
        }

        composable(Routes.COMPOSE) {
            ComposeScreen(
                viewModel = composeViewModel,
                relayPool = feedViewModel.relayPool,
                replyTo = replyTarget,
                quoteTo = quoteTarget,
                onBack = { navController.popBackStack() },
                outboxRouter = feedViewModel.outboxRouter
            )
        }

        composable(Routes.RELAYS) {
            RelayScreen(
                viewModel = relayViewModel,
                relayPool = feedViewModel.relayPool,
                onBack = {
                    feedViewModel.refreshRelays()
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.BLOSSOM_SERVERS) {
            BlossomServersScreen(
                viewModel = blossomServersViewModel,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() }
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
                onBack = { navController.popBackStack() }
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
                    subManager = feedViewModel.subManager
                )
            }
            val isBlockedState by feedViewModel.muteRepo.blockedPubkeys.collectAsState()
            val profileListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val profilePinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            val profileZapInProgress by feedViewModel.zapInProgress.collectAsState()
            UserProfileScreen(
                viewModel = userProfileViewModel,
                contactRepo = feedViewModel.contactRepo,
                relayPool = feedViewModel.relayPool,
                onBack = { navController.popBackStack() },
                onReply = { event ->
                    replyTarget = event
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
                onZap = { event, amountMsats, message -> feedViewModel.sendZap(event, amountMsats, message) },
                userPubkey = feedViewModel.getUserPubkey(),
                isWalletConnected = feedViewModel.nwcRepo.hasConnection(),
                onWallet = { navController.navigate(Routes.WALLET) },
                zapSuccess = feedViewModel.zapSuccess,
                zapError = feedViewModel.zapError,
                zapInProgressIds = profileZapInProgress,
                ownLists = feedViewModel.listRepo.ownLists.collectAsState().value,
                onAddToList = { dTag, pk -> feedViewModel.addToList(dTag, pk) },
                onRemoveFromList = { dTag, pk -> feedViewModel.removeFromList(dTag, pk) },
                onCreateList = { name -> feedViewModel.createList(name) },
                profilePubkey = pubkey,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                nip05Repo = feedViewModel.nip05Repo,
                listedIds = profileListedIds,
                pinnedIds = profilePinnedIds,
                onTogglePin = { eventId -> feedViewModel.togglePin(eventId) },
                onAddNoteToList = { eventId -> addToListEventId = eventId },
                onSendDm = if (!isOwnProfile) {{ navController.navigate("dm/$pubkey") }} else null
            )
        }

        composable(Routes.SEARCH) {
            val searchListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            SearchScreen(
                viewModel = searchViewModel,
                relayPool = feedViewModel.relayPool,
                eventRepo = feedViewModel.eventRepo,
                muteRepo = feedViewModel.muteRepo,
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onNoteClick = { event ->
                    navController.navigate("thread/${event.id}")
                },
                onQuotedNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onReply = { event ->
                    replyTarget = event
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji ->
                    feedViewModel.toggleReaction(event, emoji)
                },
                onListClick = { list ->
                    navController.navigate("list/${list.pubkey}/${list.dTag}")
                },
                onToggleFollow = { pubkey ->
                    feedViewModel.toggleFollow(pubkey)
                },
                onBlockUser = { pubkey ->
                    feedViewModel.blockUser(pubkey)
                },
                userPubkey = feedViewModel.getUserPubkey(),
                listedIds = searchListedIds,
                onAddToList = { eventId -> addToListEventId = eventId }
            )
        }

        composable(Routes.DM_LIST) {
            LaunchedEffect(Unit) {
                dmListViewModel.markDmsRead()
            }
            DmListScreen(
                viewModel = dmListViewModel,
                eventRepo = feedViewModel.eventRepo,
                onBack = null,
                onConversation = { pubkey ->
                    navController.navigate("dm/$pubkey")
                }
            )
        }

        composable(
            Routes.DM_CONVERSATION,
            arguments = listOf(navArgument("pubkey") { type = NavType.StringType })
        ) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            val dmConvoViewModel: DmConversationViewModel = viewModel()
            LaunchedEffect(pubkey) {
                dmConvoViewModel.init(pubkey, feedViewModel.dmRepo, feedViewModel.relayListRepo)
            }
            val peerProfile = feedViewModel.eventRepo.getProfileData(pubkey)
            val userPubkey = feedViewModel.getUserPubkey()
            DmConversationScreen(
                viewModel = dmConvoViewModel,
                relayPool = feedViewModel.relayPool,
                peerProfile = peerProfile,
                userPubkey = userPubkey,
                eventRepo = feedViewModel.eventRepo,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                onBack = { navController.popBackStack() }
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
                    subManager = feedViewModel.subManager,
                    queueProfileFetch = { pubkey -> feedViewModel.forceProfileFetch(pubkey) },
                    muteRepo = feedViewModel.muteRepo
                )
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { feedViewModel.resumeEngagement() }
            }
            var threadZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val threadZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var threadZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val isNwcConnected = feedViewModel.nwcRepo.hasConnection()

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    threadZapAnimatingIds = threadZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    threadZapAnimatingIds = threadZapAnimatingIds - eventId
                }
            }

            if (threadZapTarget != null) {
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { threadZapTarget = null },
                    onZap = { amountMsats, message ->
                        val event = threadZapTarget ?: return@ZapDialog
                        threadZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) }
                )
            }
            val threadListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val threadPinnedIds by feedViewModel.pinRepo.pinnedIds.collectAsState()
            ThreadScreen(
                viewModel = threadViewModel,
                eventRepo = feedViewModel.eventRepo,
                contactRepo = feedViewModel.contactRepo,
                nip05Repo = feedViewModel.nip05Repo,
                userPubkey = feedViewModel.getUserPubkey(),
                onBack = { navController.popBackStack() },
                onReply = { event ->
                    replyTarget = event
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
                onAddToList = { eventId -> addToListEventId = eventId }
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

        composable(Routes.CONSOLE) {
            consoleViewModel.init(feedViewModel.relayPool)
            ConsoleScreen(
                viewModel = consoleViewModel,
                onBack = { navController.popBackStack() }
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
            RelayDetailScreen(
                relayUrl = relayUrl,
                relayInfoRepo = feedViewModel.relayInfoRepo,
                healthTracker = feedViewModel.healthTracker,
                consoleEntries = consoleLog,
                operatorProfile = operatorProfile,
                onBack = { navController.popBackStack() },
                onOperatorClick = if (operatorPubkey != null) {{ navController.navigate("profile/$operatorPubkey") }} else null
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
                eventRepo = feedViewModel.eventRepo,
                onBack = { navController.popBackStack() },
                onListDetail = { list ->
                    navController.navigate("list/${list.pubkey}/${list.dTag}")
                },
                onBookmarkSetDetail = { set ->
                    navController.navigate("bookmark_set/${set.pubkey}/${set.dTag}")
                },
                onCreateList = { name -> feedViewModel.createList(name) },
                onCreateBookmarkSet = { name -> feedViewModel.createBookmarkSet(name) },
                onDeleteList = { dTag -> feedViewModel.deleteList(dTag) },
                onDeleteBookmarkSet = { dTag -> feedViewModel.deleteBookmarkSet(dTag) }
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
                    composeViewModel.clear()
                    navController.navigate(Routes.COMPOSE)
                },
                onReact = { event, emoji -> feedViewModel.toggleReaction(event, emoji) },
                onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                onRemoveFromSet = if (isOwnList) { eventId ->
                    feedViewModel.removeNoteFromBookmarkSet(dTag, eventId)
                } else null,
                onToggleFollow = { pubkey -> feedViewModel.toggleFollow(pubkey) },
                onBlockUser = { pubkey -> feedViewModel.blockUser(pubkey) }
            )
        }

        composable(Routes.ONBOARDING_PROFILE) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onContinue = {
                    if (onboardingViewModel.finishProfile(feedViewModel.relayPool)) {
                        navController.navigate(Routes.ONBOARDING_SUGGESTIONS) {
                            popUpTo(Routes.ONBOARDING_PROFILE) { inclusive = true }
                        }
                    }
                }
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

            OnboardingSuggestionsScreen(
                activeNow = activeNow,
                creators = creators,
                news = news,
                selectedPubkeys = selectedPubkeys,
                onToggleFollowAll = { section -> onboardingViewModel.toggleFollowAll(section) },
                onTogglePubkey = { pubkey -> onboardingViewModel.togglePubkey(pubkey) },
                totalSelected = selectedPubkeys.size,
                onContinue = {
                    onboardingViewModel.finishOnboarding(
                        relayPool = feedViewModel.relayPool,
                        contactRepo = feedViewModel.contactRepo,
                        selectedPubkeys = selectedPubkeys
                    )
                    feedViewModel.reloadForNewAccount()
                    feedViewModel.initRelays()
                    walletViewModel.refreshState()
                    navController.navigate(Routes.FEED) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.NOTIFICATIONS) {
            LaunchedEffect(Unit) {
                notificationsViewModel.markRead()
            }
            var notifZapTarget by remember { mutableStateOf<NostrEvent?>(null) }
            val notifZapInProgress by feedViewModel.zapInProgress.collectAsState()
            var notifZapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
            val notifListedIds by feedViewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
            val isNwcConnected = feedViewModel.nwcRepo.hasConnection()

            LaunchedEffect(Unit) {
                feedViewModel.zapSuccess.collect { eventId ->
                    notifZapAnimatingIds = notifZapAnimatingIds + eventId
                    kotlinx.coroutines.delay(1500)
                    notifZapAnimatingIds = notifZapAnimatingIds - eventId
                }
            }

            if (notifZapTarget != null) {
                ZapDialog(
                    isWalletConnected = isNwcConnected,
                    onDismiss = { notifZapTarget = null },
                    onZap = { amountMsats, message ->
                        val event = notifZapTarget ?: return@ZapDialog
                        notifZapTarget = null
                        feedViewModel.sendZap(event, amountMsats, message)
                    },
                    onGoToWallet = { navController.navigate(Routes.WALLET) }
                )
            }

            NotificationsScreen(
                viewModel = notificationsViewModel,
                scrollToTopTrigger = scrollToTopTrigger,
                userPubkey = feedViewModel.getUserPubkey(),
                onNoteClick = { eventId ->
                    navController.navigate("thread/$eventId")
                },
                onProfileClick = { pubkey ->
                    navController.navigate("profile/$pubkey")
                },
                onReply = { event ->
                    replyTarget = event
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
                onAddToList = { eventId -> addToListEventId = eventId },
                nip05Repo = feedViewModel.nip05Repo,
                isZapAnimating = { it in notifZapAnimatingIds },
                isZapInProgress = { it in notifZapInProgress },
                isInList = { it in notifListedIds }
            )
        }
    }

    } // Scaffold
}
