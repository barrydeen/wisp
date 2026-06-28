package cooking.zap.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.DmConversation
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.GroupRoom
import cooking.zap.app.ui.component.GroupCard
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.viewmodel.DmListViewModel
import cooking.zap.app.viewmodel.GroupListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmListScreen(
    viewModel: DmListViewModel,
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    userPubkey: String? = null,
    signer: NostrSigner? = null,
    onBack: (() -> Unit)? = null,
    onConversation: (DmConversation) -> Unit,
    onNewGroupDm: () -> Unit = {},
    onGroupRoom: (relayUrl: String, groupId: String) -> Unit = { _, _ -> }
) {
    val conversations by viewModel.conversationList.collectAsState()
    val groups by groupListViewModel.groups.collectAsState()

    // Tab 0 = Chat Rooms (groups) — the primary, default-selected tab.
    // Tab 1 = Direct Messages. rememberSaveable restores the last-viewed
    // tab across configuration changes and saved-state restoration; a fresh
    // composition (no saved state) defaults to Groups.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDiscoverSheet by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<GroupListViewModel.JoinError?>(null) }
    var adminError by remember { mutableStateOf<GroupListViewModel.AdminError?>(null) }

    // Collect one-shot join rejections from the relay and surface them as a dialog.
    LaunchedEffect(groupListViewModel) {
        groupListViewModel.joinErrors.collect { joinError = it }
    }
    // Admin-action rejections (create group / invite / edit / kick / leave / delete) that
    // would otherwise fail silently. A shared dialog tells the admin what the relay said.
    LaunchedEffect(groupListViewModel) {
        groupListViewModel.adminErrors.collect { adminError = it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_chat)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            Box {
                cooking.zap.app.ui.component.ZapGradientFab(
                    onClick = {
                        if (selectedTab == 1) onNewGroupDm()
                        else showFabMenu = true
                    },
                    contentDescription = null
                ) {
                    if (selectedTab == 1) {
                        Icon(Icons.Outlined.GroupAdd, contentDescription = stringResource(R.string.cd_new_group_dm), tint = Color.White)
                    } else {
                        Icon(painterResource(R.drawable.ic_fab_plus), contentDescription = stringResource(R.string.cd_group_actions), tint = Color.White)
                    }
                }
                if (selectedTab == 0) {
                    DropdownMenu(
                        expanded = showFabMenu,
                        onDismissRequest = { showFabMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_discover_groups)) },
                            onClick = { showFabMenu = false; showDiscoverSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_join_group)) },
                            onClick = { showFabMenu = false; showJoinDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_create_group)) },
                            onClick = { showFabMenu = false; showCreateDialog = true }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    // Dismiss the group-actions overflow so it doesn't
                    // re-open when returning to this tab.
                    onClick = { selectedTab = 0; showFabMenu = false },
                    text = { Text(stringResource(R.string.tab_chat_rooms)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; showFabMenu = false },
                    text = { Text(stringResource(R.string.tab_direct_messages)) }
                )
            }

            val unreadGroups by groupListViewModel.unreadGroups.collectAsState()
            val notifiedGroups by groupListViewModel.notifiedGroups.collectAsState()

            when (selectedTab) {
                0 -> GroupListContent(
                    groups = groups,
                    eventRepo = eventRepo,
                    onGroupRoom = onGroupRoom,
                    unreadGroups = unreadGroups,
                    notifiedGroups = notifiedGroups,
                    onToggleNotify = { relayUrl, groupId, enabled ->
                        groupListViewModel.setGroupNotified(relayUrl, groupId, enabled)
                    }
                )
                1 -> DmListContent(conversations, eventRepo, onConversation)
            }
        }
    }

    if (showJoinDialog) {
        JoinGroupDialog(
            onDismiss = { showJoinDialog = false },
            onJoin = { relayUrl, groupId, inviteCode ->
                showJoinDialog = false
                groupListViewModel.joinGroup(relayUrl, groupId, signer, inviteCode)
            }
        )
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { relayUrl, name, about, isPrivate ->
                showCreateDialog = false
                groupListViewModel.createGroup(relayUrl, name, signer,
                    about = about,
                    isPrivate = isPrivate)
            }
        )
    }

    if (showDiscoverSheet) {
        DiscoverGroupsSheet(
            groupListViewModel = groupListViewModel,
            eventRepo = eventRepo,
            onDismiss = { showDiscoverSheet = false },
            onJoin = { relayUrl, groupId ->
                showDiscoverSheet = false
                groupListViewModel.joinGroup(relayUrl, groupId, signer)
            }
        )
    }

    joinError?.let { err ->
        AlertDialog(
            onDismissRequest = { joinError = null },
            title = { Text(stringResource(R.string.title_join_rejected)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.msg_join_rejected_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (err.message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Relay said: ${err.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { joinError = null }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }

    adminError?.let { err ->
        // A create rejection citing membership/restriction is the relay's "active members only"
        // gate — surface a clean, actionable line instead of the raw relay string.
        val isCreateMembersOnly = err.action.startsWith("createGroup") &&
            err.message.contains("member", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { adminError = null },
            title = { Text(stringResource(R.string.title_action_failed)) },
            text = {
                Column {
                    if (isCreateMembersOnly) {
                        Text(
                            stringResource(R.string.msg_create_members_only),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "The relay rejected this action.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Step: ${err.action}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // For the members-only create case the clean message above stands on its own;
                    // only append the raw relay string for the generic rejection path.
                    if (!isCreateMembersOnly && err.message.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Relay said: ${err.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { adminError = null }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }
}

@Composable
private fun DmListContent(
    conversations: List<DmConversation>,
    eventRepo: EventRepository,
    onConversation: (DmConversation) -> Unit
) {
    if (conversations.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                stringResource(R.string.error_no_messages),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.error_send_message_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = conversations, key = { it.conversationKey }, contentType = { "conversation" }) { convo ->
                ConversationRow(
                    convo = convo,
                    eventRepo = eventRepo,
                    onClick = { onConversation(convo) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupListContent(
    groups: List<GroupRoom>,
    eventRepo: EventRepository,
    onGroupRoom: (relayUrl: String, groupId: String) -> Unit,
    unreadGroups: Set<String> = emptySet(),
    notifiedGroups: Set<String> = emptySet(),
    onToggleNotify: (relayUrl: String, groupId: String, enabled: Boolean) -> Unit = { _, _, _ -> }
) {
    if (groups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                stringResource(R.string.error_no_chat_rooms),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.error_join_room_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = groups, key = { "${it.relayUrl}|${it.groupId}" }) { room ->
                val key = "${room.relayUrl}|${room.groupId}"
                GroupRoomRow(
                    room = room,
                    hasUnread = key in unreadGroups,
                    isNotified = key in notifiedGroups,
                    onToggleNotify = { enabled -> onToggleNotify(room.relayUrl, room.groupId, enabled) },
                    onClick = { onGroupRoom(room.relayUrl, room.groupId) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupRoomRow(
    room: GroupRoom,
    hasUnread: Boolean = false,
    isNotified: Boolean = false,
    onToggleNotify: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val lastMsg = room.messages.lastOrNull()
    val displayName = room.metadata?.name ?: room.groupId.ifEmpty { room.relayUrl }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        IconButton(
            onClick = { onToggleNotify(!isNotified) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isNotified) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                contentDescription = stringResource(
                    if (isNotified) R.string.cd_unmute_notifications else R.string.cd_mute_notifications
                ),
                tint = if (isNotified) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Box {
            ProfilePicture(url = room.metadata?.picture, size = 40)
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = lastMsg?.content ?: room.metadata?.about ?: room.relayUrl
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (room.lastMessageAt > 0L) {
            Text(
                text = formatTimestamp(room.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoin: (relayUrl: String, groupId: String, inviteCode: String?) -> Unit
) {
    var inviteLink by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_join_chat_room)) },
        text = {
            Column {
                OutlinedTextField(
                    value = inviteLink,
                    onValueChange = { inviteLink = it; showError = false },
                    label = { Text(stringResource(R.string.label_invite_link)) },
                    placeholder = { Text("pantry.zap.cooking'roomid") },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.error_invalid_invite_link)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = cooking.zap.app.nostr.Nip29.parseInviteLink(inviteLink.trim())
                    if (parsed != null) {
                        onJoin(parsed.first, parsed.second, parsed.third)
                    } else {
                        showError = true
                    }
                }
            ) {
                Text(stringResource(R.string.action_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (
        relayUrl: String,
        name: String,
        about: String,
        isPrivate: Boolean
    ) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(cooking.zap.app.nostr.Nip29.DEFAULT_GROUP_RELAYS.first()) }
    // Default to Public. Rooms are always closed (invite/approval join) regardless of this choice;
    // the toggle only controls discoverability + read access (see createGroup).
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_create_group)) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text(stringResource(R.string.label_group_about)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text(stringResource(R.string.label_relay_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.label_room_access),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                AccessLevelOption(
                    label = stringResource(R.string.room_access_public_label),
                    description = stringResource(R.string.room_access_public_desc),
                    selected = !isPrivate,
                    onSelect = { isPrivate = false }
                )
                AccessLevelOption(
                    label = stringResource(R.string.room_access_private_label),
                    description = stringResource(R.string.room_access_private_desc),
                    selected = isPrivate,
                    onSelect = { isPrivate = true }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val url = relayUrl.trim()
                    if (url.isNotEmpty()) onCreate(
                        url, groupName.trim(), about.trim(), isPrivate
                    )
                }
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun AccessLevelOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationRow(
    convo: DmConversation,
    eventRepo: EventRepository,
    onClick: () -> Unit
) {
    val lastMsg = convo.messages.lastOrNull()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (convo.isGroup) {
            GroupAvatarCluster(participants = convo.participants, eventRepo = eventRepo)
        } else {
            val profile = remember(convo.peerPubkey) { eventRepo.getProfileData(convo.peerPubkey) }
            ProfilePicture(url = profile?.picture)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val displayName = if (convo.isGroup) {
                convo.participants.take(3).joinToString(", ") { pk ->
                    eventRepo.getProfileData(pk)?.displayString ?: pk.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" }
                }.let { if (convo.participants.size > 3) "$it +${convo.participants.size - 3}" else it }
            } else {
                eventRepo.getProfileData(convo.peerPubkey)?.displayString
                    ?: convo.peerPubkey.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" }
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            lastMsg?.let {
                Text(
                    text = it.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = formatTimestamp(convo.lastMessageAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupAvatarCluster(participants: List<String>, eventRepo: EventRepository) {
    Box(modifier = Modifier.size(48.dp)) {
        participants.take(3).forEachIndexed { i, pubkey ->
            val profile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
            ProfilePicture(
                url = profile?.picture,
                size = 28,
                modifier = Modifier
                    .align(
                        when (i) {
                            0 -> Alignment.TopStart
                            1 -> Alignment.TopEnd
                            else -> Alignment.BottomCenter
                        }
                    )
                    .offset(
                        x = when (i) { 1 -> 4.dp; 2 -> 2.dp; else -> 0.dp },
                        y = when (i) { 2 -> 4.dp; else -> 0.dp }
                    )
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MMM d", Locale.US)
private val timeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = Date(epoch * 1000)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    return if (cal.get(java.util.Calendar.YEAR) != currentYear) {
        timeYearFormat.format(date)
    } else {
        timeFormat.format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverGroupsSheet(
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    onDismiss: () -> Unit,
    onJoin: (relayUrl: String, groupId: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val discoveredGroups by groupListViewModel.discoveredGroups.collectAsState()
    val isLoading by groupListViewModel.discoveryLoading.collectAsState()

    LaunchedEffect(Unit) {
        groupListViewModel.discoverGroups()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.title_discover_groups),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (isLoading && discoveredGroups.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp)
                ) {
                    CircularProgressIndicator()
                }
            } else if (discoveredGroups.isEmpty() && !isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_no_groups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(
                        items = discoveredGroups,
                        key = { "${it.relayUrl}|${it.metadata.groupId}" }
                    ) { group ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            GroupCard(
                                relayUrl = group.relayUrl,
                                groupId = group.metadata.groupId,
                                initialMetadata = group.metadata,
                                initialMembers = group.members,
                                onClick = { onJoin(group.relayUrl, group.metadata.groupId) },
                                eventRepo = eventRepo
                            )
                        }
                    }
                    if (isLoading) {
                        item {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
