package com.wisp.app.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.Nip37
<<<<<<< HEAD
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.ui.component.parseImetaTags
import com.wisp.app.R
=======
>>>>>>> 049a7dc (added translations)
import com.wisp.app.viewmodel.DraftsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    viewModel: DraftsViewModel,
    onBack: () -> Unit,
    onDraftClick: (Nip37.Draft) -> Unit,
    onDeleteDraft: (String) -> Unit
) {
    val drafts by viewModel.drafts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
<<<<<<< HEAD
                title = { Text(stringResource(R.string.drafts_scheduled_title)) },
=======
                title = { Text(stringResource(R.string.drawer_drafts)) },
>>>>>>> 049a7dc (added translations)
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
<<<<<<< HEAD
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.drafts_tab)) },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.scheduled_tab)) },
                    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            when (selectedTab) {
                0 -> DraftsTab(
                    drafts = drafts,
                    userProfile = userProfile,
                    onDraftClick = onDraftClick,
                    onDeleteDraft = onDeleteDraft
                )
                1 -> ScheduledTab(
                    posts = scheduledPosts,
                    loading = scheduledLoading,
                    userProfile = userProfile,
                    onDelete = onDeleteScheduled
                )
            }
        }
    }
}

@Composable
private fun DraftsTab(
    drafts: List<Nip37.Draft>,
    userProfile: ProfileData?,
    onDraftClick: (Nip37.Draft) -> Unit,
    onDeleteDraft: (String) -> Unit
) {
    if (drafts.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
            message = stringResource(R.string.no_drafts_saved)
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = drafts, key = { it.dTag }) { draft ->
                DraftItem(
                    draft = draft,
                    userProfile = userProfile,
                    onClick = { onDraftClick(draft) },
                    onDelete = { onDeleteDraft(draft.dTag) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun ScheduledTab(
    posts: List<NostrEvent>,
    loading: Boolean,
    userProfile: ProfileData?,
    onDelete: (String) -> Unit
) {
    when {
        loading && posts.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        posts.isEmpty() -> {
            EmptyState(
                icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                message = stringResource(R.string.no_scheduled_posts)
            )
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = posts, key = { it.id }) { post ->
                    ScheduledPostItem(
                        post = post,
                        userProfile = userProfile,
                        onDelete = { onDelete(post.id) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun DraftItem(
    draft: Nip37.Draft,
    userProfile: ProfileData?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isReply = draft.tags.any { it.size >= 2 && it[0] == "e" }
    val displayName = userProfile?.displayString ?: stringResource(R.string.draft_label)
    val emojiMap = remember(draft.dTag) { emptyMap<String, String>() }
    val imetaMap = remember(draft.dTag) { emptyMap<String, String>() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header row — mirrors PostCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(url = userProfile?.picture)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!userProfile?.nip05.isNullOrBlank()) {
                    Text(
                        text = userProfile!!.nip05!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isReply) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.reply_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = formatDraftTimestamp(draft.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.delete_draft),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Note content — full RichContent
        RichContent(
            content = draft.content.ifBlank { stringResource(R.string.empty_content) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (draft.content.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            emojiMap = emojiMap,
            imetaMap = imetaMap
        )

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ScheduledPostItem(
    post: NostrEvent,
    userProfile: ProfileData?,
    onDelete: () -> Unit
) {
    val displayName = remember(post.pubkey, userProfile?.displayString) {
        userProfile?.displayString ?: (post.pubkey.take(8) + "…" + post.pubkey.takeLast(4))
    }
    val publishTime = post.created_at
    val isFuture = publishTime > System.currentTimeMillis() / 1000
    val emojiMap = remember(post.id) { Nip30.parseEmojiTags(post) }
    val imetaMap = remember(post.id) { parseImetaTags(post) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header row — mirrors PostCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(url = userProfile?.picture)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!userProfile?.nip05.isNullOrBlank()) {
                    Text(
                        text = userProfile!!.nip05!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Publish time badge in place of the timestamp
            Surface(
                color = if (isFuture) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = if (isFuture) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = formatScheduledTime(publishTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFuture) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            // Remove button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.remove_from_schedule),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
=======
        if (drafts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(64.dp))
                Text(
                    "No drafts",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
>>>>>>> 049a7dc (added translations)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items = drafts, key = { it.dTag }) { draft ->
                    val isReply = draft.tags.any { it.size >= 2 && it[0] == "e" }
                    val preview = draft.content.take(100)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDraftClick(draft) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (isReply) {
                                Text(
                                    text = stringResource(R.string.title_reply),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = preview.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatDraftTimestamp(draft.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { onDeleteDraft(draft.dTag) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
    }
}

private val draftTimeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)

private fun formatDraftTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    return draftTimeFormat.format(Date(epoch * 1000))
}
