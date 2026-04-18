package com.wisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditOff
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.Nip29
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupRoom
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.GroupListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: String,
    relayUrl: String,
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    myPubkey: String?,
    signer: NostrSigner?,
    relayPool: RelayPool,
    onBack: () -> Unit,
    onLeave: () -> Unit = {},
    onDelete: () -> Unit = {},
    onPickPicture: ((String) -> Unit) -> Unit = {}
) {
    val groups by groupListViewModel.groups.collectAsState()
    val room = groups.firstOrNull { it.groupId == groupId && it.relayUrl == relayUrl }

    val isAdmin = myPubkey != null && (
        room?.admins?.contains(myPubkey) == true ||
        // Also show admin controls if we are the creator and admins haven't loaded yet
        (room?.admins.isNullOrEmpty() && room?.members.isNullOrEmpty())
    )

    var showEditDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var assignRoleTarget by remember { mutableStateOf<String?>(null) }
    var latestInviteCode by remember { mutableStateOf<String?>(null) }

    val clipboardManager = LocalClipboardManager.current
    val inviteLink = Nip29.inviteLink(relayUrl, groupId)
    val codedInviteLink = latestInviteCode?.let { Nip29.inviteLink(relayUrl, groupId, it) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_group_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (isAdmin) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.cd_edit_group))
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
            // Group header
            item {
                GroupHeader(room = room)
            }

            // Access & visibility — show all 4 dimensions with icon + label + description,
            // so users always know the room's posture (public/private, open/closed, etc.).
            val metadata = room?.metadata
            if (metadata != null) {
                item {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    Text(
                        stringResource(R.string.section_room_access),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    GroupFlagDetailRow(
                        icon = if (metadata.isPrivate) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        label = stringResource(
                            if (metadata.isPrivate) R.string.group_flag_private_label
                            else R.string.group_flag_public_label
                        ),
                        description = stringResource(
                            if (metadata.isPrivate) R.string.group_flag_private_desc
                            else R.string.group_flag_public_desc
                        )
                    )
                    GroupFlagDetailRow(
                        icon = if (metadata.isClosed) Icons.Outlined.PersonOff else Icons.Outlined.Groups,
                        label = stringResource(
                            if (metadata.isClosed) R.string.group_flag_closed_label
                            else R.string.group_flag_open_label
                        ),
                        description = stringResource(
                            if (metadata.isClosed) R.string.group_flag_closed_desc
                            else R.string.group_flag_open_desc
                        )
                    )
                    GroupFlagDetailRow(
                        icon = if (metadata.isRestricted) Icons.Outlined.EditOff else Icons.Outlined.Edit,
                        label = stringResource(
                            if (metadata.isRestricted) R.string.group_flag_restricted_label
                            else R.string.group_flag_unrestricted_label
                        ),
                        description = stringResource(
                            if (metadata.isRestricted) R.string.group_flag_restricted_desc
                            else R.string.group_flag_unrestricted_desc
                        )
                    )
                    GroupFlagDetailRow(
                        icon = if (metadata.isHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        label = stringResource(
                            if (metadata.isHidden) R.string.group_flag_hidden_label
                            else R.string.group_flag_visible_label
                        ),
                        description = stringResource(
                            if (metadata.isHidden) R.string.group_flag_hidden_desc
                            else R.string.group_flag_visible_desc
                        )
                    )
                }
            }

            // Invite link
            item {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(inviteLink))
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.label_invite_link),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            inviteLink,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_link),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            }

            // Notification toggle
            item {
                val isNotified = groupListViewModel.isGroupNotified(relayUrl, groupId)
                var notified by remember { mutableStateOf(isNotified) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            notified = !notified
                            groupListViewModel.setGroupNotified(relayUrl, groupId, notified)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        if (notified) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.label_notifications),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(if (notified) R.string.label_notifications_on else R.string.label_notifications_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notified,
                        onCheckedChange = {
                            notified = it
                            groupListViewModel.setGroupNotified(relayUrl, groupId, it)
                        }
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            }

            // Admin-only invite codes section
            if (isAdmin) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.section_invite_codes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val code = groupListViewModel.createInvite(relayUrl, groupId, signer)
                                    if (code != null) latestInviteCode = code
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                Icons.Outlined.PersonAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.action_generate_invite),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        latestInviteCode?.let { code ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(code))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.label_latest_invite_code),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        code,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(code))
                                }) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = stringResource(R.string.action_copy_code),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            codedInviteLink?.let { link ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(link))
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.action_copy_invite_link),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // Leave / Delete actions
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLeaveDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.action_leave_group),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                if (isAdmin) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeleteDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.action_delete_group),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                }
            }

            // Members section
            val members = room?.members ?: emptyList()
            val admins = room?.admins ?: emptyList()

            if (members.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.label_members_count, members.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                items(items = members, key = { it }) { pubkey ->
                    MemberRow(
                        pubkey = pubkey,
                        isAdmin = admins.contains(pubkey),
                        showRemove = isAdmin && pubkey != myPubkey && !admins.contains(pubkey),
                        showAssignRole = isAdmin && pubkey != myPubkey,
                        eventRepo = eventRepo,
                        onRemove = { removeTarget = pubkey },
                        onAssignRole = { assignRoleTarget = pubkey }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.label_members_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showEditDialog) {
        EditGroupDialog(
            room = room,
            onDismiss = { showEditDialog = false },
            onPickPicture = onPickPicture,
            onSave = { name, about, picture ->
                showEditDialog = false
                groupListViewModel.updateMetadataOnRelay(relayUrl, groupId, name, about, picture, signer)
            }
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.title_leave_group)) },
            text = { Text(stringResource(R.string.msg_leave_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    groupListViewModel.leaveGroup(relayUrl, groupId, signer)
                    onLeave()
                }) {
                    Text(stringResource(R.string.action_leave_group), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.title_delete_group)) },
            text = { Text(stringResource(R.string.msg_delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    groupListViewModel.deleteGroup(relayUrl, groupId, signer)
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete_group), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    removeTarget?.let { target ->
        val profile = remember(target) { eventRepo.getProfileData(target) }
        val displayName = profile?.displayString ?: target.take(10) + "…"
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.title_remove_user)) },
            text = { Text(stringResource(R.string.msg_remove_user_confirm, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    groupListViewModel.removeUser(relayUrl, groupId, target, signer)
                    removeTarget = null
                }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    assignRoleTarget?.let { target ->
        val profile = remember(target) { eventRepo.getProfileData(target) }
        val displayName = profile?.displayString ?: target.take(10) + "…"
        var rolesText by remember(target) { mutableStateOf("admin") }
        AlertDialog(
            onDismissRequest = { assignRoleTarget = null },
            title = { Text(stringResource(R.string.title_assign_role)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.msg_assign_role, displayName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rolesText,
                        onValueChange = { rolesText = it },
                        label = { Text(stringResource(R.string.label_roles)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val roles = rolesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    groupListViewModel.putUser(relayUrl, groupId, target, roles, signer)
                    assignRoleTarget = null
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { assignRoleTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun GroupHeader(room: GroupRoom?) {
    val metadata = room?.metadata
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    ) {
        ProfilePicture(url = metadata?.picture, size = 72)
        Spacer(Modifier.height(12.dp))
        Text(
            text = metadata?.name ?: room?.groupId ?: "Chat Room",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!metadata?.about.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = metadata!!.about!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        // Header posture chip(s): show restrictive flags when set, otherwise a single
        // "Public" chip so a fully-open room still carries a clear badge.
        if (metadata != null) {
            val anyFlag = metadata.isPrivate || metadata.isClosed || metadata.isRestricted || metadata.isHidden
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                if (!anyFlag) {
                    GroupFlagChip(Icons.Outlined.LockOpen,
                        stringResource(R.string.group_flag_public_label))
                } else {
                    if (metadata.isPrivate) {
                        GroupFlagChip(Icons.Outlined.Lock,
                            stringResource(R.string.group_flag_private_label))
                    }
                    if (metadata.isClosed) {
                        GroupFlagChip(Icons.Outlined.PersonOff,
                            stringResource(R.string.group_flag_closed_label))
                    }
                    if (metadata.isRestricted) {
                        GroupFlagChip(Icons.Outlined.EditOff,
                            stringResource(R.string.group_flag_restricted_label))
                    }
                    if (metadata.isHidden) {
                        GroupFlagChip(Icons.Outlined.VisibilityOff,
                            stringResource(R.string.group_flag_hidden_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupFlagChip(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun GroupFlagDetailRow(icon: ImageVector, label: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MemberRow(
    pubkey: String,
    isAdmin: Boolean,
    showRemove: Boolean,
    showAssignRole: Boolean,
    eventRepo: EventRepository,
    onRemove: () -> Unit,
    onAssignRole: () -> Unit
) {
    val profile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        ProfilePicture(url = profile?.picture, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.displayString ?: (pubkey.take(8) + "…"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isAdmin) {
            Icon(
                Icons.Outlined.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        if (showAssignRole) {
            IconButton(onClick = onAssignRole) {
                Icon(
                    Icons.Outlined.PersonAdd,
                    contentDescription = stringResource(R.string.cd_assign_role),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.PersonRemove,
                    contentDescription = stringResource(R.string.cd_remove_user),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EditGroupDialog(
    room: GroupRoom?,
    onDismiss: () -> Unit,
    onPickPicture: ((String) -> Unit) -> Unit,
    onSave: (name: String, about: String, picture: String) -> Unit
) {
    var name by remember { mutableStateOf(room?.metadata?.name ?: "") }
    var about by remember { mutableStateOf(room?.metadata?.about ?: "") }
    var picture by remember { mutableStateOf(room?.metadata?.picture ?: "") }
    var uploadingPicture by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_edit_group)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text(stringResource(R.string.label_group_about)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = picture,
                        onValueChange = { picture = it },
                        label = { Text(stringResource(R.string.label_group_picture)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            uploadingPicture = true
                            onPickPicture { url ->
                                picture = url
                                uploadingPicture = false
                            }
                        },
                        enabled = !uploadingPicture
                    ) {
                        if (uploadingPicture) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.cd_upload_group_picture))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, about, picture) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
