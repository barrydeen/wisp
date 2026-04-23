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
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip37
import com.wisp.app.nostr.Nip68
import com.wisp.app.nostr.Nip71
import com.wisp.app.nostr.Nip69
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
import com.wisp.app.ui.util.GifToMp4Converter
import com.wisp.app.ui.util.MediaCompressor
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
private val BARE_BECH32_REGEX = Regex("(?<!nostr:)(?<![a-z0-9/.:#])((note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]{10,})")
private val HASHTAG_REGEX = Regex("(?:^|(?<=\\s))#([a-zA-Z0-9_]+)")

/** Tracks an inserted @mention as a range in the compose text, mapped to its pubkey. */
data class Mention(val start: Int, val end: Int, val pubkey: String)

private fun restoreMentionsFromState(state: SavedStateHandle): List<Mention> {
    val raw = state.get<Array<String>>("draft_mentions") ?: return emptyList()
    return raw.mapNotNull { entry ->
        val parts = entry.split(',', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val start = parts[0].toIntOrNull() ?: return@mapNotNull null
        val end = parts[1].toIntOrNull() ?: return@mapNotNull null
        Mention(start, end, parts[2])
    }
}

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

    private val _mentions = MutableStateFlow<List<Mention>>(restoreMentionsFromState(savedStateHandle))
    val mentions: StateFlow<List<Mention>> = _mentions

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

    private val _isZapPoll = MutableStateFlow(false)
    val isZapPoll: StateFlow<Boolean> = _isZapPoll

    private val _zapPollMinSats = MutableStateFlow<Long?>(null)
    val zapPollMinSats: StateFlow<Long?> = _zapPollMinSats

    private val _zapPollMaxSats = MutableStateFlow<Long?>(null)
    val zapPollMaxSats: StateFlow<Long?> = _zapPollMaxSats

    private val _zapPollConsensus = MutableStateFlow<Int?>(null)
    val zapPollConsensus: StateFlow<Int?> = _zapPollConsensus

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

    fun toggleZapPoll() {
        _isZapPoll.value = !_isZapPoll.value
        if (_isZapPoll.value) {
            _pollType.value = Nip88.PollType.SINGLECHOICE
        }
    }

    fun setZapPollMinSats(value: Long?) { _zapPollMinSats.value = value }
    fun setZapPollMaxSats(value: Long?) { _zapPollMaxSats.value = value }
    fun setZapPollConsensus(value: Int?) { _zapPollConsensus.value = value?.coerceIn(0, 100) }

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
                    // GIFs are transcoded to MP4 during upload, so treat them as video for gallery constraints.
                    val isVideo = mime.startsWith("video/") || mime == "image/gif"
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
                    val (rawBytes, rawMime, rawExt) = readFileFromUri(contentResolver, uri)
                    val (bytes, mime, ext) = when {
                        rawMime == "image/gif" -> GifToMp4Converter.convert(rawBytes, getApplication())
                        rawMime.startsWith("image/") -> MediaCompressor.compressForContent(rawBytes, rawMime).asTriple()
                        else -> Triple(rawBytes, rawMime, rawExt)
                    }
                    // Re-extract dimensions from post-pipeline bytes so dim tags match what's uploaded.
                    val dims = extractDimensionsFromBytes(bytes, mime)
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
        val prev = _content.value
        // Shift/drop tracked mention ranges based on the edit delta between prev and value.
        if (prev.text != value.text) {
            val (editStart, oldEnd, newEnd) = diffRange(prev.text, value.text)
            if (editStart >= 0) {
                val delta = (newEnd - editStart) - (oldEnd - editStart)
                _mentions.value = _mentions.value.mapNotNull { m ->
                    when {
                        oldEnd <= m.start -> m.copy(start = m.start + delta, end = m.end + delta)
                        editStart >= m.end -> m
                        else -> null // edit overlaps the mention range — user is breaking it; drop
                    }
                }
                saveMentionsToState()
            }
        }

        // Auto-prefix bare bech32 IDs with nostr:
        val prefixed = prefixBareBech32(value)
        _content.value = prefixed
        savedStateHandle["draft_content"] = prefixed.text
        detectMentionQuery(prefixed)
        detectHashtags(prefixed.text)
    }

    /** Returns (editStart, oldEnd, newEnd) for the minimal edit between [old] and [new].
     *  editStart is the first differing index; oldEnd/newEnd are the exclusive ends of the
     *  changed regions in [old] and [new] respectively. Returns (-1,-1,-1) if strings are equal. */
    private fun diffRange(old: String, new: String): Triple<Int, Int, Int> {
        if (old == new) return Triple(-1, -1, -1)
        val maxPrefix = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(old.length - prefix, new.length - prefix)
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return Triple(prefix, old.length - suffix, new.length - suffix)
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

        val displayName = sanitizeMentionDisplay(candidate)
        val insert = "@$displayName"
        val before = text.substring(0, mentionStartIndex)
        val after = if (cursor < text.length) text.substring(cursor) else ""
        val newText = before + insert + after
        val newCursor = before.length + insert.length

        // The replaced range is [mentionStartIndex, cursor); new content has length `insert.length` there.
        val replacedLen = cursor - mentionStartIndex
        val lenDelta = insert.length - replacedLen
        val shifted = _mentions.value.mapNotNull { m ->
            when {
                m.end <= mentionStartIndex -> m
                m.start >= cursor -> m.copy(start = m.start + lenDelta, end = m.end + lenDelta)
                else -> null // range overlaps replaced @query — drop (shouldn't normally happen)
            }
        }
        val newMention = Mention(before.length, before.length + insert.length, candidate.profile.pubkey)
        _mentions.value = shifted + newMention
        saveMentionsToState()

        _content.value = TextFieldValue(newText, TextRange(newCursor))
        savedStateHandle["draft_content"] = newText
        clearMentionState()
    }

    private fun sanitizeMentionDisplay(candidate: MentionCandidate): String {
        val raw = candidate.profile.displayName?.takeIf { it.isNotBlank() }
            ?: candidate.profile.name?.takeIf { it.isNotBlank() }
            ?: return candidate.profile.pubkey.take(8) + "…" + candidate.profile.pubkey.takeLast(4)
        // Strip whitespace and leading @ so the mention remains a single token and mention detection
        // can't re-trigger on a name that itself contains spaces.
        return raw.trim().removePrefix("@").replace(Regex("\\s+"), "_")
    }


    fun publish(
        relayPool: RelayPool,
        replyTo: NostrEvent? = null,
        quoteTo: NostrEvent? = null,
        onSuccess: () -> Unit = {},
        outboxRouter: OutboxRouter? = null,
        signer: NostrSigner? = null,
        onNotePublished: (() -> Unit)? = null,
        powManager: PowManager? = null,
        resolvedEmojis: Map<String, String> = emptyMap()
    ) {
        val rawText = _content.value.text
        val (materialized, _) = materializeMentions(rawText, _mentions.value)
        val text = materialized.trim()

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
        startCountdown(text, s, relayPool, replyTo, quoteTo, outboxRouter, onSuccess, onNotePublished, powManager, resolvedEmojis)
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
        powManager: PowManager? = null,
        resolvedEmojis: Map<String, String> = emptyMap()
    ) {
        countdownJob?.cancel()
        pendingPublish = {
            viewModelScope.launch {
                try {
                    val sentCount = publishNote(content, signer, relayPool, replyTo, quoteTo, outboxRouter, powManager, resolvedEmojis)
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
        powManager: PowManager? = null,
        resolvedEmojis: Map<String, String> = emptyMap()
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
                .filter { it.isNotBlank() }
            if (nonBlankOptions.size < 2) {
                _error.value = getApplication<Application>().getString(R.string.error_poll_options)
                _publishing.value = false
                return 0
            }
            val pollRelays = relayPool.getWriteRelayUrls()
            if (_isZapPoll.value) {
                val zapPollOptions = nonBlankOptions.mapIndexed { i, label ->
                    Nip69.ZapPollOption(i, label.trim())
                }
                tags.addAll(Nip69.buildZapPollTags(
                    options = zapPollOptions,
                    valueMinimum = _zapPollMinSats.value,
                    valueMaximum = _zapPollMaxSats.value,
                    consensusThreshold = _zapPollConsensus.value,
                    relayUrls = pollRelays
                ))
                eventKind = Nip69.KIND_ZAP_POLL
            } else {
                val nip88Options = nonBlankOptions.mapIndexed { i, label ->
                    Nip88.PollOption(i.toString(), label.trim())
                }
                tags.addAll(Nip88.buildPollTags(nip88Options, _pollType.value, relayUrls = pollRelays))
                eventKind = Nip88.KIND_POLL
            }
        } else {
            eventKind = 1
        }

        // Add emoji tags for any :shortcode: references in the content
        tags.addAll(Nip30.buildEmojiTagsForContent(content, resolvedEmojis))

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
            _mentions.value = emptyList()
            savedStateHandle.remove<String>("draft_content")
            savedStateHandle.remove<Array<String>>("draft_mentions")
            _uploadedUrls.value = emptyList()
            _error.value = null
            _publishing.value = false
            _scheduleEnabled.value = false
            _scheduleTimestamp.value = null
            return sentCount
        }

        // Pubkeys whose inbox relays should also receive this note: the reply target plus
        // anyone mentioned in the content. Dedup happens inside OutboxRouter.publishToInbox.
        val inboxPubkeys = buildSet {
            replyTo?.pubkey?.let { add(it) }
            addAll(mentionedPubkeys)
        }

        // Hand off to PowManager for background mining if PoW enabled
        if (_powEnabled.value && powManager != null) {
            powManager.submitNote(
                signer = signer,
                content = finalContent,
                tags = tags,
                kind = eventKind,
                inboxPubkeys = inboxPubkeys,
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
            _mentions.value = emptyList()
            savedStateHandle.remove<String>("draft_content")
            savedStateHandle.remove<Array<String>>("draft_mentions")
            _uploadedUrls.value = emptyList()
            _error.value = null
            _publishing.value = false
            return -1
        }

        val event = signer.signEvent(kind = eventKind, content = finalContent, tags = tags)
        android.util.Log.d("GALLERY", "[ComposeVM] publishNote kind=$eventKind id=${event.id.take(12)} content='${finalContent.take(50)}' tags=${tags.size} galleryMode=${_galleryMode.value} uploadedUrls=${_uploadedUrls.value.size}")
        val msg = ClientMessage.event(event)
        var sentCount = if (outboxRouter != null && inboxPubkeys.isNotEmpty()) {
            outboxRouter.publishToInbox(msg, inboxPubkeys)
        } else {
            relayPool.sendToWriteRelays(msg)
        }
        // If no relays were reachable, try reconnecting write relays and retry once
        if (sentCount == 0) {
            val reconnected = relayPool.ensureWriteRelaysConnected()
            if (reconnected > 0) {
                sentCount = if (outboxRouter != null && inboxPubkeys.isNotEmpty()) {
                    outboxRouter.publishToInbox(msg, inboxPubkeys)
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

    private fun saveMentionsToState() {
        savedStateHandle["draft_mentions"] = _mentions.value.map { "${it.start},${it.end},${it.pubkey}" }.toTypedArray()
    }

    /** Builds the publish-ready content by splicing tracked mention ranges into nostr:nprofile URIs.
     *  Stale ranges (beyond text length) are skipped defensively. */
    private fun materializeMentions(text: String, mentions: List<Mention>): Pair<String, Set<String>> {
        if (mentions.isEmpty()) return text to emptySet()
        val sorted = mentions.filter { it.start in 0..text.length && it.end in it.start..text.length }
            .sortedByDescending { it.start }
        var out = text
        val pubkeys = mutableSetOf<String>()
        for (m in sorted) {
            val uri = "nostr:" + Nip19.nprofileEncode(m.pubkey)
            out = out.substring(0, m.start) + uri + out.substring(m.end)
            pubkeys.add(m.pubkey)
        }
        return out to pubkeys
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

    private fun extractDimensionsFromBytes(bytes: ByteArray, mime: String): Pair<Int, Int>? {
        return try {
            if (mime.startsWith("video/")) {
                val tmp = java.io.File.createTempFile("dims_", ".mp4", getApplication<Application>().cacheDir)
                try {
                    tmp.writeBytes(bytes)
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tmp.absolutePath)
                        val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        if (w != null && h != null && w > 0 && h > 0) w to h else null
                    } finally {
                        retriever.release()
                    }
                } finally {
                    tmp.delete()
                }
            } else if (mime.startsWith("image/")) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
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
        // Materialize @mentions into nostr:nprofile URIs so the draft is restorable as a standalone text.
        val (materialized, _) = materializeMentions(_content.value.text, _mentions.value)
        val text = materialized.trim()
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
        _mentions.value = emptyList()
        savedStateHandle.remove<String>("draft_content")
        savedStateHandle.remove<Array<String>>("draft_mentions")
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
        _isZapPoll.value = false
        _zapPollMinSats.value = null
        _zapPollMaxSats.value = null
        _zapPollConsensus.value = null
        _scheduleEnabled.value = false
        _scheduleTimestamp.value = null
        clearMentionState()
    }
}
