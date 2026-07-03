package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip09
import cooking.zap.app.nostr.Nip37
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.DeletedEventsRepository
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

    fun loadDrafts(relayPool: RelayPool, signer: NostrSigner?, deletedEventsRepo: DeletedEventsRepository? = null) {
        if (draftsLoaded || signer == null) return
        draftsLoaded = true

        val filter = Filter(
            kinds = listOf(Nip37.KIND_DRAFT),
            authors = listOf(signer.pubkeyHex),
            limit = 50
        )
        viewModelScope.launch(Dispatchers.Default) {
            // Ensure write relays are connected before querying — they may be idle right after launch,
            // in which case a bare REQ reaches 0 relays and the drafts list comes back empty.
            val req = ClientMessage.req(draftsSubId, filter)
            var reqSent = relayPool.sendToWriteRelays(req)
            if (reqSent == 0 && relayPool.ensureWriteRelaysConnected(2_000) > 0) {
                reqSent = relayPool.sendToWriteRelays(req)
            }
            // Match the save fallback: drafts may live on read relays for accounts without
            // reachable write relays, so query every connected relay when write relays yield none.
            if (reqSent == 0) relayPool.sendToAllRelays(req)

            // Track the newest version seen per draft coordinate so an older copy arriving from
            // a lagging relay can't resurrect a draft that was superseded or emptied (deleted).
            val latestCreatedAt = HashMap<String, Long>()

            relayPool.events.collect { event ->
                if (event.kind != Nip37.KIND_DRAFT) return@collect
                if (event.pubkey != signer.pubkeyHex) return@collect
                val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return@collect

                val prev = latestCreatedAt[dTag]
                if (prev != null && event.created_at < prev) return@collect
                latestCreatedAt[dTag] = event.created_at

                try {
                    // NIP-37 deletes a draft by publishing an empty replacement — the decrypted
                    // content is then blank. Match iOS Wisp: show every non-empty draft and do not
                    // cross-reference the kind-5 deletion registry, which hid drafts iOS still shows.
                    val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                    if (decrypted.isBlank()) {
                        _drafts.value = _drafts.value.filter { it.dTag != dTag }
                        return@collect
                    }
                    val draft = Nip37.parseDraft(event, decrypted) ?: return@collect
                    val current = _drafts.value.toMutableList()
                    val idx = current.indexOfFirst { it.dTag == draft.dTag }
                    if (idx >= 0) current[idx] = draft else current.add(draft)
                    _drafts.value = current.sortedByDescending { it.createdAt }
                } catch (_: Exception) {
                    // Decryption/parse failed — skip
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

    fun deleteDraft(
        dTag: String,
        relayPool: RelayPool,
        signer: NostrSigner?,
        deletedEventsRepo: DeletedEventsRepository? = null
    ) {
        if (signer == null) return
        removeDraftLocally(dTag)
        // Persist locally first so any future relay response for this coord is silently dropped,
        // even before the kind 5 deletion propagates.
        deletedEventsRepo?.markDeletedAddress(Nip37.KIND_DRAFT, signer.pubkeyHex, dTag)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // NIP-09: publish an addressable-delete (kind 5 with "a" tag) so relays drop the draft.
                val deletionTags = Nip09.buildAddressableDeletionTags(Nip37.KIND_DRAFT, signer.pubkeyHex, dTag)
                val deleteEvent = signer.signEvent(kind = 5, content = "", tags = deletionTags)
                relayPool.sendToWriteRelays(ClientMessage.event(deleteEvent))

                // Also publish an empty-content replacement so clients that don't honor NIP-09
                // (or that use the replace-by-address semantics of NIP-37) still observe deletion.
                val replacementTags = Nip37.buildDraftTags(dTag, 1)
                val encrypted = signer.nip44Encrypt("", signer.pubkeyHex)
                val replacement = signer.signEvent(
                    kind = Nip37.KIND_DRAFT,
                    content = encrypted,
                    tags = replacementTags
                )
                relayPool.sendToWriteRelays(ClientMessage.event(replacement))
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
