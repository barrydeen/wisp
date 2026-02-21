package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.wisp.app.nostr.BookmarkSet
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.PostCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSetScreen(
    bookmarkSet: BookmarkSet?,
    eventRepo: EventRepository,
    userPubkey: String?,
    isOwnList: Boolean,
    onBack: () -> Unit,
    onDeleteList: (() -> Unit)? = null,
    onNoteClick: (NostrEvent) -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onProfileClick: (String) -> Unit = {},
    onRemoveFromSet: ((String) -> Unit)? = null,
    onToggleFollow: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {}
) {
    val profileVersion by eventRepo.profileVersion.collectAsState()
    val reactionVersion by eventRepo.reactionVersion.collectAsState()

    val events = remember(bookmarkSet?.eventIds, profileVersion) {
        bookmarkSet?.eventIds?.mapNotNull { id -> eventRepo.getEvent(id) }
            ?.sortedByDescending { it.created_at }
            ?: emptyList()
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookmarkSet?.name ?: "List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isOwnList && onDeleteList != null) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete List") },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteList()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (bookmarkSet == null || events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (bookmarkSet == null) "List not found"
                    else if (bookmarkSet.eventIds.isEmpty()) "No notes in this list"
                    else "Notes not loaded yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(items = events, key = { it.id }) { event ->
                    val profile = eventRepo.getProfileData(event.pubkey)
                    val likeCount = reactionVersion.let { eventRepo.getReactionCount(event.id) }
                    val userEmojis = reactionVersion.let {
                        userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
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
                        userReactionEmojis = userEmojis,
                        likeCount = likeCount,
                        eventRepo = eventRepo,
                        onFollowAuthor = { onToggleFollow(event.pubkey) },
                        onBlockAuthor = { onBlockUser(event.pubkey) },
                        isOwnEvent = event.pubkey == userPubkey
                    )
                }
            }
        }
    }
}
