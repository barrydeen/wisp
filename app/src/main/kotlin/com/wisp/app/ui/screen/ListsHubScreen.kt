package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.BookmarkSet
import com.wisp.app.nostr.FollowSet
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.ui.component.ProfilePicture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsHubScreen(
    listRepo: ListRepository,
    bookmarkSetRepo: BookmarkSetRepository,
    bookmarkRepo: BookmarkRepository,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onListDetail: (FollowSet) -> Unit,
    onBookmarkSetDetail: (BookmarkSet) -> Unit,
    onBookmarksClick: () -> Unit,
    onCreateList: (String, Boolean) -> Unit,
    onCreateBookmarkSet: (String, Boolean) -> Unit,
    onDeleteList: (String) -> Unit,
    onDeleteBookmarkSet: (String) -> Unit
) {
    val ownLists by listRepo.ownLists.collectAsState()
    val ownSets by bookmarkSetRepo.ownSets.collectAsState()
    val bookmarkedIds by bookmarkRepo.bookmarkedIds.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf<DeleteTarget?>(null) }

    if (showCreateDialog) {
        CreateListDialog(
            onConfirm = { name, type, isPrivate ->
                when (type) {
                    ListType.PEOPLE -> onCreateList(name, isPrivate)
                    ListType.NOTES -> onCreateBookmarkSet(name, isPrivate)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (deleteConfirmation != null) {
        val target = deleteConfirmation!!
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            title = { Text(stringResource(R.string.btn_delete_list)) },
            text = { Text("Are you sure you want to delete \"${target.name}\"? A deletion request will be sent to relays, but it cannot be guaranteed.") },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        is DeleteTarget.People -> onDeleteList(target.dTag)
                        is DeleteTarget.Notes -> onDeleteBookmarkSet(target.dTag)
                    }
                    deleteConfirmation = null
                }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    val peopleLists = remember(ownLists) { ownLists.sortedBy { it.name } }
    val notesLists = remember(ownSets) { ownSets.sortedBy { it.name } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_lists)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_create))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // People Lists section
            item {
                Text(
                    stringResource(R.string.tab_people),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (peopleLists.isEmpty()) {
                item {
                    Text(
                        "No people lists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                items(peopleLists, key = { "p:${it.pubkey}:${it.dTag}" }) { followSet ->
                    FollowSetRow(
                        followSet = followSet,
                        eventRepo = eventRepo,
                        profileVersion = profileVersion,
                        onClick = { onListDetail(followSet) },
                        onDelete = {
                            deleteConfirmation = DeleteTarget.People(followSet.dTag, followSet.name)
                        },
                        onViewJson = { /* handled inside row */ }
                    )
                }
            }

            // Notes Lists section
            item {
                Text(
                    stringResource(R.string.tab_notes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }

            // Global bookmarks row
            item(key = "global-bookmarks") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBookmarksClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bookmarks",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "${bookmarkedIds.size} notes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (notesLists.isEmpty()) {
                item {
                    Text(
                        "No notes lists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                items(notesLists, key = { "n:${it.pubkey}:${it.dTag}" }) { bookmarkSet ->
                    BookmarkSetRow(
                        bookmarkSet = bookmarkSet,
                        eventRepo = eventRepo,
                        onClick = { onBookmarkSetDetail(bookmarkSet) },
                        onDelete = {
                            deleteConfirmation = DeleteTarget.Notes(bookmarkSet.dTag, bookmarkSet.name)
                        },
                        onViewJson = { /* handled inside row */ }
                    )
                }
            }
        }
    }
}

private sealed class DeleteTarget(val dTag: String, val name: String) {
    class People(dTag: String, name: String) : DeleteTarget(dTag, name)
    class Notes(dTag: String, name: String) : DeleteTarget(dTag, name)
}

@Composable
private fun FollowSetRow(
    followSet: FollowSet,
    eventRepo: EventRepository,
    profileVersion: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onViewJson: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }

    val memberAvatars = remember(followSet.members, profileVersion) {
        followSet.members.take(3).map { pk ->
            eventRepo.getProfileData(pk)?.picture
        }
    }

    if (showJsonDialog) {
        val jsonText = remember(followSet) {
            val event = eventRepo.findAddressableEvent(
                com.wisp.app.nostr.Nip51.KIND_FOLLOW_SET, followSet.pubkey, followSet.dTag
            )
            event?.toJson() ?: "Event not found in cache"
        }
        JsonViewDialog(json = jsonText, onDismiss = { showJsonDialog = false })
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            Icons.Outlined.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = followSet.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (followSet.isPrivate) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = "Private list",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                memberAvatars.forEach { url ->
                    ProfilePicture(url = url, size = 20)
                    Spacer(Modifier.width(2.dp))
                }
                if (memberAvatars.isNotEmpty()) Spacer(Modifier.width(4.dp))
                Text(
                    "${followSet.members.size} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_copy_list_json)) },
                onClick = {
                    menuExpanded = false
                    showJsonDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun BookmarkSetRow(
    bookmarkSet: BookmarkSet,
    eventRepo: EventRepository,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onViewJson: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }

    if (showJsonDialog) {
        val jsonText = remember(bookmarkSet) {
            val event = eventRepo.findAddressableEvent(
                com.wisp.app.nostr.Nip51.KIND_BOOKMARK_SET, bookmarkSet.pubkey, bookmarkSet.dTag
            )
            event?.toJson() ?: "Event not found in cache"
        }
        JsonViewDialog(json = jsonText, onDismiss = { showJsonDialog = false })
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bookmarkSet.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (bookmarkSet.isPrivate) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = "Private list",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                "${bookmarkSet.eventIds.size} notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_copy_list_json)) },
                onClick = {
                    menuExpanded = false
                    showJsonDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun JsonViewDialog(json: String, onDismiss: () -> Unit) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btn_copy_list_json)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = json,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json))
            }) {
                Text(stringResource(R.string.btn_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

private enum class ListType { PEOPLE, NOTES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateListDialog(
    onConfirm: (String, ListType, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ListType.PEOPLE) }
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btn_create)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedType == ListType.PEOPLE,
                        onClick = { selectedType = ListType.PEOPLE },
                        label = { Text(stringResource(R.string.tab_people)) },
                        leadingIcon = if (selectedType == ListType.PEOPLE) {{
                            Icon(Icons.Outlined.People, null, modifier = Modifier.size(18.dp))
                        }} else null
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedType == ListType.NOTES,
                        onClick = { selectedType = ListType.NOTES },
                        label = { Text(stringResource(R.string.tab_notes)) },
                        leadingIcon = if (selectedType == ListType.NOTES) {{
                            Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(18.dp))
                        }} else null
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.placeholder_new_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.btn_private),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it }
                    )
                }
                if (isPrivate) {
                    Text(
                        "Items will be encrypted. The list name is always visible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedType, isPrivate) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.btn_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
