package com.wisp.app.repo

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MentionCandidate(
    val profile: ProfileData,
    val isContact: Boolean
)

class MentionSearchRepository(
    private val profileRepo: ProfileRepository,
    private val contactRepo: ContactRepository,
    private val relayPool: RelayPool,
    private val keyRepo: KeyRepository
) {
    private val _candidates = MutableStateFlow<List<MentionCandidate>>(emptyList())
    val candidates: StateFlow<List<MentionCandidate>> = _candidates

    private var remoteJob: Job? = null
    private val subId = "mention-search"

    companion object {
        private val DEFAULT_SEARCH_RELAYS = listOf(
            "wss://relay.nostr.band",
            "wss://search.nos.today"
        )
    }

    fun search(query: String, scope: CoroutineScope) {
        remoteJob?.cancel()
        closeSubscription()

        if (query.isBlank()) {
            val contacts = contactRepo.getFollowList().take(8).mapNotNull { entry ->
                profileRepo.get(entry.pubkey)?.let { MentionCandidate(it, isContact = true) }
            }
            _candidates.value = contacts
            return
        }

        // Local search — instant
        val localResults = profileRepo.search(query, limit = 20)
        val followSet = contactRepo.getFollowList().map { it.pubkey }.toSet()

        val (contacts, others) = localResults.partition { it.pubkey in followSet }
        val localCandidates = contacts.map { MentionCandidate(it, isContact = true) } +
            others.map { MentionCandidate(it, isContact = false) }

        _candidates.value = localCandidates.take(10)

        // Remote search — debounced 300ms
        remoteJob = scope.launch {
            delay(300)

            val filter = Filter(kinds = listOf(0), search = query, limit = 10)
            val req = ClientMessage.req(subId, filter)
            val searchRelays = keyRepo.getSearchRelays().ifEmpty { DEFAULT_SEARCH_RELAYS }

            for (url in searchRelays) {
                relayPool.sendToRelayOrEphemeral(url, req)
            }

            val seenPubkeys = _candidates.value.map { it.profile.pubkey }.toMutableSet()

            // Collect events with 3s timeout
            val collectJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.kind == 0 && event.pubkey !in seenPubkeys) {
                        seenPubkeys.add(event.pubkey)
                        val profile = ProfileData.fromEvent(event) ?: return@collect
                        profileRepo.updateFromEvent(event)
                        val candidate = MentionCandidate(profile, event.pubkey in followSet)
                        _candidates.value = _candidates.value + candidate
                    }
                }
            }

            // Also listen for EOSE to stop early
            val eoseJob = launch {
                relayPool.eoseSignals.collect { sid ->
                    if (sid == subId) {
                        collectJob.cancel()
                    }
                }
            }

            delay(3000)
            collectJob.cancel()
            eoseJob.cancel()
            closeSubscription()
        }
    }

    private fun closeSubscription() {
        try {
            relayPool.sendToAll(ClientMessage.close(subId))
        } catch (_: Exception) {}
    }

    fun clear() {
        remoteJob?.cancel()
        closeSubscription()
        _candidates.value = emptyList()
    }
}
