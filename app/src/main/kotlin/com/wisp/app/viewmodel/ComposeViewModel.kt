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
import com.wisp.app.repo.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
// Matches bare bech32 IDs not already preceded by "nostr:"
private val BARE_BECH32_REGEX = Regex("(?<!nostr:)(?<![a-z0-9])((note1|nevent1|npub1|nprofile1)[a-z0-9]{10,})")

class ComposeViewModel(app: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

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

    private val _explicit = MutableStateFlow(false)
    val explicit: StateFlow<Boolean> = _explicit

    fun toggleExplicit() {
        _explicit.value = !_explicit.value
    }

    private var mentionStartIndex: Int = -1
    private var countdownJob: Job? = null
    private var pendingPublish: (() -> Unit)? = null
    private var mentionSearchRepo: MentionSearchRepository? = null
    private var eventRepo: EventRepository? = null
    private var initialized = false

    var currentDraftId: String? = null
        private set

    fun init(profileRepo: ProfileRepository, contactRepo: ContactRepository, relayPool: RelayPool, eventRepo: EventRepository? = null) {
        if (initialized) return
        initialized = true
        this.eventRepo = eventRepo
        mentionSearchRepo = MentionSearchRepository(profileRepo, contactRepo, relayPool, keyRepo)
        // Forward candidates from search repo
        viewModelScope.launch {
            mentionSearchRepo!!.candidates.collect { _mentionCandidates.value = it }
        }
    }

    fun uploadMedia(uris: List<Uri>, contentResolver: ContentResolver, signer: NostrSigner? = null) {
        viewModelScope.launch {
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                try {
                    _uploadProgress.value = if (total > 1) "Uploading ${index + 1}/$total..." else "Uploading..."
                    val (bytes, mime, ext) = readFileFromUri(contentResolver, uri)
                    val url = blossomRepo.uploadMedia(bytes, mime, ext, signer)
                    _uploadedUrls.value = _uploadedUrls.value + url
                    val current = _content.value.text
                    val newText = if (current.isBlank()) url else "$current\n$url"
                    _content.value = TextFieldValue(newText, TextRange(newText.length))
                    savedStateHandle["draft_content"] = newText
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
        val current = _content.value.text
        val newText = current.replace(url, "").replace("\n\n", "\n").trim()
        _content.value = TextFieldValue(newText, TextRange(newText.length))
        savedStateHandle["draft_content"] = newText
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
        signer: NostrSigner? = null
    ) {
        val text = _content.value.text.trim()

        if (text.isBlank()) {
            _error.value = "Post cannot be empty"
            return
        }

        val s = signer
        if (s == null) {
            _error.value = "Not logged in"
            return
        }

        _publishing.value = true
        startCountdown(text, s, relayPool, replyTo, quoteTo, outboxRouter, onSuccess)
    }

    private fun startCountdown(
        content: String,
        signer: NostrSigner,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent?,
        outboxRouter: OutboxRouter?,
        onSuccess: () -> Unit
    ) {
        countdownJob?.cancel()
        pendingPublish = {
            viewModelScope.launch {
                try {
                    val sentCount = publishNote(content, signer, relayPool, replyTo, quoteTo, outboxRouter)
                    if (sentCount == 0) return@launch
                    onSuccess()
                } catch (e: Exception) {
                    _error.value = "Failed to publish: ${e.message}"
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

    /** Publishes a note and stores the event ID. Returns the number of relays sent to (0 = failure). */
    private suspend fun publishNote(
        content: String,
        signer: NostrSigner,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent? = null,
        outboxRouter: OutboxRouter? = null
    ): Int {
        val tags = mutableListOf<List<String>>()
        if (_explicit.value) {
            tags.add(listOf("content-warning", ""))
        }
        if (replyTo != null) {
            val hint = outboxRouter?.getRelayHint(replyTo.pubkey) ?: ""
            tags.addAll(Nip10.buildReplyTags(replyTo, hint))
        }

        val (mentionedPubkeys, mentionedEventIds) = extractNostrRefs(content)
        val existingPubkeys = tags.filter { it.firstOrNull() == "p" }.map { it[1] }.toSet()
        for (pubkey in mentionedPubkeys) {
            if (pubkey !in existingPubkeys) {
                tags.add(listOf("p", pubkey))
            }
        }
        val existingEventIds = tags.filter { it.firstOrNull() == "e" }.map { it[1] }.toSet()
        for (eventId in mentionedEventIds) {
            if (eventId !in existingEventIds) {
                tags.add(listOf("e", eventId, "", "mention"))
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
        val event = signer.signEvent(kind = 1, content = finalContent, tags = tags)
        val msg = ClientMessage.event(event)
        val sentCount = if (replyTo != null && outboxRouter != null) {
            outboxRouter.publishToInbox(msg, replyTo.pubkey)
        } else {
            relayPool.sendToWriteRelays(msg)
        }
        if (sentCount == 0) {
            _error.value = "No relays connected — note was not published"
            _publishing.value = false
            return 0
        }
        relayPool.trackPublish(event.id, sentCount)
        // Cache locally so reply appears immediately without waiting for relay echo
        eventRepo?.cacheEvent(event)
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
        clearMentionState()
    }
}
