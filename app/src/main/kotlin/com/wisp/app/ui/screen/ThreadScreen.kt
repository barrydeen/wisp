package com.wisp.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.PostCard
import com.wisp.app.viewmodel.ThreadViewModel
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    eventRepo: EventRepository,
    contactRepo: ContactRepository,
    userPubkey: String?,
    onBack: () -> Unit,
    onReply: (NostrEvent) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onToggleFollow: (String) -> Unit = {},
    onZap: (NostrEvent) -> Unit = {},
    zapAnimatingIds: Set<String> = emptySet()
) {
    val flatThread by viewModel.flatThread.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val reactionVersion by eventRepo.reactionVersion.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thread") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading && flatThread.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(items = flatThread, key = { it.first.id }) { (event, depth) ->
                    val profileData = eventRepo.getProfileData(event.pubkey)
                    val likeCount = reactionVersion.let { eventRepo.getReactionCount(event.id) }
                    val zapSats = eventRepo.getZapSats(event.id)
                    val userEmoji = reactionVersion.let { userPubkey?.let { eventRepo.getUserReactionEmoji(event.id, it) } }
                    PostCard(
                        event = event,
                        profile = profileData,
                        onReply = { onReply(event) },
                        onProfileClick = { onProfileClick(event.pubkey) },
                        onNavigateToProfile = onProfileClick,
                        onNoteClick = { onNoteClick(event) },
                        onReact = { emoji -> onReact(event, emoji) },
                        userReactionEmoji = userEmoji,
                        onZap = { onZap(event) },
                        likeCount = likeCount,
                        zapSats = zapSats,
                        isZapAnimating = event.id in zapAnimatingIds,
                        eventRepo = eventRepo,
                        modifier = Modifier.padding(start = (min(depth, 4) * 24).dp)
                    )
                }
            }
        }
    }
}
