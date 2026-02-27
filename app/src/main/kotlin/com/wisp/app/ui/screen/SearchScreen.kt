package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkCache
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.ui.component.FollowButton
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.LocalFilter
import com.wisp.app.viewmodel.SearchTab
import com.wisp.app.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    relayPool: RelayPool,
    eventRepo: EventRepository,
    profileRepo: ProfileRepository,
    muteRepo: MuteRepository? = null,
    contactRepo: ContactRepository? = null,
    extendedNetworkCache: ExtendedNetworkCache? = null,
    onProfileClick: (String) -> Unit,
    onNoteClick: (NostrEvent) -> Unit,
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onListClick: (FollowSet) -> Unit = {},
    onToggleFollow: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    userPubkey: String? = null,
    listedIds: Set<String> = emptySet(),
    onAddToList: (String) -> Unit = {},
    onDeleteEvent: (String, Int) -> Unit = { _, _ -> }
) {
    val query by viewModel.query.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val localFilter by viewModel.localFilter.collectAsState()
    val localUsers by viewModel.localUsers.collectAsState()
    val localNotes by viewModel.localNotes.collectAsState()
    val users by viewModel.users.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchRelays by viewModel.searchRelays.collectAsState()
    val selectedRelay by viewModel.selectedRelay.collectAsState()

    val tabs = SearchTab.entries

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // People / Notes filter
            ResultFilterSelector(
                localFilter = localFilter,
                onSelectFilter = { viewModel.selectLocalFilter(it) }
            )

            // Tabs
            TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
                Tab(
                    selected = selectedTab == SearchTab.MY_DEVICE,
                    onClick = { viewModel.selectTab(SearchTab.MY_DEVICE) },
                    text = { Text("My Device") }
                )
                Tab(
                    selected = selectedTab == SearchTab.RELAYS,
                    onClick = { viewModel.selectTab(SearchTab.RELAYS) },
                    text = { Text("Relays") }
                )
            }

            when (selectedTab) {
                SearchTab.MY_DEVICE -> MyDeviceTab(
                    query = query,
                    localFilter = localFilter,
                    localUsers = localUsers,
                    localNotes = localNotes,
                    eventRepo = eventRepo,
                    contactRepo = contactRepo,
                    onQueryChange = { viewModel.updateQuery(it, profileRepo, eventRepo) },
                    onClear = { viewModel.clear() },
                    onProfileClick = onProfileClick,
                    onNoteClick = onNoteClick,
                    onQuotedNoteClick = onQuotedNoteClick,
                    onReply = onReply,
                    onReact = onReact,
                    onToggleFollow = onToggleFollow,
                    onBlockUser = onBlockUser,
                    userPubkey = userPubkey,
                    listedIds = listedIds,
                    onAddToList = onAddToList,
                    onDeleteEvent = onDeleteEvent
                )

                SearchTab.RELAYS -> RelaysTab(
                    query = query,
                    users = users,
                    notes = notes,
                    lists = lists,
                    isSearching = isSearching,
                    localFilter = localFilter,
                    searchRelays = searchRelays,
                    selectedRelay = selectedRelay,
                    onSelectRelay = { viewModel.selectRelay(it) },
                    onAddRelay = { viewModel.addSearchRelay(it) },
                    onRemoveRelay = { viewModel.removeSearchRelay(it) },
                    onQueryChange = { viewModel.updateQuery(it, profileRepo, eventRepo) },
                    onClear = { viewModel.clear() },
                    onSearch = { viewModel.search(query, relayPool, eventRepo, muteRepo) },
                    eventRepo = eventRepo,
                    contactRepo = contactRepo,
                    onProfileClick = onProfileClick,
                    onNoteClick = onNoteClick,
                    onQuotedNoteClick = onQuotedNoteClick,
                    onReply = onReply,
                    onReact = onReact,
                    onListClick = onListClick,
                    onToggleFollow = onToggleFollow,
                    onBlockUser = onBlockUser,
                    userPubkey = userPubkey,
                    listedIds = listedIds,
                    onAddToList = onAddToList,
                    onDeleteEvent = onDeleteEvent
                )
            }
        }
    }
}

@Composable
private fun MyDeviceTab(
    query: String,
    localFilter: LocalFilter,
    localUsers: List<ProfileData>,
    localNotes: List<NostrEvent>,
    eventRepo: EventRepository,
    contactRepo: ContactRepository?,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: (NostrEvent) -> Unit,
    onQuotedNoteClick: ((String) -> Unit)?,
    onReply: (NostrEvent) -> Unit,
    onReact: (NostrEvent, String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onBlockUser: (String) -> Unit,
    userPubkey: String?,
    listedIds: Set<String>,
    onAddToList: (String) -> Unit,
    onDeleteEvent: (String, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onClear = onClear
        )

        when {
            query.isBlank() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Search cached users and notes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            localFilter == LocalFilter.PEOPLE && localUsers.isEmpty() ||
            localFilter == LocalFilter.NOTES && localNotes.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No results on your device",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            localFilter == LocalFilter.PEOPLE -> {
                val sortedLocalUsers = localUsers.sortedByDescending {
                    contactRepo?.isFollowing(it.pubkey) == true
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedLocalUsers, key = { it.pubkey }) { profile ->
                        UserResultItem(
                            profile = profile,
                            isFollowing = contactRepo?.isFollowing(profile.pubkey) == true,
                            onClick = { onProfileClick(profile.pubkey) },
                            onToggleFollow = { onToggleFollow(profile.pubkey) }
                        )
                    }
                }
            }

            localFilter == LocalFilter.NOTES -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(localNotes, key = { it.id }) { event ->
                        val profile = eventRepo.getProfileData(event.pubkey)
                        PostCard(
                            event = event,
                            profile = profile,
                            onReply = { onReply(event) },
                            onProfileClick = { onProfileClick(event.pubkey) },
                            onNavigateToProfile = onProfileClick,
                            onNoteClick = { onNoteClick(event) },
                            onQuotedNoteClick = onQuotedNoteClick,
                            onReact = { emoji -> onReact(event, emoji) },
                            eventRepo = eventRepo,
                            onFollowAuthor = { onToggleFollow(event.pubkey) },
                            onBlockAuthor = { onBlockUser(event.pubkey) },
                            isOwnEvent = event.pubkey == userPubkey,
                            onAddToList = { onAddToList(event.id) },
                            isInList = event.id in listedIds,
                            onDelete = { onDeleteEvent(event.id, event.kind) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysTab(
    query: String,
    users: List<ProfileData>,
    notes: List<NostrEvent>,
    lists: List<FollowSet>,
    isSearching: Boolean,
    localFilter: LocalFilter,
    searchRelays: List<String>,
    selectedRelay: String?,
    onSelectRelay: (String?) -> Unit,
    onAddRelay: (String) -> Boolean,
    onRemoveRelay: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    eventRepo: EventRepository,
    contactRepo: ContactRepository?,
    onProfileClick: (String) -> Unit,
    onNoteClick: (NostrEvent) -> Unit,
    onQuotedNoteClick: ((String) -> Unit)?,
    onReply: (NostrEvent) -> Unit,
    onReact: (NostrEvent, String) -> Unit,
    onListClick: (FollowSet) -> Unit,
    onToggleFollow: (String) -> Unit,
    onBlockUser: (String) -> Unit,
    userPubkey: String?,
    listedIds: Set<String>,
    onAddToList: (String) -> Unit,
    onDeleteEvent: (String, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Relay selector
        RelaySelector(
            searchRelays = searchRelays,
            selectedRelay = selectedRelay,
            onSelectRelay = onSelectRelay,
            onAddRelay = onAddRelay,
            onRemoveRelay = onRemoveRelay
        )

        // Search bar + Go
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onClear = onClear,
            onSearch = onSearch
        )

    when {
        isSearching -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        users.isEmpty() && notes.isEmpty() && lists.isEmpty() && query.isNotEmpty() && !isSearching -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No results found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        users.isEmpty() && notes.isEmpty() && lists.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Press search to query relays",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (localFilter == LocalFilter.PEOPLE) {
                    val sortedUsers = users.sortedByDescending {
                        contactRepo?.isFollowing(it.pubkey) == true
                    }
                    items(sortedUsers, key = { it.pubkey }) { profile ->
                        UserResultItem(
                            profile = profile,
                            isFollowing = contactRepo?.isFollowing(profile.pubkey) == true,
                            onClick = { onProfileClick(profile.pubkey) },
                            onToggleFollow = { onToggleFollow(profile.pubkey) }
                        )
                    }
                } else {
                    if (lists.isNotEmpty()) {
                        item {
                            Text(
                                "Lists",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(lists, key = { "${it.pubkey}:${it.dTag}" }) { list ->
                            ListResultItem(
                                followSet = list,
                                eventRepo = eventRepo,
                                onClick = { onListClick(list) }
                            )
                        }
                        if (notes.isNotEmpty()) {
                            item {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }

                    items(notes, key = { it.id }) { event ->
                        val profile = eventRepo.getProfileData(event.pubkey)
                        PostCard(
                            event = event,
                            profile = profile,
                            onReply = { onReply(event) },
                            onProfileClick = { onProfileClick(event.pubkey) },
                            onNavigateToProfile = onProfileClick,
                            onNoteClick = { onNoteClick(event) },
                            onQuotedNoteClick = onQuotedNoteClick,
                            onReact = { emoji -> onReact(event, emoji) },
                            eventRepo = eventRepo,
                            onFollowAuthor = { onToggleFollow(event.pubkey) },
                            onBlockAuthor = { onBlockUser(event.pubkey) },
                            isOwnEvent = event.pubkey == userPubkey,
                            onAddToList = { onAddToList(event.id) },
                            isInList = event.id in listedIds,
                            onDelete = { onDeleteEvent(event.id, event.kind) }
                        )
                    }
                }
            }
        }
    }
    } // Column
}

@Composable
private fun ResultFilterSelector(
    localFilter: LocalFilter,
    onSelectFilter: (LocalFilter) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = localFilter == LocalFilter.PEOPLE,
            onClick = { onSelectFilter(LocalFilter.PEOPLE) },
            label = { Text("People") }
        )
        FilterChip(
            selected = localFilter == LocalFilter.NOTES,
            onClick = { onSelectFilter(LocalFilter.NOTES) },
            label = { Text("Notes") }
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search users and notes") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onSearch = { onSearch?.invoke() }
            ),
            modifier = Modifier.weight(1f)
        )
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Go")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySelector(
    searchRelays: List<String>,
    selectedRelay: String?,
    onSelectRelay: (String?) -> Unit,
    onAddRelay: (String) -> Boolean,
    onRemoveRelay: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = selectedRelay?.removePrefix("wss://") ?: "All relays",
            onValueChange = {},
            readOnly = true,
            label = { Text("Search relay") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All relays") },
                onClick = {
                    onSelectRelay(null)
                    expanded = false
                }
            )
            searchRelays.forEach { url ->
                DropdownMenuItem(
                    text = {
                        Text(
                            url.removePrefix("wss://"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemoveRelay(url) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove relay",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        onSelectRelay(url)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add new") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    showAddDialog = true
                }
            )
        }
    }

    if (showAddDialog) {
        AddRelayDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url ->
                if (onAddRelay(url)) {
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
private fun AddRelayDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var url by remember { mutableStateOf("wss://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add search relay") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("wss://relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(url) })
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ListResultItem(
    followSet: FollowSet,
    eventRepo: EventRepository,
    onClick: () -> Unit
) {
    val authorProfile = eventRepo.getProfileData(followSet.pubkey)
    val authorName = authorProfile?.displayString
        ?: followSet.pubkey.take(8) + "..."

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = followSet.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "by $authorName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${followSet.members.size} members",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UserResultItem(
    profile: ProfileData,
    isFollowing: Boolean = false,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(url = profile.picture, size = 48)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayString,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!profile.nip05.isNullOrBlank()) {
                Text(
                    text = profile.nip05,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FollowButton(
            isFollowing = isFollowing,
            onClick = onToggleFollow
        )
    }
}
