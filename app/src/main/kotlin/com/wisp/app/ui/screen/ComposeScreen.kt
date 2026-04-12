package com.wisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.PowPreferences
import com.wisp.app.R
import com.wisp.app.ui.component.EmojiShortcodePopup
import com.wisp.app.ui.component.EmojiVisualTransformation
import com.wisp.app.ui.component.MentionOutputTransformation
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.ui.component.detectEmojiAutocomplete
import com.wisp.app.ui.component.insertEmojiShortcode
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.viewmodel.ComposeViewModel
import com.wisp.app.viewmodel.PowManager
import com.wisp.app.viewmodel.PowStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    viewModel: ComposeViewModel,
    relayPool: RelayPool,
    replyTo: NostrEvent?,
    quoteTo: NostrEvent? = null,
    onBack: () -> Unit,
    onSaveDraft: () -> Unit = {},
    outboxRouter: com.wisp.app.relay.OutboxRouter? = null,
    eventRepo: EventRepository? = null,
    profileRepo: ProfileRepository? = null,
    userPubkey: String? = null,
    signer: com.wisp.app.nostr.NostrSigner? = null,
    onNotePublished: (() -> Unit)? = null,
    powManager: PowManager? = null,
    powPrefs: PowPreferences? = null,
    resolvedEmojis: Map<String, String> = emptyMap()
) {
    val content by viewModel.content.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val error by viewModel.error.collectAsState()
    val uploadedUrls by viewModel.uploadedUrls.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val countdownSeconds by viewModel.countdownSeconds.collectAsState()
    val mentionCandidates by viewModel.mentionCandidates.collectAsState()
    val mentionQuery by viewModel.mentionQuery.collectAsState()
    val explicit by viewModel.explicit.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()
    val powEnabled by viewModel.powEnabled.collectAsState()
    val galleryMode by viewModel.galleryMode.collectAsState()
    val pollEnabled by viewModel.pollEnabled.collectAsState()
    val pollOptions by viewModel.pollOptions.collectAsState()
    val pollType by viewModel.pollType.collectAsState()
    val isZapPoll by viewModel.isZapPoll.collectAsState()
    val zapPollMinSats by viewModel.zapPollMinSats.collectAsState()
    val zapPollMaxSats by viewModel.zapPollMaxSats.collectAsState()
    val zapPollConsensus by viewModel.zapPollConsensus.collectAsState()
    val scheduleEnabled by viewModel.scheduleEnabled.collectAsState()
    val scheduleTimestamp by viewModel.scheduleTimestamp.collectAsState()
    val powStatus = powManager?.status?.collectAsState()?.value ?: PowStatus.Idle
    val isMiningBusy = powStatus is PowStatus.Mining
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadMedia(uris, context.contentResolver, signer)
    }

    val outputTransformation = remember(profileRepo, resolvedEmojis) {
        MentionOutputTransformation(
            resolveDisplayName = { bech32 ->
                if (profileRepo == null) return@MentionOutputTransformation null
                try {
                    val data = Nip19.decodeNostrUri("nostr:$bech32")
                    if (data is com.wisp.app.nostr.NostrUriData.ProfileRef) {
                        profileRepo.get(data.pubkey)?.displayString
                    } else null
                } catch (_: Exception) { null }
            },
            resolvedEmojis = resolvedEmojis
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            galleryMode -> stringResource(R.string.compose_gallery_mode)
                            quoteTo != null -> stringResource(R.string.compose_quote)
                            replyTo != null -> stringResource(R.string.compose_reply)
                            else -> stringResource(R.string.compose_new_post)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (replyTo != null || quoteTo != null) {
                        // No gallery toggle when replying or quoting
                    } else if (galleryMode) {
                        OutlinedButton(
                            onClick = { viewModel.toggleGalleryMode() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Article,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.compose_switch_to_text))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.toggleGalleryMode() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.compose_switch_to_gallery))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(WindowInsets.navigationBars)
                .imePadding()
        ) {
            if (galleryMode) {
                // ---- Gallery mode: completely separate layout ----
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Upload area
                    GalleryComposeSection(
                        uploadedUrls = uploadedUrls,
                        uploadProgress = uploadProgress,
                        countdownSeconds = countdownSeconds,
                        onPickMedia = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        onRemoveUrl = { viewModel.removeMediaUrl(it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Emoji shortcode autocomplete for gallery caption
                    val galleryEmojiState = remember(content) { detectEmojiAutocomplete(content) }
                    if (galleryEmojiState != null) {
                        EmojiShortcodePopup(
                            query = galleryEmojiState.query,
                            resolvedEmojis = resolvedEmojis,
                            onSelect = { shortcode ->
                                val newTfv = insertEmojiShortcode(content, galleryEmojiState.triggerIndex, shortcode)
                                viewModel.updateContent(newTfv)
                            }
                        )
                    }

                    // Caption text field (plain OutlinedTextField, no GIF keyboard / contentReceiver)
                    val galleryEmojiVisual = remember(resolvedEmojis) {
                        EmojiVisualTransformation(resolvedEmojis)
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { viewModel.updateContent(it) },
                        label = { Text(stringResource(R.string.compose_gallery_placeholder)) },
                        enabled = !publishing && countdownSeconds == null,
                        visualTransformation = galleryEmojiVisual,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        maxLines = 8
                    )

                    // Toolbar row: NSFW, PoW, Schedule only
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleExplicit() }) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = "Mark as explicit",
                                tint = if (explicit) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { powPrefs?.let { viewModel.togglePow(it) } }) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = "Proof of Work",
                                tint = if (powEnabled) WispThemeColors.zapColor
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = {
                            if (scheduleEnabled) {
                                viewModel.toggleSchedule()
                            } else {
                                viewModel.toggleSchedule()
                                showDatePicker = true
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = "Schedule post",
                                tint = if (scheduleEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.weight(1f))
                    }

                    // NSFW feedback banner
                    AnimatedVisibility(
                        visible = explicit,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.content_marked_nsfw),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Schedule info banner
                    AnimatedVisibility(
                        visible = scheduleEnabled && scheduleTimestamp != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { showDatePicker = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                val formattedTime = scheduleTimestamp?.let {
                                    val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                                    fmt.format(Date(it * 1000))
                                } ?: ""
                                Text(
                                    text = "Scheduled for $formattedTime",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove schedule",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { viewModel.toggleSchedule() }
                                )
                            }
                        }
                    }

                    // Hashtag chips
                    AnimatedVisibility(
                        visible = hashtags.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Tag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                hashtags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
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
            } else {
                // ---- Regular note mode: existing compose flow ----
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Reply context (expandable)
                    replyTo?.let {
                        val replyProfile = profileRepo?.get(it.pubkey)
                        val replyAuthorName = replyProfile?.displayString
                            ?: "${it.pubkey.take(8)}..."
                        var replyExpanded by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clickable { replyExpanded = !replyExpanded }
                        ) {
                            Column(
                                modifier = Modifier
                                    .animateContentSize()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ProfilePicture(url = replyProfile?.picture, size = 24)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.compose_replying_to),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = replyAuthorName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = if (replyExpanded) Icons.Filled.KeyboardArrowUp
                                            else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (replyExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (it.content.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = if (replyExpanded) it.content
                                            else it.content.take(140) + if (it.content.length > 140) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (replyExpanded) Int.MAX_VALUE else 2
                                    )
                                }
                            }
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

                    // Emoji shortcode autocomplete
                    val emojiState = remember(content) { detectEmojiAutocomplete(content) }
                    if (emojiState != null && mentionQuery == null) {
                        EmojiShortcodePopup(
                            query = emojiState.query,
                            resolvedEmojis = resolvedEmojis,
                            onSelect = { shortcode ->
                                val newTfv = insertEmojiShortcode(content, emojiState.triggerIndex, shortcode)
                                viewModel.updateContent(newTfv)
                            }
                        )
                    }

                    // Text field with GIF keyboard support via BasicTextField(TextFieldState)
                    val textFieldState = remember { TextFieldState(content.text) }
                    val interactionSource = remember { MutableInteractionSource() }
                    val enabled = !publishing && countdownSeconds == null

                    // Sync ViewModel -> TextFieldState (for programmatic updates: upload URL, mention select, etc.)
                    LaunchedEffect(content) {
                        if (textFieldState.text.toString() != content.text) {
                            textFieldState.edit {
                                replace(0, length, content.text)
                                selection = content.selection
                            }
                        }
                    }

                    // Sync TextFieldState -> ViewModel (for user typing)
                    LaunchedEffect(textFieldState) {
                        snapshotFlow {
                            textFieldState.text.toString() to textFieldState.selection
                        }.collect { (text, selection) ->
                            if (text != content.text) {
                                viewModel.updateContent(TextFieldValue(text, selection))
                            }
                        }
                    }

                    BasicTextField(
                        state = textFieldState,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .contentReceiver(object : ReceiveContentListener {
                                override fun onReceive(
                                    transferableContent: TransferableContent
                                ): TransferableContent? {
                                    if (!transferableContent.hasMediaType(MediaType.Image)) {
                                        return transferableContent
                                    }
                                    val clipData = transferableContent.clipEntry.clipData
                                    val uris = (0 until clipData.itemCount)
                                        .mapNotNull { i -> clipData.getItemAt(i).uri }
                                    if (uris.isNotEmpty()) {
                                        viewModel.uploadMedia(uris, context.contentResolver, signer)
                                    }
                                    return transferableContent.consume { item -> item.uri != null }
                                }
                            }),
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        outputTransformation = outputTransformation,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorator = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = textFieldState.text.toString(),
                                innerTextField = innerTextField,
                                enabled = enabled,
                                singleLine = false,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = interactionSource,
                                label = { Text(stringResource(R.string.compose_placeholder)) }
                            )
                        }
                    )

                    // Attach row with preview toggle
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        IconButton(onClick = { viewModel.toggleExplicit() }) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = "Mark as explicit",
                                tint = if (explicit) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { powPrefs?.let { viewModel.togglePow(it) } }) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = "Proof of Work",
                                tint = if (powEnabled) WispThemeColors.zapColor
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { viewModel.togglePoll() }) {
                            Icon(
                                Icons.Outlined.BarChart,
                                contentDescription = "Add poll",
                                tint = if (pollEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = {
                            if (scheduleEnabled) {
                                viewModel.toggleSchedule()
                            } else {
                                viewModel.toggleSchedule()
                                showDatePicker = true
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = "Schedule post",
                                tint = if (scheduleEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (uploadProgress != null) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.compose_uploading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.weight(1f))

                            if (content.text.isNotBlank()) {
                            TextButton(
                                onClick = onSaveDraft
                            ) {
                                Text(stringResource(R.string.btn_save_draft))
                            }
                        }
                    }

                    // Hashtag chips
                    AnimatedVisibility(
                        visible = hashtags.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Tag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                hashtags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Poll editor
                    AnimatedVisibility(
                        visible = pollEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            // Poll type selector (Standard / Zap)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                listOf(false to "Standard" , true to "Zap").forEach { (isZap, label) ->
                                    val selected = isZapPoll == isZap
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                               else Color.Transparent,
                                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { if (!selected) viewModel.toggleZapPoll() }
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                            pollOptions.forEachIndexed { index, option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    OutlinedTextField(
                                        value = option,
                                        onValueChange = { viewModel.updatePollOption(index, it) },
                                        label = { Text(stringResource(R.string.poll_option, index + 1)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (pollOptions.size > 2) {
                                        IconButton(
                                            onClick = { viewModel.removePollOption(index) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove option",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                if (pollOptions.size < 10) {
                                    TextButton(onClick = { viewModel.addPollOption() }) {
                                        Text(stringResource(R.string.poll_add_option))
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                if (!isZapPoll) {
                                    FilterChip(
                                        selected = pollType == Nip88.PollType.MULTIPLECHOICE,
                                        onClick = { viewModel.togglePollType() },
                                        label = {
                                            Text(
                                                if (pollType == Nip88.PollType.SINGLECHOICE) stringResource(R.string.poll_single_choice)
                                                else stringResource(R.string.poll_multiple_choice)
                                            )
                                        }
                                    )
                                }
                            }
                            // Zap poll settings
                            if (isZapPoll) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    OutlinedTextField(
                                        value = zapPollMinSats?.toString() ?: "",
                                        onValueChange = { viewModel.setZapPollMinSats(it.toLongOrNull()) },
                                        label = { Text("Min sats") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )
                                    OutlinedTextField(
                                        value = zapPollMaxSats?.toString() ?: "",
                                        onValueChange = { viewModel.setZapPollMaxSats(it.toLongOrNull()) },
                                        label = { Text("Max sats") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )
                                }
                            }
                        }
                    }

                    // NSFW feedback banner
                    AnimatedVisibility(
                        visible = explicit,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.content_marked_nsfw),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Schedule info banner
                    AnimatedVisibility(
                        visible = scheduleEnabled && scheduleTimestamp != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { showDatePicker = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                val formattedTime = scheduleTimestamp?.let {
                                    val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                                    fmt.format(Date(it * 1000))
                                } ?: ""
                                Text(
                                    text = "Scheduled for $formattedTime",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove schedule",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { viewModel.toggleSchedule() }
                                )
                            }
                        }
                    }

                    // Live preview
                    AnimatedVisibility(
                        visible = !imeVisible && (content.text.isNotBlank() || (pollEnabled && pollOptions.any { it.isNotBlank() })) && eventRepo != null
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
                                    emojiMap = resolvedEmojis,
                                    eventRepo = eventRepo
                                )
                                // Poll preview
                                if (pollEnabled) {
                                    val previewOptions = pollOptions.filter { it.isNotBlank() }
                                    if (previewOptions.isNotEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        previewOptions.forEachIndexed { index, label ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                                ) {
                                                    Text(
                                                        text = if (pollType == Nip88.PollType.SINGLECHOICE) "○" else "☐",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (pollType == Nip88.PollType.SINGLECHOICE) "Single choice poll"
                                                   else "Multiple choice poll",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
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
            }

            // Bottom bar — always visible above keyboard (shared by both modes)
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp)) {
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
                            Text(stringResource(R.string.btn_undo))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.publishNow() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.compose_post_now, countdownSeconds!!))
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
                                signer = signer,
                                onNotePublished = onNotePublished,
                                powManager = powManager,
                                resolvedEmojis = resolvedEmojis
                            )
                        },
                        enabled = !publishing && !isMiningBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                isMiningBusy -> stringResource(R.string.compose_mining)
                                publishing && scheduleEnabled -> stringResource(R.string.compose_scheduling)
                                publishing -> stringResource(R.string.compose_publishing)
                                scheduleEnabled -> stringResource(R.string.compose_schedule_post)
                                else -> stringResource(R.string.compose_publish)
                            }
                        )
                    }
                }
            }
        }

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = (scheduleTimestamp ?: (System.currentTimeMillis() / 1000 + 3600)) * 1000
            )
            DatePickerDialog(
                onDismissRequest = {
                    showDatePicker = false
                    if (scheduleTimestamp == null) viewModel.toggleSchedule()
                },
                confirmButton = {
                    TextButton(onClick = {
                        val selectedDate = datePickerState.selectedDateMillis
                        if (selectedDate != null) {
                            pendingDateMillis = selectedDate
                            showDatePicker = false
                            showTimePicker = true
                        }
                    }) { Text("Next") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDatePicker = false
                        if (scheduleTimestamp == null) viewModel.toggleSchedule()
                    }) { Text("Cancel") }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showTimePicker) {
            val cal = Calendar.getInstance().apply {
                scheduleTimestamp?.let { timeInMillis = it * 1000 }
            }
            val timePickerState = rememberTimePickerState(
                initialHour = cal.get(Calendar.HOUR_OF_DAY),
                initialMinute = cal.get(Calendar.MINUTE)
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showTimePicker = false
                    if (scheduleTimestamp == null) viewModel.toggleSchedule()
                },
                title = { Text("Select time") },
                text = { TimePicker(state = timePickerState) },
                confirmButton = {
                    TextButton(onClick = {
                        val datePart = pendingDateMillis ?: return@TextButton
                        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = datePart
                        }
                        val calendar = Calendar.getInstance().apply {
                            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        viewModel.setScheduleTimestamp(calendar.timeInMillis / 1000)
                        showTimePicker = false
                        pendingDateMillis = null
                    }) { Text("Confirm") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showTimePicker = false
                        pendingDateMillis = null
                        if (scheduleTimestamp == null) viewModel.toggleSchedule()
                    }) { Text("Cancel") }
                }
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryComposeSection(
    uploadedUrls: List<String>,
    uploadProgress: String?,
    countdownSeconds: Int?,
    onPickMedia: () -> Unit,
    onRemoveUrl: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (uploadedUrls.isEmpty()) {
            // Empty state — prominent upload area
            Surface(
                onClick = onPickMedia,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (uploadProgress != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                uploadProgress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.compose_gallery_tap_to_add),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Image pager with uploaded media
            val pagerState = rememberPagerState(pageCount = { uploadedUrls.size })
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = uploadedUrls[page],
                            contentDescription = "Uploaded media ${page + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Remove button
                        IconButton(
                            onClick = { onRemoveUrl(uploadedUrls[page]) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Page indicator dots
                if (uploadedUrls.size > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        repeat(uploadedUrls.size) { index ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage)
                                            Color.White
                                        else
                                            Color.White.copy(alpha = 0.4f)
                                    )
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Add more / uploading indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPickMedia,
                    enabled = uploadProgress == null && countdownSeconds == null,
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add more", style = MaterialTheme.typography.labelMedium)
                }
                if (uploadProgress != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        uploadProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${uploadedUrls.size} ${if (uploadedUrls.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
