package com.wisp.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.net.Uri
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupPreview
import com.wisp.app.ui.component.EmojiReactionPopup
import com.wisp.app.ui.component.EmojiShortcodePopup
import com.wisp.app.ui.component.pendingEmojiReactCallback
import com.wisp.app.ui.component.MentionOutputTransformation
import com.wisp.app.ui.component.insertEmojiShortcode
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.viewmodel.GroupRoomViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.math.BigInteger
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GroupRoomScreen(
    viewModel: GroupRoomViewModel,
    initialRoom: com.wisp.app.repo.GroupRoom? = null,
    scrollToMessageId: String? = null,
    relayPool: RelayPool,
    eventRepo: EventRepository,
    signer: NostrSigner?,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: ((String) -> Unit)? = null,
    onGroupDetail: () -> Unit = {},
    onPickMedia: (() -> Unit)? = null,
    onUploadMedia: ((List<Uri>, onUrl: (String) -> Unit) -> Unit)? = null,
    uploadProgress: String? = null,
    onJoin: (() -> Unit)? = null,
    onAlreadyMember: (() -> Unit)? = null,
    fetchGroupPreview: (suspend (String, String) -> GroupPreview?)? = null,
    myPubkey: String? = null,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)? = null,
    onZapPreset: ((messageId: String, senderPubkey: String, amountSats: Int) -> Unit)? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    zapVersion: Int = 0,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onFollowAuthor: ((String) -> Unit)? = null,
    onBlockAuthor: ((String) -> Unit)? = null,
    isFollowing: ((String) -> Boolean)? = null,
    noteActions: com.wisp.app.ui.component.NoteActions? = null,
    onEmojiUsed: ((String) -> Unit)? = null
) {
    val textFieldFocus = remember { FocusRequester() }

    val messages by viewModel.messages.collectAsState()
    val room by viewModel.room.collectAsState()
    // Use initialRoom as a fallback on the first frame before the ViewModel's LaunchedEffect
    // runs — prevents flashing the join screen for groups the user has already joined.
    val effectiveRoom = room ?: initialRoom
    val messageText by viewModel.messageText.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val relayError by viewModel.relayError.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()

    // In-chat search state
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchCurrentIndex by remember { mutableIntStateOf(0) }

    // BasicTextField state for GIF keyboard support via contentReceiver
    val textFieldState = remember { TextFieldState() }
    val groupEmojiTransformation = remember(resolvedEmojis) {
        MentionOutputTransformation(resolveDisplayName = { null }, resolvedEmojis = resolvedEmojis)
    }

    // TextFieldValue mirror for cursor-aware autocomplete detection
    var tfv by remember { mutableStateOf(TextFieldValue()) }

    // Sync ViewModel → TextFieldState (for appendToText / external clears)
    LaunchedEffect(messageText) {
        if (textFieldState.text.toString() != messageText) {
            textFieldState.edit {
                replace(0, length, messageText)
                selection = TextRange(messageText.length)
            }
        }
        if (tfv.text != messageText) {
            tfv = TextFieldValue(messageText, TextRange(messageText.length))
        }
    }

    // Sync TextFieldState → ViewModel (user typing / GIF keyboard insertions)
    LaunchedEffect(textFieldState) {
        snapshotFlow {
            textFieldState.text.toString() to textFieldState.selection
        }.collect { (text, selection) ->
            if (text != messageText) {
                viewModel.updateText(text)
                tfv = TextFieldValue(text, selection)
            }
        }
    }

    val listState = rememberLazyListState()
    var prevMessageCount by remember { mutableIntStateOf(0) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var highlightTrigger by remember { mutableIntStateOf(0) }
    val scrollScope = rememberCoroutineScope()
    var textFieldFocused by remember { mutableStateOf(false) }

    // Track whether we've handled the initial scrollToMessageId target
    var scrollTargetHandled by remember { mutableStateOf(scrollToMessageId == null) }

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (prevMessageCount == 0) {
            // First load: scroll to target message if provided, otherwise bottom
            if (!scrollTargetHandled && scrollToMessageId != null) {
                val index = messages.indexOfFirst { it.id == scrollToMessageId }
                if (index >= 0) {
                    listState.scrollToItem(index)
                    highlightedMessageId = scrollToMessageId
                    highlightTrigger++
                    // Clear the search-style blink after the fade animation finishes
                    kotlinx.coroutines.delay(1500)
                    highlightedMessageId = null
                    scrollTargetHandled = true
                } else {
                    listState.scrollToItem(messages.size - 1)
                }
            } else {
                listState.scrollToItem(messages.size - 1)
            }
        } else if (!scrollTargetHandled && scrollToMessageId != null) {
            // Messages arrived after initial load — check if target is now available
            val index = messages.indexOfFirst { it.id == scrollToMessageId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                highlightedMessageId = scrollToMessageId
                highlightTrigger++
                kotlinx.coroutines.delay(1500)
                highlightedMessageId = null
                scrollTargetHandled = true
            }
        } else if (messages.size > prevMessageCount) {
            listState.animateScrollToItem(messages.size - 1)
        }
        prevMessageCount = messages.size
    }

    // Snap to newest messages when keyboard opens
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    val isJoined = effectiveRoom != null
    val title = effectiveRoom?.metadata?.name ?: viewModel.groupId.ifEmpty { "Chat Room" }

    val onlinePubkeys by eventRepo.onlinePubkeys.collectAsState()
    val groupMembersSet = remember(effectiveRoom?.members) {
        effectiveRoom?.members?.toSet() ?: emptySet()
    }
    val onlineMembersForGroup = remember(onlinePubkeys, groupMembersSet) {
        if (groupMembersSet.isEmpty()) onlinePubkeys
        else onlinePubkeys.filter { it in groupMembersSet }
    }
    val topBarSurfaceColor = MaterialTheme.colorScheme.surface

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onGroupDetail() }
                    ) {
                        ProfilePicture(url = effectiveRoom?.metadata?.picture, size = 40)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (onlineMembersForGroup.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 1.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                        onlineMembersForGroup.take(4).forEachIndexed { i, pubkey ->
                                            Box(
                                                modifier = Modifier
                                                    .zIndex((4 - i).toFloat())
                                                    .size(17.dp)
                                                    .background(topBarSurfaceColor, CircleShape)
                                                    .padding(0.5.dp)
                                            ) {
                                                ProfilePicture(
                                                    url = eventRepo.getProfileData(pubkey)?.picture,
                                                    size = 16
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${onlineMembersForGroup.size} Online",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchActive = !searchActive
                        if (!searchActive) {
                            searchQuery = ""
                            searchMatches = emptyList()
                            searchCurrentIndex = 0
                            highlightedMessageId = null
                        }
                    }) {
                        Icon(
                            if (searchActive) Icons.Outlined.Close else Icons.Filled.Search,
                            contentDescription = "Search messages"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (!isJoined && onJoin != null) {
            JoinRoomPrompt(
                relayUrl = viewModel.relayUrl,
                groupId = viewModel.groupId,
                fetchGroupPreview = fetchGroupPreview,
                onJoin = onJoin,
                onAlreadyMember = onAlreadyMember,
                myPubkey = myPubkey,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                // Search bar
                if (searchActive) {
                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { try { searchFocusRequester.requestFocus() } catch (_: IllegalStateException) {} }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        val executeSearch = {
                            val query = searchQuery
                            if (query.isBlank()) {
                                searchMatches = emptyList()
                                searchCurrentIndex = 0
                                highlightedMessageId = null
                            } else {
                                val lowerQuery = query.lowercase()
                                val matches = messages.indices.filter {
                                    messages[it].content.lowercase().contains(lowerQuery)
                                }
                                searchMatches = matches
                                if (matches.isNotEmpty()) {
                                    searchCurrentIndex = 0
                                    highlightedMessageId = messages[matches[0]].id
                                } else {
                                    searchCurrentIndex = 0
                                    highlightedMessageId = null
                                }
                            }
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search messages…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                                .focusRequester(searchFocusRequester)
                        )
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = if (searchMatches.isEmpty()) "0/0"
                                       else "${searchCurrentIndex + 1}/${searchMatches.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    if (searchMatches.isNotEmpty()) {
                                        val prev = (searchCurrentIndex - 1 + searchMatches.size) % searchMatches.size
                                        searchCurrentIndex = prev
                                        highlightedMessageId = messages[searchMatches[prev]].id
                                    }
                                },
                                enabled = searchMatches.size > 1,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match", modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (searchMatches.isNotEmpty()) {
                                        val next = (searchCurrentIndex + 1) % searchMatches.size
                                        searchCurrentIndex = next
                                        highlightedMessageId = messages[searchMatches[next]].id
                                    }
                                },
                                enabled = searchMatches.size > 1,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                }

                // Scroll to search result when navigating matches
                LaunchedEffect(searchCurrentIndex, searchMatches) {
                    if (searchMatches.isEmpty()) return@LaunchedEffect
                    val msgIndex = searchMatches[searchCurrentIndex]
                    listState.animateScrollToItem(msgIndex)
                    highlightedMessageId = messages[msgIndex].id
                    highlightTrigger++
                }

                val density = LocalDensity.current
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }
                val pasteImagesModifier =
                    if (onUploadMedia != null) {
                        Modifier.contentReceiver(object : ReceiveContentListener {
                            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                                if (!transferableContent.hasMediaType(MediaType.Image)) {
                                    return transferableContent
                                }
                                val clipData = transferableContent.clipEntry.clipData
                                val uris = (0 until clipData.itemCount)
                                    .mapNotNull { i -> clipData.getItemAt(i).uri }
                                if (uris.isNotEmpty()) {
                                    onUploadMedia(uris) { url -> viewModel.appendToText(url) }
                                }
                                return transferableContent.consume { item -> item.uri != null }
                            }
                        })
                    } else {
                        Modifier
                    }
                val fieldBorder = BorderStroke(
                    0.33.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
                val sendEnabled =
                    messageText.isNotBlank() && signer != null && uploadProgress == null && !sending

                @Composable
                fun MessageComposerField(
                    lineLimits: TextFieldLineLimits,
                    modifier: Modifier = Modifier
                ) {
                    BasicTextField(
                        state = textFieldState,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = modifier
                            .focusRequester(textFieldFocus)
                            .onFocusChanged { textFieldFocused = it.isFocused }
                            .then(pasteImagesModifier),
                        enabled = uploadProgress == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        outputTransformation = groupEmojiTransformation,
                        lineLimits = lineLimits,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorator = { innerTextField ->
                            Box {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        stringResource(R.string.placeholder_message),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = with(density) { bottomBarHeightPx.toDp() })
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            GroupMessageBubble(
                                message = message,
                                isSearchHighlighted = message.id == highlightedMessageId,
                                allMessages = messages,
                                eventRepo = eventRepo,
                                myPubkey = myPubkey,
                                reactionEmojiUrls = effectiveRoom?.reactionEmojiUrls ?: emptyMap(),
                                resolvedEmojis = resolvedEmojis,
                                unicodeEmojis = unicodeEmojis,
                                zapVersion = zapVersion,
                                isZapAnimating = message.id in zapAnimatingIds,
                                isZapInProgress = message.id in zapInProgressIds,
                                highlightTrigger = if (message.id == highlightedMessageId) highlightTrigger else 0,
                                onProfileClick = onProfileClick,
                                onNoteClick = onNoteClick,
                                onReply = {
                                    viewModel.setReplyTarget(it)
                                    textFieldFocus.requestFocus()
                                },
                                onScrollToMessage = { targetId ->
                                    val index = messages.indexOfFirst { it.id == targetId }
                                    if (index >= 0) {
                                        scrollScope.launch {
                                            listState.animateScrollToItem(index)
                                            highlightedMessageId = targetId
                                            highlightTrigger++
                                        }
                                    }
                                },
                                onReact = { msgId, pubkey, emoji ->
                                    viewModel.sendReaction(msgId, pubkey, emoji, signer, relayPool, resolvedEmojis)
                                    onEmojiUsed?.invoke(emoji)
                                },
                                onZap = onZap,
                                onZapPreset = onZapPreset,
                                onOpenEmojiLibrary = onOpenEmojiLibrary,
                                onFollowAuthor = onFollowAuthor,
                                onBlockAuthor = onBlockAuthor,
                                isFollowing = isFollowing,
                                noteActions = noteActions
                            )
                        }
                    }

                    // Overlay: errors, autocomplete, and the message composer float above the list
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .onSizeChanged { bottomBarHeightPx = it.height }
                    ) {
                        if (relayError != null && messages.isEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = relayError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        sendError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // Autocomplete dropdowns
                        val autocomplete = remember(tfv) { detectGroupAutocomplete(tfv) }
                        if (autocomplete != null) {
                            val members = effectiveRoom?.members ?: emptyList()
                            when (autocomplete.mode) {
                                AutocompleteMode.MENTION -> {
                                    val query = autocomplete.query.lowercase()
                                    val candidates = remember(query, members) {
                                        members.mapNotNull { pubkey ->
                                            val p = eventRepo.getProfileData(pubkey) ?: return@mapNotNull null
                                            val name = p.name?.lowercase() ?: ""
                                            val display = p.displayName?.lowercase() ?: ""
                                            if (query.isEmpty() || name.contains(query) || display.contains(query)) p
                                            else null
                                        }.take(6)
                                    }
                                    if (candidates.isNotEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            tonalElevation = 3.dp,
                                            shadowElevation = 2.dp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 200.dp)
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            androidx.compose.foundation.lazy.LazyColumn {
                                                items(candidates, key = { it.pubkey }) { profile ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                val npub = "nostr:" + Nip19.nprofileEncode(profile.pubkey)
                                                                val newTfv = insertAutocomplete(tfv, autocomplete.triggerIndex, npub)
                                                                tfv = newTfv
                                                                viewModel.updateText(newTfv.text)
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        ProfilePicture(url = profile.picture, size = 28)
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            text = profile.displayString,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                AutocompleteMode.EMOJI -> {
                                    EmojiShortcodePopup(
                                        query = autocomplete.query,
                                        resolvedEmojis = resolvedEmojis,
                                        onSelect = { shortcode ->
                                            val newTfv = insertEmojiShortcode(tfv, autocomplete.triggerIndex, shortcode)
                                            tfv = newTfv
                                            viewModel.updateText(newTfv.text)
                                        }
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                                .navigationBarsPadding()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = fieldBorder
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    if (replyTarget != null) {
                                        val rt = replyTarget!!
                                        val replyProfile = remember(rt.senderPubkey) {
                                            eventRepo.getProfileData(rt.senderPubkey)
                                        }
                                        val replyName =
                                            replyProfile?.displayString ?: rt.senderPubkey.take(8) + "…"
                                        GroupChatQuotedMessage(
                                            authorPubkey = rt.senderPubkey,
                                            authorName = replyName,
                                            content = rt.content,
                                            modifier = Modifier.fillMaxWidth(),
                                            maxContentLines = 1,
                                            onDismiss = { viewModel.clearReplyTarget() }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (onPickMedia != null) {
                                            Surface(
                                                onClick = onPickMedia,
                                                enabled = uploadProgress == null && !sending,
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Add,
                                                        contentDescription = stringResource(R.string.cd_attach_media),
                                                        tint = if (uploadProgress == null && !sending) {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                                        },
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        MessageComposerField(
                                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 28.dp)
                                        )
                                        if (sending || uploadProgress != null) {
                                            Box(
                                                modifier = Modifier.size(36.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    viewModel.sendMessage(relayPool, signer, resolvedEmojis)
                                                },
                                                enabled = sendEnabled,
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = stringResource(R.string.cd_send),
                                                    tint = if (sendEnabled) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinRoomPrompt(
    relayUrl: String,
    groupId: String,
    fetchGroupPreview: (suspend (String, String) -> GroupPreview?)?,
    onJoin: () -> Unit,
    onAlreadyMember: (() -> Unit)? = null,
    myPubkey: String? = null,
    modifier: Modifier = Modifier
) {
    var preview by remember(relayUrl, groupId) { mutableStateOf<GroupPreview?>(null) }
    var metadataLoading by remember(relayUrl, groupId) { mutableStateOf(fetchGroupPreview != null) }

    LaunchedEffect(relayUrl, groupId) {
        if (relayUrl.isNotEmpty() && groupId.isNotEmpty() && fetchGroupPreview != null) {
            val result = fetchGroupPreview(relayUrl, groupId)
            // If the user's pubkey appears in the relay's members list they joined via another
            // client — silently register the group locally instead of showing the join prompt.
            if (myPubkey != null && result?.members?.contains(myPubkey) == true) {
                onAlreadyMember?.invoke()
                return@LaunchedEffect
            }
            preview = result
            metadataLoading = false
        } else if (relayUrl.isEmpty() || groupId.isEmpty()) {
            metadataLoading = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            if (metadataLoading) {
                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                ProfilePicture(url = preview?.metadata?.picture, size = 80)
            }
            Text(
                text = preview?.metadata?.name ?: groupId,
                style = MaterialTheme.typography.titleLarge
            )
            val host = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
            Text(
                text = host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            preview?.metadata?.about?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val memberCount = preview?.members?.size ?: 0
            if (memberCount > 0) {
                Text(
                    text = "$memberCount member${if (memberCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onJoin) {
                Text("Join Chat Room")
            }
        }
    }
}

@Composable
private fun GroupMemberAvatarStrip(
    messages: List<GroupMessage>,
    members: List<String>,
    eventRepo: EventRepository,
    onProfileClick: (String) -> Unit
) {
    val maxShown = 12
    val orderedMembers = remember(messages, members) {
        val memberSet = members.toSet()
        // Unique senders in reverse-chronological order, filtered to known members
        val recentSenders = messages.reversed().map { it.senderPubkey }
            .distinct()
            .filter { it in memberSet || memberSet.isEmpty() }
        val remaining = members.filter { it !in recentSenders }
        (recentSenders + remaining).take(maxShown)
    }

    if (orderedMembers.isEmpty()) return

    val overflow = (members.size - orderedMembers.size).coerceAtLeast(0)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Overlapping avatar stack: draw in reverse so index 0 (most recent) appears on top
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            orderedMembers.forEachIndexed { i, pubkey ->
                val profile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
                Box(
                    modifier = Modifier
                        .zIndex((orderedMembers.size - i).toFloat())
                        .size(28.dp)
                        .background(surfaceColor, CircleShape)
                        .padding(1.dp)
                ) {
                    ProfilePicture(
                        url = profile?.picture,
                        size = 26,
                        onClick = { onProfileClick(pubkey) }
                    )
                }
            }
            if (overflow > 0) {
                Box(
                    modifier = Modifier
                        .zIndex(0f)
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val GROUP_CHAT_ZAP_PRESET_AMOUNTS = listOf(21, 69, 100, 420, 500, 1000, 2500, 5000, 10000)

private fun formatGroupCamChipAmount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1000 -> "${n / 1000}K"
    else -> n.toString()
}

/** Left accent bar color (raw profile hue), matching web QuotedMessage bar. */
private fun profileBarColorFromPubkey(pubkey: String): Color {
    val rgb = hexToProfileRgb(pubkey)
    return Color(rgb.r / 255f, rgb.g / 255f, rgb.b / 255f)
}

@Composable
private fun GroupChatSectionEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

/** Compact quote row matching web `QuotedMessage.svelte` (bar + name + preview). */
@Composable
private fun GroupChatQuotedMessage(
    authorPubkey: String,
    authorName: String,
    content: String,
    modifier: Modifier = Modifier,
    maxContentLines: Int = 1,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val quoteBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val backgroundScheme = MaterialTheme.colorScheme.background
    val isDarkTheme =
        (backgroundScheme.red * 0.299f + backgroundScheme.green * 0.587f + backgroundScheme.blue * 0.114f) < 0.5f
    val nameColor = remember(authorPubkey, isDarkTheme) { groupMemberColor(authorPubkey, isDarkTheme) }
    val barColor = remember(authorPubkey) { profileBarColorFromPubkey(authorPubkey) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(quoteBg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(barColor, RoundedCornerShape(2.dp))
        )
        Column(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Author row — optionally includes a dismiss button on the right
            if (onDismiss != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onDismiss()
                            }
                    )
                }
            } else {
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = maxContentLines,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun GroupChatHorizontalChipStrip(
    scrollState: ScrollState,
    stripBackground: Color,
    chipFill: Color,
    chevronBackground: Color = chipFill,
    verticalPadding: androidx.compose.ui.unit.Dp = 4.dp,
    chipSpacing: androidx.compose.ui.unit.Dp = 2.dp,
    trailingOnClick: () -> Unit,
    trailingEnabled: Boolean,
    trailingContentDescription: String,
    chips: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(stripBackground)
            .padding(horizontal = 8.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chips area — clipped to its bounds, gradient fades to strip bg on the right
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(chipSpacing)
            ) {
                chips()
                // Extra trailing space so last chip can scroll past gradient
                Spacer(Modifier.width(36.dp))
            }
            // Gradient fade at the right edge — use alpha-0 of the same color for correct compositing
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(stripBackground.copy(alpha = 0f), stripBackground)
                        )
                    )
            )
        }
        // No spacer — zero gap between chips and chevron
        Surface(
            onClick = trailingOnClick,
            enabled = trailingEnabled,
            shape = RoundedCornerShape(8.dp),
            color = chevronBackground,
            modifier = Modifier.size(32.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = trailingContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    isSearchHighlighted: Boolean = false,
    allMessages: List<GroupMessage>,
    eventRepo: EventRepository,
    myPubkey: String?,
    reactionEmojiUrls: Map<String, String>,
    resolvedEmojis: Map<String, String>,
    unicodeEmojis: List<String>,
    zapVersion: Int = 0,
    isZapAnimating: Boolean = false,
    isZapInProgress: Boolean = false,
    highlightTrigger: Int = 0,
    onProfileClick: (String) -> Unit,
    onNoteClick: ((String) -> Unit)? = null,
    onReply: (GroupMessage) -> Unit,
    onScrollToMessage: ((String) -> Unit)? = null,
    onReact: (messageId: String, senderPubkey: String, emoji: String) -> Unit,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)? = null,
    onZapPreset: ((messageId: String, senderPubkey: String, amountSats: Int) -> Unit)? = null,
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onFollowAuthor: ((String) -> Unit)? = null,
    onBlockAuthor: ((String) -> Unit)? = null,
    isFollowing: ((String) -> Boolean)? = null,
    noteActions: com.wisp.app.ui.component.NoteActions? = null
) {
    val profile = remember(message.senderPubkey) { eventRepo.getProfileData(message.senderPubkey) }
    val displayName = profile?.displayString ?: (message.senderPubkey.take(8) + "…")
    val background = MaterialTheme.colorScheme.background
    val isDarkTheme = (background.red * 0.299f + background.green * 0.587f + background.blue * 0.114f) < 0.5f
    val nameColor = remember(message.senderPubkey, isDarkTheme) { groupMemberColor(message.senderPubkey, isDarkTheme) }

    var showEmojiPicker by remember(message.id) { mutableStateOf(false) }
    var showActionsSheet by remember(message.id) { mutableStateOf(false) }
    var showDetailsSheet by remember(message.id) { mutableStateOf(false) }
    val actionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val rowTapInteraction = remember { MutableInteractionSource() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val myReactions = remember(message.reactions, myPubkey) {
        if (myPubkey == null) emptySet()
        else message.reactions.filter { (_, reactors) -> myPubkey in reactors }.keys.toSet()
    }

    val replyToMessage = remember(message.replyToId, allMessages) {
        message.replyToId?.let { id -> allMessages.firstOrNull { it.id == id } }
    }

    val messageEmojiMap = remember(message.emojiTags, reactionEmojiUrls, resolvedEmojis) {
        resolvedEmojis + reactionEmojiUrls + message.emojiTags
    }

    val zapSats = remember(message.id, zapVersion) { eventRepo.getZapSats(message.id) }
    val hasZaps = zapSats > 0
    val isOwnMessage = message.senderPubkey == myPubkey

    val replyHighlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlightTrigger) {
        if (highlightTrigger > 0) {
            replyHighlightAlpha.snapTo(0.3f)
            replyHighlightAlpha.animateTo(0f, tween(durationMillis = 1200))
        }
    }

    val searchHighlightAlpha = if (isSearchHighlighted) {
        val transition = rememberInfiniteTransition(label = "searchHighlight")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "highlightAlpha"
        ).value
    } else 0f

    val highlightAlpha = if (isSearchHighlighted) searchHighlightAlpha else replyHighlightAlpha.value

    val swipeOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThreshold = remember(density) { with(density) { 80.dp.toPx() } }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(22.dp)
                .scale(scaleX = -1f, scaleY = -1f)
                .alpha((swipeOffset.value / swipeThreshold).coerceIn(0f, 1f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .offset { IntOffset(swipeOffset.value.toInt(), 0) }
                .pointerInput(message.id) {
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = { triggered = false },
                        onDragEnd = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                            triggered = false
                        },
                        onDragCancel = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                            triggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount > 0 && !triggered) {
                                val next = (swipeOffset.value + dragAmount).coerceIn(0f, swipeThreshold * 1.3f)
                                scope.launch { swipeOffset.snapTo(next) }
                                if (next >= swipeThreshold) {
                                    triggered = true
                                    onReply(message)
                                    scope.launch { swipeOffset.animateTo(0f, spring()) }
                                }
                            }
                        }
                    )
                }
                .clickable(
                    interactionSource = rowTapInteraction,
                    indication = null
                ) { showActionsSheet = true },
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isOwnMessage) {
                ProfilePicture(
                    url = profile?.picture,
                    size = 36,
                    onClick = { onProfileClick(message.senderPubkey) }
                )
                Spacer(Modifier.width(10.dp))
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f, fill = isOwnMessage),
                contentAlignment = if (isOwnMessage) Alignment.BottomEnd else Alignment.BottomStart
            ) {
                Surface(
                    shape = if (isOwnMessage)
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
                    else
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
                    color = if (isOwnMessage) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    modifier = Modifier
                        .widthIn(min = 80.dp, max = maxWidth - 28.dp)
                        .wrapContentWidth(align = if (isOwnMessage) Alignment.End else Alignment.Start)
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Header row hidden for own messages
                        if (!isOwnMessage) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = nameColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (hasZaps) {
                                    Text(
                                        text = formatZapSats(zapSats),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = com.wisp.app.ui.theme.WispThemeColors.zapColor
                                    )
                                }
                                Text(
                                    text = formatGroupTimestamp(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        if (replyToMessage != null) {
                            Spacer(Modifier.height(6.dp))
                            val replyProfile = remember(replyToMessage.senderPubkey) {
                                eventRepo.getProfileData(replyToMessage.senderPubkey)
                            }
                            val replyName = replyProfile?.displayString ?: replyToMessage.senderPubkey.take(8) + "…"
                            GroupChatQuotedMessage(
                                authorPubkey = replyToMessage.senderPubkey,
                                authorName = replyName,
                                content = replyToMessage.content,
                                onClick = { onScrollToMessage?.invoke(replyToMessage.id) }
                            )
                        }

                        Spacer(Modifier.height(5.dp))
                        RichContent(
                            content = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            emojiMap = messageEmojiMap,
                            eventRepo = eventRepo,
                            onProfileClick = onProfileClick,
                            onNoteClick = onNoteClick,
                            noteActions = noteActions
                        )

                        if (message.reactions.isNotEmpty()) {
                            val reactionScrollState = rememberScrollState()
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(reactionScrollState)
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                message.reactions.forEach { (emoji, reactors) ->
                                    val isMe = myPubkey != null && myPubkey in reactors
                                    val shortcode = if (emoji.startsWith(":") && emoji.endsWith(":")) emoji.removeSurrounding(":") else null
                                    val emojiUrl = shortcode?.let { reactionEmojiUrls[it] ?: resolvedEmojis[it] }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                        modifier = Modifier.clickable { onReact(message.id, message.senderPubkey, emoji) }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.size(18.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (emojiUrl != null) {
                                                    AsyncImage(
                                                        model = emojiUrl,
                                                        contentDescription = emoji,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Text(text = emoji, fontSize = 13.sp, lineHeight = 18.sp)
                                                }
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            val displayReactors = reactors.take(3)
                                            displayReactors.forEachIndexed { i, pubkey ->
                                                val reactorProfile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
                                                ProfilePicture(
                                                    url = reactorProfile?.picture,
                                                    size = 16,
                                                    modifier = if (i > 0) Modifier.offset(x = (i * -4).dp) else Modifier
                                                )
                                            }
                                            if (reactors.size > 3) {
                                                Text(
                                                    text = "+${reactors.size - 3}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.offset(x = (displayReactors.size * -4).dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEmojiPicker) {
        EmojiReactionPopup(
            onSelect = { emoji ->
                onReact(message.id, message.senderPubkey, emoji)
                showEmojiPicker = false
            },
            onDismiss = { showEmojiPicker = false },
            selectedEmojis = myReactions,
            resolvedEmojis = resolvedEmojis,
            unicodeEmojis = unicodeEmojis,
            onOpenEmojiLibrary = onOpenEmojiLibrary?.let { { showEmojiPicker = false; it() } }
        )
    }

    if (showActionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionsSheet = false },
            sheetState = actionsSheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val sheetScroll = rememberScrollState()
            val zapScroll = rememberScrollState()
            val reactScroll = rememberScrollState()
            val modalBg = MaterialTheme.colorScheme.surface
            val chipFill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            val stripBg = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
            val following = !isOwnMessage && isFollowing?.invoke(message.senderPubkey) == true
            // Custom emoji shortcodes take priority; UTF emojis only shown when no custom ones exist
            val reactionPick = remember(unicodeEmojis) {
                val customOnly = unicodeEmojis.filter { it.startsWith(":") && it.endsWith(":") }
                if (customOnly.isNotEmpty()) customOnly.take(28) else unicodeEmojis.take(28)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(sheetScroll)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f),
                    border = BorderStroke(
                        0.33.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showActionsSheet = false
                            onReply(message)
                        }
                ) {
                    Column(
                        Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GroupChatQuotedMessage(
                            authorPubkey = message.senderPubkey,
                            authorName = displayName,
                            content = message.content,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .scale(scaleX = -1f, scaleY = -1f)
                            )
                            Text(
                                text = stringResource(R.string.group_room_comment),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                // React section
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GroupChatSectionEyebrow(stringResource(R.string.group_room_eyebrow_react))
                    GroupChatHorizontalChipStrip(
                        scrollState = reactScroll,
                        stripBackground = stripBg,
                        chipFill = chipFill,
                        chevronBackground = modalBg,
                        verticalPadding = 8.dp,
                        chipSpacing = 6.dp,
                        trailingOnClick = {
                            showActionsSheet = false
                            if (onOpenEmojiLibrary != null) {
                                pendingEmojiReactCallback = { emoji ->
                                    onReact(message.id, message.senderPubkey, emoji)
                                }
                                onOpenEmojiLibrary()
                            } else {
                                showEmojiPicker = true
                            }
                        },
                        trailingEnabled = true,
                        trailingContentDescription = stringResource(R.string.cd_open_reaction_picker)
                    ) {
                        reactionPick.forEach { em ->
                            val isCustom = em.startsWith(":") && em.endsWith(":")
                            val sc = if (isCustom) em.removeSurrounding(":") else null
                            val emojiUrl = sc?.let { resolvedEmojis[it] }
                            // Skip custom emojis with no resolved URL
                            if (isCustom && emojiUrl == null) return@forEach
                            Box(
                                modifier = Modifier
                                    .clickable {
                                        showActionsSheet = false
                                        onReact(message.id, message.senderPubkey, em)
                                    }
                                    .padding(horizontal = 3.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (emojiUrl != null) {
                                    AsyncImage(
                                        model = emojiUrl,
                                        contentDescription = em,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    Text(text = em, fontSize = 26.sp)
                                }
                            }
                        }
                    }
                }

                if (onZap != null) {
                    // Zap section
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GroupChatSectionEyebrow(stringResource(R.string.group_room_eyebrow_zap))
                        GroupChatHorizontalChipStrip(
                            scrollState = zapScroll,
                            stripBackground = stripBg,
                            chipFill = chipFill,
                            chevronBackground = modalBg,
                            trailingOnClick = {
                                showActionsSheet = false
                                onZap(message.id, message.senderPubkey)
                            },
                            trailingEnabled = !isZapInProgress,
                            trailingContentDescription = stringResource(R.string.cd_open_full_zap)
                        ) {
                            GROUP_CHAT_ZAP_PRESET_AMOUNTS.forEach { amt ->
                                Surface(
                                    onClick = {
                                        showActionsSheet = false
                                        if (onZapPreset != null) {
                                            onZapPreset(message.id, message.senderPubkey, amt)
                                        } else {
                                            onZap(message.id, message.senderPubkey)
                                        }
                                    },
                                    enabled = !isZapInProgress,
                                    shape = RoundedCornerShape(8.dp),
                                    color = modalBg
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .height(32.dp)
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_bolt),
                                            contentDescription = null,
                                            tint = WispThemeColors.zapColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = formatGroupCamChipAmount(amt),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Actions section — follow/block hidden for own messages
                val showFollow = !isOwnMessage && onFollowAuthor != null
                val showBlock = !isOwnMessage && onBlockAuthor != null
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GroupChatSectionEyebrow(stringResource(R.string.group_room_eyebrow_actions))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (showFollow) {
                                GroupChatCamPanelButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = true,
                                    onClick = {
                                        showActionsSheet = false
                                        onFollowAuthor?.invoke(message.senderPubkey)
                                    },
                                    icon = {
                                        Icon(
                                            Icons.Outlined.PersonAdd,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = if (following) stringResource(R.string.btn_unfollow) else stringResource(R.string.btn_follow)
                                )
                            }
                            if (showBlock) {
                                GroupChatCamPanelButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = true,
                                    onClick = {
                                        showActionsSheet = false
                                        onBlockAuthor?.invoke(message.senderPubkey)
                                    },
                                    icon = {
                                        Icon(
                                            Icons.Outlined.Block,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = stringResource(R.string.btn_block)
                                )
                            }
                            GroupChatCamPanelButton(
                                modifier = Modifier.weight(1f),
                                enabled = true,
                                onClick = {
                                    showActionsSheet = false
                                    try {
                                        val nevent = Nip19.neventEncode(message.id.hexToByteArray())
                                        val url = "https://njump.me/$nevent"
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, url)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, null))
                                    } catch (_: Exception) {}
                                },
                                icon = {
                                    Icon(
                                        Icons.Outlined.Share,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                label = stringResource(R.string.btn_share)
                            )
                            GroupChatCamPanelButton(
                                modifier = Modifier.weight(1f),
                                enabled = true,
                                onClick = {
                                    showActionsSheet = false
                                    showDetailsSheet = true
                                },
                                icon = {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                label = stringResource(R.string.btn_details)
                            )
                        }
                }  // end actions section Column
            }
        }
    }

    if (showDetailsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.btn_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Text(
                    text = "Author: ${message.senderPubkey.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
                Text(
                    text = "Time: ${formatGroupTimestamp(message.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
                Text(
                    text = "Event ID: ${message.id.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                GroupMessageActionRow(
                    label = stringResource(R.string.btn_copy_note_id),
                    onClick = {
                        showDetailsSheet = false
                        try {
                            val relays = eventRepo.getEventRelays(message.id).take(3).toList()
                            val neventId = Nip19.neventEncode(
                                eventId = message.id.hexToByteArray(),
                                relays = relays,
                                author = message.senderPubkey.hexToByteArray()
                            )
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(neventId))
                        } catch (_: Exception) {}
                    }
                )
                GroupMessageActionRow(
                    label = stringResource(R.string.btn_copy_note_json),
                    onClick = {
                        showDetailsSheet = false
                        val event = eventRepo.getEvent(message.id)
                        if (event != null) {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(event.toJson()))
                        }
                    }
                )
                GroupMessageActionRow(
                    label = stringResource(R.string.btn_copy_text),
                    onClick = {
                        showDetailsSheet = false
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.content))
                    }
                )
            }
        }
    }
}

@Composable
private fun GroupChatCamPanelButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GroupMessageActionRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}


/** Deterministic profile-name color matching JS hexToColor + getProfileTextColor. */
private fun groupMemberColor(pubkey: String, isDarkTheme: Boolean): androidx.compose.ui.graphics.Color {
    val base = hexToProfileRgb(pubkey)
    val adjusted = adjustProfileTextBrightness(base, if (isDarkTheme) 1.08f else 0.95f)
    return androidx.compose.ui.graphics.Color(
        red = adjusted.r / 255f,
        green = adjusted.g / 255f,
        blue = adjusted.b / 255f,
        alpha = 1f
    )
}

private data class Rgb(val r: Int, val g: Int, val b: Int)

private fun adjustProfileTextBrightness(rgb: Rgb, factor: Float): Rgb {
    return Rgb(
        r = (rgb.r * factor).roundToInt().coerceIn(0, 255),
        g = (rgb.g * factor).roundToInt().coerceIn(0, 255),
        b = (rgb.b * factor).roundToInt().coerceIn(0, 255)
    )
}

private fun hexToProfileRgb(hex: String): Rgb {
    if (hex.isBlank() || !HEX_REGEX.matches(hex)) return Rgb(128, 128, 128)
    val hue = BigInteger(hex, 16).mod(BigInteger.valueOf(360L)).toInt()
    val h = hue / 60f
    val s = 0.7f
    val v = when {
        hue in 32..204 -> 0.75f
        hue in 216..273 -> 0.96f
        else -> 0.9f
    }

    val c = v * s
    val x = c * (1 - abs((h % 2f) - 1))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Rgb(
        r = ((r1 + m) * 255f).roundToInt(),
        g = ((g1 + m) * 255f).roundToInt(),
        b = ((b1 + m) * 255f).roundToInt()
    )
}

private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

private val groupTimeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val groupDateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun formatGroupTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = Date(epoch * 1000)
    val now = System.currentTimeMillis()
    return if (now - epoch * 1000 < 86400_000) groupTimeFormat.format(date)
           else groupDateTimeFormat.format(date)
}

private fun formatZapSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${"%.1f".format(sats / 1_000_000.0)}M"
    sats >= 1_000 -> "${"%.1f".format(sats / 1_000.0)}k"
    else -> sats.toString()
}

private enum class AutocompleteMode { MENTION, EMOJI }

private data class AutocompleteState(
    val mode: AutocompleteMode,
    val triggerIndex: Int,  // index of '@' or ':' in the text
    val query: String       // text after the trigger up to cursor
)

/** Walk backwards from the cursor to detect an active @ or : autocomplete trigger. */
private fun detectGroupAutocomplete(tfv: TextFieldValue): AutocompleteState? {
    val text = tfv.text
    val cursor = tfv.selection.start
    if (cursor == 0 || text.isEmpty()) return null

    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        when {
            c == '@' -> {
                // Valid trigger: at start or preceded by whitespace
                if (i == 0 || text[i - 1].isWhitespace()) {
                    val query = text.substring(i + 1, cursor)
                    if (!query.contains(' ') && !query.contains('\n')) {
                        return AutocompleteState(AutocompleteMode.MENTION, i, query)
                    }
                }
                return null
            }
            c == ':' -> {
                // Only trigger on a lone ':' — not a closing ':' (which would have non-space chars before it)
                val query = text.substring(i + 1, cursor)
                if (!query.contains(' ') && !query.contains('\n') && !query.contains(':')) {
                    return AutocompleteState(AutocompleteMode.EMOJI, i, query)
                }
                return null
            }
            c.isWhitespace() || c == '\n' -> return null
        }
        i--
    }
    return null
}

/** Replace the trigger + partial query with the selected completion, placing cursor after. */
private fun insertAutocomplete(tfv: TextFieldValue, triggerIndex: Int, insertion: String): TextFieldValue {
    val text = tfv.text
    val cursor = tfv.selection.start
    val before = text.substring(0, triggerIndex)
    val after = if (cursor < text.length) text.substring(cursor) else ""
    val newText = "$before$insertion $after"
    return TextFieldValue(newText, TextRange(triggerIndex + insertion.length + 1))
}
