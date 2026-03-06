package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.ui.component.PostCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarkedIds: Set<String>,
    eventRepo: EventRepository,
    userPubkey: String?,
    onBack: () -> Unit,
    onNoteClick: (NostrEvent) -> Unit = {},
    onQuotedNoteClick: ((String) -> Unit)? = null,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onProfileClick: (String) -> Unit = {},
    onRemoveBookmark: ((String) -> Unit)? = null,
    onToggleFollow: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    translationRepo: TranslationRepository? = null
) {
    val profileVersion by eventRepo.profileVersion.collectAsState()
    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val eventCacheVersion by eventRepo.eventCacheVersion.collectAsState()
    val translationVersion by translationRepo?.version?.collectAsState() ?: remember { mutableStateOf(0) }

    val events = remember(bookmarkedIds, profileVersion, eventCacheVersion) {
        bookmarkedIds.mapNotNull { id -> eventRepo.getEvent(id) }
            .sortedByDescending { it.created_at }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (bookmarkedIds.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No bookmarked notes",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Notes not loaded yet",
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
                    val translationState = remember(translationVersion, event.id) {
                        translationRepo?.getState(event.id) ?: com.wisp.app.repo.TranslationState()
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
                        isOwnEvent = event.pubkey == userPubkey,
                        translationState = translationState,
                        onTranslate = { translationRepo?.translate(event.id, event.content) }
                    )
                }
            }
        }
    }
}
