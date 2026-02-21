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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.BookmarkSet
import com.wisp.app.nostr.FollowSet
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.ui.component.ProfilePicture

private sealed class UnifiedListItem(val name: String, val sortKey: String) {
    class People(val followSet: FollowSet) : UnifiedListItem(followSet.name, followSet.name)
    class Notes(val bookmarkSet: BookmarkSet) : UnifiedListItem(bookmarkSet.name, bookmarkSet.name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsHubScreen(
    listRepo: ListRepository,
    bookmarkRepo: BookmarkRepository,
    bookmarkSetRepo: BookmarkSetRepository,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onBookmarks: () -> Unit,
    onListDetail: (FollowSet) -> Unit,
    onBookmarkSetDetail: (BookmarkSet) -> Unit,
    onCreateList: (String) -> Unit,
    onCreateBookmarkSet: (String) -> Unit,
    onDeleteList: (String) -> Unit,
    onDeleteBookmarkSet: (String) -> Unit
) {
    val ownLists by listRepo.ownLists.collectAsState()
    val ownSets by bookmarkSetRepo.ownSets.collectAsState()
    val bookmarkedIds by bookmarkRepo.bookmarkedIds.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateListDialog(
            onConfirm = { name, type ->
                when (type) {
                    ListType.PEOPLE -> onCreateList(name)
                    ListType.NOTES -> onCreateBookmarkSet(name)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    val unifiedItems = remember(ownLists, ownSets) {
        val items = mutableListOf<UnifiedListItem>()
        for (list in ownLists) items.add(UnifiedListItem.People(list))
        for (set in ownSets) {
            // Only show non-empty sets or sets that aren't just deleted stubs
            items.add(UnifiedListItem.Notes(set))
        }
        items.sortBy { it.sortKey }
        items
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                Icon(Icons.Default.Add, contentDescription = "Create List")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Bookmarks section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBookmarks)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        Icons.Outlined.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Bookmarks",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${bookmarkedIds.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Your Lists header
            item {
                Text(
                    "Your Lists",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (unifiedItems.isEmpty()) {
                item {
                    Text(
                        "No lists yet. Tap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                items(unifiedItems, key = { item ->
                    when (item) {
                        is UnifiedListItem.People -> "p:${item.followSet.pubkey}:${item.followSet.dTag}"
                        is UnifiedListItem.Notes -> "n:${item.bookmarkSet.pubkey}:${item.bookmarkSet.dTag}"
                    }
                }) { item ->
                    when (item) {
                        is UnifiedListItem.People -> FollowSetRow(
                            followSet = item.followSet,
                            eventRepo = eventRepo,
                            profileVersion = profileVersion,
                            onClick = { onListDetail(item.followSet) },
                            onDelete = { onDeleteList(item.followSet.dTag) }
                        )
                        is UnifiedListItem.Notes -> BookmarkSetRow(
                            bookmarkSet = item.bookmarkSet,
                            onClick = { onBookmarkSetDetail(item.bookmarkSet) },
                            onDelete = { onDeleteBookmarkSet(item.bookmarkSet.dTag) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowSetRow(
    followSet: FollowSet,
    eventRepo: EventRepository,
    profileVersion: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val memberAvatars = remember(followSet.members, profileVersion) {
        followSet.members.take(3).map { pk ->
            eventRepo.getProfileData(pk)?.picture
        }
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
            Text(
                text = followSet.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BookmarkSetRow(
    bookmarkSet: BookmarkSet,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
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
            Text(
                text = bookmarkSet.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${bookmarkSet.eventIds.size} notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private enum class ListType { PEOPLE, NOTES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateListDialog(
    onConfirm: (String, ListType) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ListType.PEOPLE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create List") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedType == ListType.PEOPLE,
                        onClick = { selectedType = ListType.PEOPLE },
                        label = { Text("People") },
                        leadingIcon = if (selectedType == ListType.PEOPLE) {{
                            Icon(Icons.Outlined.People, null, modifier = Modifier.size(18.dp))
                        }} else null
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedType == ListType.NOTES,
                        onClick = { selectedType = ListType.NOTES },
                        label = { Text("Notes") },
                        leadingIcon = if (selectedType == ListType.NOTES) {{
                            Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(18.dp))
                        }} else null
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("List name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedType) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
