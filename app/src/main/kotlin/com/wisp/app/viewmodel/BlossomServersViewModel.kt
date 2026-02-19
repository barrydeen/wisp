package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BlossomServersViewModel(app: Application) : AndroidViewModel(app) {
    val blossomRepo = BlossomRepository(app)
    private val keyRepo = KeyRepository(app)

    val servers: StateFlow<List<String>> = blossomRepo.servers

    private val _newServerUrl = MutableStateFlow("")
    val newServerUrl: StateFlow<String> = _newServerUrl

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

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

    fun publishServerList(relayPool: RelayPool) {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Blossom.buildServerListTags(servers.value)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Blossom.KIND_SERVER_LIST,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
    }
}
