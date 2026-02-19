package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.OnboardingPhase
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayProber
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepo = BlossomRepository(app)

    // Relay discovery state (background)
    private val _phase = MutableStateFlow(OnboardingPhase.DISCOVERING)
    val phase: StateFlow<OnboardingPhase> = _phase

    private val _discoveredRelays = MutableStateFlow<List<RelayConfig>?>(null)
    val discoveredRelays: StateFlow<List<RelayConfig>?> = _discoveredRelays

    private val _probingUrl = MutableStateFlow<String?>(null)
    val probingUrl: StateFlow<String?> = _probingUrl

    // Pubkeys harvested during discovery — used for follow suggestions
    private val _harvestedPubkeys = MutableStateFlow<List<String>>(emptyList())
    val harvestedPubkeys: StateFlow<List<String>> = _harvestedPubkeys

    // Profile form state (foreground)
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _about = MutableStateFlow("")
    val about: StateFlow<String> = _about

    private val _picture = MutableStateFlow("")
    val picture: StateFlow<String> = _picture

    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }

    fun uploadImage(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                _uploading.value = "Uploading..."
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val url = blossomRepo.uploadMedia(bytes, mimeType, ext)
                _picture.value = url
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    fun startDiscovery() {
        val keypair = keyRepo.getKeypair() ?: return
        // Reload prefs to the new pubkey (ViewModel may have been created before signUp)
        keyRepo.reloadPrefs(keypair.pubkey.toHex())
        viewModelScope.launch {
            val relays = RelayProber.discoverAndSelect(
                keypair = keypair,
                onPhase = { phase -> _phase.value = phase },
                onProbing = { url -> _probingUrl.value = url }
            )
            _probingUrl.value = null
            _discoveredRelays.value = relays
        }
    }

    /**
     * Finish profile step: save relays, init relay pool, publish kind 0.
     * Returns true if successful.
     */
    fun finishProfile(relayPool: RelayPool): Boolean {
        val keypair = keyRepo.getKeypair() ?: return false
        val relays = _discoveredRelays.value ?: RelayConfig.DEFAULTS

        return try {
            _publishing.value = true

            // Save and connect to discovered relays
            keyRepo.saveRelays(relays)
            relayPool.updateRelays(relays)

            _phase.value = OnboardingPhase.BROADCASTING

            // Publish kind 10002 relay list so other users can find us
            val relayTags = Nip65.buildRelayTags(relays)
            val relayListEvent = NostrEvent.create(
                privkey = keypair.privkey,
                pubkey = keypair.pubkey,
                kind = 10002,
                content = "",
                tags = relayTags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(relayListEvent))

            // Publish kind 0 profile if user filled in any field
            if (_name.value.isNotBlank() || _about.value.isNotBlank() || _picture.value.isNotBlank()) {
                val content = buildJsonObject {
                    if (_name.value.isNotBlank()) put("name", JsonPrimitive(_name.value))
                    if (_about.value.isNotBlank()) put("about", JsonPrimitive(_about.value))
                    if (_picture.value.isNotBlank()) put("picture", JsonPrimitive(_picture.value))
                }.toString()

                val event = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 0,
                    content = content
                )
                relayPool.sendToWriteRelays(ClientMessage.event(event))
            }

            _publishing.value = false
            true
        } catch (e: Exception) {
            _error.value = "Failed: ${e.message}"
            _publishing.value = false
            false
        }
    }

    /**
     * Finish onboarding: publish kind 3 follow list (if any selected), mark complete.
     */
    fun finishOnboarding(
        relayPool: RelayPool,
        contactRepo: ContactRepository,
        selectedPubkeys: Set<String>
    ) {
        val keypair = keyRepo.getKeypair() ?: return

        if (selectedPubkeys.isNotEmpty()) {
            // Build follow list from selected pubkeys
            var follows = contactRepo.getFollowList()
            for (pubkey in selectedPubkeys) {
                follows = Nip02.addFollow(follows, pubkey)
            }
            val tags = Nip02.buildFollowTags(follows)
            val event = NostrEvent.create(
                privkey = keypair.privkey,
                pubkey = keypair.pubkey,
                kind = 3,
                content = "",
                tags = tags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            contactRepo.updateFromEvent(event)
        }

        keyRepo.markOnboardingComplete()
    }
}
