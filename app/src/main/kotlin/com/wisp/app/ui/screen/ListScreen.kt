package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.ProfilePicture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    followSet: FollowSet?,
    eventRepo: EventRepository,
    isOwnList: Boolean,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
    onRemoveMember: ((String) -> Unit)? = null,
    onAddMember: ((String) -> Unit)? = null,
    onUseAsFeed: (() -> Unit)? = null,
    onDeleteList: (() -> Unit)? = null,
    onFollowAll: ((Set<String>) -> Unit)? = null,
    contactRepo: ContactRepository? = null
) {
    val profileVersion by eventRepo.profileVersion.collectAsState()
    var showPickerDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFollowAllConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    if (showPickerDialog && followSet != null && contactRepo != null && onAddMember != null) {
        FollowPickerDialog(
            contactRepo = contactRepo,
            eventRepo = eventRepo,
            existingMembers = followSet.members,
            onAdd = { pubkey ->
                onAddMember(pubkey)
            },
            onDismiss = { showPickerDialog = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete List") },
            text = { Text("Are you sure you want to delete \"${followSet?.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteList?.invoke()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showFollowAllConfirm && followSet != null) {
        AlertDialog(
            onDismissRequest = { showFollowAllConfirm = false },
            title = { Text("Follow All") },
            text = { Text("Follow ${followSet.members.size} people from this list?") },
            confirmButton = {
                TextButton(onClick = {
                    showFollowAllConfirm = false
                    onFollowAll?.invoke(followSet.members)
                }) { Text("Follow All") }
            },
            dismissButton = {
                TextButton(onClick = { showFollowAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        followSet?.name ?: "List",
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
                    if (isOwnList && onAddMember != null && contactRepo != null) {
                        IconButton(onClick = { showPickerDialog = true }) {
                            Icon(Icons.Default.Add, "Add Members")
                        }
                    }
                    if (isOwnList || (!isOwnList && onFollowAll != null)) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "More options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (!isOwnList && onFollowAll != null) {
                                    DropdownMenuItem(
                                        text = { Text("Follow All Members") },
                                        onClick = {
                                            menuExpanded = false
                                            showFollowAllConfirm = true
                                        }
                                    )
                                }
                                if (isOwnList && onDeleteList != null) {
                                    DropdownMenuItem(
                                        text = { Text("Delete List", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuExpanded = false
                                            showDeleteConfirm = true
                                        }
                                    )
                                }
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
        if (followSet == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("List not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val members = followSet.members.toList()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "${members.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (onUseAsFeed != null) {
                            OutlinedButton(onClick = onUseAsFeed) {
                                Text("Use as Feed")
                            }
                        }
                        if (!isOwnList && onFollowAll != null && members.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { showFollowAllConfirm = true }) {
                                Text("Follow All")
                            }
                        }
                    }
                }
            }

            items(members, key = { it }) { pubkey ->
                val profile = remember(profileVersion, pubkey) {
                    eventRepo.getProfileData(pubkey)
                }
                val displayName = profile?.displayString
                    ?: pubkey.take(8) + "..." + pubkey.takeLast(4)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProfileClick(pubkey) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    ProfilePicture(url = profile?.picture, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isOwnList && onRemoveMember != null) {
                        IconButton(onClick = { onRemoveMember(pubkey) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (members.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(
                            "This list has no members",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowPickerDialog(
    contactRepo: ContactRepository,
    eventRepo: EventRepository,
    existingMembers: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val followList by contactRepo.followList.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var manualInput by remember { mutableStateOf("") }
    val profileVersion by eventRepo.profileVersion.collectAsState()

    val filteredFollows = remember(followList, searchQuery, existingMembers, profileVersion) {
        val query = searchQuery.lowercase()
        followList.filter { entry ->
            if (query.isBlank()) true
            else {
                val profile = eventRepo.getProfileData(entry.pubkey)
                val name = profile?.displayString?.lowercase() ?: ""
                name.contains(query) || entry.pubkey.startsWith(query)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Members") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter by name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(filteredFollows, key = { it.pubkey }) { entry ->
                        val profile = eventRepo.getProfileData(entry.pubkey)
                        val displayName = profile?.displayString
                            ?: entry.pubkey.take(8) + "..." + entry.pubkey.takeLast(4)
                        val isMember = entry.pubkey in existingMembers

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isMember) onAdd(entry.pubkey)
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            ProfilePicture(url = profile?.picture, size = 36)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isMember) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Already added",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Or add by npub / hex:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = { Text("npub1... or hex") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val resolved = resolveInput(manualInput.trim())
                                if (resolved != null) {
                                    onAdd(resolved)
                                    manualInput = ""
                                }
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val resolved = resolveInput(manualInput.trim())
                            if (resolved != null) {
                                onAdd(resolved)
                                manualInput = ""
                            }
                        },
                        enabled = manualInput.isNotBlank()
                    ) {
                        Text("Add")
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

private fun resolveInput(input: String): String? {
    if (input.isBlank()) return null
    // Try npub decode
    if (input.startsWith("npub1")) {
        return try {
            Nip19.npubDecode(input).toHex()
        } catch (_: Exception) { null }
    }
    // Try hex (64 char)
    if (input.length == 64 && input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return input.lowercase()
    }
    return null
}
