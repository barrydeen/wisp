package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip37
import com.wisp.app.nostr.Nip68
import com.wisp.app.nostr.Nip71
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.repo.MentionSearchRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
// Matches bare bech32 IDs not already preceded by "nostr:" or embedded in a URL
private val BARE_BECH32_REGEX = Regex("(?<!nostr:)(?<![a-z0-9/.:#])((note1|nevent1|npub1|nprofile1)[a-z0-9]{10,})")
private val HASHTAG_REGEX = Regex("(?:^|(?<=\\s))#([a-zA-Z0-9_]+)")

class ComposeViewModel(app: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val interfacePrefs = InterfacePreferences(app)
    val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

    fun reloadBlossomRepo() {
        blossomRepo.reload(keyRepo.getPubkeyHex())
    }

    private val _content = MutableStateFlow(
        TextFieldValue(savedStateHandle.get<String>("draft_content") ?: "")
    )
    val content: StateFlow<TextFieldValue> = _content

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _uploadProgress = MutableStateFlow<String?>(null)
    val uploadProgress: StateFlow<String?> = _uploadProgress

    private val _uploadedUrls = MutableStateFlow<List<String>>(emptyList())
    val uploadedUrls: StateFlow<List<String>> = _uploadedUrls

    private val _countdownSeconds = MutableStateFlow<Int?>(null)
    val countdownSeconds: StateFlow<Int?> = _countdownSeconds

    private val _mentionQuery = MutableStateFlow<String?>(null)
    val mentionQuery: StateFlow<String?> = _mentionQuery

    private val _mentionCandidates = MutableStateFlow<List<MentionCandidate>>(emptyList())
    val mentionCandidates: StateFlow<List<MentionCandidate>> = _mentionCandidates

    private val _hashtags = MutableStateFlow<List<String>>(emptyList())
    val hashtags: StateFlow<List<String>> = _hashtags

    private val _explicit = MutableStateFlow(false)
    val explicit: StateFlow<Boolean> = _explicit

    fun toggleExplicit() {
        _explicit.value = !_explicit.value
    }

    private val _powEnabled = MutableStateFlow(false)
    val powEnabled: StateFlow<Boolean> = _powEnabled

    fun initPowState(enabled: Boolean) {
        _powEnabled.value = enabled
    }

    fun togglePow(powPrefs: com.wisp.app.repo.PowPreferences) {
        val newValue = !_powEnabled.value
        _powEnabled.value = newValue
        powPrefs.setNotePowEnabled(newValue)
    }

    private val _galleryMode = MutableStateFlow(false)
    val galleryMode: StateFlow<Boolean> = _galleryMode

    /** Tracks whether the current gallery upload contains a video (to prevent mixing). */
    private val _galleryHasVideo = MutableStateFlow(false)

    /** Tracks media dimensions per URL (width x height) for dim tags and video orientation detection. */
    private val _mediaDimensions = mutableMapOf<String, Pair<Int, Int>>()

    companion object {
        val SCHEDULER_RELAYS = listOf("wss://scheduler.nostrarchives.com")
        const val MAX_GALLERY_IMAGES = 21
    }

    fun toggleGalleryMode() {
        _galleryMode.value = !_galleryMode.value
        if (_galleryMode.value) _pollEnabled.value = false
    }

    private val _pollEnabled = MutableStateFlow(false)
    val pollEnabled: StateFlow<Boolean> = _pollEnabled

    private val _pollOptions = MutableStateFlow(listOf("", ""))
    val pollOptions: StateFlow<List<String>> = _pollOptions

    private val _pollType = MutableStateFlow(Nip88.PollType.SINGLECHOICE)
    val pollType: StateFlow<Nip88.PollType> = _pollType

    private val _scheduleEnabled = MutableStateFlow(false)
    val scheduleEnabled: StateFlow<Boolean> = _scheduleEnabled

    private val _scheduleTimestamp = MutableStateFlow<Long?>(null)
    val scheduleTimestamp: StateFlow<Long?> = _scheduleTimestamp

    fun toggleSchedule() {
        _scheduleEnabled.value = !_scheduleEnabled.value
        if (!_scheduleEnabled.value) _scheduleTimestamp.value = null
    }

    fun setScheduleTimestamp(epochSeconds: Long) {
        _scheduleTimestamp.value = epochSeconds
    }

    fun togglePoll() {
        _pollEnabled.value = !_pollEnabled.value
        if (_pollEnabled.value) _galleryMode.value = false
    }

    fun updatePollOption(index: Int, text: String) {
        val options = _pollOptions.value.toMutableList()
        if (index in options.indices) {
            options[index] = text
            _pollOptions.value = options
        }
    }

    fun addPollOption() {
        if (_pollOptions.value.size < 10) {
            _pollOptions.value = _pollOptions.value + ""
        }
    }

    fun removePollOption(index: Int) {
        if (_pollOptions.value.size > 2 && index in _pollOptions.value.indices) {
            _pollOptions.value = _pollOptions.value.toMutableList().apply { removeAt(index) }
        }
    }

    fun togglePollType() {
        _pollType.value = if (_pollType.value == Nip88.PollType.SINGLECHOICE)
            Nip88.PollType.MULTIPLECHOICE else Nip88.PollType.SINGLECHOICE
    }

    private var mentionStartIndex: Int = -1
    private var countdownJob: Job? = null
    private var pendingPublish: (() -> Unit)? = null
    private var mentionSearchRepo: MentionSearchRepository? = null
    private var eventRepo: EventRepository? = null
    private var initialized = false

    var currentDraftId: String? = null
        private set

    fun init(profileRepo: ProfileRepository, contactRepo: ContactRepository, relayPool: RelayPool, eventRepo: EventRepository? = null, eventPersistence: com.wisp.app.db.EventPersistence? = null) {
        if (initialized) return
        initialized = true
        this.eventRepo = eventRepo
        mentionSearchRepo = MentionSearchRepository(profileRepo, contactRepo, relayPool, keyRepo).also {
            it.eventPersistence = eventPersistence
        }
        // Forward candidates from search repo
        viewModelScope.launch {
            mentionSearchRepo!!.candidates.collect { _mentionCandidates.value = it }
        }
    }

    fun uploadMedia(uris: List<Uri>, contentResolver: ContentResolver, signer: NostrSigner? = null) {
        viewModelScope.launch {
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                // Gallery mode limits: 1 video max, 21 images max, no mixing
                if (_galleryMode.value) {
                    val mime = contentResolver.getType(uri) ?: ""
                    val isVideo = mime.startsWith("video/")
                    val currentUrls = _uploadedUrls.value
                    if (isVideo && currentUrls.isNotEmpty()) {
                        _error.value = "Video gallery posts can only contain one video"
                        break
                    }
                    if (isVideo && total > 1) {
                        _error.value = "Video gallery posts can only contain one video"
                        break
                    }
                    if (!isVideo && _galleryHasVideo.value) {
                        _error.value = "Cannot mix images and videos in a gallery post"
                        break
                    }
                    if (!isVideo && currentUrls.size >= MAX_GALLERY_IMAGES) {
                        _error.value = "Gallery posts can contain up to $MAX_GALLERY_IMAGES images"
                        break
                    }
                }
                try {
                    _uploadProgress.value = if (total > 1) "Uploading ${index + 1}/$total..." else "Uploading..."
                    // Extract dimensions before upload for dim tags and video orientation
                    val dims = extractMediaDimensions(uri, contentResolver)
                    val (bytes, mime, ext) = readFileFromUri(contentResolver, uri)
                    val url = blossomRepo.uploadMedia(bytes, mime, ext, signer)
                    _uploadedUrls.value = _uploadedUrls.value + url
                    if (dims != null) _mediaDimensions[url] = dims
                    if (_galleryMode.value && mime.startsWith("video/")) {
                        _galleryHasVideo.value = true
                    }
                    // In gallery mode, don't insert URLs into the text — they're shown in the pager
                    if (!_galleryMode.value) {
                        val current = _content.value.text
                        val newText = if (current.isBlank()) url else "$current\n$url"
                        _content.value = TextFieldValue(newText, TextRange(newText.length))
                        savedStateHandle["draft_content"] = newText
                    }
                } catch (e: Exception) {
                    _error.value = "Upload failed: ${e.message}"
                    break
                }
            }
            _uploadProgress.value = null
        }
    }

    fun removeMediaUrl(url: String) {
        _uploadedUrls.value = _uploadedUrls.value - url
        _mediaDimensions.remove(url)
        // Reset video flag if all media removed
        if (_uploadedUrls.value.isEmpty()) _galleryHasVideo.value = false
        if (!_galleryMode.value) {
            val current = _content.value.text
            val newText = current.replace(url, "").replace("\n\n", "\n").trim()
            _content.value = TextFieldValue(newText, TextRange(newText.length))
            savedStateHandle["draft_content"] = newText
        }
    }

    fun updateContent(value: TextFieldValue) {
        // Check if cursor entered a nostr: URI and user backspaced — delete entire mention
        val prev = _content.value
        if (value.text.length < prev.text.length && value.text.length == prev.text.length - 1) {
            // Single character deletion — check if we're inside a nostr: URI
            val deletedAt = value.selection.start
            for (match in NOSTR_URI_REGEX.findAll(prev.text)) {
                if (deletedAt > match.range.first && deletedAt <= match.range.last + 1) {
                    // Delete the entire mention
                    val before = prev.text.substring(0, match.range.first)
                    val after = prev.text.substring(match.range.last + 1)
                    val newText = before + after
                    _content.value = TextFieldValue(newText, TextRange(match.range.first))
                    savedStateHandle["draft_content"] = newText
                    detectMentionQuery(_content.value)
                    return
                }
            }
        }

        // Auto-prefix bare bech32 IDs with nostr:
        val prefixed = prefixBareBech32(value)
        _content.value = prefixed
        savedStateHandle["draft_content"] = prefixed.text
        detectMentionQuery(prefixed)
        detectHashtags(prefixed.text)
    }

    private fun prefixBareBech32(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val match = BARE_BECH32_REGEX.find(text) ?: return value
        // Validate it's actually a decodable bech32 before prefixing
        val bare = match.groupValues[1]
        val valid = try {
            Nip19.decodeNostrUri("nostr:$bare") != null
        } catch (_: Exception) { false }
        if (!valid) return value

        val newText = text.substring(0, match.range.first) + "nostr:" + text.substring(match.range.first)
        val cursorShift = if (value.selection.start > match.range.first) 6 else 0
        return TextFieldValue(newText, TextRange(value.selection.start + cursorShift))
    }

    private fun detectMentionQuery(value: TextFieldValue) {
        val text = value.text
        val cursor = value.selection.start

        if (cursor == 0 || text.isEmpty()) {
            clearMentionState()
            return
        }

        // Walk backwards from cursor to find @ trigger
        var atIndex = -1
        for (i in (cursor - 1) downTo 0) {
            val c = text[i]
            if (c == '@') {
                // Valid trigger: at start of text or preceded by whitespace/newline
                if (i == 0 || text[i - 1].isWhitespace()) {
                    atIndex = i
                }
                break
            }
            if (c.isWhitespace()) break
        }

        if (atIndex == -1) {
            clearMentionState()
            return
        }

        mentionStartIndex = atIndex
        val query = text.substring(atIndex + 1, cursor)
        _mentionQuery.value = query
        mentionSearchRepo?.search(query, viewModelScope)
    }

    private fun detectHashtags(text: String) {
        _hashtags.value = HASHTAG_REGEX.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
    }

    private fun clearMentionState() {
        _mentionQuery.value = null
        mentionStartIndex = -1
        mentionSearchRepo?.clear()
    }

    fun selectMention(candidate: MentionCandidate) {
        val value = _content.value
        val text = value.text
        val cursor = value.selection.start

        if (mentionStartIndex < 0 || mentionStartIndex > text.length) {
            clearMentionState()
            return
        }

        val nprofile = "nostr:" + Nip19.nprofileEncode(candidate.profile.pubkey)
        val before = text.substring(0, mentionStartIndex)
        val after = if (cursor < text.length) text.substring(cursor) else ""
        val newText = before + nprofile + " " + after
        val newCursor = before.length + nprofile.length + 1

        _content.value = TextFieldValue(newText, TextRange(newCursor))
        clearMentionState()
    }


    fun publish(
        relayPool: RelayPool,
        replyTo: NostrEvent? = null,
        quoteTo: NostrEvent? = null,
        onSuccess: () -> Unit = {},
        outboxRouter: OutboxRouter? = null,
        signer: NostrSigner? = null,
        onNotePublished: (() -> Unit)? = null,
        powManager: PowManager? = null
    ) {
        val text = _content.value.text.trim()

        // Gallery posts can have an empty caption — the media is the content
        if (text.isBlank() && !_galleryMode.value) {
            _error.value = getApplication<Application>().getString(R.string.error_post_empty)
            return
        }
        if (_galleryMode.value && _uploadedUrls.value.isEmpty()) {
            _error.value = "Gallery post requires at least one uploaded image or video"
            return
        }

        val s = signer
        if (s == null) {
            _error.value = getApplication<Application>().getString(R.string.error_not_logged_in)
            return
        }

        if (_scheduleEnabled.value) {
            val ts = _scheduleTimestamp.value
            if (ts == null) {
                _error.value = getApplication<Application>().getString(R.string.error_schedule_date_required)
                return
            }
            if (ts <= System.currentTimeMillis() / 1000) {
                _error.value = getApplication<Application>().getString(R.string.error_schedule_future)
                return
            }
        }

        _publishing.value = true
        startCountdown(text, s, relayPool, replyTo, quoteTo, outboxRouter, onSuccess, onNotePublished, powManager)
    }

    private fun startCountdown(
        content: String,
        signer: NostrSigner,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent?,
        outboxRouter: OutboxRouter?,
        onSuccess: () -> Unit,
        onNotePublished: (() -> Unit)? = null,
        powManager: PowManager? = null
    ) {
        countdownJob?.cancel()
        pendingPublish = {
            viewModelScope.launch {
                try {
                    val sentCount = publishNote(content, signer, relayPool, replyTo, quoteTo, outboxRouter, powManager)
                    if (sentCount == 0) return@launch
                    onNotePublished?.invoke()
                    onSuccess()
                } catch (e: Exception) {
                    _error.value = getApplication<Application>().getString(R.string.error_publish_failed, e.message ?: "Unknown error")
                    _publishing.value = false
                }
            }
        }
        _countdownSeconds.value = 10
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 1) {
                _countdownSeconds.value = i
                delay(1000)
            }
            _countdownSeconds.value = null
            pendingPublish?.invoke()
            pendingPublish = null
        }
    }

    fun cancelPublish() {
        countdownJob?.cancel()
        countdownJob = null
        pendingPublish = null
        _countdownSeconds.value = null
        _publishing.value = false
    }

    fun publishNow() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = null
        pendingPublish?.invoke()
        pendingPublish = null
    }

    /** Publishes a note and stores the event ID. Returns the number of relays sent to (0 = failure, -1 = handed to PowManager). */
    private suspend fun publishNote(
        content: String,
        signer: NostrSigner,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent? = null,
        outboxRouter: OutboxRouter? = null,
        powManager: PowManager? = null
    ): Int {
        val tags = mutableListOf<List<String>>()
        if (_explicit.value) {
            tags.add(listOf("content-warning", ""))
        }
        if (replyTo != null) {
            val hint = outboxRouter?.getRelayHint(replyTo.pubkey) ?: ""
            tags.addAll(Nip10.buildReplyTags(replyTo, hint))
        }

        val (mentionedPubkeys, _) = extractNostrRefs(content)
        val existingPubkeys = tags.filter { it.firstOrNull() == "p" }.map { it[1] }.toSet()
        for (pubkey in mentionedPubkeys) {
            if (pubkey !in existingPubkeys) {
                tags.add(listOf("p", pubkey))
            }
        }
        val finalContent = if (quoteTo != null) {
            val quoteHint = outboxRouter?.getRelayHint(quoteTo.pubkey) ?: ""
            tags.addAll(Nip18.buildQuoteTags(quoteTo, quoteHint))
            val relayHints = if (quoteHint.isNotEmpty()) listOf(quoteHint) else emptyList()
            Nip18.appendNoteUri(content, quoteTo.id, relayHints, quoteTo.pubkey)
        } else {
            content
        }

        for (hashtag in _hashtags.value) {
            tags.add(listOf("t", hashtag))
        }

        // Build poll tags if poll is enabled
        val eventKind: Int
        if (_galleryMode.value) {
            val urls = _uploadedUrls.value
            if (urls.isEmpty()) {
                _error.value = "Gallery post requires at least one uploaded image or video"
                _publishing.value = false
                return 0
            }
            // Detect if media is video or image based on URL extension
            val videoExts = setOf("mp4", "webm", "mov", "avi", "mkv", "m4v")
            val isVideo = urls.any { url ->
                val ext = url.substringAfterLast('.').lowercase().substringBefore('?')
                ext in videoExts
            }
            if (isVideo) {
                val videoUrl = urls.first()
                val dims = _mediaDimensions[videoUrl]
                val dimStr = dims?.let { "${it.first}x${it.second}" }
                val isVertical = dims != null && dims.second > dims.first
                val videoMeta = listOf(Nip71.VideoMeta(url = videoUrl, dim = dimStr))
                tags.addAll(Nip71.buildVideoTags(title = null, media = videoMeta, hashtags = _hashtags.value))
                eventKind = if (isVertical) Nip71.KIND_VIDEO_VERTICAL else Nip71.KIND_VIDEO_HORIZONTAL
            } else {
                val imetaEntries = urls.map { url ->
                    val dims = _mediaDimensions[url]
                    val dimStr = dims?.let { "${it.first}x${it.second}" }
                    Nip68.ImetaEntry(url = url, dim = dimStr)
                }
                tags.addAll(Nip68.buildPictureTags(title = null, media = imetaEntries, hashtags = _hashtags.value))
                eventKind = Nip68.KIND_PICTURE
            }
        } else if (_pollEnabled.value) {
            val nonBlankOptions = _pollOptions.value
                .mapIndexed { i, label -> Nip88.PollOption(i.toString(), label.trim()) }
                .filter { it.label.isNotBlank() }
            if (nonBlankOptions.size < 2) {
                _error.value = getApplication<Application>().getString(R.string.error_poll_options)
                _publishing.value = false
                return 0
            }
            val pollRelays = relayPool.getWriteRelayUrls()
            tags.addAll(Nip88.buildPollTags(nonBlankOptions, _pollType.value, relayUrls = pollRelays))
            eventKind = Nip88.KIND_POLL
        } else {
            eventKind = 1
        }

        if (interfacePrefs.isClientTagEnabled()) {
            tags.add(listOf("client", "Wisp"))
        }

        // Scheduled post — sign with future created_at and send to scheduler relays
        if (_scheduleEnabled.value && _scheduleTimestamp.value != null) {
            val scheduledAt = _scheduleTimestamp.value!!
            val event = signer.signEvent(kind = eventKind, content = finalContent, tags = tags, createdAt = scheduledAt)
            val msg = ClientMessage.event(event)
            var sentCount = 0
            for (url in SCHEDULER_RELAYS) {
                // Pre-approve auth so the relay auto-signs without prompting
                relayPool.autoApproveRelayAuth(url)
                // Connect without sending anything — relay will issue AUTH challenge on open
                relayPool.connectEphemeralRelay(url)
                // Wait for auth to complete before sending the EVENT (up to 5s)
                withTimeoutOrNull(5_000) {
                    relayPool.authCompleted.first { it == url }
                }
                if (relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)) sentCount++
            }
            if (sentCount == 0) {
                _error.value = getApplication<Application>().getString(R.string.error_scheduler_relay)
                _publishing.value = false
                return 0
            }
            deleteDraftOnPublish(relayPool, signer)
            _content.value = TextFieldValue()
            savedStateHandle.remove<String>("draft_content")
            _uploadedUrls.value = emptyList()
            _error.value = null
            _publishing.value = false
            _scheduleEnabled.value = false
            _scheduleTimestamp.value = null
            return sentCount
        }

        // Hand off to PowManager for background mining if PoW enabled
        if (_powEnabled.value && powManager != null) {
            val replyPubkey = replyTo?.pubkey
            powManager.submitNote(
                signer = signer,
                content = finalContent,
                tags = tags,
                kind = eventKind,
                replyToPubkey = replyPubkey,
                onPublished = {
                    if (replyTo != null) {
                        eventRepo?.addReplyCount(replyTo.id, "pow-pending")
                        val rootId = Nip10.getRootId(replyTo)
                        if (rootId != null && rootId != replyTo.id) {
                            eventRepo?.addReplyCount(rootId, "pow-pending")
                        }
                    }
                }
            )
            deleteDraftOnPublish(relayPool, signer)
            _content.value = TextFieldValue()
            savedStateHandle.remove<String>("draft_content")
            _uploadedUrls.value = emptyList()
            _error.value = null
            _publishing.value = false
            return -1
        }

        val event = signer.signEvent(kind = eventKind, content = finalContent, tags = tags)
        android.util.Log.d("GALLERY", "[ComposeVM] publishNote kind=$eventKind id=${event.id.take(12)} content='${finalContent.take(50)}' tags=${tags.size} galleryMode=${_galleryMode.value} uploadedUrls=${_uploadedUrls.value.size}")
        val msg = ClientMessage.event(event)
        var sentCount = if (replyTo != null && outboxRouter != null) {
            outboxRouter.publishToInbox(msg, replyTo.pubkey)
        } else {
            relayPool.sendToWriteRelays(msg)
        }
        // If no relays were reachable, try reconnecting write relays and retry once
        if (sentCount == 0) {
            val reconnected = relayPool.ensureWriteRelaysConnected()
            if (reconnected > 0) {
                sentCount = if (replyTo != null && outboxRouter != null) {
                    outboxRouter.publishToInbox(msg, replyTo.pubkey)
                } else {
                    relayPool.sendToWriteRelays(msg)
                }
            }
        }
        if (sentCount == 0) {
            _error.value = getApplication<Application>().getString(R.string.error_no_relays_connected)
            _publishing.value = false
            return 0
        }
        relayPool.trackPublish(event.id, sentCount)
        // Insert into feed so the note appears immediately without waiting for relay echo
        eventRepo?.addEvent(event)
        if (replyTo != null) {
            // Increment on direct parent so the PostCard showing replyTo updates
            eventRepo?.addReplyCount(replyTo.id, event.id)
            // Also increment on root so the thread root PostCard updates
            val rootId = Nip10.getRootId(replyTo)
            if (rootId != null && rootId != replyTo.id) {
                eventRepo?.addReplyCount(rootId, event.id)
            }
        }
        deleteDraftOnPublish(relayPool, signer)
        _content.value = TextFieldValue()
        savedStateHandle.remove<String>("draft_content")
        _uploadedUrls.value = emptyList()
        _error.value = null
        _publishing.value = false
        return sentCount
    }

    private fun extractNostrRefs(content: String): Pair<Set<String>, Set<String>> {
        val pubkeys = mutableSetOf<String>()
        val eventIds = mutableSetOf<String>()
        for (match in NOSTR_URI_REGEX.findAll(content)) {
            val bech32 = match.groupValues[1]
            try {
                when (val data = Nip19.decodeNostrUri("nostr:$bech32")) {
                    is com.wisp.app.nostr.NostrUriData.ProfileRef -> pubkeys.add(data.pubkey)
                    is com.wisp.app.nostr.NostrUriData.NoteRef -> eventIds.add(data.eventId)
                    is com.wisp.app.nostr.NostrUriData.AddressRef -> {}
                    null -> {}
                }
            } catch (_: Exception) {}
        }
        return pubkeys to eventIds
    }

    private fun readFileFromUri(
        contentResolver: ContentResolver,
        uri: Uri
    ): Triple<ByteArray, String, String> {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Cannot read file")
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        return Triple(bytes, mimeType, ext)
    }

    private fun extractMediaDimensions(uri: Uri, contentResolver: ContentResolver): Pair<Int, Int>? {
        return try {
            val mime = contentResolver.getType(uri) ?: ""
            if (mime.startsWith("video/")) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(getApplication<Application>(), uri)
                    val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) w to h else null
                } finally {
                    retriever.release()
                }
            } else if (mime.startsWith("image/")) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                val w = opts.outWidth
                val h = opts.outHeight
                if (w > 0 && h > 0) w to h else null
            } else null
        } catch (_: Exception) { null }
    }

    fun loadDraft(draft: Nip37.Draft) {
        currentDraftId = draft.dTag
        val text = draft.content
        _content.value = TextFieldValue(text, TextRange(text.length))
        savedStateHandle["draft_content"] = text
    }

    fun saveDraft(
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        signer: NostrSigner?
    ) {
        val text = _content.value.text.trim()
        if (text.isBlank() || signer == null) return

        val draftId = currentDraftId ?: Nip37.newDraftId()
        currentDraftId = draftId

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val innerTags = mutableListOf<List<String>>()
                if (replyTo != null) {
                    innerTags.addAll(Nip10.buildReplyTags(replyTo))
                }
                val innerJson = Nip37.serializeDraftContent(
                    pubkeyHex = signer.pubkeyHex,
                    innerKind = 1,
                    content = text,
                    tags = innerTags
                )
                val encrypted = signer.nip44Encrypt(innerJson, signer.pubkeyHex)
                val wrapperTags = Nip37.buildDraftTags(draftId, 1)
                val event = signer.signEvent(
                    kind = Nip37.KIND_DRAFT,
                    content = encrypted,
                    tags = wrapperTags
                )
                relayPool.sendToWriteRelays(ClientMessage.event(event))
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    fun deleteDraftOnPublish(relayPool: RelayPool, signer: NostrSigner?) {
        val dTag = currentDraftId ?: return
        if (signer == null) return
        currentDraftId = null

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val tags = Nip37.buildDraftTags(dTag, 1)
                val encrypted = signer.nip44Encrypt("", signer.pubkeyHex)
                val event = signer.signEvent(
                    kind = Nip37.KIND_DRAFT,
                    content = encrypted,
                    tags = tags
                )
                relayPool.sendToWriteRelays(ClientMessage.event(event))
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    fun clear() {
        currentDraftId = null
        _content.value = TextFieldValue()
        savedStateHandle.remove<String>("draft_content")
        _error.value = null
        _uploadedUrls.value = emptyList()
        _uploadProgress.value = null
        _explicit.value = false
        _hashtags.value = emptyList()
        _powEnabled.value = false
        _galleryMode.value = false
        _galleryHasVideo.value = false
        _mediaDimensions.clear()
        _pollEnabled.value = false
        _pollOptions.value = listOf("", "")
        _pollType.value = Nip88.PollType.SINGLECHOICE
        _scheduleEnabled.value = false
        _scheduleTimestamp.value = null
        clearMentionState()
    }
}
