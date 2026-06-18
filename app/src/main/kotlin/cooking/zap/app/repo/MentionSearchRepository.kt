package cooking.zap.app.repo

import cooking.zap.app.db.EventPersistence
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    var eventPersistence: EventPersistence? = null

    private var relaySearchJob: Job? = null

    fun search(query: String, scope: CoroutineScope) {
        relaySearchJob?.cancel()
        relaySearchJob = null

        if (query.isBlank()) {
            val contacts = contactRepo.getFollowList().take(5).mapNotNull { entry ->
                profileRepo.get(entry.pubkey)?.let { MentionCandidate(it, isContact = true) }
            }
            _candidates.value = contacts
            return
        }

        val lowerQuery = query.lowercase()
        val followPubkeys = contactRepo.getFollowList().map { it.pubkey }.toSet()

        val contactResults = followPubkeys.mapNotNull { pubkey ->
            val profile = profileRepo.get(pubkey) ?: return@mapNotNull null
            val nameMatch = profile.name?.lowercase()?.contains(lowerQuery) == true
            val displayMatch = profile.displayName?.lowercase()?.contains(lowerQuery) == true
            if (!nameMatch && !displayMatch) return@mapNotNull null
            MentionCandidate(profile, isContact = true)
        }.take(5)

        if (contactResults.size >= 5) {
            _candidates.value = contactResults
            return
        }

        val seenPubkeys = contactResults.map { it.profile.pubkey }.toMutableSet()
        var localResults = contactResults

        val persistence = eventPersistence
        if (persistence != null) {
            val remaining = 5 - contactResults.size
            val events = persistence.searchProfiles(query, limit = 500)
            val dbResults = events.mapNotNull { event ->
                if (!seenPubkeys.add(event.pubkey)) return@mapNotNull null
                val profile = ProfileData.fromEvent(event) ?: return@mapNotNull null
                val nameMatch = profile.name?.lowercase()?.contains(lowerQuery) == true
                val displayMatch = profile.displayName?.lowercase()?.contains(lowerQuery) == true
                if (!nameMatch && !displayMatch) return@mapNotNull null
                MentionCandidate(profile, isContact = false)
            }.take(remaining)
            localResults = contactResults + dbResults
            seenPubkeys.addAll(dbResults.map { it.profile.pubkey })
        }

        _candidates.value = localResults
        if (localResults.size >= 5) return

        val capturedLocal = localResults
        relaySearchJob = scope.launch(Dispatchers.IO) {
            val subId = "mention-${System.nanoTime()}"
            val filter = Filter(kinds = listOf(0), search = query, limit = 10)
            relayPool.sendToRelayOrEphemeral(SEARCH_RELAY, ClientMessage.req(subId, filter))

            val relayResults = mutableListOf<MentionCandidate>()
            val eventJob = launch {
                relayPool.relayEvents.collect { ev ->
                    if (ev.subscriptionId != subId) return@collect
                    val profile = ProfileData.fromEvent(ev.event) ?: return@collect
                    if (seenPubkeys.add(profile.pubkey)) {
                        relayResults += MentionCandidate(profile, isContact = false)
                        _candidates.value = capturedLocal + relayResults
                    }
                }
            }
            try {
                withTimeoutOrNull(3_000L) {
                    relayPool.eoseSignals.first { it == subId }
                }
            } finally {
                eventJob.cancel()
                relayPool.closeOnAllRelays(subId)
            }
        }
    }

    fun clear() {
        relaySearchJob?.cancel()
        relaySearchJob = null
        _candidates.value = emptyList()
    }

    private companion object {
        const val SEARCH_RELAY = "wss://search.nostrarchives.com"
    }
}
