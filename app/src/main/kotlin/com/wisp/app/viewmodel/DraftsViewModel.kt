package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip37
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DraftsViewModel(app: Application) : AndroidViewModel(app) {

    private val _drafts = MutableStateFlow<List<Nip37.Draft>>(emptyList())
    val drafts: StateFlow<List<Nip37.Draft>> = _drafts

    private val _scheduledPosts = MutableStateFlow<List<NostrEvent>>(emptyList())
    val scheduledPosts: StateFlow<List<NostrEvent>> = _scheduledPosts

    private val _scheduledLoading = MutableStateFlow(false)
    val scheduledLoading: StateFlow<Boolean> = _scheduledLoading

    private var draftsLoaded = false
    private val draftsSubId = "drafts_${System.currentTimeMillis()}"
    private var scheduledJob: Job? = null

    companion object {
        val SCHEDULER_RELAY = "wss://scheduler.nostrarchives.com"
    }

    fun loadDrafts(relayPool: RelayPool, signer: NostrSigner?) {
        if (draftsLoaded || signer == null) return
        draftsLoaded = true

        val filter = Filter(
            kinds = listOf(Nip37.KIND_DRAFT),
            authors = listOf(signer.pubkeyHex),
            limit = 50
        )
        relayPool.sendToWriteRelays(ClientMessage.req(draftsSubId, filter))

        viewModelScope.launch(Dispatchers.Default) {
            relayPool.events.collect { event ->
                if (event.kind != Nip37.KIND_DRAFT) return@collect
                if (event.pubkey != signer.pubkeyHex) return@collect

                try {
                    val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                    if (decrypted.isBlank()) {
                        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                        if (dTag != null) {
                            _drafts.value = _drafts.value.filter { it.dTag != dTag }
                        }
                        return@collect
                    }
                    val draft = Nip37.parseDraft(event, decrypted) ?: return@collect
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

    fun loadScheduledPosts(relayPool: RelayPool, signer: NostrSigner?) {
        if (signer == null) return

        // Cancel previous collector to avoid stacking up collectors on every screen visit
        scheduledJob?.cancel()

        // Evict any stale relay (disconnected after scheduling, stuck with autoReconnect=false)
        // so connectEphemeralRelay always creates a fresh connection with a clean auth handshake.
        relayPool.disconnectRelay(SCHEDULER_RELAY)

        // Only show spinner if we have nothing to display yet — keep existing posts visible while refreshing
        if (_scheduledPosts.value.isEmpty()) _scheduledLoading.value = true

        val subId = "scheduled_${System.currentTimeMillis()}"

        scheduledJob = viewModelScope.launch(Dispatchers.Default) {
            relayPool.autoApproveRelayAuth(SCHEDULER_RELAY)
            relayPool.connectEphemeralRelay(SCHEDULER_RELAY)

            val filter = Filter(
                kinds = listOf(1),
                authors = listOf(signer.pubkeyHex),
                limit = 100
            )

            // Wait for AUTH — always required on a fresh connection
            withTimeoutOrNull(5_000) {
                relayPool.authCompleted.first { it == SCHEDULER_RELAY }
            }
            relayPool.sendToRelayOrEphemeral(SCHEDULER_RELAY, ClientMessage.req(subId, filter), skipBadCheck = true)

            // Stop spinner after 10s if relay sends nothing
            val timeoutJob = launch {
                delay(10_000)
                _scheduledLoading.value = false
            }

            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.relayUrl != SCHEDULER_RELAY) return@collect
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.kind != 1) return@collect
                if (event.pubkey != signer.pubkeyHex) return@collect

                timeoutJob.cancel()
                _scheduledLoading.value = false
                val current = _scheduledPosts.value.toMutableList()
                if (current.none { it.id == event.id }) {
                    current.add(event)
                    _scheduledPosts.value = current.sortedByDescending { it.created_at }
                }
            }
        }
    }

    fun deleteScheduledPost(eventId: String, relayPool: RelayPool, signer: NostrSigner?) {
        if (signer == null) return
        _scheduledPosts.value = _scheduledPosts.value.filter { it.id != eventId }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val tags = Nip09.buildDeletionTags(eventId, 1)
                val event = signer.signEvent(kind = 5, content = "", tags = tags)
                relayPool.sendToRelayOrEphemeral(SCHEDULER_RELAY, ClientMessage.event(event), skipBadCheck = true)
            } catch (_: Exception) {
                // Best effort
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
        draftsLoaded = false
        scheduledJob?.cancel()
        scheduledJob = null
        _scheduledPosts.value = emptyList()
        _scheduledLoading.value = false
    }
}
