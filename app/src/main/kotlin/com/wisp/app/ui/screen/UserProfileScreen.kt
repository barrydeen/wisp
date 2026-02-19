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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import com.wisp.app.ui.component.FollowButton
import com.wisp.app.ui.component.FullScreenImageViewer
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.ZapDialog
import com.wisp.app.viewmodel.UserProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

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
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onZap: (NostrEvent, Long, String) -> Unit = { _, _, _ -> },
    userPubkey: String? = null,
    isWalletConnected: Boolean = false,
    onWallet: () -> Unit = {},
    zapSuccess: SharedFlow<String>? = null,
    zapError: SharedFlow<String>? = null,
    ownLists: List<FollowSet> = emptyList(),
    onAddToList: ((String, String) -> Unit)? = null,
    onRemoveFromList: ((String, String) -> Unit)? = null,
    onCreateList: ((String) -> Unit)? = null,
    profilePubkey: String = ""
) {
    val profile by viewModel.profile.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    val rootNotes by viewModel.rootNotes.collectAsState()
    val replies by viewModel.replies.collectAsState()
    val followList by viewModel.followList.collectAsState()
    val relayList by viewModel.relayList.collectAsState()
    val followProfileVersion by viewModel.followProfileVersion.collectAsState()
    val myFollowList by contactRepo.followList.collectAsState()

    val reactionVersion by eventRepo?.reactionVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val replyCountVersion by eventRepo?.replyCountVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val zapVersion by eventRepo?.zapVersion?.collectAsState() ?: remember { mutableIntStateOf(0) }

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

    var showAddToListDialog by remember { mutableStateOf(false) }

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
                    if (!isOwnProfile) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
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
                ProfileHeader(
                    profile = profile,
                    isOwnProfile = isOwnProfile,
                    isFollowing = isFollowing,
                    onEditProfile = onEditProfile,
                    onToggleFollow = { viewModel.toggleFollow(contactRepo, relayPool) }
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
                    if (rootNotes.isEmpty()) {
                        item { EmptyTabContent("No notes yet") }
                    } else {
                        items(items = rootNotes, key = { it.id }) { event ->
                            val likeCount = reactionVersion.let { eventRepo?.getReactionCount(event.id) ?: 0 }
                            val replyCount = replyCountVersion.let { eventRepo?.getReplyCount(event.id) ?: 0 }
                            val zapSats = zapVersion.let { eventRepo?.getZapSats(event.id) ?: 0L }
                            val userEmoji = reactionVersion.let { userPubkey?.let { eventRepo?.getUserReactionEmoji(event.id, it) } }
                            val repostPubkey = viewModel.repostAuthors[event.id]
                            val repostedByName = repostPubkey?.let { pk ->
                                eventRepo?.getProfileData(pk)?.displayString
                                    ?: pk.take(8) + "..."
                            }
                            PostCard(
                                event = event,
                                profile = if (repostPubkey != null) eventRepo?.getProfileData(event.pubkey) else profile,
                                onReply = { onReply(event) },
                                onNavigateToProfile = onNavigateToProfile,
                                onNoteClick = { onNoteClick(event) },
                                onReact = { emoji -> onReact(event, emoji) },
                                userReactionEmoji = userEmoji,
                                onZap = { zapTargetEvent = event },
                                likeCount = likeCount,
                                replyCount = replyCount,
                                zapSats = zapSats,
                                isZapAnimating = event.id in zapAnimatingIds,
                                eventRepo = eventRepo,
                                repostedBy = repostedByName
                            )
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
                            val userEmoji = reactionVersion.let { userPubkey?.let { eventRepo?.getUserReactionEmoji(event.id, it) } }
                            PostCard(
                                event = event,
                                profile = profile,
                                onReply = { onReply(event) },
                                onNavigateToProfile = onNavigateToProfile,
                                onNoteClick = { onNoteClick(event) },
                                onReact = { emoji -> onReact(event, emoji) },
                                userReactionEmoji = userEmoji,
                                onZap = { zapTargetEvent = event },
                                likeCount = likeCount,
                                replyCount = replyCount,
                                zapSats = zapSats,
                                isZapAnimating = event.id in zapAnimatingIds,
                                eventRepo = eventRepo
                            )
                        }
                    }
                }
                2 -> {
                    if (followList.isEmpty()) {
                        item { EmptyTabContent("Not following anyone") }
                    } else {
                        // Read followProfileVersion to trigger recomposition on profile loads
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
                    if (relayList.isEmpty()) {
                        item { EmptyTabContent("No relay list published") }
                    } else {
                        items(items = relayList, key = { it.url }) { relay ->
                            RelayRow(relay)
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
    onToggleFollow: () -> Unit
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
                FollowButton(
                    isFollowing = isFollowing,
                    onClick = onToggleFollow
                )
            }
        }

        Text(
            text = profile?.displayString ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        profile?.nip05?.let { nip05 ->
            Text(
                text = nip05,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        profile?.about?.let { about ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
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
