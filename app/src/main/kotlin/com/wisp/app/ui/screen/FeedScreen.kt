package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RelayIcon
import com.wisp.app.ui.component.WispDrawerContent
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.relay.ScoredRelay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.wisp.app.viewmodel.FeedType
import com.wisp.app.viewmodel.FeedViewModel
import com.wisp.app.viewmodel.InitLoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onCompose: () -> Unit,
    onReply: (NostrEvent) -> Unit,
    onRelays: () -> Unit,
    onProfileEdit: () -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onDms: () -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (NostrEvent) -> Unit = {},
    onQuote: (NostrEvent) -> Unit = {},
    onNoteClick: (NostrEvent) -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onSearch: () -> Unit = {},
    onLogout: () -> Unit = {},
    onMediaServers: () -> Unit = {},
    onWallet: () -> Unit = {},
    onLists: () -> Unit = {},
    onSafety: () -> Unit = {},
    onConsole: () -> Unit = {},
    onKeys: () -> Unit = {},
    onAddToList: (String) -> Unit = {},
    onRelayDetail: (String) -> Unit = {},
    scrollToTopTrigger: Int = 0
) {
    val feed by viewModel.feed.collectAsState()
    val feedType by viewModel.feedType.collectAsState()
    val selectedRelay by viewModel.selectedRelay.collectAsState()
    val replyCountVersion by viewModel.eventRepo.replyCountVersion.collectAsState()
    val zapVersion by viewModel.eventRepo.zapVersion.collectAsState()
    val reactionVersion by viewModel.eventRepo.reactionVersion.collectAsState()
    val repostVersion by viewModel.eventRepo.repostVersion.collectAsState()
    val relaySourceVersion by viewModel.eventRepo.relaySourceVersion.collectAsState()
    val profileVersion by viewModel.eventRepo.profileVersion.collectAsState()
    val nip05Version by viewModel.nip05Repo.version.collectAsState()
    val connectedCount by viewModel.relayPool.connectedCount.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }
    val userPubkey = viewModel.getUserPubkey()
    val selectedList by viewModel.selectedList.collectAsState()
    val ownLists by viewModel.listRepo.ownLists.collectAsState()
    var showRelayPicker by remember { mutableStateOf(false) }
    var showListPicker by remember { mutableStateOf(false) }
    var showRelayDropdown by remember { mutableStateOf(false) }
    var showFeedTypeDropdown by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val userProfile = profileVersion.let { userPubkey?.let { viewModel.eventRepo.getProfileData(it) } }

    val newNoteCount by viewModel.newNoteCount.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val initLoadingState by viewModel.initLoadingState.collectAsState()
    val zapInProgress by viewModel.zapInProgress.collectAsState()
    val listedIds by viewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
    val pinnedIds by viewModel.pinRepo.pinnedIds.collectAsState()

    var zapTargetEvent by remember { mutableStateOf<NostrEvent?>(null) }
    var zapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
    var zapErrorMessage by remember { mutableStateOf<String?>(null) }

    val isWalletConnected = viewModel.nwcRepo.hasConnection()

    // Re-establish subscriptions when app returns from background.
    // Use the Activity lifecycle (not NavBackStackEntry) so tab navigation
    // doesn't trigger unnecessary re-subscriptions that duplicate counts.
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(activity) {
        var pausedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pausedAt = System.currentTimeMillis()
            } else if (event == Lifecycle.Event.ON_RESUME && pausedAt > 0L) {
                val pausedMs = System.currentTimeMillis() - pausedAt
                pausedAt = 0L
                viewModel.onAppResume(pausedMs)
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Collect zap success events for animation
    LaunchedEffect(Unit) {
        viewModel.zapSuccess.collect { eventId ->
            zapAnimatingIds = zapAnimatingIds + eventId
            delay(1500)
            zapAnimatingIds = zapAnimatingIds - eventId
        }
    }

    // Collect zap errors
    LaunchedEffect(Unit) {
        viewModel.zapError.collect { error ->
            zapErrorMessage = error
        }
    }

    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    var isScrollingUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                isScrollingUp = index < previousIndex || (index == previousIndex && offset < previousOffset)
                previousIndex = index
                previousOffset = offset
            }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= feed.size - 5 && feed.isNotEmpty()
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    LaunchedEffect(isAtTop) {
        if (isAtTop) viewModel.resetNewNoteCount()
    }


    if (showRelayPicker) {
        RelayPickerDialog(
            scoredRelays = viewModel.getScoredRelays(),
            onSelect = { url ->
                viewModel.setSelectedRelay(url)
                viewModel.setFeedType(FeedType.RELAY)
                showRelayPicker = false
            },
            onProbe = { domain -> viewModel.probeRelay(domain) },
            onDismiss = { showRelayPicker = false }
        )
    }

    if (showListPicker) {
        ListPickerDialog(
            lists = ownLists,
            selectedList = selectedList,
            onSelect = { list ->
                viewModel.setSelectedList(list)
                viewModel.setFeedType(FeedType.LIST)
                showListPicker = false
            },
            onCreate = { name ->
                viewModel.createList(name)
            },
            onDismiss = { showListPicker = false }
        )
    }

    if (zapTargetEvent != null) {
        ZapDialog(
            isWalletConnected = isWalletConnected,
            onDismiss = { zapTargetEvent = null },
            onZap = { amountMsats, message ->
                val event = zapTargetEvent ?: return@ZapDialog
                zapTargetEvent = null
                viewModel.sendZap(event, amountMsats, message)
            },
            onGoToWallet = onWallet
        )
    }

    if (zapErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { zapErrorMessage = null },
            title = { Text("Zap Failed") },
            text = { Text(zapErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { zapErrorMessage = null }) { Text("OK") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            WispDrawerContent(
                profile = userProfile,
                pubkey = userPubkey,
                onProfile = {
                    scope.launch { drawerState.close() }
                    onProfileEdit()
                },
                onFeed = {
                    scope.launch { drawerState.close() }
                },
                onSearch = {
                    scope.launch { drawerState.close() }
                    onSearch()
                },
                onMessages = {
                    scope.launch { drawerState.close() }
                    onDms()
                },
                onWallet = {
                    scope.launch { drawerState.close() }
                    onWallet()
                },
                onLists = {
                    scope.launch { drawerState.close() }
                    onLists()
                },
                onMediaServers = {
                    scope.launch { drawerState.close() }
                    onMediaServers()
                },
                onSafety = {
                    scope.launch { drawerState.close() }
                    onSafety()
                },
                onKeys = {
                    scope.launch { drawerState.close() }
                    onKeys()
                },
                onConsole = {
                    scope.launch { drawerState.close() }
                    onConsole()
                },
                onRelaySettings = {
                    scope.launch { drawerState.close() }
                    onRelays()
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            Box {
                                Surface(
                                    onClick = { showFeedTypeDropdown = true },
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val feedLabel = when (feedType) {
                                            FeedType.FOLLOWS -> "Follows"
                                            FeedType.EXTENDED_FOLLOWS -> "Extended"
                                            FeedType.RELAY -> if (selectedRelay != null) {
                                                selectedRelay!!.removePrefix("wss://").removeSuffix("/")
                                            } else "Relay"
                                            FeedType.LIST -> if (selectedList != null) {
                                                selectedList!!.name
                                            } else "List"
                                        }
                                        Text(
                                            feedLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 160.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "Change feed",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showFeedTypeDropdown,
                                    onDismissRequest = { showFeedTypeDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Follows") },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            viewModel.setFeedType(FeedType.FOLLOWS)
                                        },
                                        trailingIcon = if (feedType == FeedType.FOLLOWS) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Extended") },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            viewModel.setFeedType(FeedType.EXTENDED_FOLLOWS)
                                        },
                                        trailingIcon = if (feedType == FeedType.EXTENDED_FOLLOWS) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Relay") },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            showRelayPicker = true
                                        },
                                        trailingIcon = if (feedType == FeedType.RELAY) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text("List") },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            showListPicker = true
                                        },
                                        trailingIcon = if (feedType == FeedType.LIST) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                }
                            }
                            } // Row
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            ProfilePicture(url = userProfile?.picture, size = 32)
                        }
                    },
                    actions = {
                        Box {
                            Surface(
                                onClick = { showRelayDropdown = true },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.size(8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.foundation.Canvas(
                                            modifier = Modifier.size(8.dp)
                                        ) {
                                            drawCircle(
                                                color = if (connectedCount > 0)
                                                    androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                                else
                                                    androidx.compose.ui.graphics.Color(0xFFFF5252)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$connectedCount",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showRelayDropdown,
                                onDismissRequest = { showRelayDropdown = false }
                            ) {
                                val connectedUrls = viewModel.relayPool.getAllConnectedUrls()
                                if (connectedUrls.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "No relays connected",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {}
                                    )
                                } else {
                                    val coverageCounts = viewModel.getRelayCoverageCounts()
                                    connectedUrls.forEach { url ->
                                        val count = coverageCounts[url]
                                        val label = buildString {
                                            append(url.removePrefix("wss://").removeSuffix("/"))
                                            if (count != null && count > 0) append(" ($count)")
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            onClick = {
                                                showRelayDropdown = false
                                                viewModel.setSelectedRelay(url)
                                                viewModel.setFeedType(FeedType.RELAY)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCompose,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New post")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            // Relay feed header bar
            if (feedType == FeedType.RELAY && selectedRelay != null) {
                RelayFeedBar(
                    relayUrl = selectedRelay!!,
                    relayInfoRepo = viewModel.relayInfoRepo,
                    onViewDetails = { onRelayDetail(selectedRelay!!) }
                )
            }
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refreshFeed() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (feed.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            feedType == FeedType.FOLLOWS && viewModel.contactRepo.getFollowList().isEmpty() -> {
                                Text(
                                    "Follow some people to see their posts here",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            feedType == FeedType.EXTENDED_FOLLOWS && viewModel.extendedNetworkRepo.cachedNetwork.value == null -> {
                                Text(
                                    "Tap Extended in the feed menu to discover your network",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            feedType == FeedType.LIST && selectedList == null -> {
                                Text(
                                    "Select a list to see posts",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            feedType == FeedType.LIST && selectedList != null && selectedList!!.members.isEmpty() -> {
                                Text(
                                    "This list is empty",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            initLoadingState != InitLoadingState.Done && initLoadingState != InitLoadingState.Idle -> {
                                InitLoadingOverlay(initLoadingState)
                            }
                            else -> {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items = feed, key = { it.id }) { event ->
                                FeedItem(
                                    event = event,
                                    viewModel = viewModel,
                                    userPubkey = userPubkey,
                                    profileVersion = profileVersion,
                                    reactionVersion = reactionVersion,
                                    replyCountVersion = replyCountVersion,
                                    zapVersion = zapVersion,
                                    repostVersion = repostVersion,
                                    relaySourceVersion = relaySourceVersion,
                                    nip05Version = nip05Version,
                                    isZapAnimating = event.id in zapAnimatingIds,
                                    isZapInProgress = event.id in zapInProgress,
                                    isInList = event.id in listedIds,
                                    isPinned = event.id in pinnedIds,
                                    onReply = { onReply(event) },
                                    onProfileClick = { onProfileClick(event.pubkey) },
                                    onNavigateToProfile = onProfileClick,
                                    onNoteClick = { onNoteClick(event) },
                                    onQuotedNoteClick = onQuotedNoteClick,
                                    onReact = { emoji -> onReact(event, emoji) },
                                    onRepost = { onRepost(event) },
                                    onQuote = { onQuote(event) },
                                    onZap = { zapTargetEvent = event },
                                    onAddToList = { onAddToList(event.id) },
                                    onPin = { viewModel.togglePin(event.id) },
                                    onRelayClick = { url ->
                                        viewModel.setSelectedRelay(url)
                                        viewModel.setFeedType(FeedType.RELAY)
                                    }
                                )
                            }
                        }

                        NewNotesButton(
                            visible = newNoteCount > 0 && !isAtTop,
                            count = newNoteCount,
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                    viewModel.resetNewNoteCount()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        )

                        ScrollToTopButton(
                            visible = !isAtTop && isScrollingUp && newNoteCount == 0,
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        )
                    }
                }
            }
            } // Column
        }
    }
}

/**
 * Extracted per-item composable so that version-keyed `remember` blocks
 * prevent recomputing data for items whose values haven't actually changed.
 */
@Composable
private fun FeedItem(
    event: NostrEvent,
    viewModel: FeedViewModel,
    userPubkey: String?,
    profileVersion: Int,
    reactionVersion: Int,
    replyCountVersion: Int,
    zapVersion: Int,
    repostVersion: Int = 0,
    relaySourceVersion: Int,
    nip05Version: Int = 0,
    isZapAnimating: Boolean,
    isZapInProgress: Boolean = false,
    isInList: Boolean = false,
    isPinned: Boolean = false,
    onReply: () -> Unit,
    onProfileClick: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNoteClick: () -> Unit,
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReact: (String) -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onZap: () -> Unit,
    onAddToList: () -> Unit = {},
    onPin: () -> Unit = {},
    onRelayClick: (String) -> Unit = {}
) {
    val profileData = remember(profileVersion, event.pubkey) {
        viewModel.eventRepo.getProfileData(event.pubkey)
    }
    val likeCount = remember(reactionVersion, event.id) {
        viewModel.eventRepo.getReactionCount(event.id)
    }
    val replyCount = remember(replyCountVersion, event.id) {
        viewModel.eventRepo.getReplyCount(event.id)
    }
    val zapSats = remember(zapVersion, event.id) {
        viewModel.eventRepo.getZapSats(event.id)
    }
    val userEmojis = remember(reactionVersion, event.id, userPubkey) {
        userPubkey?.let { viewModel.eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
    }
    val relayIcons = remember(relaySourceVersion, event.id) {
        viewModel.eventRepo.getEventRelays(event.id).map { url ->
            url to viewModel.relayInfoRepo.getIconUrl(url)
        }
    }
    val repostAuthorPubkey = remember(event.id) {
        viewModel.eventRepo.getRepostAuthor(event.id)
    }
    val repostedByName = remember(repostAuthorPubkey, profileVersion) {
        repostAuthorPubkey?.let { pk ->
            viewModel.eventRepo.getProfileData(pk)?.displayString
                ?: pk.take(8) + "..."
        }
    }
    val reactionDetails = remember(reactionVersion, event.id) {
        viewModel.eventRepo.getReactionDetails(event.id)
    }
    val zapDetails = remember(zapVersion, event.id) {
        viewModel.eventRepo.getZapDetails(event.id)
    }
    val repostCount = remember(repostVersion, event.id) {
        viewModel.eventRepo.getRepostCount(event.id)
    }
    val hasUserReposted = remember(repostVersion, event.id) {
        viewModel.eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(zapVersion, event.id) {
        viewModel.eventRepo.hasUserZapped(event.id)
    }
    val isFollowing = remember(event.pubkey) {
        viewModel.contactRepo.isFollowing(event.pubkey)
    }
    PostCard(
        event = event,
        profile = profileData,
        onReply = onReply,
        onProfileClick = onProfileClick,
        onNavigateToProfile = onNavigateToProfile,
        onNoteClick = onNoteClick,
        onReact = onReact,
        userReactionEmojis = userEmojis,
        onRepost = onRepost,
        onQuote = onQuote,
        hasUserReposted = hasUserReposted,
        repostCount = repostCount,
        onZap = onZap,
        hasUserZapped = hasUserZapped,
        likeCount = likeCount,
        replyCount = replyCount,
        zapSats = zapSats,
        isZapAnimating = isZapAnimating,
        isZapInProgress = isZapInProgress,
        eventRepo = viewModel.eventRepo,
        relayIcons = relayIcons,
        repostedBy = repostedByName,
        reactionDetails = reactionDetails,
        zapDetails = zapDetails,
        onNavigateToProfileFromDetails = onNavigateToProfile,
        onRelayClick = onRelayClick,
        onFollowAuthor = { viewModel.toggleFollow(event.pubkey) },
        onBlockAuthor = { viewModel.blockUser(event.pubkey) },
        isFollowingAuthor = isFollowing,
        isOwnEvent = event.pubkey == userPubkey,
        nip05Repo = viewModel.nip05Repo,
        onAddToList = onAddToList,
        isInList = isInList,
        onPin = onPin,
        isPinned = isPinned,
        onQuotedNoteClick = onQuotedNoteClick
    )
}

@Composable
private fun RelayPickerDialog(
    scoredRelays: List<ScoredRelay>,
    onSelect: (String) -> Unit,
    onProbe: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var isProbing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Relay") },
        text = {
            Column {
                // URL bar
                androidx.compose.material3.OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        probeError = null
                    },
                    placeholder = { Text("relay.example.com") },
                    singleLine = true,
                    enabled = !isProbing,
                    trailingIcon = {
                        if (isProbing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = {
                                    val domain = urlInput.trim()
                                        .removePrefix("wss://")
                                        .removePrefix("ws://")
                                        .removeSuffix("/")
                                    if (domain.isNotBlank()) {
                                        isProbing = true
                                        probeError = null
                                        scope.launch {
                                            val result = onProbe(domain)
                                            isProbing = false
                                            if (result != null) {
                                                onSelect(result)
                                            } else {
                                                probeError = "Couldn't connect to relay"
                                            }
                                        }
                                    }
                                },
                                enabled = urlInput.isNotBlank()
                            ) {
                                Text("Go", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (probeError != null) {
                    Text(
                        probeError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.size(12.dp))

                // Scored relay list
                if (scoredRelays.isEmpty()) {
                    Text(
                        "No relay scores available yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(scoredRelays) { scored ->
                            Surface(
                                onClick = { onSelect(scored.url) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        scored.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "covers ${scored.coverCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ListPickerDialog(
    lists: List<FollowSet>,
    selectedList: FollowSet?,
    onSelect: (FollowSet) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newListName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select List") },
        text = {
            Column {
                if (lists.isEmpty()) {
                    Text(
                        "No lists yet. Create one below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    lists.forEach { list ->
                        Surface(
                            onClick = { onSelect(list) },
                            color = if (selectedList?.dTag == list.dTag)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    list.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${list.members.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        placeholder = { Text("New list name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (newListName.isNotBlank()) {
                                onCreate(newListName.trim())
                                newListName = ""
                            }
                        },
                        enabled = newListName.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Back to top",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun NewNotesButton(
    visible: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "$count new notes",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun InitLoadingOverlay(state: InitLoadingState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Text(
            text = "Setting Up Your Feed",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(24.dp))

        val progress = initLoadingProgress(state)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = initLoadingText(state),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "init-status"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun initLoadingProgress(state: InitLoadingState): Float {
    return when (state) {
        is InitLoadingState.Idle -> 0f
        is InitLoadingState.Connecting -> {
            val frac = if (state.total > 0) state.connected.toFloat() / state.total else 0f
            frac * 0.08f
        }
        is InitLoadingState.FetchingSelfData -> 0.10f
        is InitLoadingState.FoundFollows -> 0.12f
        is InitLoadingState.FetchingRelayLists -> {
            val frac = if (state.total > 0) state.found.toFloat() / state.total else 0f
            0.12f + frac * 0.28f
        }
        is InitLoadingState.ComputingRouting -> 0.45f
        is InitLoadingState.DiscoveringNetwork -> {
            val frac = if (state.total > 0) state.fetched.toFloat() / state.total else 0f
            0.45f + frac * 0.30f
        }
        is InitLoadingState.ExpandingRelays -> 0.80f
        is InitLoadingState.Subscribing -> 0.90f
        is InitLoadingState.Done -> 1f
    }
}

private fun initLoadingText(state: InitLoadingState): String {
    return when (state) {
        is InitLoadingState.Idle -> "Preparing..."
        is InitLoadingState.Connecting -> "Connecting to relays... ${state.connected}/${state.total}"
        is InitLoadingState.FetchingSelfData -> "Searching for you on the network..."
        is InitLoadingState.FoundFollows -> "Found your ${state.count} follows"
        is InitLoadingState.FetchingRelayLists -> "Fetching relay lists... ${state.found}/${state.total}"
        is InitLoadingState.ComputingRouting -> "Computed routing across ${state.relayCount} relays for ${state.coveredAuthors} authors"
        is InitLoadingState.DiscoveringNetwork -> "Discovering extended network... ${state.fetched}/${state.total}"
        is InitLoadingState.ExpandingRelays -> "Connecting to ${state.relayCount} extended relays..."
        is InitLoadingState.Subscribing -> "Subscribing to feed..."
        is InitLoadingState.Done -> "Done!"
    }
}

@Composable
private fun RelayFeedBar(
    relayUrl: String,
    relayInfoRepo: RelayInfoRepository,
    onViewDetails: () -> Unit
) {
    val info = remember(relayUrl) { relayInfoRepo.getInfo(relayUrl) }
    val iconUrl = remember(relayUrl) { relayInfoRepo.getIconUrl(relayUrl) }
    val domain = remember(relayUrl) {
        relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }

    Surface(
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RelayIcon(
                    iconUrl = iconUrl,
                    relayUrl = relayUrl,
                    size = 28.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info?.name ?: domain,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (info?.name != null) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Surface(
                    onClick = onViewDetails,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Details",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}
