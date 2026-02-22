package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelaySetType
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    var relayPool: RelayPool? = null

    private val _selectedTab = MutableStateFlow(RelaySetType.GENERAL)
    val selectedTab: StateFlow<RelaySetType> = _selectedTab

    private val _relays = MutableStateFlow(keyRepo.getRelays())
    val relays: StateFlow<List<RelayConfig>> = _relays

    private val _dmRelays = MutableStateFlow(keyRepo.getDmRelays())
    val dmRelays: StateFlow<List<String>> = _dmRelays

    private val _searchRelays = MutableStateFlow(keyRepo.getSearchRelays())
    val searchRelays: StateFlow<List<String>> = _searchRelays

    private val _blockedRelays = MutableStateFlow(keyRepo.getBlockedRelays())
    val blockedRelays: StateFlow<List<String>> = _blockedRelays

    private val _newRelayUrl = MutableStateFlow("")
    val newRelayUrl: StateFlow<String> = _newRelayUrl

    fun selectTab(tab: RelaySetType) {
        _selectedTab.value = tab
        _newRelayUrl.value = ""
    }

    fun updateNewRelayUrl(value: String) {
        _newRelayUrl.value = value
    }

    fun addRelay(): Boolean {
        val url = _newRelayUrl.value.trim()
        if (url.isBlank() || !url.startsWith("wss://")) return false

        when (_selectedTab.value) {
            RelaySetType.GENERAL -> {
                if (_relays.value.any { it.url == url }) return false
                val updated = _relays.value + RelayConfig(url)
                _relays.value = updated
                keyRepo.saveRelays(updated)
            }
            RelaySetType.DM -> {
                if (url in _dmRelays.value) return false
                val updated = _dmRelays.value + url
                _dmRelays.value = updated
                keyRepo.saveDmRelays(updated)
            }
            RelaySetType.SEARCH -> {
                if (url in _searchRelays.value) return false
                val updated = _searchRelays.value + url
                _searchRelays.value = updated
                keyRepo.saveSearchRelays(updated)
            }
            RelaySetType.BLOCKED -> {
                if (url in _blockedRelays.value) return false
                val updated = _blockedRelays.value + url
                _blockedRelays.value = updated
                keyRepo.saveBlockedRelays(updated)
                relayPool?.updateBlockedUrls(updated)
            }
        }
        _newRelayUrl.value = ""
        return true
    }

    fun removeRelay(url: String) {
        when (_selectedTab.value) {
            RelaySetType.GENERAL -> {
                val updated = _relays.value.filter { it.url != url }
                _relays.value = updated
                keyRepo.saveRelays(updated)
            }
            RelaySetType.DM -> {
                val updated = _dmRelays.value.filter { it != url }
                _dmRelays.value = updated
                keyRepo.saveDmRelays(updated)
            }
            RelaySetType.SEARCH -> {
                val updated = _searchRelays.value.filter { it != url }
                _searchRelays.value = updated
                keyRepo.saveSearchRelays(updated)
            }
            RelaySetType.BLOCKED -> {
                val updated = _blockedRelays.value.filter { it != url }
                _blockedRelays.value = updated
                keyRepo.saveBlockedRelays(updated)
                relayPool?.updateBlockedUrls(updated)
            }
        }
    }

    fun toggleRead(url: String) {
        val updated = _relays.value.map {
            if (it.url == url) it.copy(read = !it.read) else it
        }
        _relays.value = updated
        keyRepo.saveRelays(updated)
    }

    fun toggleWrite(url: String) {
        val updated = _relays.value.map {
            if (it.url == url) it.copy(write = !it.write) else it
        }
        _relays.value = updated
        keyRepo.saveRelays(updated)
    }

    fun publishRelayList(relayPool: RelayPool, signer: NostrSigner? = null): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return false
        return try {
            val tab = _selectedTab.value
            val tags: List<List<String>>
            val kind: Int

            when (tab) {
                RelaySetType.GENERAL -> {
                    tags = Nip65.buildRelayTags(_relays.value)
                    kind = tab.eventKind
                }
                RelaySetType.DM -> {
                    tags = Nip51.buildRelaySetTags(_dmRelays.value)
                    kind = tab.eventKind
                }
                RelaySetType.SEARCH -> {
                    tags = Nip51.buildRelaySetTags(_searchRelays.value)
                    kind = tab.eventKind
                }
                RelaySetType.BLOCKED -> {
                    tags = Nip51.buildRelaySetTags(_blockedRelays.value)
                    kind = tab.eventKind
                }
            }

            viewModelScope.launch {
                val event = s.signEvent(kind = kind, content = "", tags = tags)
                val msg = ClientMessage.event(event)
                relayPool.sendToWriteRelays(msg)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
