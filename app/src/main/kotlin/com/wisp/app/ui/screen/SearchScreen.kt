package com.wisp.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.FollowButton
import com.wisp.app.ui.component.PostCard
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.RelayOption
import com.wisp.app.viewmodel.SearchFilter
import com.wisp.app.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    relayPool: RelayPool,
    eventRepo: EventRepository,
    muteRepo: MuteRepository? = null,
    contactRepo: ContactRepository? = null,
    onProfileClick: (String) -> Unit,
    onNoteClick: (NostrEvent) -> Unit,
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onToggleFollow: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    userPubkey: String? = null,
    listedIds: Set<String> = emptySet(),
    onAddToList: (String) -> Unit = {},
    onDeleteEvent: (String, Int) -> Unit = { _, _ -> },
    translationRepo: TranslationRepository? = null,
    onPollVote: (String, List<String>) -> Unit = { _, _ -> }
) {
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val users by viewModel.users.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedRelayOption by viewModel.selectedRelayOption.collectAsState()
    val selectedRelayUrl by viewModel.selectedRelayUrl.collectAsState()
    val searchRelays by viewModel.searchRelays.collectAsState()
    val authorFilter by viewModel.authorFilter.collectAsState()
    val authorSearchResults by viewModel.authorSearchResults.collectAsState()
    val isAuthorSearching by viewModel.isAuthorSearching.collectAsState()
    var advancedExpanded by remember { mutableStateOf(authorFilter != null) }

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
            // Filter chips
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == SearchFilter.PEOPLE,
                    onClick = { viewModel.selectFilter(SearchFilter.PEOPLE) },
                    label = { Text("People") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = filter == SearchFilter.NOTES,
                    onClick = { viewModel.selectFilter(SearchFilter.NOTES) },
                    label = { Text("Notes") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // Advanced search toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Advanced search",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (advancedExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = advancedExpanded) {
                Column {
                    RelaySelector(
                        searchRelays = searchRelays,
                        selectedOption = selectedRelayOption,
                        selectedRelayUrl = selectedRelayUrl,
                        onSelectDefault = { viewModel.selectDefaultRelay() },
                        onSelectAllRelays = { viewModel.selectAllRelays() },
                        onSelectRelay = { viewModel.selectRelay(it) },
                        onAddRelay = { viewModel.addSearchRelay(it) },
                        onRemoveRelay = { viewModel.removeSearchRelay(it) }
                    )

                    // Author filter (Notes only)
                    if (filter == SearchFilter.NOTES) {
                        Spacer(modifier = Modifier.height(8.dp))
                        AuthorFilter(
                            authorFilter = authorFilter,
                            authorSearchResults = authorSearchResults,
                            isAuthorSearching = isAuthorSearching,
                            onSearchAuthors = { viewModel.searchAuthors(it, relayPool, eventRepo) },
                            onSelectAuthor = { viewModel.setAuthorFilter(it) },
                            onClearAuthor = { viewModel.clearAuthorFilter() }
                        )
                    }
                }
            }

            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.updateQuery(it) },
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
                        onSearch = { viewModel.search(query, relayPool, eventRepo, muteRepo) }
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.search(query, relayPool, eventRepo, muteRepo) }
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }

            // Results
            when {
                isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                users.isEmpty() && notes.isEmpty() && query.isNotEmpty() && !isSearching -> {
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

                users.isEmpty() && notes.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Search for users and notes on relays",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    val translationVersion by translationRepo?.version?.collectAsState()
                        ?: remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    val pollVoteVersion by eventRepo.pollVoteVersion.collectAsState()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (filter == SearchFilter.PEOPLE) {
                            val sortedUsers = users.sortedByDescending {
                                contactRepo?.isFollowing(it.pubkey) == true
                            }
                            items(sortedUsers, key = { it.pubkey }, contentType = { "user" }) { profile ->
                                UserResultItem(
                                    profile = profile,
                                    isFollowing = contactRepo?.isFollowing(profile.pubkey) == true,
                                    onClick = { onProfileClick(profile.pubkey) },
                                    onToggleFollow = { onToggleFollow(profile.pubkey) }
                                )
                            }
                        } else {
                            items(notes, key = { it.id }, contentType = { "post" }) { event ->
                                val profile = eventRepo.getProfileData(event.pubkey)
                                val translationState = remember(translationVersion, event.id) {
                                    translationRepo?.getState(event.id)
                                        ?: com.wisp.app.repo.TranslationState()
                                }
                                val searchPollVoteCounts = remember(pollVoteVersion, event.id) {
                                    if (event.kind == 1068) eventRepo.getPollVoteCounts(event.id) else emptyMap()
                                }
                                val searchPollTotalVotes = remember(pollVoteVersion, event.id) {
                                    if (event.kind == 1068) eventRepo.getPollTotalVotes(event.id) else 0
                                }
                                val searchUserPollVotes = remember(pollVoteVersion, event.id) {
                                    if (event.kind == 1068) eventRepo.getUserPollVotes(event.id) else emptyList()
                                }
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
                                    onDelete = { onDeleteEvent(event.id, event.kind) },
                                    translationState = translationState,
                                    onTranslate = { translationRepo?.translate(event.id, event.content) },
                                    pollVoteCounts = searchPollVoteCounts,
                                    pollTotalVotes = searchPollTotalVotes,
                                    userPollVotes = searchUserPollVotes,
                                    onPollVote = { optionIds -> onPollVote(event.id, optionIds) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySelector(
    searchRelays: List<String>,
    selectedOption: RelayOption,
    selectedRelayUrl: String?,
    onSelectDefault: () -> Unit,
    onSelectAllRelays: () -> Unit,
    onSelectRelay: (String) -> Unit,
    onAddRelay: (String) -> Boolean,
    onRemoveRelay: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val displayText = when (selectedOption) {
        RelayOption.DEFAULT -> SearchViewModel.DEFAULT_SEARCH_RELAY.removePrefix("wss://")
        RelayOption.ALL_RELAYS -> "All relays"
        RelayOption.INDIVIDUAL -> selectedRelayUrl?.removePrefix("wss://") ?: ""
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        OutlinedTextField(
            value = displayText,
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
            // Default search relay
            DropdownMenuItem(
                text = { Text(SearchViewModel.DEFAULT_SEARCH_RELAY.removePrefix("wss://")) },
                onClick = {
                    onSelectDefault()
                    expanded = false
                }
            )

            if (searchRelays.isNotEmpty()) {
                HorizontalDivider()
            }

            // User's search relays
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

            // All relays option
            DropdownMenuItem(
                text = { Text("All relays") },
                onClick = {
                    onSelectAllRelays()
                    expanded = false
                }
            )

            HorizontalDivider()

            // Add new relay
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
private fun AuthorFilter(
    authorFilter: ProfileData?,
    authorSearchResults: List<ProfileData>,
    isAuthorSearching: Boolean,
    onSearchAuthors: (String) -> Unit,
    onSelectAuthor: (ProfileData) -> Unit,
    onClearAuthor: () -> Unit
) {
    var authorQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Author",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (authorFilter != null) {
            // Show selected author
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilePicture(url = authorFilter.picture, size = 32)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = authorFilter.displayString,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = onClearAuthor,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear author",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            // Author search input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = authorQuery,
                    onValueChange = { authorQuery = it },
                    placeholder = { Text("Search for author") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearchAuthors(authorQuery) }
                    )
                )
                IconButton(onClick = { onSearchAuthors(authorQuery) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search authors")
                }
            }

            // Author search results
            if (isAuthorSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            authorSearchResults.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectAuthor(profile)
                            authorQuery = ""
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfilePicture(url = profile.picture, size = 32)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayString,
                            style = MaterialTheme.typography.bodyMedium,
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
                }
            }
        }
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
