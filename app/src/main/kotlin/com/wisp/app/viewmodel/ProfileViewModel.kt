package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepo = BlossomRepository(app)

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _about = MutableStateFlow("")
    val about: StateFlow<String> = _about

    private val _picture = MutableStateFlow("")
    val picture: StateFlow<String> = _picture

    private val _nip05 = MutableStateFlow("")
    val nip05: StateFlow<String> = _nip05

    private val _banner = MutableStateFlow("")
    val banner: StateFlow<String> = _banner

    private val _lud16 = MutableStateFlow("")
    val lud16: StateFlow<String> = _lud16

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }
    fun updatePicture(value: String) { _picture.value = value }
    fun updateNip05(value: String) { _nip05.value = value }
    fun updateBanner(value: String) { _banner.value = value }
    fun updateLud16(value: String) { _lud16.value = value }

    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading

    fun uploadImage(contentResolver: ContentResolver, uri: Uri, target: ImageTarget) {
        viewModelScope.launch {
            try {
                _uploading.value = when (target) {
                    ImageTarget.PICTURE -> "Uploading avatar..."
                    ImageTarget.BANNER -> "Uploading banner..."
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val url = blossomRepo.uploadMedia(bytes, mimeType, ext)
                when (target) {
                    ImageTarget.PICTURE -> _picture.value = url
                    ImageTarget.BANNER -> _banner.value = url
                }
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    enum class ImageTarget { PICTURE, BANNER }

    fun loadCurrentProfile(eventRepo: EventRepository) {
        val keypair = keyRepo.getKeypair() ?: return
        val pubkeyHex = keypair.pubkey.joinToString("") { "%02x".format(it) }
        val profile = eventRepo.getProfileData(pubkeyHex) ?: return
        _name.value = profile.name ?: ""
        _about.value = profile.about ?: ""
        _picture.value = profile.picture ?: ""
        _nip05.value = profile.nip05 ?: ""
        _banner.value = profile.banner ?: ""
        _lud16.value = profile.lud16 ?: ""
    }

    fun publishProfile(relayPool: RelayPool): Boolean {
        val keypair = keyRepo.getKeypair()
        if (keypair == null) {
            _error.value = "Not logged in"
            return false
        }

        return try {
            _publishing.value = true
            val content = buildJsonObject {
                if (_name.value.isNotBlank()) put("name", JsonPrimitive(_name.value))
                if (_about.value.isNotBlank()) put("about", JsonPrimitive(_about.value))
                if (_picture.value.isNotBlank()) put("picture", JsonPrimitive(_picture.value))
                if (_nip05.value.isNotBlank()) put("nip05", JsonPrimitive(_nip05.value))
                if (_banner.value.isNotBlank()) put("banner", JsonPrimitive(_banner.value))
                if (_lud16.value.isNotBlank()) put("lud16", JsonPrimitive(_lud16.value))
            }.toString()

            val event = NostrEvent.create(
                privkey = keypair.privkey,
                pubkey = keypair.pubkey,
                kind = 0,
                content = content
            )
            val msg = ClientMessage.event(event)
            relayPool.sendToWriteRelays(msg)
            _error.value = null
            _publishing.value = false
            true
        } catch (e: Exception) {
            _error.value = "Failed to publish: ${e.message}"
            _publishing.value = false
            false
        }
    }
}
