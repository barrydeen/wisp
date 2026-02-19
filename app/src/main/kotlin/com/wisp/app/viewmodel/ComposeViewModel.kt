package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ComposeViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    val blossomRepo = BlossomRepository(app)

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content

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

    private var countdownJob: Job? = null
    private var pendingPublish: (() -> Unit)? = null

    fun uploadMedia(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            try {
                _uploadProgress.value = "Uploading..."
                val (bytes, mime, ext) = readFileFromUri(contentResolver, uri)
                val url = blossomRepo.uploadMedia(bytes, mime, ext)
                _uploadedUrls.value = _uploadedUrls.value + url
                val current = _content.value
                _content.value = if (current.isBlank()) url else "$current\n$url"
                _uploadProgress.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploadProgress.value = null
            }
        }
    }

    fun removeMediaUrl(url: String) {
        _uploadedUrls.value = _uploadedUrls.value - url
        val current = _content.value
        _content.value = current.replace(url, "").replace("\n\n", "\n").trim()
    }

    fun updateContent(value: String) {
        _content.value = value
    }

    fun publish(
        relayPool: RelayPool,
        replyTo: NostrEvent? = null,
        quoteTo: NostrEvent? = null,
        onSuccess: () -> Unit = {},
        outboxRouter: OutboxRouter? = null
    ) {
        val text = _content.value.trim()

        if (text.isBlank()) {
            _error.value = "Post cannot be empty"
            return
        }

        val keypair = keyRepo.getKeypair()
        if (keypair == null) {
            _error.value = "Not logged in"
            return
        }

        _publishing.value = true
        startCountdown(text, keypair, relayPool, replyTo, quoteTo, outboxRouter, onSuccess)
    }

    private fun startCountdown(
        content: String,
        keypair: Keys.Keypair,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent?,
        outboxRouter: OutboxRouter?,
        onSuccess: () -> Unit
    ) {
        countdownJob?.cancel()
        pendingPublish = {
            try {
                publishNote(content, keypair, relayPool, replyTo, quoteTo, outboxRouter)
                _uploadedUrls.value = emptyList()
                onSuccess()
            } catch (e: Exception) {
                _error.value = "Failed to publish: ${e.message}"
                _publishing.value = false
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

    private fun publishNote(
        content: String,
        keypair: Keys.Keypair,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent? = null,
        outboxRouter: OutboxRouter? = null
    ) {
        val tags = mutableListOf<List<String>>()
        if (replyTo != null) {
            val hint = outboxRouter?.getRelayHint(replyTo.pubkey) ?: ""
            tags.addAll(Nip10.buildReplyTags(replyTo, hint))
        }
        val finalContent = if (quoteTo != null) {
            tags.addAll(Nip18.buildQuoteTags(quoteTo))
            Nip18.appendNoteUri(content, quoteTo.id)
        } else {
            content
        }
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = 1,
            content = finalContent,
            tags = tags
        )
        val msg = ClientMessage.event(event)
        // Replies go to target's inbox relays; root posts go to own write relays
        if (replyTo != null && outboxRouter != null) {
            outboxRouter.publishToInbox(msg, replyTo.pubkey)
        } else {
            relayPool.sendToWriteRelays(msg)
        }
        _content.value = ""
        _error.value = null
        _publishing.value = false
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

    fun clear() {
        _content.value = ""
        _error.value = null
        _uploadedUrls.value = emptyList()
        _uploadProgress.value = null
    }
}
