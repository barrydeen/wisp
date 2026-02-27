package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RelayIcon
import com.wisp.app.ui.component.WispDrawerContent
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.relay.ScoredRelay
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import com.wisp.app.viewmodel.FeedType
import com.wisp.app.viewmodel.FeedViewModel
import com.wisp.app.viewmodel.InitLoadingState
import com.wisp.app.viewmodel.RelayFeedStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isTorEnabled: Boolean = false,
    torStatus: com.wisp.app.relay.TorStatus = com.wisp.app.relay.TorStatus.DISABLED,
    onToggleTor: (Boolean) -> Unit = {},
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
    onDrafts: () -> Unit = {},
    onSafety: () -> Unit = {},
    onCustomEmojis: () -> Unit = {},
    onConsole: () -> Unit = {},
    onKeys: () -> Unit = {},
    onAddToList: (String) -> Unit = {},
    onRelayDetail: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null,
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
    val followList by viewModel.contactRepo.followList.collectAsState()
    val profileVersion by viewModel.eventRepo.profileVersion.collectAsState()
    val nip05Version by viewModel.nip05Repo.version.collectAsState()
    val connectedCount by viewModel.relayPool.connectedCount.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.scrollToItem(0)
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
    val initLoadingState by viewModel.initLoadingState.collectAsState()
    val relayFeedStatus by viewModel.relayFeedStatus.collectAsState()
    val zapInProgress by viewModel.zapInProgress.collectAsState()
    val listedIds by viewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
    val pinnedIds by viewModel.pinRepo.pinnedIds.collectAsState()

    var zapTargetEvent by remember { mutableStateOf<NostrEvent?>(null) }
    var zapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
    var zapErrorMessage by remember { mutableStateOf<String?>(null) }

    val isWalletConnected = viewModel.nwcRepo.hasConnection()

    val noteActions = remember(userPubkey) {
        NoteActions(
            onReply = onReply,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = { event -> zapTargetEvent = event },
            onProfileClick = onProfileClick,
            onNoteClick = { eventId -> onQuotedNoteClick?.invoke(eventId) },
            onAddToList = onAddToList,
            onFollowAuthor = { pubkey -> viewModel.toggleFollow(pubkey) },
            onBlockAuthor = { pubkey -> viewModel.blockUser(pubkey) },
            onPin = { eventId -> viewModel.togglePin(eventId) },
            onDelete = { eventId, kind -> viewModel.deleteEvent(eventId, kind) },
            isFollowing = { pubkey -> viewModel.contactRepo.isFollowing(pubkey) },
            userPubkey = userPubkey,
            nip05Repo = viewModel.nip05Repo,
            onHashtagClick = onHashtagClick
        )
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


    val initialLoadDone by viewModel.initialLoadDone.collectAsState()

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
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                isTorEnabled = isTorEnabled,
                torStatus = torStatus,
                onToggleTor = onToggleTor,
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
                onDrafts = {
                    scope.launch { drawerState.close() }
                    onDrafts()
                },
                onMediaServers = {
                    scope.launch { drawerState.close() }
                    onMediaServers()
                },
                onSafety = {
                    scope.launch { drawerState.close() }
                    onSafety()
                },
                onCustomEmojis = {
                    scope.launch { drawerState.close() }
                    onCustomEmojis()
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                    relayFeedStatus = relayFeedStatus,
                    onViewDetails = { onRelayDetail(selectedRelay!!) }
                )
            }
            Box(
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
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                            feedType == FeedType.RELAY -> {
                                RelayFeedEmptyState(
                                    status = relayFeedStatus,
                                    relayUrl = selectedRelay ?: "",
                                    onRetry = { viewModel.retryRelayFeed() }
                                )
                            }
                            initLoadingState != InitLoadingState.Done -> {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                                    followList = followList,
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
                                    onDelete = { viewModel.deleteEvent(event.id, event.kind) },
                                    onRelayClick = { url ->
                                        viewModel.setSelectedRelay(url)
                                        viewModel.setFeedType(FeedType.RELAY)
                                    },
                                    noteActions = noteActions,
                                    onManageEmojis = onCustomEmojis
                                )
                            }
                            if (initialLoadDone) {
                                item(key = "load-more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        TextButton(onClick = { viewModel.loadMore() }) {
                                            Text("Load more")
                                        }
                                    }
                                }
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
    followList: List<com.wisp.app.nostr.Nip02.FollowEntry> = emptyList(),
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
    onDelete: () -> Unit = {},
    onRelayClick: (String) -> Unit = {},
    noteActions: NoteActions? = null,
    onManageEmojis: (() -> Unit)? = null
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
    val repostPubkeys = remember(repostVersion, event.id) {
        viewModel.eventRepo.getReposterPubkeys(event.id)
    }
    val hasUserReposted = remember(repostVersion, event.id) {
        viewModel.eventRepo.hasUserReposted(event.id)
    }
    val hasUserZapped = remember(zapVersion, event.id) {
        viewModel.eventRepo.hasUserZapped(event.id)
    }
    val isFollowing = remember(followList, event.pubkey) {
        viewModel.contactRepo.isFollowing(event.pubkey)
    }
    val resolvedEmojis by viewModel.customEmojiRepo.resolvedEmojis.collectAsState()
    val unicodeEmojis by viewModel.customEmojiRepo.unicodeEmojis.collectAsState()
    val eventReactionEmojiUrls = remember(reactionVersion, event.id) {
        viewModel.eventRepo.getReactionEmojiUrls(event.id)
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
        repostDetails = repostPubkeys,
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
        onDelete = onDelete,
        onQuotedNoteClick = onQuotedNoteClick,
        noteActions = noteActions,
        reactionEmojiUrls = eventReactionEmojiUrls,
        resolvedEmojis = resolvedEmojis,
        unicodeEmojis = unicodeEmojis,
        onManageEmojis = onManageEmojis
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
private fun RelayFeedBar(
    relayUrl: String,
    relayInfoRepo: RelayInfoRepository,
    relayFeedStatus: RelayFeedStatus,
    onViewDetails: () -> Unit
) {
    val info = remember(relayUrl) { relayInfoRepo.getInfo(relayUrl) }
    val iconUrl = remember(relayUrl) { relayInfoRepo.getIconUrl(relayUrl) }
    val domain = remember(relayUrl) {
        relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }

    val statusColor = when (relayFeedStatus) {
        is RelayFeedStatus.Streaming -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        is RelayFeedStatus.Connecting, is RelayFeedStatus.Subscribing -> androidx.compose.ui.graphics.Color(0xFFFFC107)
        is RelayFeedStatus.Disconnected, is RelayFeedStatus.ConnectionFailed,
        is RelayFeedStatus.TimedOut -> androidx.compose.ui.graphics.Color(0xFFFF5252)
        is RelayFeedStatus.RateLimited, is RelayFeedStatus.BadRelay,
        is RelayFeedStatus.Cooldown -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                Box {
                    RelayIcon(
                        iconUrl = iconUrl,
                        relayUrl = relayUrl,
                        size = 28.dp
                    )
                    // Connection status dot overlay
                    if (relayFeedStatus !is RelayFeedStatus.Idle) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                        ) {
                            drawCircle(color = androidx.compose.ui.graphics.Color.Black, radius = size.minDimension / 2)
                            drawCircle(color = statusColor, radius = size.minDimension / 2 - 1.dp.toPx())
                        }
                    }
                }
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

@Composable
private fun RelayFeedEmptyState(
    status: RelayFeedStatus,
    relayUrl: String,
    onRetry: () -> Unit
) {
    val domain = remember(relayUrl) {
        relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        when (status) {
            is RelayFeedStatus.Connecting -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Connecting to $domain...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is RelayFeedStatus.Subscribing -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Connected. Waiting for events...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is RelayFeedStatus.NoEvents -> {
                Text(
                    "No events found on this relay",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is RelayFeedStatus.TimedOut -> {
                Text(
                    "Connection timed out",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            is RelayFeedStatus.RateLimited -> {
                Text(
                    "This relay is rate limiting you",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            is RelayFeedStatus.BadRelay -> {
                Text(
                    "This relay has been marked unreliable",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    status.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Try anyway") }
            }
            is RelayFeedStatus.Cooldown -> {
                Text(
                    "Relay on cooldown",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${status.remainingSeconds}s remaining",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is RelayFeedStatus.ConnectionFailed -> {
                Text(
                    "Connection failed",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    status.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            is RelayFeedStatus.Disconnected -> {
                Text(
                    "Relay disconnected",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Reconnect") }
            }
            is RelayFeedStatus.Streaming, is RelayFeedStatus.Idle -> {
                // Streaming with empty feed shouldn't normally happen, show spinner
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
