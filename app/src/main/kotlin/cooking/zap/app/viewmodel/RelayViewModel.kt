package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.RelaySetType
import cooking.zap.app.repo.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    var relayPool: RelayPool? = null

    private val _selectedTab = MutableStateFlow(RelaySetType.GENERAL)
    val selectedTab: StateFlow<RelaySetType> = _selectedTab

    val relays: StateFlow<List<RelayConfig>> = keyRepo.relaysFlow
    val dmRelays: StateFlow<List<String>> = keyRepo.dmRelaysFlow
    val searchRelays: StateFlow<List<String>> = keyRepo.searchRelaysFlow
    val blockedRelays: StateFlow<List<String>> = keyRepo.blockedRelaysFlow

    /** Re-point prefs at the current user's file so flows pick up their relay data. */
    fun reload() {
        keyRepo.reloadPrefs(keyRepo.getPubkeyHex())
    }

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
        val url = _newRelayUrl.value.trim().trimEnd('/')
        if (url.isBlank()) return false
        if (!RelayConfig.isValidUrl(url)) return false

        when (_selectedTab.value) {
            RelaySetType.GENERAL -> {
                if (relays.value.any { it.url == url }) return false
                keyRepo.saveRelays(relays.value + RelayConfig(url))
            }
            RelaySetType.DM -> {
                if (url in dmRelays.value) return false
                keyRepo.saveDmRelays(dmRelays.value + url)
            }
            RelaySetType.SEARCH -> {
                if (url in searchRelays.value) return false
                keyRepo.saveSearchRelays(searchRelays.value + url)
            }
            RelaySetType.BLOCKED -> {
                if (url in blockedRelays.value) return false
                val updated = blockedRelays.value + url
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
                keyRepo.saveRelays(relays.value.filter { it.url != url })
            }
            RelaySetType.DM -> {
                keyRepo.saveDmRelays(dmRelays.value.filter { it != url })
            }
            RelaySetType.SEARCH -> {
                keyRepo.saveSearchRelays(searchRelays.value.filter { it != url })
            }
            RelaySetType.BLOCKED -> {
                val updated = blockedRelays.value.filter { it != url }
                keyRepo.saveBlockedRelays(updated)
                relayPool?.updateBlockedUrls(updated)
            }
        }
    }

    fun toggleRead(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(read = !it.read) else it
        }
        keyRepo.saveRelays(updated)
    }

    fun toggleWrite(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(write = !it.write) else it
        }
        keyRepo.saveRelays(updated)
    }

    fun toggleAuth(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(auth = !it.auth) else it
        }
        keyRepo.saveRelays(updated)
    }

    fun publishRelayList(relayPool: RelayPool, signer: NostrSigner? = null): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return false
        return try {
            val tab = _selectedTab.value
            val tags: List<List<String>>
            val kind: Int = tab.eventKind

            when (tab) {
                RelaySetType.GENERAL -> {
                    tags = Nip65.buildRelayTags(relays.value)
                }
                RelaySetType.DM -> {
                    tags = Nip51.buildRelaySetTags(dmRelays.value)
                }
                RelaySetType.SEARCH -> {
                    tags = Nip51.buildRelaySetTags(searchRelays.value)
                }
                RelaySetType.BLOCKED -> {
                    tags = Nip51.buildRelaySetTags(blockedRelays.value)
                }
            }

            viewModelScope.launch {
                val event = s.signEvent(kind = kind, content = "", tags = tags)
                val msg = ClientMessage.event(event)
                relayPool.sendToWriteRelays(msg)
                for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                    relayPool.sendToRelayOrEphemeral(url, msg)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
