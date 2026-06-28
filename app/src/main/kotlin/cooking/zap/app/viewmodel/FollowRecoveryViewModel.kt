package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.relay.RelayEvent
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Collections

data class FollowListCandidate(
    val eventId: String,
    val createdAt: Long,
    val followCount: Int,
    val pTags: List<List<String>>,
    val content: String,
    val foundOnRelays: List<String>,
    val isCurrent: Boolean,
    val isRecommended: Boolean
)

data class FollowScanResult(
    val candidates: List<FollowListCandidate>,
    val respondingRelays: Int
)

sealed class FollowRecoveryState {
    object Idle : FollowRecoveryState()
    object Scanning : FollowRecoveryState()
    data class Results(val result: FollowScanResult) : FollowRecoveryState()
    data class Confirming(
        val candidate: FollowListCandidate,
        val result: FollowScanResult,
        val gained: Int,
        val lost: Int
    ) : FollowRecoveryState()
    object Restoring : FollowRecoveryState()
    data class Done(val followCount: Int) : FollowRecoveryState()
    data class Error(val message: String) : FollowRecoveryState()
}

class FollowRecoveryViewModel(
    private val relayPool: RelayPool,
    private val contactRepo: ContactRepository,
    private val keyRepo: KeyRepository
) : ViewModel() {

    private fun buildSigner(): LocalSigner? {
        val keypair = keyRepo.getKeypair() ?: return null
        return LocalSigner(keypair.privkey, keypair.pubkey)
    }

    private val _state = MutableStateFlow<FollowRecoveryState>(FollowRecoveryState.Idle)
    val state: StateFlow<FollowRecoveryState> = _state

    private var scanJob: Job? = null

    private val archivalRelays = listOf(
        "wss://relay.snort.social",
        "wss://purplepag.es",
        "wss://relay.nostr.net",
        "wss://nostr.wine",
        "wss://offchain.pub",
        "wss://nostr.mom"
    )

    fun startScan() {
        val pubkey = keyRepo.getPubkeyHex() ?: return
        _state.value = FollowRecoveryState.Scanning
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = doScan(pubkey)
                _state.value = FollowRecoveryState.Results(result)
            } catch (e: Exception) {
                _state.value = FollowRecoveryState.Error(e.message ?: "Scan failed")
            }
        }
    }

    private suspend fun doScan(pubkey: String): FollowScanResult {
        val subId = "fr-${pubkey.take(8)}"
        val req = ClientMessage.req(subId, Filter(kinds = listOf(3), authors = listOf(pubkey), limit = 50))
        val collected = Collections.synchronizedList(mutableListOf<RelayEvent>())

        // Bypass the pool's per-event dedup so kind:3 events already seen during
        // normal session startup still reach our collector.
        relayPool.registerDedupBypass("fr-")

        coroutineScope {
            val collectJob = launch {
                relayPool.relayEvents
                    .filter { it.subscriptionId == subId && it.event.kind == 3 }
                    .collect { re -> collected.add(re) }
            }

            // Let the collector register on the SharedFlow before sending REQ
            yield()

            relayPool.sendToReadRelays(req)
            for (url in archivalRelays) {
                relayPool.sendToRelayOrEphemeral(url, req)
            }

            delay(9_000)
            collectJob.cancelAndJoin()
        }

        relayPool.closeOnAllRelays(subId)

        val respondingRelays = collected.map { it.relayUrl }.toSet()
        val relaysByEvent = collected
            .groupBy({ it.event.id }, { it.relayUrl })
            .mapValues { (_, v) -> v.distinct() }
        val eventsById = collected.associate { it.event.id to it.event }

        val sorted = eventsById.values.sortedByDescending { it.created_at }
        val currentEventId = sorted.firstOrNull()?.id

        val candidates = sorted.map { event ->
            val pTags = event.tags.filter { it.firstOrNull() == "p" }
            FollowListCandidate(
                eventId = event.id,
                createdAt = event.created_at,
                followCount = pTags.size,
                pTags = pTags,
                content = event.content,
                foundOnRelays = relaysByEvent[event.id] ?: emptyList(),
                isCurrent = event.id == currentEventId,
                isRecommended = false
            )
        }

        val currentCount = candidates.firstOrNull()?.followCount ?: 0

        // Cluster non-current candidates: versions within 5 follows (inclusive) of an
        // already-kept version are the same "era" — keep only the newest per cluster.
        // Then drop any cluster whose count is within 10 of current (noise).
        val clustered = mutableListOf<FollowListCandidate>()
        for (c in candidates.filter { !it.isCurrent && it.followCount > 0 }) {
            val covered = clustered.any { kotlin.math.abs(it.followCount - c.followCount) <= 5 }
            if (!covered) clustered.add(c)
        }
        val current = candidates.firstOrNull { it.isCurrent }
        val filtered = (listOfNotNull(current) + clustered.filter { c ->
            kotlin.math.abs(c.followCount - currentCount) >= 10
        })

        // Pick recommended from the filtered (visible) list only — prevents a candidate
        // that was clustered/filtered out from being marked isRecommended.
        val recommendedId = filtered
            .filter { !it.isCurrent && it.followCount > 0 }
            .maxByOrNull { it.followCount }
            ?.takeIf { it.followCount > currentCount || currentCount == 0 }
            ?.eventId

        return FollowScanResult(
            candidates = filtered.map { c ->
                c.copy(isRecommended = c.eventId == recommendedId)
            },
            respondingRelays = respondingRelays.size
        )
    }

    fun selectCandidate(candidate: FollowListCandidate) {
        val s = _state.value as? FollowRecoveryState.Results ?: return
        val currentPubkeys = contactRepo.getFollowList().map { it.pubkey }.toSet()
        val candidatePubkeys = candidate.pTags.mapNotNull { it.getOrNull(1) }.toSet()
        val gained = (candidatePubkeys - currentPubkeys).size
        val lost = (currentPubkeys - candidatePubkeys).size
        _state.value = FollowRecoveryState.Confirming(candidate, s.result, gained, lost)
    }

    fun backToResults() {
        val s = _state.value as? FollowRecoveryState.Confirming ?: return
        _state.value = FollowRecoveryState.Results(s.result)
    }

    fun confirmRestore(candidate: FollowListCandidate) {
        val signer = buildSigner() ?: return
        _state.value = FollowRecoveryState.Restoring
        viewModelScope.launch {
            try {
                val event = signer.signEvent(
                    kind = 3,
                    content = candidate.content,
                    tags = candidate.pTags
                )
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                contactRepo.updateFromEvent(event)
                _state.value = FollowRecoveryState.Done(candidate.followCount)
            } catch (e: Exception) {
                _state.value = FollowRecoveryState.Error(e.message ?: "Restore failed")
            }
        }
    }

    fun reset() {
        scanJob?.cancel()
        _state.value = FollowRecoveryState.Idle
    }
}
