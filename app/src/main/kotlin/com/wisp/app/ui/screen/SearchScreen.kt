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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it, profileRepo, eventRepo) },
                placeholder = { Text("Search users and notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.selectTab(SearchTab.RELAYS)
                        viewModel.search(query, relayPool, eventRepo, muteRepo)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

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
                    onSelectFilter = { viewModel.selectLocalFilter(it) },
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
                    eventRepo = eventRepo,
                    contactRepo = contactRepo,
                    extendedNetworkCache = extendedNetworkCache,
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
    onSelectFilter: (LocalFilter) -> Unit,
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(localUsers, key = { it.pubkey }) { profile ->
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

@Composable
private fun RelaysTab(
    query: String,
    users: List<ProfileData>,
    notes: List<NostrEvent>,
    lists: List<FollowSet>,
    isSearching: Boolean,
    eventRepo: EventRepository,
    contactRepo: ContactRepository?,
    extendedNetworkCache: ExtendedNetworkCache?,
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
            val filteredUsers = users.filter { profile ->
                val pk = profile.pubkey
                contactRepo?.isFollowing(pk) == true ||
                        extendedNetworkCache?.firstDegreePubkeys?.contains(pk) == true ||
                        extendedNetworkCache?.qualifiedPubkeys?.contains(pk) == true ||
                        profile.nip05 != null
            }.take(5)

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filteredUsers.isNotEmpty()) {
                    item {
                        Text(
                            "Users",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(filteredUsers, key = { it.pubkey }) { profile ->
                        UserResultItem(
                            profile = profile,
                            isFollowing = contactRepo?.isFollowing(profile.pubkey) == true,
                            onClick = { onProfileClick(profile.pubkey) },
                            onToggleFollow = { onToggleFollow(profile.pubkey) }
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

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

                if (notes.isNotEmpty()) {
                    item {
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
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
