package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BlossomServersViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

    val servers: StateFlow<List<String>> = blossomRepo.servers

    private val _newServerUrl = MutableStateFlow("")
    val newServerUrl: StateFlow<String> = _newServerUrl

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _published = MutableStateFlow(false)
    val published: StateFlow<Boolean> = _published

    /** Re-point prefs at the current user's file so flows pick up their server data. */
    fun reload() {
        blossomRepo.reload(keyRepo.getPubkeyHex())
    }

    fun refreshServers() {
        blossomRepo.refreshFromPrefs()
    }

    fun updateNewServerUrl(url: String) {
        _newServerUrl.value = url
    }

    fun addServer() {
        val url = _newServerUrl.value.trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            _error.value = "URL must start with https://"
            return
        }
        val current = servers.value.toMutableList()
        if (current.contains(url)) {
            _error.value = "Server already added"
            return
        }
        current.add(url)
        blossomRepo.saveBlossomServers(current)
        _newServerUrl.value = ""
        _error.value = null
    }

    fun removeServer(url: String) {
        val current = servers.value.toMutableList()
        current.remove(url)
        blossomRepo.saveBlossomServers(current)
    }

    fun publishServerList(relayPool: RelayPool, signer: NostrSigner? = null) {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return
        val tags = Blossom.buildServerListTags(servers.value)
        _published.value = false
        viewModelScope.launch {
            try {
                val event = s.signEvent(kind = Blossom.KIND_SERVER_LIST, content = "", tags = tags)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                _published.value = true
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to sign event: ${e.message}"
            }
        }
    }
}
