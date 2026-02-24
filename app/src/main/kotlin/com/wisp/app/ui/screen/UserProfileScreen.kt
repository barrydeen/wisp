package com.wisp.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.Nip05Status
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.ui.component.FollowButton
import com.wisp.app.ui.component.FullScreenImageViewer
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.QrCodeDialog
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.viewmodel.UserProfileViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UserProfileScreen(
    viewModel: UserProfileViewModel,
    contactRepo: ContactRepository,
    relayPool: RelayPool,
    onBack: () -> Unit,
    onReply: (NostrEvent) -> Unit = {},
    eventRepo: EventRepository? = null,
    onNavigateToProfile: ((String) -> Unit)? = null,
    onToggleFollow: ((String) -> Unit)? = null,
    isOwnProfile: Boolean = false,
    onEditProfile: () -> Unit = {},
    isBlocked: Boolean = false,
    onBlockUser: (() -> Unit)? = null,
    onUnblockUser: (() -> Unit)? = null,
    onNoteClick: (NostrEvent) -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onZap: (NostrEvent, Long, String) -> Unit = { _, _, _ -> },
    userPubkey: String? = null,
    isWalletConnected: Boolean = false,
    onWallet: () -> Unit = {},
    zapSuccess: SharedFlow<String>? = null,
    zapError: SharedFlow<String>? = null,
    zapInProgressIds: Set<String> = emptySet(),
    ownLists: List<FollowSet> = emptyList(),
    onAddToList: ((String, String) -> Unit)? = null,
    onRemoveFromList: ((String, String) -> Unit)? = null,
    onCreateList: ((String) -> Unit)? = null,
    profilePubkey: String = "",
    relayInfoRepo: RelayInfoRepository? = null,
    nip05Repo: Nip05Repository? = null,
    listedIds: Set<String> = emptySet(),
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
    onDeleteEvent: (String, Int) -> Unit = { _, _ -> },
    onAddNoteToList: (String) -> Unit = {},
    onSendDm: (() -> Unit)? = null,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val profile by viewModel.profile.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    val rootNotes by viewModel.rootNotes.collectAsState()
    val replies by viewModel.replies.collectAsState()
    val followList by viewModel.followList.collectAsState()
    val relayList by viewModel.relayList.collectAsState()
    val relayHints by viewModel.relayHints.collectAsState()
    val followedBy by viewModel.followedBy.collectAsState()
    val followProfileVersion by viewModel.followProfileVersion.collectAsState()
    val myFollowList by contactRepo.followList.collectAsState()

    val nip05Version by nip05Repo?.version?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val reactionVersion by eventRepo?.reactionVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val replyCountVersion by eventRepo?.replyCountVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val zapVersion by eventRepo?.zapVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val relaySourceVersion by eventRepo?.relaySourceVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }

    var zapTargetEvent by remember { mutableStateOf<NostrEvent?>(null) }
    var zapAnimatingIds by remember { mutableStateOf(emptySet<String>()) }
    var zapErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        zapSuccess?.collect { eventId ->
            zapAnimatingIds = zapAnimatingIds + eventId
            delay(1500)
            zapAnimatingIds = zapAnimatingIds - eventId
        }
    }

    LaunchedEffect(Unit) {
        zapError?.collect { error ->
            zapErrorMessage = error
        }
    }

    if (zapTargetEvent != null) {
        ZapDialog(
            isWalletConnected = isWalletConnected,
            onDismiss = { zapTargetEvent = null },
            onZap = { amountMsats, message ->
                val event = zapTargetEvent ?: return@ZapDialog
                zapTargetEvent = null
                onZap(event, amountMsats, message)
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

    var showQrDialog by remember { mutableStateOf(false) }
    var showAddToListDialog by remember { mutableStateOf(false) }

    if (showQrDialog) {
        QrCodeDialog(
            pubkeyHex = profilePubkey,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showAddToListDialog && !isOwnProfile) {
        AddToListDialog(
            pubkey = profilePubkey,
            ownLists = ownLists,
            onAddToList = { dTag -> onAddToList?.invoke(dTag, profilePubkey) },
            onRemoveFromList = { dTag -> onRemoveFromList?.invoke(dTag, profilePubkey) },
            onCreateList = onCreateList,
            onDismiss = { showAddToListDialog = false }
        )
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("Notes", "Replies", "Following", "Relays")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        profile?.displayString ?: "Profile",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    val context = LocalContext.current
                    IconButton(onClick = { showQrDialog = true }) {
                        Icon(Icons.Default.QrCode2, "QR Code")
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Profile JSON") },
                            onClick = {
                                menuExpanded = false
                                profile?.let { p ->
                                    val json = buildJsonObject {
                                        p.name?.let { put("name", it) }
                                        p.displayName?.let { put("display_name", it) }
                                        p.about?.let { put("about", it) }
                                        p.picture?.let { put("picture", it) }
                                        p.banner?.let { put("banner", it) }
                                        p.nip05?.let { put("nip05", it) }
                                        p.lud16?.let { put("lud16", it) }
                                    }.toString()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Profile JSON", json))
                                    Toast.makeText(context, "Profile JSON copied", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        if (!isOwnProfile) {
                            DropdownMenuItem(
                                text = { Text("Add to List") },
                                onClick = {
                                    menuExpanded = false
                                    showAddToListDialog = true
                                }
                            )
                            if (isBlocked) {
                                DropdownMenuItem(
                                    text = { Text("Unblock") },
                                    onClick = {
                                        menuExpanded = false
                                        onUnblockUser?.invoke()
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Block") },
                                    onClick = {
                                        menuExpanded = false
                                        onBlockUser?.invoke()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                @Suppress("UNUSED_EXPRESSION")
                nip05Version
                ProfileHeader(
                    profile = profile,
                    isOwnProfile = isOwnProfile,
                    isFollowing = isFollowing,
                    onEditProfile = onEditProfile,
                    onToggleFollow = { viewModel.toggleFollow(contactRepo, relayPool, signer) },
                    nip05Repo = nip05Repo,
                    pubkey = profilePubkey,
                    eventRepo = eventRepo,
                    onNavigateToProfile = onNavigateToProfile,
                    onSendDm = onSendDm,
                    followingCount = followList.size,
                    followedBy = followedBy,
                    followsYou = !isOwnProfile && userPubkey != null && followList.any { it.pubkey == userPubkey }
                )
            }

            stickyHeader {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    // Pinned notes at the top of the Notes tab
                    val pinnedEvents = pinnedIds.mapNotNull { id -> eventRepo?.getEvent(id) }
                        .sortedByDescending { it.created_at }
                    if (pinnedEvents.isNotEmpty()) {
                        item {
                            Text(
                                "Pinned",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        items(items = pinnedEvents, key = { "pinned-${it.id}" }) { event ->
                            val eventProfile = eventRepo?.getProfileData(event.pubkey)
                            PostCard(
                                event = event,
                                profile = eventProfile,
                                onReply = { onReply(event) },
                                onNavigateToProfile = onNavigateToProfile,
                                onNoteClick = { onNoteClick(event) },
                                onQuotedNoteClick = onQuotedNoteClick,
                                onReact = { emoji -> onReact(event, emoji) },
                                eventRepo = eventRepo,
                                onAddToList = { onAddNoteToList(event.id) },
                                isInList = event.id in listedIds,
                                onPin = { onTogglePin(event.id) },
                                isPinned = true,
                                onDelete = { onDeleteEvent(event.id, event.kind) },
                                isOwnEvent = event.pubkey == userPubkey
                            )
                        }
                    }

                    if (rootNotes.isEmpty() && pinnedEvents.isEmpty()) {
                        item { EmptyTabContent("No notes yet") }
                    } else {
                        items(items = rootNotes, key = { it.id }) { event ->
                            val likeCount = reactionVersion.let { eventRepo?.getReactionCount(event.id) ?: 0 }
                            val replyCount = replyCountVersion.let { eventRepo?.getReplyCount(event.id) ?: 0 }
                            val zapSats = zapVersion.let { eventRepo?.getZapSats(event.id) ?: 0L }
                            val userEmojis = reactionVersion.let { userPubkey?.let { eventRepo?.getUserReactionEmojis(event.id, it) } ?: emptySet() }
                            val reactionDetails = remember(reactionVersion, event.id) {
                                eventRepo?.getReactionDetails(event.id) ?: emptyMap()
                            }
                            val zapDetails = remember(zapVersion, event.id) {
                                eventRepo?.getZapDetails(event.id) ?: emptyList()
                            }
                            val relayIcons = remember(relaySourceVersion, event.id) {
                                eventRepo?.getEventRelays(event.id)?.map { url ->
                                    url to relayInfoRepo?.getIconUrl(url)
                                } ?: emptyList()
                            }
                            val repostPubkey = viewModel.repostAuthors[event.id]
                            val repostedByName = repostPubkey?.let { pk ->
                                eventRepo?.getProfileData(pk)?.displayString
                                    ?: pk.take(8) + "..."
                            }
                            val hasUserReposted = eventRepo?.hasUserReposted(event.id) == true
                            val hasUserZapped = zapVersion.let { eventRepo?.hasUserZapped(event.id) == true }
                            val eventReactionEmojiUrls = reactionVersion.let { eventRepo?.getReactionEmojiUrls(event.id) ?: emptyMap() }
                            PostCard(
                                event = event,
                                profile = if (repostPubkey != null) eventRepo?.getProfileData(event.pubkey) else profile,
                                onReply = { onReply(event) },
                                onNavigateToProfile = onNavigateToProfile,
                                onNoteClick = { onNoteClick(event) },
                                onQuotedNoteClick = onQuotedNoteClick,
                                onReact = { emoji -> onReact(event, emoji) },
                                userReactionEmojis = userEmojis,
                                hasUserReposted = hasUserReposted,
                                onZap = { zapTargetEvent = event },
                                hasUserZapped = hasUserZapped,
                                likeCount = likeCount,
                                replyCount = replyCount,
                                zapSats = zapSats,
                                isZapAnimating = event.id in zapAnimatingIds,
                                isZapInProgress = event.id in zapInProgressIds,
                                eventRepo = eventRepo,
                                repostedBy = repostedByName,
                                reactionDetails = reactionDetails,
                                zapDetails = zapDetails,
                                reactionEmojiUrls = eventReactionEmojiUrls,
                                relayIcons = relayIcons,
                                onNavigateToProfileFromDetails = onNavigateToProfile,
                                onFollowAuthor = { onToggleFollow?.invoke(event.pubkey) },
                                onBlockAuthor = { onBlockUser?.invoke() },
                                isFollowingAuthor = contactRepo.isFollowing(event.pubkey),
                                isOwnEvent = event.pubkey == userPubkey,
                                nip05Repo = nip05Repo,
                                onAddToList = { onAddNoteToList(event.id) },
                                isInList = event.id in listedIds,
                                onPin = { onTogglePin(event.id) },
                                isPinned = event.id in pinnedIds,
                                onDelete = { onDeleteEvent(event.id, event.kind) }
                            )
                        }
                        if (rootNotes.isNotEmpty()) {
                            item {
                                LoadMoreButton(onClick = { viewModel.loadMoreNotes() })
                            }
                        }
                    }
                }
                1 -> {
                    if (replies.isEmpty()) {
                        item { EmptyTabContent("No replies yet") }
                    } else {
                        items(items = replies, key = { it.id }) { event ->
                            val likeCount = reactionVersion.let { eventRepo?.getReactionCount(event.id) ?: 0 }
                            val replyCount = replyCountVersion.let { eventRepo?.getReplyCount(event.id) ?: 0 }
                            val zapSats = zapVersion.let { eventRepo?.getZapSats(event.id) ?: 0L }
                            val userEmojis = reactionVersion.let { userPubkey?.let { eventRepo?.getUserReactionEmojis(event.id, it) } ?: emptySet() }
                            val reactionDetails = remember(reactionVersion, event.id) {
                                eventRepo?.getReactionDetails(event.id) ?: emptyMap()
                            }
                            val zapDetails = remember(zapVersion, event.id) {
                                eventRepo?.getZapDetails(event.id) ?: emptyList()
                            }
                            val relayIcons = remember(relaySourceVersion, event.id) {
                                eventRepo?.getEventRelays(event.id)?.map { url ->
                                    url to relayInfoRepo?.getIconUrl(url)
                                } ?: emptyList()
                            }
                            val hasUserReposted2 = eventRepo?.hasUserReposted(event.id) == true
                            val hasUserZapped2 = zapVersion.let { eventRepo?.hasUserZapped(event.id) == true }
                            val eventReactionEmojiUrls2 = reactionVersion.let { eventRepo?.getReactionEmojiUrls(event.id) ?: emptyMap() }
                            PostCard(
                                event = event,
                                profile = profile,
                                onReply = { onReply(event) },
                                onNavigateToProfile = onNavigateToProfile,
                                onNoteClick = { onNoteClick(event) },
                                onQuotedNoteClick = onQuotedNoteClick,
                                onReact = { emoji -> onReact(event, emoji) },
                                userReactionEmojis = userEmojis,
                                hasUserReposted = hasUserReposted2,
                                onZap = { zapTargetEvent = event },
                                hasUserZapped = hasUserZapped2,
                                likeCount = likeCount,
                                replyCount = replyCount,
                                zapSats = zapSats,
                                isZapAnimating = event.id in zapAnimatingIds,
                                isZapInProgress = event.id in zapInProgressIds,
                                eventRepo = eventRepo,
                                reactionDetails = reactionDetails,
                                zapDetails = zapDetails,
                                reactionEmojiUrls = eventReactionEmojiUrls2,
                                relayIcons = relayIcons,
                                onNavigateToProfileFromDetails = onNavigateToProfile,
                                onFollowAuthor = { onToggleFollow?.invoke(event.pubkey) },
                                onBlockAuthor = { onBlockUser?.invoke() },
                                isFollowingAuthor = contactRepo.isFollowing(event.pubkey),
                                isOwnEvent = event.pubkey == userPubkey,
                                nip05Repo = nip05Repo,
                                onAddToList = { onAddNoteToList(event.id) },
                                isInList = event.id in listedIds,
                                onPin = { onTogglePin(event.id) },
                                isPinned = event.id in pinnedIds,
                                onDelete = { onDeleteEvent(event.id, event.kind) }
                            )
                        }
                        item {
                            LoadMoreButton(onClick = { viewModel.loadMoreReplies() })
                        }
                    }
                }
                2 -> {
                    if (followList.isEmpty()) {
                        item { EmptyTabContent("Not following anyone") }
                    } else {
                        @Suppress("UNUSED_EXPRESSION")
                        followProfileVersion
                        items(items = followList, key = { it.pubkey }) { entry ->
                            val isEntryFollowed = myFollowList.any { it.pubkey == entry.pubkey }
                            FollowEntryRow(
                                entry = entry,
                                eventRepo = eventRepo,
                                isFollowing = isEntryFollowed,
                                isOwnProfile = isOwnProfile,
                                onToggleFollow = { onToggleFollow?.invoke(entry.pubkey) },
                                onClick = { onNavigateToProfile?.invoke(entry.pubkey) }
                            )
                        }
                    }
                }
                3 -> {
                    if (relayList.isEmpty() && relayHints.isEmpty()) {
                        item { EmptyTabContent("No relay list published") }
                    } else {
                        if (relayList.isNotEmpty()) {
                            item {
                                SectionLabel("Relay List (NIP-65)")
                            }
                            items(items = relayList, key = { it.url }) { relay ->
                                RelayRow(relay)
                            }
                        }
                        // Show relay hints (discovered from events) that aren't already in the relay list
                        val relayListUrls = relayList.map { it.url }.toSet()
                        val extraHints = relayHints.filter { it !in relayListUrls }.sorted()
                        if (extraHints.isNotEmpty()) {
                            item {
                                SectionLabel(if (relayList.isEmpty()) "Discovered Relays" else "Other Discovered Relays")
                            }
                            items(items = extraHints, key = { it }) { url ->
                                HintRelayRow(url)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profile: ProfileData?,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    onEditProfile: () -> Unit,
    onToggleFollow: () -> Unit,
    nip05Repo: Nip05Repository? = null,
    pubkey: String = "",
    eventRepo: EventRepository? = null,
    onNavigateToProfile: ((String) -> Unit)? = null,
    onSendDm: (() -> Unit)? = null,
    followingCount: Int = 0,
    followedBy: List<String> = emptyList(),
    followsYou: Boolean = false
) {
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    if (fullScreenImageUrl != null) {
        FullScreenImageViewer(
            imageUrl = fullScreenImageUrl!!,
            onDismiss = { fullScreenImageUrl = null }
        )
    }

    // Banner
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        profile?.banner?.let { bannerUrl ->
            AsyncImage(
                model = bannerUrl,
                contentDescription = "Banner",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { fullScreenImageUrl = bannerUrl }
            )
        }
    }

    // Profile info overlapping banner
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.offset(y = (-16).dp)
        ) {
            ProfilePicture(
                url = profile?.picture,
                size = 72,
                onClick = profile?.picture?.let { url -> { fullScreenImageUrl = url } }
            )
            Spacer(Modifier.weight(1f))
            if (isOwnProfile) {
                OutlinedButton(onClick = onEditProfile) {
                    Text("Edit Profile")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onSendDm != null) {
                        IconButton(onClick = onSendDm) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    FollowButton(
                        isFollowing = isFollowing,
                        onClick = onToggleFollow
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = profile?.displayString ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (followsYou) {
                Text(
                    text = "Follows you",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        profile?.nip05?.let { nip05 ->
            if (pubkey.isNotEmpty()) nip05Repo?.checkOrFetch(pubkey, nip05)
            val status = if (pubkey.isNotEmpty()) nip05Repo?.getStatus(pubkey) else null
            val isImpersonator = status == Nip05Status.IMPERSONATOR
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isImpersonator) "\u2715 $nip05" else nip05,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isImpersonator) Color.Red else MaterialTheme.colorScheme.primary
                )
                if (status == Nip05Status.VERIFIED) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Verified",
                        tint = Color(0xFFFF8C00),
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (status == Nip05Status.ERROR) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry verification",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { nip05Repo?.retry(pubkey) }
                    )
                }
            }
        }

        profile?.about?.let { about ->
            Spacer(Modifier.height(8.dp))
            RichContent(
                content = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                eventRepo = eventRepo,
                onProfileClick = onNavigateToProfile
            )
        }

        profile?.lud16?.let { lightning ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ElectricBolt,
                    contentDescription = "Lightning address",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = lightning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Following / Followers in your network counts
        if (followingCount > 0 || followedBy.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (followingCount > 0) {
                    Text(
                        text = "$followingCount",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "Following",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (followingCount > 0 && followedBy.isNotEmpty()) {
                    Spacer(Modifier.width(16.dp))
                }
                if (followedBy.isNotEmpty()) {
                    Text(
                        text = "${followedBy.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "Followers in your network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Followed-by avatars row
        if (followedBy.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val displayPubkeys = followedBy.take(10)
            val othersCount = followedBy.size - displayPubkeys.size
            val avatarSize = 22
            val overlap = 16
            val boxWidth = (avatarSize + (displayPubkeys.size - 1) * overlap).dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(boxWidth).height(avatarSize.dp)) {
                    displayPubkeys.forEachIndexed { index, pk ->
                        Box(modifier = Modifier.offset(x = (index * overlap).dp)) {
                            ProfilePicture(
                                url = eventRepo?.getProfileData(pk)?.picture,
                                size = avatarSize,
                                onClick = { onNavigateToProfile?.invoke(pk) }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (othersCount > 0) {
                    Text(
                        text = "+$othersCount others in your network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FollowEntryRow(
    entry: Nip02.FollowEntry,
    eventRepo: EventRepository?,
    isFollowing: Boolean,
    isOwnProfile: Boolean,
    onToggleFollow: () -> Unit,
    onClick: () -> Unit
) {
    val profile = eventRepo?.getProfileData(entry.pubkey)
    val displayName = profile?.displayString
        ?: entry.pubkey.take(8) + "..." + entry.pubkey.takeLast(4)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        ProfilePicture(url = profile?.picture, size = 40)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.petname != null) {
                Text(
                    text = entry.petname,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!isOwnProfile) {
            Spacer(Modifier.width(8.dp))
            FollowButton(
                isFollowing = isFollowing,
                onClick = onToggleFollow
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayRow(relay: RelayConfig) {
    val displayUrl = relay.url
        .removePrefix("wss://")
        .removePrefix("ws://")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = displayUrl,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (relay.read) {
                Text(
                    text = "R",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (relay.write) {
                Text(
                    text = "W",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun HintRelayRow(url: String) {
    val displayUrl = url
        .removePrefix("wss://")
        .removePrefix("ws://")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = displayUrl,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "hint",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AddToListDialog(
    pubkey: String,
    ownLists: List<FollowSet>,
    onAddToList: (String) -> Unit,
    onRemoveFromList: (String) -> Unit,
    onCreateList: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    var newListName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to List") },
        text = {
            Column {
                if (ownLists.isEmpty()) {
                    Text(
                        "No lists yet. Create one below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    ownLists.forEach { list ->
                        val isMember = pubkey in list.members
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMember) onRemoveFromList(list.dTag)
                                    else onAddToList(list.dTag)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isMember,
                                onCheckedChange = {
                                    if (isMember) onRemoveFromList(list.dTag)
                                    else onAddToList(list.dTag)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
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
                Spacer(Modifier.height(12.dp))
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
                                onCreateList?.invoke(newListName.trim())
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {}
    )
}

@Composable
private fun LoadMoreButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        OutlinedButton(onClick = onClick) {
            Text("Load More")
        }
    }
}

@Composable
private fun EmptyTabContent(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
