package com.wisp.app.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupPreview
import com.wisp.app.ui.component.EmojiReactionPopup
import com.wisp.app.ui.component.NoteActions
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.ui.component.RichContent
import com.wisp.app.viewmodel.GroupRoomViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GroupRoomScreen(
    viewModel: GroupRoomViewModel,
    initialRoom: com.wisp.app.repo.GroupRoom? = null,
    relayPool: RelayPool,
    eventRepo: EventRepository,
    signer: NostrSigner?,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
    onGroupDetail: () -> Unit = {},
    onPickMedia: (() -> Unit)? = null,
    onUploadMedia: ((List<Uri>, onUrl: (String) -> Unit) -> Unit)? = null,
    uploadProgress: String? = null,
    onJoin: (() -> Unit)? = null,
    onAlreadyMember: (() -> Unit)? = null,
    fetchGroupPreview: (suspend (String, String) -> GroupPreview?)? = null,
    myPubkey: String? = null,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    peerEmojiMaps: Map<String, Map<String, String>> = emptyMap(),
    zapVersion: Int = 0,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    noteActions: com.wisp.app.ui.component.NoteActions? = null
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

    // BasicTextField state for GIF keyboard support via contentReceiver
    val textFieldState = remember { TextFieldState() }
    val interactionSource = remember { MutableInteractionSource() }

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

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (prevMessageCount == 0) {
            listState.scrollToItem(messages.size - 1)
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
    val subtitle = viewModel.relayUrl

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onGroupDetail() }
                    ) {
                        ProfilePicture(url = effectiveRoom?.metadata?.picture, size = 36)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
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
                GroupMemberAvatarStrip(
                    messages = messages,
                    members = effectiveRoom?.members ?: emptyList(),
                    eventRepo = eventRepo,
                    onProfileClick = onProfileClick
                )
                HorizontalDivider()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f)
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        GroupMessageBubble(
                            message = message,
                            allMessages = messages,
                            eventRepo = eventRepo,
                            myPubkey = myPubkey,
                            reactionEmojiUrls = effectiveRoom?.reactionEmojiUrls ?: emptyMap(),
                            resolvedEmojis = resolvedEmojis,
                            peerEmojiMap = peerEmojiMaps[message.senderPubkey] ?: emptyMap(),
                            unicodeEmojis = unicodeEmojis,
                            zapVersion = zapVersion,
                            isZapAnimating = message.id in zapAnimatingIds,
                            isZapInProgress = message.id in zapInProgressIds,
                            onProfileClick = onProfileClick,
                            onReply = {
                                viewModel.setReplyTarget(it)
                                textFieldFocus.requestFocus()
                            },
                            onReact = { msgId, pubkey, emoji ->
                                viewModel.sendReaction(msgId, pubkey, emoji, signer, relayPool, resolvedEmojis)
                            },
                            onZap = onZap,
                            onOpenEmojiLibrary = onOpenEmojiLibrary,
                            noteActions = noteActions
                        )
                    }
                }

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

                // Reply preview bar
                if (replyTarget != null) {
                    val replyProfile = remember(replyTarget!!.senderPubkey) {
                        eventRepo.getProfileData(replyTarget!!.senderPubkey)
                    }
                    val replyName = replyProfile?.displayString ?: replyTarget!!.senderPubkey.take(8) + "…"
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = replyName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = replyTarget!!.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { viewModel.clearReplyTarget() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
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
                            val query = autocomplete.query.lowercase()
                            val matches = remember(query, resolvedEmojis) {
                                resolvedEmojis.entries
                                    .filter { (shortcode, _) -> query.isEmpty() || shortcode.contains(query) }
                                    .take(12)
                            }
                            if (matches.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    tonalElevation = 3.dp,
                                    shadowElevation = 2.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier.padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        matches.forEach { (shortcode, url) ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.clickable {
                                                    val insertion = ":$shortcode:"
                                                    val newTfv = insertAutocomplete(tfv, autocomplete.triggerIndex, insertion)
                                                    tfv = newTfv
                                                    viewModel.updateText(newTfv.text)
                                                }
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier.padding(6.dp)
                                                ) {
                                                    AsyncImage(
                                                        model = url,
                                                        contentDescription = shortcode,
                                                        modifier = Modifier.size(28.dp)
                                                    )
                                                    Text(
                                                        text = shortcode,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontSize = 9.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.width(36.dp)
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    if (onPickMedia != null) {
                        IconButton(
                            onClick = onPickMedia,
                            enabled = uploadProgress == null && !sending
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = stringResource(R.string.cd_attach_media),
                                tint = if (uploadProgress == null && !sending)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    }
                    BasicTextField(
                        state = textFieldState,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(textFieldFocus)
                            .then(
                                if (onUploadMedia != null) Modifier.contentReceiver(object : ReceiveContentListener {
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
                                            onUploadMedia(uris) { url -> viewModel.appendToText(url) }
                                        }
                                        return transferableContent.consume { item -> item.uri != null }
                                    }
                                }) else Modifier
                            ),
                        enabled = uploadProgress == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorator = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = textFieldState.text.toString(),
                                innerTextField = innerTextField,
                                enabled = uploadProgress == null,
                                singleLine = false,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = interactionSource,
                                placeholder = { Text(stringResource(R.string.placeholder_message)) }
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    if (sending || uploadProgress != null) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(
                            onClick = { viewModel.sendMessage(relayPool, signer) },
                            enabled = messageText.isNotBlank() && signer != null
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.cd_send),
                                tint = if (messageText.isNotBlank() && signer != null)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    allMessages: List<GroupMessage>,
    eventRepo: EventRepository,
    myPubkey: String?,
    reactionEmojiUrls: Map<String, String>,
    resolvedEmojis: Map<String, String>,
    peerEmojiMap: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String>,
    zapVersion: Int = 0,
    isZapAnimating: Boolean = false,
    isZapInProgress: Boolean = false,
    onProfileClick: (String) -> Unit,
    onReply: (GroupMessage) -> Unit,
    onReact: (messageId: String, senderPubkey: String, emoji: String) -> Unit,
    onZap: ((messageId: String, senderPubkey: String) -> Unit)? = null,
    onOpenEmojiLibrary: (() -> Unit)? = null,
    noteActions: com.wisp.app.ui.component.NoteActions? = null
) {
    val profile = remember(message.senderPubkey) { eventRepo.getProfileData(message.senderPubkey) }
    val displayName = profile?.displayString ?: (message.senderPubkey.take(8) + "…")

    var showEmojiPicker by remember(message.id) { mutableStateOf(false) }

    val myReactions = remember(message.reactions, myPubkey) {
        if (myPubkey == null) emptySet()
        else message.reactions.filter { (_, reactors) -> myPubkey in reactors }.keys.toSet()
    }

    val replyToMessage = remember(message.replyToId, allMessages) {
        message.replyToId?.let { id -> allMessages.firstOrNull { it.id == id } }
    }

    // Combined emoji map: our library → sender's library → room reactions → message's own tags (highest priority)
    val messageEmojiMap = remember(message.emojiTags, reactionEmojiUrls, resolvedEmojis, peerEmojiMap) {
        resolvedEmojis + peerEmojiMap + reactionEmojiUrls + message.emojiTags
    }

    // Zap totals — re-evaluated whenever zapVersion ticks
    val zapSats = remember(message.id, zapVersion) { eventRepo.getZapSats(message.id) }
    val hasZaps = zapSats > 0

    val swipeOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThreshold = remember(density) { with(density) { 80.dp.toPx() } }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply icon revealed behind the message as you swipe
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(20.dp)
                .alpha((swipeOffset.value / swipeThreshold).coerceIn(0f, 1f))
        )

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            },
        verticalAlignment = Alignment.Top
    ) {
        ProfilePicture(
            url = profile?.picture,
            size = 36,
            onClick = { onProfileClick(message.senderPubkey) }
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatGroupTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            // Reply quote block
            if (replyToMessage != null) {
                val replyProfile = remember(replyToMessage.senderPubkey) {
                    eventRepo.getProfileData(replyToMessage.senderPubkey)
                }
                val replyName = replyProfile?.displayString ?: replyToMessage.senderPubkey.take(8) + "…"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = replyName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = replyToMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            RichContent(
                content = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                emojiMap = messageEmojiMap,
                eventRepo = eventRepo,
                onProfileClick = onProfileClick,
                noteActions = noteActions
            )

            // Per-emoji reaction badges
            if (message.reactions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    message.reactions.forEach { (emoji, reactors) ->
                        val isMe = myPubkey != null && myPubkey in reactors
                        val shortcode = if (emoji.startsWith(":") && emoji.endsWith(":")) emoji.removeSurrounding(":") else null
                        val emojiUrl = shortcode?.let { reactionEmojiUrls[it] ?: resolvedEmojis[it] }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { onReact(message.id, message.senderPubkey, emoji) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                if (emojiUrl != null) {
                                    AsyncImage(
                                        model = emojiUrl,
                                        contentDescription = emoji,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Text(text = emoji, fontSize = 14.sp)
                                }
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = reactors.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Action row
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    // Reply
                    IconButton(onClick = { onReply(message) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = "Reply",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // React — opens emoji picker
                    IconButton(
                        onClick = { showEmojiPicker = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.FavoriteBorder,
                            contentDescription = "React",
                            modifier = Modifier.size(18.dp),
                            tint = if (myReactions.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Zap
                    if (onZap != null) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = !isZapInProgress) {
                                    onZap(message.id, message.senderPubkey)
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .wrapContentSize(unbounded = true, align = Alignment.Center)
                                ) {
                                    Icon(
                                        Icons.Filled.Bolt,
                                        contentDescription = "Zap",
                                        modifier = Modifier.size(18.dp),
                                        tint = when {
                                            isZapInProgress -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            hasZaps || isZapAnimating -> com.wisp.app.ui.theme.WispThemeColors.zapColor
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    // Burst animation overlay
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .wrapContentSize(unbounded = true, align = Alignment.Center)
                                    ) {
                                        com.wisp.app.ui.component.ZapBurstEffect(
                                            isActive = isZapAnimating,
                                            modifier = Modifier.size(80.dp)
                                        )
                                    }
                                }
                                if (hasZaps) {
                                    Text(
                                        text = formatZapSats(zapSats),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = com.wisp.app.ui.theme.WispThemeColors.zapColor
                                    )
                                }
                            }
                        }
                    }
                }
                if (showEmojiPicker) {
                    EmojiReactionPopup(
                        onSelect = { emoji -> onReact(message.id, message.senderPubkey, emoji) },
                        onDismiss = { showEmojiPicker = false },
                        selectedEmojis = myReactions,
                        resolvedEmojis = resolvedEmojis,
                        unicodeEmojis = unicodeEmojis,
                        onOpenEmojiLibrary = onOpenEmojiLibrary?.let { { showEmojiPicker = false; it() } }
                    )
                }
            }
        }
    }
    } // close swipe Box
}

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
