package com.wisp.app.repo

import com.wisp.app.db.EventPersistence
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    fun search(query: String, scope: CoroutineScope) {
        if (query.isBlank()) {
            val contacts = contactRepo.getFollowList().take(5).mapNotNull { entry ->
                profileRepo.get(entry.pubkey)?.let { MentionCandidate(it, isContact = true) }
            }
            _candidates.value = contacts
            return
        }

        val lowerQuery = query.lowercase()
        val followPubkeys = contactRepo.getFollowList().map { it.pubkey }.toSet()

        // First: search contacts only via ProfileRepository
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

        // Fill remaining slots from ObjectBox
        val persistence = eventPersistence
        if (persistence == null) {
            _candidates.value = contactResults
            return
        }

        val seenPubkeys = contactResults.map { it.profile.pubkey }.toMutableSet()
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

        _candidates.value = contactResults + dbResults
    }

    fun clear() {
        _candidates.value = emptyList()
    }
}
