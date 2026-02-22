package com.wisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.ui.component.MentionVisualTransformation
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.viewmodel.ComposeViewModel
import androidx.compose.foundation.border

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    viewModel: ComposeViewModel,
    relayPool: RelayPool,
    replyTo: NostrEvent?,
    quoteTo: NostrEvent? = null,
    onBack: () -> Unit,
    outboxRouter: com.wisp.app.relay.OutboxRouter? = null,
    eventRepo: EventRepository? = null,
    profileRepo: ProfileRepository? = null,
    userPubkey: String? = null,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val content by viewModel.content.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val error by viewModel.error.collectAsState()
    val uploadedUrls by viewModel.uploadedUrls.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val countdownSeconds by viewModel.countdownSeconds.collectAsState()
    val mentionCandidates by viewModel.mentionCandidates.collectAsState()
    val mentionQuery by viewModel.mentionQuery.collectAsState()
    val previewVisible by viewModel.previewVisible.collectAsState()
    val context = LocalContext.current

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.uploadMedia(uri, context.contentResolver)
    }

    val accentColor = MaterialTheme.colorScheme.primary
    val visualTransformation = remember(accentColor, profileRepo) {
        MentionVisualTransformation(
            accentColor = accentColor,
            resolveDisplayName = { bech32 ->
                if (profileRepo == null) return@MentionVisualTransformation null
                try {
                    val data = Nip19.decodeNostrUri("nostr:$bech32")
                    if (data is com.wisp.app.nostr.NostrUriData.ProfileRef) {
                        profileRepo.get(data.pubkey)?.displayString
                    } else null
                } catch (_: Exception) { null }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            quoteTo != null -> "Quote"
                            replyTo != null -> "Reply"
                            else -> "New Post"
                        }
                    )
                },
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
        // Outer non-scrollable Column handles IME padding
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
        ) {
            // Inner scrollable content takes remaining space
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Reply context
                replyTo?.let {
                    val replyProfile = profileRepo?.get(it.pubkey)
                    val replyAuthorName = replyProfile?.displayString
                        ?: "${it.pubkey.take(8)}..."
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfilePicture(url = replyProfile?.picture, size = 24)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Replying to ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = replyAuthorName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (it.content.isNotBlank()) {
                        Text(
                            text = it.content.take(140) + if (it.content.length > 140) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }

                // Quote context with resolved display names
                quoteTo?.let {
                    val quoteAuthorName = profileRepo?.get(it.pubkey)?.displayString
                        ?: "${it.pubkey.take(8)}..."
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = quoteAuthorName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = it.content.take(200) + if (it.content.length > 200) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4
                            )
                        }
                    }
                }

                // Mention autocomplete dropdown
                AnimatedVisibility(
                    visible = mentionQuery != null && mentionCandidates.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 3.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(bottom = 4.dp)
                    ) {
                        LazyColumn {
                            items(mentionCandidates, key = { it.profile.pubkey }) { candidate ->
                                MentionCandidateRow(
                                    candidate = candidate,
                                    onClick = { viewModel.selectMention(candidate) }
                                )
                            }
                        }
                    }
                }

                // Text field with visual transformation
                OutlinedTextField(
                    value = content,
                    onValueChange = { viewModel.updateContent(it) },
                    label = { Text("What's on your mind?") },
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    enabled = !publishing && countdownSeconds == null
                )

                // Media previews
                if (uploadedUrls.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    uploadedUrls.forEach { url ->
                        Box(modifier = Modifier.padding(bottom = 8.dp)) {
                            AsyncImage(
                                model = url,
                                contentDescription = "Attached media",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { viewModel.removeMediaUrl(url) },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Attach row with preview toggle
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        enabled = uploadProgress == null && countdownSeconds == null
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = "Attach media")
                    }

                    if (eventRepo != null) {
                        IconButton(onClick = { viewModel.togglePreview() }) {
                            Icon(
                                if (previewVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (previewVisible) "Hide Preview" else "Preview"
                            )
                        }
                    }

                    uploadProgress?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live preview
                AnimatedVisibility(
                    visible = previewVisible && !imeVisible && content.text.isNotBlank() && eventRepo != null
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val userProfile = userPubkey?.let { profileRepo?.get(it) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                ProfilePicture(url = userProfile?.picture, size = 32)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = userProfile?.displayString ?: "You",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Preview",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            RichContent(
                                content = content.text,
                                eventRepo = eventRepo
                            )
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Bottom bar — always visible above keyboard
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp, top = 4.dp)) {
                if (countdownSeconds != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.cancelPublish() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Undo")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.publishNow() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Post now (${countdownSeconds}s)")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.publish(
                                relayPool = relayPool,
                                replyTo = replyTo,
                                quoteTo = quoteTo,
                                onSuccess = { onBack() },
                                outboxRouter = outboxRouter,
                                signer = signer
                            )
                        },
                        enabled = !publishing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                uploadProgress != null -> uploadProgress!!
                                publishing -> "Uploading..."
                                else -> "Publish"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionCandidateRow(
    candidate: MentionCandidate,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(
            url = candidate.profile.picture,
            size = 32,
            showFollowBadge = candidate.isContact
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.profile.displayString,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val subtitle = candidate.profile.name?.let { "@$it" }
                ?: candidate.profile.nip05
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (candidate.isContact) {
            Text(
                text = "Following",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
