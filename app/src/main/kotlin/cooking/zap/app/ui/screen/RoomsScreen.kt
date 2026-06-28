package cooking.zap.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Nip29
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.GroupRoom
import cooking.zap.app.ui.component.GroupCard
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.viewmodel.GroupListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rooms surface — the home for NIP-29 chat rooms. Hosted as the "Rooms" tab inside the Messages
 * (DM_LIST) destination. Mirrors the web /community + /groups IA:
 *  - "Your rooms": the rooms you've joined (keeps unread / mute affordances).
 *  - "Discover": a browse list of PUBLIC, VISIBLE rooms via [GroupListViewModel.discoverGroups].
 *    Hidden / private rooms are filtered out of public browse.
 *  - Create Room (PR 1 dialog) + Paste-invite redeem.
 *
 * This is content-only (no Scaffold/top bar) so it can be dropped into the Messages tab row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsTab(
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    signer: NostrSigner? = null,
    onOpenRoom: (relayUrl: String, groupId: String) -> Unit = { _, _ -> }
) {
    val joined by groupListViewModel.groups.collectAsState()
    val discovered by groupListViewModel.discoveredGroups.collectAsState()
    val discoveryLoading by groupListViewModel.discoveryLoading.collectAsState()
    val unreadGroups by groupListViewModel.unreadGroups.collectAsState()
    val notifiedGroups by groupListViewModel.notifiedGroups.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var showRedeem by remember { mutableStateOf(false) }
    var adminError by remember { mutableStateOf<GroupListViewModel.AdminError?>(null) }
    var joinError by remember { mutableStateOf<GroupListViewModel.JoinError?>(null) }

    LaunchedEffect(groupListViewModel) {
        groupListViewModel.discoverGroups()
    }
    LaunchedEffect(groupListViewModel) {
        groupListViewModel.adminErrors.collect { adminError = it }
    }
    LaunchedEffect(groupListViewModel) {
        groupListViewModel.joinErrors.collect {
            joinError = it
            // A rejection (e.g. consumed/invalid invite code) must clear the redeem dialog's
            // "Joining…" state — dismiss it so the error shows and the user can retry.
            showRedeem = false
        }
    }

    // Public browse = visible, non-private rooms we haven't already joined.
    val joinedKeys = remember(joined) { joined.map { "${it.relayUrl}|${it.groupId}" }.toSet() }
    val browse = remember(discovered, joinedKeys) {
        discovered.filter { g ->
            !g.metadata.isHidden && !g.metadata.isPrivate &&
                "${g.relayUrl}|${g.metadata.groupId}" !in joinedKeys
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Create + invite-redeem both need to sign (9007 / 9021), so they're only offered when
        // there's a signing key — READ_ONLY accounts just browse. (Hosted in a tab, so these live
        // in an inline header row rather than a top-bar.)
        if (signer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showRedeem = true }) {
                    Icon(Icons.Outlined.Link, contentDescription = stringResource(R.string.action_paste_invite))
                }
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_create_group))
                }
            }
        }
        val nothingToShow = joined.isEmpty() && browse.isEmpty() && !discoveryLoading
        if (nothingToShow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(64.dp))
                Text(
                    stringResource(R.string.error_no_rooms),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.error_no_rooms_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (joined.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.section_your_rooms)) }
                    items(items = joined, key = { "joined|${it.relayUrl}|${it.groupId}" }) { room ->
                        val key = "${room.relayUrl}|${room.groupId}"
                        GroupRoomRow(
                            room = room,
                            hasUnread = key in unreadGroups,
                            isNotified = key in notifiedGroups,
                            onToggleNotify = { enabled ->
                                groupListViewModel.setGroupNotified(room.relayUrl, room.groupId, enabled)
                            },
                            onClick = { onOpenRoom(room.relayUrl, room.groupId) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    }
                }

                item { SectionHeader(stringResource(R.string.section_discover_rooms)) }
                if (browse.isEmpty() && discoveryLoading) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (browse.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.error_no_public_rooms),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(items = browse, key = { "browse|${it.relayUrl}|${it.metadata.groupId}" }) { group ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            GroupCard(
                                relayUrl = group.relayUrl,
                                groupId = group.metadata.groupId,
                                initialMetadata = group.metadata,
                                initialMembers = group.members,
                                onClick = { onOpenRoom(group.relayUrl, group.metadata.groupId) },
                                eventRepo = eventRepo
                            )
                        }
                    }
                    if (discoveryLoading) {
                        item {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateGroupDialog(
            onDismiss = { showCreate = false },
            onCreate = { relayUrl, name, about, isPrivate ->
                showCreate = false
                groupListViewModel.createGroup(relayUrl, name, signer, about = about, isPrivate = isPrivate)
            }
        )
    }

    if (showRedeem) {
        RedeemInviteDialog(
            groupListViewModel = groupListViewModel,
            eventRepo = eventRepo,
            onDismiss = { showRedeem = false },
            onJoin = { relayUrl, groupId, code ->
                groupListViewModel.joinGroup(relayUrl, groupId, signer, code)
            },
            onJoined = { relayUrl, groupId ->
                showRedeem = false
                onOpenRoom(relayUrl, groupId)
            }
        )
    }

    adminError?.let { err ->
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
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
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
                text = formatRoomTimestamp(room.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
    var relayUrl by remember { mutableStateOf(Nip29.DEFAULT_GROUP_RELAYS.first()) }
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
                    if (url.isNotEmpty()) onCreate(url, groupName.trim(), about.trim(), isPrivate)
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

/**
 * Redeem an invite link — mirrors the web /groups/join landing. Paste a `<relay>'<groupId>?code=…`
 * link, preview the room (name/about/members), then Join. Single-use codes are enforced relay-side,
 * so a consumed/invalid code surfaces as a join rejection. On success the joined room is opened.
 */
@Composable
private fun RedeemInviteDialog(
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    onDismiss: () -> Unit,
    onJoin: (relayUrl: String, groupId: String, code: String?) -> Unit,
    onJoined: (relayUrl: String, groupId: String) -> Unit
) {
    var link by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    // Parsed (relay, groupId, code) once the link is valid → preview + Join phase.
    var parsed by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var joining by remember { mutableStateOf(false) }

    val joined by groupListViewModel.groups.collectAsState()
    // When the room we're joining shows up in the joined list, the relay accepted the request.
    LaunchedEffect(joined, parsed, joining) {
        val target = parsed ?: return@LaunchedEffect
        if (!joining) return@LaunchedEffect
        if (joined.any { it.relayUrl == target.first.lowercase().trimEnd('/') && it.groupId == target.second }) {
            joining = false
            onJoined(target.first, target.second)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_redeem_invite)) },
        text = {
            val target = parsed
            if (target == null) {
                Column {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; showError = false },
                        label = { Text(stringResource(R.string.label_invite_link)) },
                        placeholder = { Text("pantry.zap.cooking'groupid?code=…") },
                        singleLine = true,
                        isError = showError,
                        supportingText = if (showError) {
                            { Text(stringResource(R.string.error_invalid_invite_link)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column {
                    GroupCard(
                        relayUrl = target.first,
                        groupId = target.second,
                        onFetchPreview = { r, g -> groupListViewModel.fetchGroupPreview(r, g) },
                        eventRepo = eventRepo
                    )
                    if (joining) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.label_joining_room),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val target = parsed
            if (target == null) {
                TextButton(onClick = {
                    val result = Nip29.parseInviteLink(link.trim())
                    if (result != null) parsed = result else showError = true
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            } else {
                TextButton(
                    enabled = !joining,
                    onClick = {
                        joining = true
                        onJoin(target.first, target.second, target.third)
                    }
                ) {
                    Text(stringResource(R.string.action_join))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

private val roomTimeFormat = SimpleDateFormat("MMM d", Locale.US)
private val roomTimeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatRoomTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = Date(epoch * 1000)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    return if (cal.get(java.util.Calendar.YEAR) != currentYear) {
        roomTimeYearFormat.format(date)
    } else {
        roomTimeFormat.format(date)
    }
}
