package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.R
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.EmojiLibrarySheet
import com.wisp.app.ui.component.GalleryCard
import com.wisp.app.ui.component.isGalleryEvent
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RelayIcon
import com.wisp.app.ui.component.WispDrawerContent
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.relay.ScoredRelay
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.wisp.app.nostr.RelaySet
import com.wisp.app.relay.BroadcastState
import com.wisp.app.viewmodel.FeedContentFilter
import com.wisp.app.viewmodel.FeedType
import com.wisp.app.viewmodel.FeedViewModel
import com.wisp.app.viewmodel.InitLoadingState
import com.wisp.app.viewmodel.PowStatus
import com.wisp.app.viewmodel.RelayFeedStatus
import com.wisp.app.viewmodel.TrendingMetric
import com.wisp.app.viewmodel.TrendingMode
import com.wisp.app.viewmodel.TrendingTimeframe
import com.wisp.app.viewmodel.buildTrendingRelayUrl
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import com.wisp.app.ui.theme.WispThemeColors
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.AccountInfo
import com.wisp.app.ui.component.FollowButton
import com.wisp.app.viewmodel.TRENDING_USERS_RELAY_URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    accounts: List<AccountInfo> = emptyList(),
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onLogout: () -> Unit = {},
    onMediaServers: () -> Unit = {},
    onWallet: () -> Unit = {},
    onLists: () -> Unit = {},
    onDrafts: () -> Unit = {},
    onSocialGraph: () -> Unit = {},
    onSafety: () -> Unit = {},
    onCustomEmojis: () -> Unit = {},
    onConsole: () -> Unit = {},
    onRelayHealth: () -> Unit = {},
    onKeys: () -> Unit = {},
    onPowSettings: () -> Unit = {},
    onInterfaceSettings: () -> Unit = {},
    onAddToList: (String) -> Unit = {},
    onRelayDetail: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null,
    onViewSetFeed: ((com.wisp.app.nostr.InterestSet) -> Unit)? = null,
    onArticleClick: ((Int, String, String) -> Unit)? = null,
    onGroupRoom: ((String, String) -> Unit)? = null,
    fetchGroupPreview: (suspend (String, String) -> com.wisp.app.repo.GroupPreview?)? = null,
    scrollToTopTrigger: Int = 0
) {
    val feed by viewModel.feed.collectAsState()
    val feedType by viewModel.feedType.collectAsState()
    val contentFilter by viewModel.feedContentFilter.collectAsState()
    val selectedRelay by viewModel.selectedRelay.collectAsState()
    val selectedRelaySet by viewModel.selectedRelaySet.collectAsState()
    val replyCountVersion by viewModel.eventRepo.replyCountVersion.collectAsState()
    val zapVersion by viewModel.eventRepo.zapVersion.collectAsState()
    val reactionVersion by viewModel.eventRepo.reactionVersion.collectAsState()
    val repostVersion by viewModel.eventRepo.repostVersion.collectAsState()
    val relaySourceVersion by viewModel.eventRepo.relaySourceVersion.collectAsState()
    val followList by viewModel.contactRepo.followList.collectAsState()
    val profileVersion by viewModel.eventRepo.profileVersion.collectAsState()
    val nip05Version by viewModel.nip05Repo.version.collectAsState()
    val pollVoteVersion by viewModel.eventRepo.pollVoteVersion.collectAsState()
    val translationVersion by viewModel.translationRepo.version.collectAsState()
    val connectedCount by viewModel.relayPool.connectedCount.collectAsState()
    val listState = rememberLazyListState()

    // Viewport-aware engagement: notify ViewModel of visible item range
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .mapNotNull { items ->
                if (items.isEmpty()) null
                else items.first().index to items.last().index
            }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                viewModel.onVisibleRangeChanged(first, last)
            }
    }

    var handledScrollTrigger by rememberSaveable { mutableStateOf(scrollToTopTrigger) }
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger != handledScrollTrigger) {
            handledScrollTrigger = scrollToTopTrigger
            listState.scrollToItem(0)
        }
    }
    val userPubkey = viewModel.getUserPubkey()
    val selectedList by viewModel.selectedList.collectAsState()
    val trendingMetric by viewModel.trendingMetric.collectAsState()
    val trendingTimeframe by viewModel.trendingTimeframe.collectAsState()
    val trendingMode by viewModel.trendingMode.collectAsState()
    val trendingUsers by viewModel.trendingUsers.collectAsState()
    val trendingUsersLoading by viewModel.trendingUsersLoading.collectAsState()
    // Jump to top only when feed type, selected list, selected relay, or trending filters actually change
    // (not on recomposition after back-navigation from thread/compose)
    var prevFeedType by rememberSaveable { mutableStateOf(feedType.name) }
    var prevSelectedListId by rememberSaveable { mutableStateOf(selectedList?.dTag) }
    var prevSelectedRelay by rememberSaveable { mutableStateOf(selectedRelay) }
    var prevSelectedRelaySet by rememberSaveable { mutableStateOf(selectedRelaySet?.name) }
    var prevTrendingMetric by rememberSaveable { mutableStateOf(trendingMetric.name) }
    var prevTrendingTimeframe by rememberSaveable { mutableStateOf(trendingTimeframe.name) }
    var prevTrendingMode by rememberSaveable { mutableStateOf(trendingMode.name) }

    LaunchedEffect(feedType, selectedList, selectedRelay, selectedRelaySet, trendingMetric, trendingTimeframe, trendingMode) {
        val feedTypeChanged = feedType.name != prevFeedType
        val listChanged = selectedList?.dTag != prevSelectedListId
        val relayChanged = selectedRelay != prevSelectedRelay
        val relaySetChanged = selectedRelaySet?.name != prevSelectedRelaySet
        val metricChanged = trendingMetric.name != prevTrendingMetric
        val timeframeChanged = trendingTimeframe.name != prevTrendingTimeframe
        val modeChanged = trendingMode.name != prevTrendingMode

        if (feedTypeChanged || listChanged || relayChanged || relaySetChanged || metricChanged || timeframeChanged || modeChanged) {
            prevFeedType = feedType.name
            prevSelectedListId = selectedList?.dTag
            prevSelectedRelay = selectedRelay
            prevSelectedRelaySet = selectedRelaySet?.name
            prevTrendingMetric = trendingMetric.name
            prevTrendingTimeframe = trendingTimeframe.name
            prevTrendingMode = trendingMode.name
            listState.scrollToItem(0)
        }
    }
    val ownLists by viewModel.listRepo.ownLists.collectAsState()
    var showRelayPicker by remember { mutableStateOf(false) }
    var showListPicker by remember { mutableStateOf(false) }
    val interestSets by viewModel.interestRepo.sets.collectAsState()
    val hashtagPickerRequested by viewModel.hashtagPickerRequested.collectAsState()
    var showHashtagPicker by remember { mutableStateOf(false) }
    LaunchedEffect(hashtagPickerRequested) {
        if (hashtagPickerRequested) {
            showHashtagPicker = true
            viewModel.clearHashtagPickerRequest()
        }
    }
    var showRelayDropdown by remember { mutableStateOf(false) }
    var showOnlineSheet by remember { mutableStateOf(false) }
    val onlinePubkeys by viewModel.eventRepo.onlinePubkeys.collectAsState()
    val globalOnlineCount by viewModel.globalOnlineCount.collectAsState()
    var showFeedTypeDropdown by remember { mutableStateOf(false) }
    var showSocialGraphDialog by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val userProfile = profileVersion.let { userPubkey?.let { viewModel.eventRepo.getProfileData(it) } }

    val newNoteCount by viewModel.newNoteCount.collectAsState()
    val newNotesButtonHidden by viewModel.newNotesButtonHidden.collectAsState()
    val initLoadingState by viewModel.initLoadingState.collectAsState()
    val relayFeedStatus by viewModel.relayFeedStatus.collectAsState()
    val localRelayConfig by viewModel.keyRepo.localRelayFlow.collectAsState()
    val pendingFirstFollow by viewModel.pendingFirstFollow.collectAsState()
    val firstFollowCheckDone by viewModel.firstFollowCheckDone.collectAsState()
    val zapInProgress by viewModel.zapInProgress.collectAsState()
    val setListedIds by viewModel.bookmarkSetRepo.allListedEventIds.collectAsState()
    val bookmarkedIds by viewModel.bookmarkRepo.bookmarkedIds.collectAsState()
    val listedIds = remember(setListedIds, bookmarkedIds) { setListedIds + bookmarkedIds }
    val pinnedIds by viewModel.pinRepo.pinnedIds.collectAsState()

    var zapTargetEvent by remember { mutableStateOf<NostrEvent?>(null) }
    var zapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
    var zapErrorMessage by remember { mutableStateOf<String?>(null) }
    var showEmojiLibrary by remember { mutableStateOf(false) }

    val isWalletConnected = viewModel.activeWalletProvider.hasConnection()

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
            onHashtagClick = onHashtagClick,
            onRelayClick = { url ->
                viewModel.setSelectedRelay(url)
                viewModel.setFeedType(FeedType.RELAY)
            },
            onArticleClick = onArticleClick,
            onPayInvoice = { bolt11 -> viewModel.payInvoice(bolt11) },
            onGroupRoom = onGroupRoom,
            groupMetadataProvider = { relayUrl, groupId -> viewModel.groupRepo.getRoom(relayUrl, groupId)?.metadata },
            fetchGroupPreview = fetchGroupPreview,
            onAddEmojiSet = { pubkey, dTag -> viewModel.addSetToEmojiList(pubkey, dTag) },
            onRemoveEmojiSet = { pubkey, dTag -> viewModel.removeSetFromEmojiList(pubkey, dTag) },
            isEmojiSetAdded = { pubkey, dTag ->
                val ref = com.wisp.app.nostr.Nip30.buildSetReference(pubkey, dTag)
                viewModel.customEmojiRepo.userEmojiList.value?.setReferences?.contains(ref) ?: false
            },
            onPollVote = { pollId, optionIds -> viewModel.publishPollVote(pollId, optionIds) }
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
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(isAtTop) {
        if (isAtTop) viewModel.resetNewNoteCount()
    }


    val favoriteRelays by viewModel.relaySetRepo.favoriteRelays.collectAsState()
    val ownRelaySets by viewModel.relaySetRepo.ownRelaySets.collectAsState()

    if (showOnlineSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showOnlineSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    stringResource(R.string.online_now),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                val greenColor = WispThemeColors.repostColor
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = greenColor)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.online_in_network, onlinePubkeys.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (globalOnlineCount != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = greenColor)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.online_all_nostr, globalOnlineCount ?: 0),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onlinePubkeys.forEach { pubkey ->
                        val profile = viewModel.eventRepo.getProfileData(pubkey)
                        ProfilePicture(
                            url = profile?.picture,
                            size = 44,
                            onClick = {
                                showOnlineSheet = false
                                onProfileClick(pubkey)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRelayPicker) {
        RelayPickerDialog(
            scoredRelays = viewModel.getScoredRelays(),
            favoriteRelays = favoriteRelays,
            relaySets = ownRelaySets,
            relayInfoRepo = viewModel.relayInfoRepo,
            localRelayUrl = localRelayConfig?.let { if (it.enabled) it.url else null },
            onSelect = { url ->
                viewModel.setSelectedRelay(url)
                viewModel.setFeedType(FeedType.RELAY)
                showRelayPicker = false
            },
            onSelectRelaySet = { relaySet ->
                viewModel.setSelectedRelaySet(relaySet)
                viewModel.setFeedType(FeedType.RELAY)
                showRelayPicker = false
            },
            onCreateRelaySet = { name -> viewModel.createRelaySet(name) },
            onProbe = { domain -> viewModel.probeRelay(domain) },
            onDismiss = { showRelayPicker = false }
        )
    }

    if (showHashtagPicker) {
        val interestSetsLoaded by viewModel.interestSetsFetched.collectAsState()
        LaunchedEffect(Unit) {
            viewModel.fetchInterestSetsIfMissing()
        }
        HashtagPickerDialog(
            sets = interestSets,
            isLoading = !interestSetsLoaded && interestSets.isEmpty(),
            onSelectHashtag = { tag ->
                showHashtagPicker = false
                onHashtagClick?.invoke(tag)
            },
            onViewSetFeed = { set ->
                showHashtagPicker = false
                onViewSetFeed?.invoke(set)
            },
            onAddHashtag = { tag, dTag -> viewModel.followHashtag(tag, dTag) },
            onRemoveHashtag = { tag, dTag -> viewModel.unfollowHashtag(tag, dTag) },
            onCreateSet = { name -> viewModel.createInterestSet(name) },
            onRenameSet = { dTag, name -> viewModel.renameInterestSet(dTag, name) },
            onDeleteSet = { dTag -> viewModel.deleteInterestSet(dTag) },
            onDismiss = { showHashtagPicker = false }
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
        val zapRecipient = zapTargetEvent!!.pubkey
        val userHasDmRelays = viewModel.relayPool.hasDmRelays()
        var recipientHasDmRelays by remember(zapRecipient) {
            mutableStateOf(viewModel.relayListRepo.hasDmRelays(zapRecipient))
        }
        if (userHasDmRelays && !recipientHasDmRelays) {
            LaunchedEffect(zapRecipient) {
                recipientHasDmRelays = viewModel.fetchDmRelaysIfMissing(zapRecipient)
            }
        }
        ZapDialog(
            isWalletConnected = isWalletConnected,
            onDismiss = { zapTargetEvent = null },
            onZap = { amountMsats, message, isAnonymous, isPrivate ->
                val event = zapTargetEvent ?: return@ZapDialog
                zapTargetEvent = null
                viewModel.sendZap(event, amountMsats, message, isAnonymous, isPrivate)
            },
            onGoToWallet = onWallet,
            canPrivateZap = userHasDmRelays && recipientHasDmRelays
        )
    }

    if (zapErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { zapErrorMessage = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.zap_failed)) },
            text = { Text(zapErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { zapErrorMessage = null }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }


    if (pendingFirstFollow != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFirstFollow() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (firstFollowCheckDone) "No follow list found" else "Checking follow list") },
            text = {
                if (!firstFollowCheckDone) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = androidx.compose.ui.Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = androidx.compose.ui.Modifier.width(12.dp))
                        Text("Checking relays for an existing follow list...")
                    }
                } else {
                    Text("No existing follow list was found on your relays. If you follow this person, your follow list will start at 1. If you believe this is wrong, rebroadcast your follow list from another client first, then try again.")
                }
            },
            confirmButton = {
                if (firstFollowCheckDone) {
                    TextButton(onClick = { viewModel.confirmFirstFollow() }) { Text("Follow anyway") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFirstFollow() }) { Text("Cancel") }
            }
        )
    }

    if (showSocialGraphDialog) {
        AlertDialog(
            onDismissRequest = { showSocialGraphDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.error_social_graph_required)) },
            text = {
                Text(stringResource(R.string.error_extended_feed_description))
            },
            confirmButton = {
                Button(onClick = {
                    showSocialGraphDialog = false
                    onSocialGraph()
                }) {
                    Text(stringResource(R.string.btn_go_to_social_graph))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSocialGraphDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
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
                accounts = accounts,
                onSwitchAccount = { pubkeyHex ->
                    scope.launch { drawerState.close() }
                    onSwitchAccount(pubkeyHex)
                },
                onAddAccount = {
                    scope.launch { drawerState.close() }
                    onAddAccount()
                },
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
                onSocialGraph = {
                    scope.launch { drawerState.close() }
                    onSocialGraph()
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
                onPowSettings = {
                    scope.launch { drawerState.close() }
                    onPowSettings()
                },
                onConsole = {
                    scope.launch { drawerState.close() }
                    onConsole()
                },
                onRelayHealth = {
                    scope.launch { drawerState.close() }
                    onRelayHealth()
                },
                onRelaySettings = {
                    scope.launch { drawerState.close() }
                    onRelays()
                },
                onInterfaceSettings = {
                    scope.launch { drawerState.close() }
                    onInterfaceSettings()
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
                CenterAlignedTopAppBar(
                    title = {
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
                                                FeedType.FOLLOWS -> stringResource(R.string.tab_follows)
                                                FeedType.EXTENDED_FOLLOWS -> stringResource(R.string.tab_extended)
                                                FeedType.RELAY -> if (selectedRelay != null) {
                                                    selectedRelay!!.removePrefix("wss://").removeSuffix("/")
                                                } else stringResource(R.string.tab_relay)
                                                FeedType.LIST -> if (selectedList != null) {
                                                    selectedList!!.name
                                                } else stringResource(R.string.tab_list)
                                                FeedType.TRENDING -> stringResource(R.string.tab_trending)
                                            }
                                            Text(
                                                feedLabel,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 140.dp)
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
                                    onDismissRequest = { showFeedTypeDropdown = false },
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_follows)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            viewModel.setFeedType(FeedType.FOLLOWS)
                                        },
                                        trailingIcon = if (feedType == FeedType.FOLLOWS) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_extended)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            if (viewModel.extendedNetworkRepo.cachedNetwork.value == null) {
                                                showSocialGraphDialog = true
                                            } else {
                                                viewModel.setFeedType(FeedType.EXTENDED_FOLLOWS)
                                            }
                                        },
                                        trailingIcon = if (feedType == FeedType.EXTENDED_FOLLOWS) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_trending)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            viewModel.setFeedType(FeedType.TRENDING)
                                        },
                                        trailingIcon = if (feedType == FeedType.TRENDING) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_relay)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            showRelayPicker = true
                                        },
                                        trailingIcon = if (feedType == FeedType.RELAY) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_list)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            showListPicker = true
                                        },
                                        trailingIcon = if (feedType == FeedType.LIST) {{
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }} else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_hashtags)) },
                                        onClick = {
                                            showFeedTypeDropdown = false
                                            showHashtagPicker = true
                                        }
                                    )
                                }
                                }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                ProfilePicture(url = userProfile?.picture, size = 32)
                            }
                            // Content type filter toggle — rotates through All → Notes → Gallery → Polls
                            IconButton(
                                onClick = {
                                    val next = when (contentFilter) {
                                        FeedContentFilter.ALL -> FeedContentFilter.TEXT_ONLY
                                        FeedContentFilter.TEXT_ONLY -> FeedContentFilter.GALLERY_ONLY
                                        FeedContentFilter.GALLERY_ONLY -> FeedContentFilter.POLLS_ONLY
                                        FeedContentFilter.POLLS_ONLY -> FeedContentFilter.ALL
                                    }
                                    viewModel.setFeedContentFilter(next)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                val (icon, tint) = when (contentFilter) {
                                    FeedContentFilter.ALL -> Icons.Outlined.Dashboard to MaterialTheme.colorScheme.onSurfaceVariant
                                    FeedContentFilter.TEXT_ONLY -> Icons.AutoMirrored.Outlined.Article to MaterialTheme.colorScheme.primary
                                    FeedContentFilter.GALLERY_ONLY -> Icons.Outlined.Photo to MaterialTheme.colorScheme.primary
                                    FeedContentFilter.POLLS_ONLY -> Icons.Outlined.HowToVote to MaterialTheme.colorScheme.primary
                                }
                                Icon(icon, contentDescription = "Filter: ${contentFilter.name}", tint = tint, modifier = Modifier.size(22.dp))
                            }
                        }
                    },
                    actions = {
                        if (onlinePubkeys.isNotEmpty()) {
                            Surface(
                                onClick = { showOnlineSheet = true },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = WispThemeColors.repostColor
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "${onlinePubkeys.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
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
                                    Icon(
                                        Icons.Outlined.Hub,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (connectedCount > 0)
                                            WispThemeColors.repostColor
                                        else
                                            androidx.compose.ui.graphics.Color(0xFFFF5252)
                                    )
                                    Spacer(Modifier.width(4.dp))
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
                    isFavorite = selectedRelay!! in favoriteRelays,
                    relaySets = ownRelaySets,
                    onViewDetails = { onRelayDetail(selectedRelay!!) },
                    onToggleFavorite = { viewModel.toggleFavoriteRelay(selectedRelay!!) },
                    onAddToRelaySet = { dTag -> viewModel.addRelayToSet(selectedRelay!!, dTag) },
                    onCreateRelaySet = { name -> viewModel.createRelaySet(name, setOf(selectedRelay!!)) }
                )
            }
            if (feedType == FeedType.RELAY && selectedRelaySet != null && selectedRelay == null) {
                RelaySetFeedBar(
                    relaySet = selectedRelaySet!!,
                    relayFeedStatus = relayFeedStatus
                )
            }
            if (feedType == FeedType.TRENDING) {
                TrendingFilterBar(
                    metric = trendingMetric,
                    timeframe = trendingTimeframe,
                    mode = trendingMode,
                    onMetricChange = { viewModel.setTrendingMetric(it) },
                    onTimeframeChange = { viewModel.setTrendingTimeframe(it) },
                    onModeChange = { viewModel.setTrendingMode(it) }
                )
            }
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (feedType == FeedType.TRENDING && trendingMode == TrendingMode.USERS) {
                    TrendingUsersContent(
                        users = trendingUsers,
                        isLoading = trendingUsersLoading,
                        relayFeedStatus = relayFeedStatus,
                        contactRepo = viewModel.contactRepo,
                        onProfileClick = onProfileClick,
                        onToggleFollow = { viewModel.toggleFollow(it) },
                        onFollowAll = { viewModel.followAll(it) },
                        onRetry = { viewModel.setTrendingMode(TrendingMode.USERS) }
                    )
                } else if (feed.isEmpty()) {
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
                                // Shouldn't normally reach here since the dropdown intercepts,
                                // but handle it as a fallback
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Compute your social graph to see posts from friends-of-friends",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { onSocialGraph() }) {
                                        Text(stringResource(R.string.btn_go_to_social_graph))
                                    }
                                }
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
                            feedType == FeedType.TRENDING -> {
                                RelayFeedEmptyState(
                                    status = relayFeedStatus,
                                    relayUrl = buildTrendingRelayUrl(trendingMetric, trendingTimeframe),
                                    onRetry = { viewModel.setFeedType(FeedType.TRENDING) }
                                )
                            }
                            initLoadingState != InitLoadingState.Done -> {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            contentFilter != FeedContentFilter.ALL -> {
                                // Content filter active but no matching posts
                                Text(
                                    when (contentFilter) {
                                        FeedContentFilter.GALLERY_ONLY -> "No gallery posts in your feed yet"
                                        FeedContentFilter.POLLS_ONLY -> "No polls in your feed yet"
                                        FeedContentFilter.TEXT_ONLY -> "No notes in your feed yet"
                                        else -> "No posts yet"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                Text(
                                    "No posts yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refreshFeed() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items = feed, key = { it.id }, contentType = { if (it.kind == 30023) "article" else "post" }) { event ->
                                if (event.kind == 30023) {
                                    FeedArticleItem(
                                        event = event,
                                        viewModel = viewModel,
                                        userPubkey = userPubkey,
                                        profileVersion = profileVersion,
                                        reactionVersion = reactionVersion,
                                        replyCountVersion = replyCountVersion,
                                        zapVersion = zapVersion,
                                        repostVersion = repostVersion,
                                        isZapAnimating = event.id in zapAnimatingIds,
                                        isZapInProgress = event.id in zapInProgress,
                                        isInList = event.id in listedIds,
                                        onArticleClick = onArticleClick,
                                        onProfileClick = onProfileClick,
                                        onReply = { onReply(event) },
                                        onReact = { emoji -> onReact(event, emoji) },
                                        onRepost = { onRepost(event) },
                                        onQuote = { onQuote(event) },
                                        onZap = { zapTargetEvent = event },
                                        onAddToList = { onAddToList(event.id) },
                                        onOpenEmojiLibrary = { showEmojiLibrary = true }
                                    )
                                } else {
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
                                    onOpenEmojiLibrary = { showEmojiLibrary = true },
                                    translationVersion = translationVersion,
                                    pollVoteVersion = pollVoteVersion,
                                    onPollVote = { optionIds -> viewModel.publishPollVote(event.id, optionIds) }
                                )
                                }
                            }
                            if (initialLoadDone) {
                                item(key = "load-more", contentType = "loader") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        TextButton(onClick = { viewModel.loadMore() }) {
                                            Text(stringResource(R.string.btn_load_more))
                                        }
                                    }
                                }
                            }
                        }

                        NewNotesButton(
                            visible = newNoteCount > 0 && !isAtTop && !newNotesButtonHidden,
                            count = newNoteCount,
                            onClick = {
                                scope.launch {
                                    listState.scrollToItem(0)
                                    viewModel.resetNewNoteCount()
                                }
                            },
                            onHide = { permanent -> viewModel.hideNewNotesButton(permanent) },
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

    if (showEmojiLibrary) {
        val sheetUnicodeEmojis by viewModel.customEmojiRepo.unicodeEmojis.collectAsState()
        EmojiLibrarySheet(
            currentEmojis = sheetUnicodeEmojis,
            onAddEmojis = { emojis ->
                emojis.forEach { viewModel.customEmojiRepo.addUnicodeEmoji(it) }
            },
            onDismiss = { showEmojiLibrary = false }
        )
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
    onOpenEmojiLibrary: (() -> Unit)? = null,
    translationVersion: Int = 0,
    pollVoteVersion: Int = 0,
    onPollVote: (List<String>) -> Unit = {}
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
    val repostTime = remember(repostVersion, event.id) {
        viewModel.eventRepo.getRepostTime(event.id)
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
    val translationState = remember(translationVersion, event.id) {
        viewModel.translationRepo.getState(event.id)
    }
    val pollVoteCounts = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) viewModel.eventRepo.getPollVoteCounts(event.id) else emptyMap()
    }
    val pollTotalVotes = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) viewModel.eventRepo.getPollTotalVotes(event.id) else 0
    }
    val userPollVotes = remember(pollVoteVersion, event.id) {
        if (event.kind == 1068) viewModel.eventRepo.getUserPollVotes(event.id) else emptyList()
    }
    if (isGalleryEvent(event)) {
        GalleryCard(
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
            onRelayClick = onRelayClick,
            repostPubkeys = repostPubkeys,
            repostTime = repostTime,
            reactionDetails = reactionDetails,
            zapDetails = zapDetails,
            repostDetails = repostPubkeys,
            onNavigateToProfileFromDetails = onNavigateToProfile,
            onFollowAuthor = { viewModel.toggleFollow(event.pubkey) },
            onBlockAuthor = { viewModel.blockUser(event.pubkey) },
            isFollowingAuthor = isFollowing,
            isOwnEvent = event.pubkey == userPubkey,
            onQuotedNoteClick = onQuotedNoteClick,
            noteActions = noteActions,
            reactionEmojiUrls = eventReactionEmojiUrls,
            resolvedEmojis = resolvedEmojis,
            unicodeEmojis = unicodeEmojis,
            onOpenEmojiLibrary = onOpenEmojiLibrary,
            onAddToList = onAddToList,
            isInList = isInList,
            onPin = onPin,
            isPinned = isPinned,
            onDelete = onDelete,
            nip05Repo = viewModel.nip05Repo
        )
    } else {
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
            repostPubkeys = repostPubkeys,
            repostTime = repostTime,
            reactionDetails = reactionDetails,
            zapDetails = zapDetails,
            repostDetails = repostPubkeys,
            onNavigateToProfileFromDetails = onNavigateToProfile,
            onRelayClick = onRelayClick,
            onFollowAuthor = { viewModel.toggleFollow(event.pubkey) },
            onBlockAuthor = { viewModel.blockUser(event.pubkey) },
            onMuteThread = {
                val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event) ?: event.id
                viewModel.muteThread(rootId)
            },
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
            onOpenEmojiLibrary = onOpenEmojiLibrary,
            pollVoteCounts = pollVoteCounts,
            pollTotalVotes = pollTotalVotes,
            userPollVotes = userPollVotes,
            onPollVote = onPollVote,
            translationState = translationState,
            onTranslate = { viewModel.translateEvent(event.id, event.content) }
        )
    }
}

@Composable
private fun FeedArticleItem(
    event: NostrEvent,
    viewModel: FeedViewModel,
    userPubkey: String?,
    profileVersion: Int,
    reactionVersion: Int,
    replyCountVersion: Int,
    zapVersion: Int,
    repostVersion: Int,
    isZapAnimating: Boolean,
    isZapInProgress: Boolean = false,
    isInList: Boolean = false,
    onArticleClick: ((Int, String, String) -> Unit)?,
    onProfileClick: (String) -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onZap: () -> Unit,
    onAddToList: () -> Unit = {},
    onOpenEmojiLibrary: (() -> Unit)? = null
) {
    val title = remember(event) { event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) }
    val summary = remember(event) { event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1) }
    val image = remember(event) { event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1) }
    val dTag = remember(event) { event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "" }
    val publishedAt = remember(event) {
        event.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull()
    }
    val profileData = remember(profileVersion, event.pubkey) {
        viewModel.eventRepo.getProfileData(event.pubkey)
    }
    val likeCount = remember(reactionVersion, event.id) { viewModel.eventRepo.getReactionCount(event.id) }
    val replyCount = remember(replyCountVersion, event.id) { viewModel.eventRepo.getReplyCount(event.id) }
    val zapSats = remember(zapVersion, event.id) { viewModel.eventRepo.getZapSats(event.id) }
    val userEmojis = remember(reactionVersion, event.id, userPubkey) {
        userPubkey?.let { viewModel.eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
    }
    val repostCount = remember(repostVersion, event.id) { viewModel.eventRepo.getRepostCount(event.id) }
    val hasUserReposted = remember(repostVersion, event.id) { viewModel.eventRepo.hasUserReposted(event.id) }
    val hasUserZapped = remember(zapVersion, event.id) { viewModel.eventRepo.hasUserZapped(event.id) }
    val resolvedEmojis by viewModel.customEmojiRepo.resolvedEmojis.collectAsState()
    val unicodeEmojis by viewModel.customEmojiRepo.unicodeEmojis.collectAsState()
    val eventReactionEmojiUrls = remember(reactionVersion, event.id) {
        viewModel.eventRepo.getReactionEmojiUrls(event.id)
    }

    val displayName = profileData?.displayString
        ?: "${event.pubkey.take(8)}...${event.pubkey.takeLast(4)}"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(
                if (onArticleClick != null) Modifier.clickable { onArticleClick(30023, event.pubkey, dTag) }
                else Modifier
            )
    ) {
        Column {
            if (image != null) {
                coil3.compose.AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Author row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    ProfilePicture(
                        url = profileData?.picture,
                        size = 28,
                        modifier = Modifier.clickable { onProfileClick(event.pubkey) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onProfileClick(event.pubkey) }
                    )
                    val timestamp = publishedAt ?: event.created_at
                    Text(
                        text = formatFeedArticleTimestamp(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                // Article badge + title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "ARTICLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = title ?: "Untitled Article",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // Action bar
            com.wisp.app.ui.component.ActionBar(
                onReply = onReply,
                onReact = onReact,
                userReactionEmojis = userEmojis,
                onRepost = onRepost,
                onQuote = onQuote,
                hasUserReposted = hasUserReposted,
                onZap = onZap,
                hasUserZapped = hasUserZapped,
                onAddToList = onAddToList,
                isInList = isInList,
                likeCount = likeCount,
                repostCount = repostCount,
                replyCount = replyCount,
                zapSats = zapSats,
                isZapAnimating = isZapAnimating,
                isZapInProgress = isZapInProgress,
                reactionEmojiUrls = eventReactionEmojiUrls,
                resolvedEmojis = resolvedEmojis,
                unicodeEmojis = unicodeEmojis,
                onOpenEmojiLibrary = onOpenEmojiLibrary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun RelayPickerDialog(
    scoredRelays: List<ScoredRelay>,
    favoriteRelays: List<String>,
    relaySets: List<RelaySet>,
    relayInfoRepo: RelayInfoRepository,
    localRelayUrl: String? = null,
    onSelect: (String) -> Unit,
    onSelectRelaySet: (RelaySet) -> Unit,
    onCreateRelaySet: (String) -> Unit,
    onProbe: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var isProbing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var expandedSetDTag by remember { mutableStateOf<String?>(null) }
    var showCreateSet by remember { mutableStateOf(false) }
    var newSetName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val couldntConnectError = stringResource(R.string.error_couldnt_connect_relay)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.menu_select_relay)) },
        text = {
            Column {
                // URL bar
                androidx.compose.material3.OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        probeError = null
                    },
                    placeholder = { Text(stringResource(R.string.placeholder_relay_example)) },
                    singleLine = true,
                    enabled = !isProbing,
                    trailingIcon = {
                        if (isProbing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = {
                                    val input = urlInput.trim().removeSuffix("/")
                                    val domain = input
                                        .removePrefix("wss://")
                                        .removePrefix("ws://")
                                    if (domain.isNotBlank()) {
                                        isProbing = true
                                        probeError = null
                                        scope.launch {
                                            // If the user specified a protocol, try only that
                                            val result = when {
                                                input.startsWith("ws://") || input.startsWith("wss://") ->
                                                    onProbe(input)
                                                else -> onProbe(domain)
                                            }
                                            isProbing = false
                                            if (result != null) {
                                                onSelect(result)
                                            } else {
                                                probeError = couldntConnectError
                                            }
                                        }
                                    }
                                },
                                enabled = urlInput.isNotBlank()
                            ) {
                                Text(stringResource(R.string.btn_go), style = MaterialTheme.typography.labelMedium)
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

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    // Local relay — always first when configured
                    if (localRelayUrl != null) {
                        item(key = "local-relay") {
                            Surface(
                                onClick = { onSelect(localRelayUrl) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Home,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            stringResource(R.string.local_relay_my_relay),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            localRelayUrl.removePrefix("ws://").removePrefix("wss://").removeSuffix("/"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Favorites section
                    if (favoriteRelays.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.tab_favorites),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        items(favoriteRelays) { url ->
                            Surface(
                                onClick = { onSelect(url) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RelayIcon(
                                        iconUrl = relayInfoRepo.getIconUrl(url),
                                        relayUrl = url,
                                        size = 24.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    // Relay Sets section — always shown so user can create their first set
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.tab_relay_sets),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                onClick = { showCreateSet = !showCreateSet },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    stringResource(R.string.btn_new_set),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    if (showCreateSet) {
                        item(key = "create-set-input") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = newSetName,
                                    onValueChange = { newSetName = it },
                                    placeholder = { Text(stringResource(R.string.placeholder_set_name)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = {
                                        if (newSetName.isNotBlank()) {
                                            onCreateRelaySet(newSetName.trim())
                                            newSetName = ""
                                            showCreateSet = false
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (newSetName.isNotBlank()) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        stringResource(R.string.btn_create),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (newSetName.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    for (relaySet in relaySets) {
                        item(key = "set-${relaySet.dTag}") {
                            Surface(
                                onClick = { expandedSetDTag = if (expandedSetDTag == relaySet.dTag) null else relaySet.dTag },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        relaySet.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        stringResource(R.string.relay_count_format, relaySet.relays.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        if (expandedSetDTag == relaySet.dTag) {
                            // Combined feed button
                            item(key = "set-combined-${relaySet.dTag}") {
                                Surface(
                                    onClick = { onSelectRelaySet(relaySet) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.tab_combined_feed),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                            // Individual relays in the set
                            items(relaySet.relays.toList(), key = { "set-relay-${relaySet.dTag}-$it" }) { url ->
                                Surface(
                                    onClick = { onSelect(url) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RelayIcon(
                                            iconUrl = relayInfoRepo.getIconUrl(url),
                                            relayUrl = url,
                                            size = 20.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (relaySets.isNotEmpty() || showCreateSet) {
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    // All Relays section
                    if (scoredRelays.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.tab_all_relays),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
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
                                        stringResource(R.string.relay_covers_format, scored.coverCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (favoriteRelays.isEmpty() && relaySets.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.error_no_relay_scores),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
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
        containerColor = MaterialTheme.colorScheme.surface,
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HashtagPickerDialog(
    sets: List<com.wisp.app.nostr.InterestSet>,
    isLoading: Boolean = false,
    onSelectHashtag: (String) -> Unit,
    onViewSetFeed: (com.wisp.app.nostr.InterestSet) -> Unit,
    onAddHashtag: (tag: String, dTag: String) -> Unit,
    onRemoveHashtag: (tag: String, dTag: String) -> Unit,
    onCreateSet: (String) -> Unit,
    onRenameSet: (String, String) -> Unit,
    onDeleteSet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newSetName by remember { mutableStateOf("") }
    var addingToDTag by remember { mutableStateOf<String?>(null) }
    var newHashtagText by remember { mutableStateOf("") }
    var renamingDTag by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDeleteDTag by remember { mutableStateOf<String?>(null) }
    var showNewSetField by remember { mutableStateOf(false) }
    val newSetFocusRequester = remember { FocusRequester() }
    var expandedDTags by remember(sets.size) {
        mutableStateOf(emptySet<String>())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tab_hashtags))
                TextButton(onClick = { showNewSetField = true }) {
                    Text(stringResource(R.string.tab_new_set))
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.btn_close),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "Loading interest sets\u2026",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (sets.isEmpty()) {
                    Text(
                        stringResource(R.string.no_interest_sets),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    sets.forEach { set ->
                        // Set header
                        if (renamingDTag == set.dTag) {
                            val renameFocusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { renameFocusRequester.requestFocus() }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).focusRequester(renameFocusRequester)
                                )
                                TextButton(onClick = {
                                    if (renameText.isNotBlank()) {
                                        onRenameSet(set.dTag, renameText.trim())
                                        renamingDTag = null
                                    }
                                }) { Text(stringResource(R.string.btn_save)) }
                                IconButton(
                                    onClick = { confirmDeleteDTag = set.dTag },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.btn_delete),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            val isExpanded = set.dTag in expandedDTags
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    set.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .weight(1f)
                                        .combinedClickable(
                                            onClick = {
                                                expandedDTags = if (isExpanded) expandedDTags - set.dTag else expandedDTags + set.dTag
                                            },
                                            onLongClick = {
                                                renamingDTag = set.dTag
                                                renameText = set.name
                                            }
                                        )
                                )
                                if (set.hashtags.isNotEmpty()) {
                                    TextButton(
                                        onClick = { onViewSetFeed(set) },
                                        modifier = Modifier.heightIn(min = 32.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(stringResource(R.string.tab_feed), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (isExpanded) {
                                    IconButton(
                                        onClick = {
                                            addingToDTag = if (addingToDTag == set.dTag) null else set.dTag
                                            newHashtagText = ""
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.cd_add_hashtag),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        val isExpanded = renamingDTag == set.dTag || set.dTag in expandedDTags
                        if (isExpanded) {
                            // Add hashtag input for this set
                            if (addingToDTag == set.dTag) {
                                val focusRequester = remember { FocusRequester() }
                                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp)
                                ) {
                                    androidx.compose.material3.OutlinedTextField(
                                        value = newHashtagText,
                                        onValueChange = { input ->
                                            newHashtagText = input.removePrefix("#").filter { c -> c.isLetterOrDigit() || c == '_' }
                                        },
                                        placeholder = { Text(stringResource(R.string.placeholder_hashtag)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        onClick = {
                                            val cleaned = newHashtagText.lowercase()
                                            if (cleaned.isNotBlank()) {
                                                onAddHashtag(cleaned, set.dTag)
                                                newHashtagText = ""
                                                addingToDTag = null
                                            }
                                        },
                                        enabled = newHashtagText.isNotBlank()
                                    ) { Text(stringResource(R.string.btn_add)) }
                                }
                            }
                            // Hashtags in this set
                            if (set.hashtags.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_hashtags_yet),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    set.hashtags.sorted().forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.combinedClickable(
                                                onClick = { onSelectHashtag(tag) },
                                                onLongClick = { onRemoveHashtag(tag, set.dTag) }
                                            )
                                        ) {
                                            Text(
                                                "#$tag",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                }
                if (showNewSetField) {
                    LaunchedEffect(Unit) { newSetFocusRequester.requestFocus() }
                }
                Spacer(Modifier.size(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newSetName,
                        onValueChange = { newSetName = it },
                        placeholder = { Text(stringResource(R.string.placeholder_new_set_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(newSetFocusRequester)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (newSetName.isNotBlank()) {
                                onCreateSet(newSetName.trim())
                                newSetName = ""
                                showNewSetField = false
                            }
                        },
                        enabled = newSetName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.btn_create))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )

    // Delete confirmation
    if (confirmDeleteDTag != null) {
        val dTag = confirmDeleteDTag!!
        val setName = sets.find { it.dTag == dTag }?.name ?: dTag
        AlertDialog(
            onDismissRequest = { confirmDeleteDTag = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.delete_set)) },
            text = { Text(stringResource(R.string.error_delete_set_hashtags, setName)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSet(dTag)
                    confirmDeleteDTag = null
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDTag = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewNotesButton(
    visible: Boolean,
    count: Int,
    onClick: () -> Unit,
    onHide: (permanent: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 4.dp,
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
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
                        stringResource(R.string.new_notes_button, count),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_notes_hide_temporary)) },
                    onClick = {
                        showMenu = false
                        onHide(false)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_notes_hide_forever)) },
                    onClick = {
                        showMenu = false
                        onHide(true)
                    }
                )
            }
        }
    }
}

@Composable
private fun TrendingFilterBar(
    metric: TrendingMetric,
    timeframe: TrendingTimeframe,
    mode: TrendingMode,
    onMetricChange: (TrendingMetric) -> Unit,
    onTimeframeChange: (TrendingTimeframe) -> Unit,
    onModeChange: (TrendingMode) -> Unit
) {
    val metricIcon = @Composable { m: TrendingMetric ->
        when (m) {
            TrendingMetric.REACTIONS -> Icon(Icons.Outlined.FavoriteBorder, contentDescription = m.label, modifier = Modifier.size(16.dp))
            TrendingMetric.REPLIES -> Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = m.label, modifier = Modifier.size(16.dp))
            TrendingMetric.REPOSTS -> Icon(Icons.Outlined.Repeat, contentDescription = m.label, modifier = Modifier.size(16.dp))
            TrendingMetric.ZAPS -> Icon(Icons.Outlined.CurrencyBitcoin, contentDescription = m.label, modifier = Modifier.size(16.dp))
        }
    }

    var showTimeframeDropdown by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = mode == TrendingMode.USERS,
                    onClick = { onModeChange(TrendingMode.USERS) },
                    label = { Icon(Icons.Outlined.Person, contentDescription = "People", modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .padding(vertical = 2.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ) {}
                }
                TrendingMetric.entries.forEach { m ->
                    FilterChip(
                        selected = mode == TrendingMode.NOTES && m == metric,
                        onClick = { onMetricChange(m) },
                        label = { metricIcon(m) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = mode == TrendingMode.NOTES) {
                    Box {
                        Surface(
                            onClick = { showTimeframeDropdown = true },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    timeframe.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Change timeframe",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showTimeframeDropdown,
                            onDismissRequest = { showTimeframeDropdown = false }
                        ) {
                            TrendingTimeframe.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    onClick = {
                                        showTimeframeDropdown = false
                                        onTimeframeChange(t)
                                    },
                                    trailingIcon = if (t == timeframe) {{
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }} else null
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun TrendingUsersContent(
    users: List<ProfileData>,
    isLoading: Boolean,
    relayFeedStatus: RelayFeedStatus,
    contactRepo: com.wisp.app.repo.ContactRepository,
    onProfileClick: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onFollowAll: (Set<String>) -> Unit,
    onRetry: () -> Unit
) {
    if (isLoading && users.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (users.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            RelayFeedEmptyState(
                status = relayFeedStatus,
                relayUrl = TRENDING_USERS_RELAY_URL,
                onRetry = onRetry
            )
        }
        return
    }

    val followList by contactRepo.followList.collectAsState()
    val followingPubkeys = remember(followList) {
        followList.map { it.pubkey }.toSet()
    }
    val unfollowedPubkeys = remember(users, followingPubkeys) {
        users.map { it.pubkey }.filter { it !in followingPubkeys }.toSet()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Up & Coming",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (unfollowedPubkeys.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { onFollowAll(unfollowedPubkeys) }
                    ) {
                        Text("Follow All (${unfollowedPubkeys.size})")
                    }
                }
            }
        }

        itemsIndexed(
            items = users,
            key = { _, profile -> profile.pubkey }
        ) { index, profile ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(300, delayMillis = index * 50)) +
                        slideInVertically(tween(300, delayMillis = index * 50)) { it / 2 }
            ) {
                val isFollowing = profile.pubkey in followingPubkeys
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onProfileClick(profile.pubkey) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfilePicture(
                            url = profile.picture,
                            size = 56,
                            onClick = { onProfileClick(profile.pubkey) }
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                profile.displayString,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!profile.nip05.isNullOrBlank()) {
                                Text(
                                    profile.nip05,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!profile.about.isNullOrBlank()) {
                                Text(
                                    profile.about,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FollowButton(
                            isFollowing = isFollowing,
                            onClick = { onToggleFollow(profile.pubkey) }
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(80.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun RelayFeedBar(
    relayUrl: String,
    relayInfoRepo: RelayInfoRepository,
    relayFeedStatus: RelayFeedStatus,
    isFavorite: Boolean = false,
    relaySets: List<RelaySet> = emptyList(),
    onViewDetails: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    onAddToRelaySet: (String) -> Unit = {},
    onCreateRelaySet: (String) -> Unit = {}
) {
    val info = remember(relayUrl) { relayInfoRepo.getInfo(relayUrl) }
    val iconUrl = remember(relayUrl) { relayInfoRepo.getIconUrl(relayUrl) }
    val domain = remember(relayUrl) {
        relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    }
    var showSetPicker by remember { mutableStateOf(false) }
    var newSetName by remember { mutableStateOf("") }

    val statusColor = when (relayFeedStatus) {
        is RelayFeedStatus.Streaming -> WispThemeColors.repostColor
        is RelayFeedStatus.Connecting, is RelayFeedStatus.Subscribing -> WispThemeColors.paidColor
        is RelayFeedStatus.Disconnected, is RelayFeedStatus.ConnectionFailed,
        is RelayFeedStatus.TimedOut -> androidx.compose.ui.graphics.Color(0xFFFF5252)
        is RelayFeedStatus.RateLimited, is RelayFeedStatus.BadRelay,
        is RelayFeedStatus.Cooldown -> WispThemeColors.zapColor
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
                Spacer(Modifier.width(6.dp))
                // Favorite star
                Surface(
                    onClick = onToggleFavorite,
                    shape = RoundedCornerShape(16.dp),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    Text(
                        if (isFavorite) "\u2605" else "\u2606",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isFavorite) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                // Add to set pill
                Surface(
                    onClick = { showSetPicker = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        stringResource(R.string.btn_add_to_set),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                // Details pill
                Surface(
                    onClick = onViewDetails,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        stringResource(R.string.btn_details),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }

    if (showSetPicker) {
        AlertDialog(
            onDismissRequest = { showSetPicker = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.menu_add_to_relay_set)) },
            text = {
                Column {
                    if (relaySets.isNotEmpty()) {
                        for (set in relaySets) {
                            val contains = relayUrl in set.relays
                            Surface(
                                onClick = {
                                    onAddToRelaySet(set.dTag)
                                    showSetPicker = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        set.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (contains) {
                                        Text(
                                            "\u2713",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = newSetName,
                        onValueChange = { newSetName = it },
                        placeholder = { Text(stringResource(R.string.placeholder_new_set_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (newSetName.isNotBlank()) {
                                IconButton(onClick = {
                                    onCreateRelaySet(newSetName.trim())
                                    newSetName = ""
                                    showSetPicker = false
                                }) {
                                    Text(stringResource(R.string.btn_create), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSetPicker = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

@Composable
private fun RelaySetFeedBar(
    relaySet: RelaySet,
    relayFeedStatus: RelayFeedStatus
) {
    val statusColor = when (relayFeedStatus) {
        is RelayFeedStatus.Streaming -> WispThemeColors.repostColor
        is RelayFeedStatus.Connecting, is RelayFeedStatus.Subscribing -> WispThemeColors.paidColor
        is RelayFeedStatus.Disconnected, is RelayFeedStatus.ConnectionFailed,
        is RelayFeedStatus.TimedOut -> androidx.compose.ui.graphics.Color(0xFFFF5252)
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
                // Status dot
                if (relayFeedStatus !is RelayFeedStatus.Idle) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.size(10.dp)
                    ) {
                        drawCircle(color = statusColor, radius = size.minDimension / 2)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = relaySet.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${relaySet.relays.size} relays",
                        style = MaterialTheme.typography.labelSmall,
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

@Composable
fun BroadcastStatusBar(
    broadcastState: BroadcastState?,
    powStatus: PowStatus = PowStatus.Idle,
    onCancelMining: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val showPow = powStatus !is PowStatus.Idle
    val showBroadcast = broadcastState != null && !showPow

    AnimatedVisibility(
        visible = showPow || showBroadcast,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (powStatus) {
                    is PowStatus.Mining -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.broadcast_mining, powStatus.attempts / 1000),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onCancelMining != null) {
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = onCancelMining,
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.broadcast_cancel_mining),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    is PowStatus.Done -> {
                        Text(
                            text = powStatus.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is PowStatus.Failed -> {
                        Text(
                            text = powStatus.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is PowStatus.Idle -> {
                        // Show broadcast state
                        val state = broadcastState ?: return@Row
                        if (state.accepted < state.sent) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (state.accepted < state.sent) {
                                stringResource(R.string.broadcast_broadcasting, state.accepted, state.sent)
                            } else {
                                if (state.accepted != 1) stringResource(R.string.broadcast_published_plural, state.accepted)
                                else stringResource(R.string.broadcast_published, state.accepted)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatFeedArticleTimestamp(epoch: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - epoch
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            .format(java.util.Date(epoch * 1000))
    }
}
