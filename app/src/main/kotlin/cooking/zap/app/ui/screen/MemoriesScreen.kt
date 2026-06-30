package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.MemoryGroup
import cooking.zap.app.ui.component.NoteActions
import cooking.zap.app.ui.component.PostCard
import cooking.zap.app.viewmodel.MemoriesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun yearLabel(yearsAgo: Int): String =
    if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"

private fun groupDateLabel(dateSec: Long): String =
    SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(dateSec * 1000))

/**
 * Memories — "On this day". Shows the signed-in user's own kind-1 notes from this
 * calendar day 1/2/3 years ago, grouped by yearsAgo. Mirrors the web's
 * `/memories` page; each note renders via the shared [PostCard]. Read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    viewModel: MemoriesViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    onBack: () -> Unit,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
) {
    val engagementVersions = rememberEngagementVersions(eventRepo)
    val groups by viewModel.groups.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val refreshNotice by viewModel.refreshNotice.collectAsState()
    val loaded by viewModel.loaded.collectAsState()

    val allEmpty = loaded && groups.all { it.events.isEmpty() }
    val todayLabel = remember {
        SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Memories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (userPubkey != null) {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh memories")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                userPubkey == null -> SignedOutState()
                loading && groups.isEmpty() -> CenteredNotice("Looking back through your notes…")
                allEmpty -> EmptyState()
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "memories-header") {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                "A look back at notes from this day",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                todayLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (refreshNotice != null) {
                        item(key = "refresh-notice") {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    refreshNotice ?: "",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    for (group in groups) {
                        item(key = "group-${group.yearsAgo}") {
                            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                                Text(
                                    yearLabel(group.yearsAgo),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    groupDateLabel(group.dateSec),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (group.events.isEmpty()) {
                            item(key = "group-empty-${group.yearsAgo}") {
                                Text(
                                    "Nothing from ${yearLabel(group.yearsAgo)} — relays may not keep notes this old.",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(items = group.events, key = { "${group.yearsAgo}-${it.id}" }) { event ->
                                MemoryNote(
                                    event = event,
                                    eventRepo = eventRepo,
                                    userPubkey = userPubkey,
                                    noteActions = noteActions,
                                    zapAnimatingIds = zapAnimatingIds,
                                    zapInProgressIds = zapInProgressIds,
                                    versions = engagementVersions,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Read-only memory note — mirrors OnlyFoodNote's reuse of the shared [PostCard]. */
@Composable
private fun MemoryNote(
    event: NostrEvent,
    eventRepo: EventRepository,
    userPubkey: String?,
    noteActions: NoteActions,
    zapAnimatingIds: Set<String>,
    zapInProgressIds: Set<String>,
    versions: EngagementVersions,
) {
    val profile by remember(event.pubkey) { derivedStateOf { versions.profile.value.let { eventRepo.getProfileData(event.pubkey) } } }
    val likeCount by remember(event.id) { derivedStateOf { versions.reaction.value.let { eventRepo.getReactionCount(event.id) } } }
    val replyCount by remember(event.id) { derivedStateOf { versions.reply.value.let { eventRepo.getReplyCount(event.id) } } }
    val zapSats by remember(event.id) { derivedStateOf { versions.zap.value.let { eventRepo.getZapSats(event.id) } } }
    val userEmojis by remember(event.id, userPubkey) {
        derivedStateOf { versions.reaction.value.let { userPubkey?.let { pk -> eventRepo.getUserReactionEmojis(event.id, pk) } ?: emptySet() } }
    }
    val repostCount by remember(event.id) { derivedStateOf { versions.repost.value.let { eventRepo.getRepostCount(event.id) } } }
    val hasUserReposted by remember(event.id) { derivedStateOf { versions.repost.value.let { eventRepo.hasUserReposted(event.id) } } }
    val hasUserZapped by remember(event.id) { derivedStateOf { versions.zap.value.let { eventRepo.hasUserZapped(event.id) } } }
    val reactionEmojiUrls by remember(event.id) { derivedStateOf { versions.reaction.value.let { eventRepo.getReactionEmojiUrls(event.id) } } }

    PostCard(
        event = event,
        profile = profile,
        onReply = { noteActions.onReply(event) },
        onProfileClick = { noteActions.onProfileClick(event.pubkey) },
        onNavigateToProfile = noteActions.onProfileClick,
        onNoteClick = { noteActions.onNoteClick(event.id) },
        onReact = { emoji -> noteActions.onReact(event, emoji) },
        userReactionEmojis = userEmojis,
        onRepost = { noteActions.onRepost(event) },
        onQuote = { noteActions.onQuote(event) },
        hasUserReposted = hasUserReposted,
        repostCount = repostCount,
        onZap = { noteActions.onZap(event) },
        hasUserZapped = hasUserZapped,
        likeCount = likeCount,
        replyCount = replyCount,
        zapSats = zapSats,
        isZapAnimating = event.id in zapAnimatingIds,
        isZapInProgress = event.id in zapInProgressIds,
        eventRepo = eventRepo,
        reactionEmojiUrls = reactionEmojiUrls,
        isOwnEvent = event.pubkey == userPubkey,
        onAddToList = { noteActions.onAddToList(event.id) },
        noteActions = noteActions,
    )
}

@Composable
private fun CenteredNotice(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            modifier = Modifier.padding(top = 48.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🍳", style = MaterialTheme.typography.displaySmall)
        Text(
            "No memories found for this day. Relays may not keep notes this old — or this day is still waiting for its first one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SignedOutState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📅", style = MaterialTheme.typography.displaySmall)
        Text(
            "Memories",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "A look back at notes from this day. Sign in to see yours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
