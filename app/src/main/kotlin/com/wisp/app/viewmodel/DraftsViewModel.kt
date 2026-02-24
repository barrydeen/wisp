package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip37
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DraftsViewModel(app: Application) : AndroidViewModel(app) {

    private val _drafts = MutableStateFlow<List<Nip37.Draft>>(emptyList())
    val drafts: StateFlow<List<Nip37.Draft>> = _drafts

    private var loaded = false
    private val subId = "drafts_${System.currentTimeMillis()}"

    fun loadDrafts(relayPool: RelayPool, signer: NostrSigner?) {
        if (loaded || signer == null) return
        loaded = true

        val filter = Filter(
            kinds = listOf(Nip37.KIND_DRAFT),
            authors = listOf(signer.pubkeyHex),
            limit = 50
        )
        relayPool.sendToWriteRelays(ClientMessage.req(subId, filter))

        viewModelScope.launch(Dispatchers.Default) {
            relayPool.events.collect { event ->
                if (event.kind != Nip37.KIND_DRAFT) return@collect
                if (event.pubkey != signer.pubkeyHex) return@collect

                try {
                    val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                    if (decrypted.isBlank()) {
                        // Blank content = deletion — remove draft with this d-tag
                        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                        if (dTag != null) {
                            _drafts.value = _drafts.value.filter { it.dTag != dTag }
                        }
                        return@collect
                    }
                    val draft = Nip37.parseDraft(event, decrypted) ?: return@collect
                    // Replace existing draft with same d-tag, or add new
                    val current = _drafts.value.toMutableList()
                    val idx = current.indexOfFirst { it.dTag == draft.dTag }
                    if (idx >= 0) {
                        current[idx] = draft
                    } else {
                        current.add(draft)
                    }
                    _drafts.value = current.sortedByDescending { it.createdAt }
                } catch (_: Exception) {
                    // Decryption failed, skip
                }
            }
        }
    }

    fun deleteDraft(dTag: String, relayPool: RelayPool, signer: NostrSigner?) {
        if (signer == null) return
        removeDraftLocally(dTag)

        viewModelScope.launch(Dispatchers.Default) {
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

    fun addDraftLocally(draft: Nip37.Draft) {
        val current = _drafts.value.toMutableList()
        val idx = current.indexOfFirst { it.dTag == draft.dTag }
        if (idx >= 0) {
            current[idx] = draft
        } else {
            current.add(draft)
        }
        _drafts.value = current.sortedByDescending { it.createdAt }
    }

    fun removeDraftLocally(dTag: String) {
        _drafts.value = _drafts.value.filter { it.dTag != dTag }
    }

    fun resetLoadState() {
        loaded = false
    }
}
