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
import com.wisp.app.ui.component.WispDrawerContent
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.viewmodel.FeedType
import com.wisp.app.viewmodel.FeedViewModel
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
    val zapInProgress by viewModel.zapInProgress.collectAsState()
    val bookmarkedIds by viewModel.bookmarkRepo.bookmarkedIds.collectAsState()
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
        var hasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                hasPaused = true
            } else if (event == Lifecycle.Event.ON_RESUME && hasPaused) {
                viewModel.onAppResume()
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
            relayUrls = viewModel.getRelayUrls(),
            onSelect = { url ->
                viewModel.setSelectedRelay(url)
                viewModel.setFeedType(FeedType.RELAY)
                showRelayPicker = false
            },
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
                                    connectedUrls.forEach { url ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    url.removePrefix("wss://").removeSuffix("/"),
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
                                    isBookmarked = event.id in bookmarkedIds,
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
                                    onBookmark = { viewModel.toggleBookmark(event.id) },
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
    isBookmarked: Boolean = false,
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
    onBookmark: () -> Unit = {},
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
    val userEmoji = remember(reactionVersion, event.id, userPubkey) {
        userPubkey?.let { viewModel.eventRepo.getUserReactionEmoji(event.id, it) }
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
        userReactionEmoji = userEmoji,
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
        onBookmark = onBookmark,
        isBookmarked = isBookmarked,
        onPin = onPin,
        isPinned = isPinned,
        onQuotedNoteClick = onQuotedNoteClick
    )
}

@Composable
private fun RelayPickerDialog(
    relayUrls: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Relay") },
        text = {
            Column {
                if (relayUrls.isEmpty()) {
                    Text("No relays configured")
                } else {
                    relayUrls.forEach { url ->
                        TextButton(
                            onClick = { onSelect(url) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                url.removePrefix("wss://").removeSuffix("/"),
                                modifier = Modifier.fillMaxWidth()
                            )
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
