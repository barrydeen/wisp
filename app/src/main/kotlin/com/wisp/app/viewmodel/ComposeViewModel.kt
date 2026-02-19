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
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
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

    private val _attachedImageUri = MutableStateFlow<Uri?>(null)
    val attachedImageUri: StateFlow<Uri?> = _attachedImageUri

    private val _uploadProgress = MutableStateFlow<String?>(null)
    val uploadProgress: StateFlow<String?> = _uploadProgress

    fun attachImage(uri: Uri) {
        _attachedImageUri.value = uri
    }

    fun removeAttachment() {
        _attachedImageUri.value = null
    }

    fun updateContent(value: String) {
        _content.value = value
    }

    fun publish(
        relayPool: RelayPool,
        replyTo: NostrEvent? = null,
        quoteTo: NostrEvent? = null,
        contentResolver: ContentResolver? = null,
        onSuccess: () -> Unit = {}
    ) {
        val text = _content.value.trim()
        val imageUri = _attachedImageUri.value

        if (text.isBlank() && imageUri == null) {
            _error.value = "Post cannot be empty"
            return
        }

        val keypair = keyRepo.getKeypair()
        if (keypair == null) {
            _error.value = "Not logged in"
            return
        }

        if (imageUri != null && contentResolver != null) {
            _publishing.value = true
            viewModelScope.launch {
                try {
                    _uploadProgress.value = "Uploading image..."
                    val (bytes, mime, ext) = readFileFromUri(contentResolver, imageUri)
                    val url = blossomRepo.uploadMedia(bytes, mime, ext)
                    _uploadProgress.value = "Publishing..."
                    val finalContent = if (text.isBlank()) url else "$text\n$url"
                    publishNote(finalContent, keypair, relayPool, replyTo, quoteTo)
                    _attachedImageUri.value = null
                    _uploadProgress.value = null
                    onSuccess()
                } catch (e: Exception) {
                    _error.value = "Upload failed: ${e.message}"
                    _publishing.value = false
                    _uploadProgress.value = null
                }
            }
        } else {
            try {
                _publishing.value = true
                publishNote(text, keypair, relayPool, replyTo, quoteTo)
                onSuccess()
            } catch (e: Exception) {
                _error.value = "Failed to publish: ${e.message}"
                _publishing.value = false
            }
        }
    }

    private fun publishNote(
        content: String,
        keypair: Keys.Keypair,
        relayPool: RelayPool,
        replyTo: NostrEvent?,
        quoteTo: NostrEvent? = null
    ) {
        val tags = mutableListOf<List<String>>()
        if (replyTo != null) tags.addAll(Nip10.buildReplyTags(replyTo))
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
        relayPool.sendToWriteRelays(msg)
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
        _attachedImageUri.value = null
        _uploadProgress.value = null
    }
}
