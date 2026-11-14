package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip05
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.toRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchFilter { PEOPLE, NOTES }

/**
 * Result of looking up a [DirectTarget] (pasted npub/nevent/naddr/hex/NIP-05).
 * [route] is where opening the match navigates; it can be known before the
 * event itself arrives (e.g. a nevent's id), or stay null if unresolved.
 */
data class DirectMatch(
    val target: DirectTarget,
    val isLoading: Boolean = true,
    val pubkey: String? = null,
    val profile: ProfileData? = null,
    val event: NostrEvent? = null,
    val route: String? = null
)

enum class RelayOption {
    DEFAULT,
    ALL_RELAYS,
    INDIVIDUAL
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(SearchFilter.PEOPLE)
    val filter: StateFlow<SearchFilter> = _filter

    // Relay selection
    private val _selectedRelayOption = MutableStateFlow(RelayOption.DEFAULT)
    val selectedRelayOption: StateFlow<RelayOption> = _selectedRelayOption

    private val _selectedRelayUrl = MutableStateFlow<String?>(null)
    val selectedRelayUrl: StateFlow<String?> = _selectedRelayUrl

    // User's search relays
    private val _searchRelays = MutableStateFlow(keyRepo.getSearchRelays())
    val searchRelays: StateFlow<List<String>> = _searchRelays

    // Author filter (for note search)
    private val _authorFilter = MutableStateFlow<ProfileData?>(null)
    val authorFilter: StateFlow<ProfileData?> = _authorFilter

    private val _authorSearchResults = MutableStateFlow<List<ProfileData>>(emptyList())
    val authorSearchResults: StateFlow<List<ProfileData>> = _authorSearchResults

    private val _isAuthorSearching = MutableStateFlow(false)
    val isAuthorSearching: StateFlow<Boolean> = _isAuthorSearching

    // Results
    private val _users = MutableStateFlow<List<ProfileData>>(emptyList())
    val users: StateFlow<List<ProfileData>> = _users

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    /** The query the current results belong to; lets the UI tell "no results" from "not searched yet". */
    private val _searchedQuery = MutableStateFlow("")
    val searchedQuery: StateFlow<String> = _searchedQuery

    private val _directMatch = MutableStateFlow<DirectMatch?>(null)
    val directMatch: StateFlow<DirectMatch?> = _directMatch

    private var searchJob: Job? = null
    private var directJob: Job? = null
    private var authorSearchJob: Job? = null
    private var relayPool: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var muteRepoRef: MuteRepository? = null
    private var autoSearchJob: Job? = null
    private var searchCounter = 0

    private var userSubId = "search-users-0"
    private var noteSubId = "search-notes-0"
    private var authorSubId = "search-author-0"
    private var metricsSubId = "engage-search-0"
    private var directSubId = "search-direct-0"

    fun selectFilter(filter: SearchFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        // A direct match shows on both tabs, so only text searches need re-running.
        if (SearchQueryParser.parse(_query.value) == null) rerunIfActive()
    }

    /** Re-run the current query after a filter/relay change so results match what's shown. */
    private fun rerunIfActive() {
        val pool = relayPool ?: return
        val repo = eventRepoRef ?: return
        if (_query.value.isBlank()) return
        search(_query.value, pool, repo, muteRepoRef)
    }

    fun selectDefaultRelay() {
        _selectedRelayOption.value = RelayOption.DEFAULT
        _selectedRelayUrl.value = null
        rerunIfActive()
    }

    fun selectAllRelays() {
        _selectedRelayOption.value = RelayOption.ALL_RELAYS
        _selectedRelayUrl.value = null
        rerunIfActive()
    }

    fun selectRelay(url: String) {
        _selectedRelayOption.value = RelayOption.INDIVIDUAL
        _selectedRelayUrl.value = url
        rerunIfActive()
    }

    fun addSearchRelay(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (!RelayConfig.isValidUrl(trimmed)) return false
        if (trimmed in _searchRelays.value) return false
        val updated = _searchRelays.value + trimmed
        keyRepo.saveSearchRelays(updated)
        _searchRelays.value = updated
        return true
    }

    fun removeSearchRelay(url: String) {
        val updated = _searchRelays.value - url
        keyRepo.saveSearchRelays(updated)
        _searchRelays.value = updated
        if (_selectedRelayOption.value == RelayOption.INDIVIDUAL && _selectedRelayUrl.value == url) {
            selectDefaultRelay()
        }
    }

    fun initSearchRefs(relayPool: RelayPool, eventRepo: EventRepository, muteRepo: MuteRepository?) {
        if (this.relayPool == null) this.relayPool = relayPool
        if (this.eventRepoRef == null) this.eventRepoRef = eventRepo
        if (this.muteRepoRef == null) this.muteRepoRef = muteRepo
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        // Debounced auto-search: trigger after 500ms of no typing
        autoSearchJob?.cancel()
        val pool = relayPool ?: return
        val repo = eventRepoRef ?: return
        val trimmed = newQuery.trim().removePrefix("nostr:")
        if (trimmed.isEmpty()) {
            resetResults()
            return
        }
        val direct = SearchQueryParser.parse(newQuery)
        // Drop a match that no longer belongs to the query so Enter can't open it.
        if (direct != _directMatch.value?.target) _directMatch.value = null
        if (direct == null && trimmed.length < 2) return
        // Pasted keys and ids resolve immediately; text and NIP-05 wait for typing to settle.
        val immediate = direct is DirectTarget.Entity || direct is DirectTarget.Hex
        autoSearchJob = viewModelScope.launch {
            if (!immediate) delay(500)
            search(newQuery, pool, repo, muteRepoRef)
        }
    }

    fun setAuthorFilter(profile: ProfileData) {
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
        rerunIfActive()
    }

    fun clearAuthorFilter() {
        _authorFilter.value = null
        rerunIfActive()
    }

    fun prepareAuthorSearch(profile: ProfileData) {
        _filter.value = SearchFilter.NOTES
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
    }

    fun searchAuthors(query: String, relayPool: RelayPool, eventRepo: EventRepository) {
        val trimmed = query.trim().removePrefix("nostr:")
        if (trimmed.isEmpty()) {
            _authorSearchResults.value = emptyList()
            return
        }

        authorSearchJob?.cancel()
        searchCounter++
        authorSubId = "search-author-$searchCounter"
        _isAuthorSearching.value = true
        _authorSearchResults.value = emptyList()

        val relaysToQuery = when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> listOf(DEFAULT_SEARCH_RELAY)
            RelayOption.ALL_RELAYS -> _searchRelays.value
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }

        val authorFilter = Filter(kinds = listOf(0), search = trimmed, limit = 10)
        val req = ClientMessage.req(authorSubId, authorFilter)
        for (url in relaysToQuery) {
            relayPool.sendToRelayOrEphemeral(url, req)
        }

        val activeSubId = authorSubId
        val seenPubkeys = mutableSetOf<String>()

        authorSearchJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != activeSubId) return@collect
                    val event = relayEvent.event
                    if (event.kind == 0 && event.pubkey !in seenPubkeys) {
                        seenPubkeys.add(event.pubkey)
                        eventRepo.cacheEvent(event)
                        val profile = ProfileData.fromEvent(event)
                        if (profile != null) {
                            _authorSearchResults.value = _authorSearchResults.value + profile
                        }
                    }
                }
            }

            val eoseJob = launch {
                relayPool.eoseSignals.collect { subId ->
                    if (subId == activeSubId) {
                        _isAuthorSearching.value = false
                    }
                }
            }

            delay(3000)
            _isAuthorSearching.value = false
            relayPool.closeOnAllRelays(activeSubId)
            eventJob.cancel()
            eoseJob.cancel()
        }
    }

    fun search(query: String, relayPool: RelayPool, eventRepo: EventRepository, muteRepo: MuteRepository? = null) {
        val trimmed = query.trim().removePrefix("nostr:")
        if (trimmed.isEmpty()) {
            resetResults()
            return
        }

        searchJob?.cancel()
        autoSearchJob?.cancel()
        directJob?.cancel()
        this.relayPool = relayPool
        this.eventRepoRef = eventRepo
        this.muteRepoRef = muteRepo

        closeSubscriptions(relayPool)

        searchCounter++
        userSubId = "search-users-$searchCounter"
        noteSubId = "search-notes-$searchCounter"
        metricsSubId = "engage-search-$searchCounter"

        _isSearching.value = true
        _users.value = emptyList()
        _notes.value = emptyList()

        _searchedQuery.value = query.trim()
        val direct = SearchQueryParser.parse(query)
        if (direct != null) {
            _isSearching.value = false
            lookupDirect(direct, relayPool, eventRepo)
            return
        }
        _directMatch.value = null

        val activeFilter = _filter.value
        val relaysToQuery = when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> listOf(DEFAULT_SEARCH_RELAY)
            RelayOption.ALL_RELAYS -> _searchRelays.value
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }

        var sentCount = 0
        val activeSubId = when (activeFilter) {
            SearchFilter.PEOPLE -> {
                val userFilter = Filter(kinds = listOf(0), search = trimmed, limit = 20)
                val userReq = ClientMessage.req(userSubId, userFilter)
                for (url in relaysToQuery) {
                    if (relayPool.sendToRelayOrEphemeral(url, userReq)) sentCount++
                }
                userSubId
            }
            SearchFilter.NOTES -> {
                val authorPubkey = _authorFilter.value?.pubkey
                val noteFilter = Filter(
                    kinds = listOf(1),
                    authors = authorPubkey?.let { listOf(it) },
                    search = trimmed,
                    limit = 50
                )
                val noteReq = ClientMessage.req(noteSubId, noteFilter)
                for (url in relaysToQuery) {
                    if (relayPool.sendToRelayOrEphemeral(url, noteReq)) sentCount++
                }
                noteSubId
            }
        }
        if (sentCount == 0) {
            // Every relay was blocked, cooling down or invalid: nothing will answer.
            _isSearching.value = false
            return
        }

        val seenUserPubkeys = mutableSetOf<String>()
        val seenNoteIds = mutableSetOf<String>()
        var eoseCount = 0

        searchJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != activeSubId) return@collect
                    when (activeFilter) {
                        SearchFilter.PEOPLE -> {
                            val event = relayEvent.event
                            if (event.kind == 0 && event.pubkey !in seenUserPubkeys) {
                                if (muteRepo?.isBlocked(event.pubkey) == true) return@collect
                                seenUserPubkeys.add(event.pubkey)
                                eventRepo.cacheEvent(event)
                                val profile = ProfileData.fromEvent(event)
                                if (profile != null) {
                                    _users.value = _users.value + profile
                                }
                            }
                        }
                        SearchFilter.NOTES -> {
                            val event = relayEvent.event
                            if (event.kind == 1 && event.id !in seenNoteIds) {
                                if (muteRepo?.isBlocked(event.pubkey) == true) return@collect
                                if (muteRepo?.containsMutedWord(event.content) == true) return@collect
                                seenNoteIds.add(event.id)
                                _notes.value = _notes.value + event
                                eventRepo.cacheEvent(event)
                            }
                        }
                    }
                }
            }

            val allEose = CompletableDeferred<Unit>()
            val eoseJob = launch {
                relayPool.eoseSignals.collect { subId ->
                    if (subId == activeSubId && ++eoseCount >= sentCount) allEose.complete(Unit)
                }
            }

            // Wait for every queried relay (or the timeout), so one fast relay
            // doesn't end the search while slower ones are still answering.
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { allEose.await() }
            _isSearching.value = false
            eventJob.cancel()
            eoseJob.cancel()
            relayPool.closeOnAllRelays(activeSubId)

            // After notes arrive, fetch reactions + reposts from read relays.
            // Search relays (NIP-50) only store notes, not engagement events,
            // so we must query the user's regular read relay pool.
            val noteIds = seenNoteIds.toList()
            if (activeFilter == SearchFilter.NOTES && noteIds.isNotEmpty()) {
                val chunkSubIds = noteIds.chunked(100).mapIndexed { i, chunk ->
                    val chunkSubId = if (i == 0) metricsSubId else "$metricsSubId-$i"
                    val engageFilter = Filter(
                        kinds = listOf(7, 6, 9735),
                        eTags = chunk,
                        limit = 500
                    )
                    relayPool.sendToReadRelays(ClientMessage.req(chunkSubId, engageFilter))
                    chunkSubId
                }
                delay(METRICS_TIMEOUT_MS)
                chunkSubIds.forEach { relayPool.closeOnAllRelays(it) }
            }
        }
    }

    /** Clear the query and its results, keeping the relay and author filters. */
    fun clearQuery() {
        _query.value = ""
        resetResults()
    }

    private fun resetResults() {
        searchJob?.cancel()
        autoSearchJob?.cancel()
        directJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _searchedQuery.value = ""
        _users.value = emptyList()
        _notes.value = emptyList()
        _directMatch.value = null
        _isSearching.value = false
    }

    fun clear() {
        clearQuery()
        authorSearchJob?.cancel()
        _authorFilter.value = null
        _authorSearchResults.value = emptyList()
        _isAuthorSearching.value = false
    }

    private fun lookupDirect(target: DirectTarget, relayPool: RelayPool, eventRepo: EventRepository) {
        directJob?.cancel()
        _users.value = emptyList()
        _notes.value = emptyList()
        searchCounter++
        directSubId = "search-direct-$searchCounter"
        val subId = directSubId

        directJob = viewModelScope.launch {
            when (target) {
                is DirectTarget.Entity -> when (val data = target.data) {
                    is NostrUriData.ProfileRef ->
                        lookupProfile(target, data.pubkey, data.relays, subId, relayPool, eventRepo)
                    is NostrUriData.NoteRef ->
                        lookupNote(target, data, subId, relayPool, eventRepo)
                    is NostrUriData.AddressRef ->
                        lookupAddress(target, data, subId, relayPool, eventRepo)
                }
                is DirectTarget.Hex -> lookupHex(target, subId, relayPool, eventRepo)
                is DirectTarget.Nip05Address -> {
                    _directMatch.value = DirectMatch(target)
                    val resolved = Nip05.resolve(target.identifier, HttpClientFactory.getNip05Client())
                    if (resolved == null) {
                        _directMatch.value = DirectMatch(target, isLoading = false)
                    } else {
                        lookupProfile(target, resolved.pubkey, resolved.relays, subId, relayPool, eventRepo)
                    }
                }
            }
        }
    }

    private suspend fun lookupProfile(
        target: DirectTarget,
        pubkey: String,
        hints: List<String>,
        subId: String,
        relayPool: RelayPool,
        eventRepo: EventRepository
    ) {
        _directMatch.value = DirectMatch(
            target,
            isLoading = true,
            pubkey = pubkey,
            profile = eventRepo.getProfileData(pubkey),
            route = "profile/$pubkey"
        )
        var newest = 0L
        fetchDirect(subId, listOf(Filter(kinds = listOf(0), authors = listOf(pubkey), limit = 1)), hints, relayPool) { event ->
            if (event.kind == 0 && event.pubkey == pubkey && event.created_at > newest) {
                newest = event.created_at
                eventRepo.cacheEvent(event)
                ProfileData.fromEvent(event)?.let { profile ->
                    _directMatch.value = _directMatch.value?.copy(profile = profile)
                }
            }
            false // keep listening: other relays may hold a newer kind 0
        }
        _directMatch.value = _directMatch.value?.copy(isLoading = false)
    }

    private suspend fun lookupNote(
        target: DirectTarget,
        ref: NostrUriData.NoteRef,
        subId: String,
        relayPool: RelayPool,
        eventRepo: EventRepository
    ) {
        val cached = eventRepo.getEvent(ref.eventId)
        _directMatch.value = DirectMatch(
            target,
            isLoading = cached == null,
            event = cached,
            pubkey = cached?.pubkey ?: ref.author,
            route = "thread/${ref.eventId}"
        )
        val event = cached ?: run {
            var found: NostrEvent? = null
            fetchDirect(subId, listOf(Filter(ids = listOf(ref.eventId))), ref.relays, relayPool) { event ->
                if (event.id != ref.eventId) return@fetchDirect false
                found = event
                true
            }
            found
        }
        if (event != null) {
            eventRepo.cacheEvent(event)
            _directMatch.value = _directMatch.value?.copy(event = event, pubkey = event.pubkey, isLoading = false)
            fetchAuthorProfile(event.pubkey, ref.relays, "$subId-p", relayPool, eventRepo)
        } else {
            _directMatch.value = _directMatch.value?.copy(isLoading = false)
        }
    }

    private suspend fun lookupAddress(
        target: DirectTarget,
        ref: NostrUriData.AddressRef,
        subId: String,
        relayPool: RelayPool,
        eventRepo: EventRepository
    ) {
        val kind = ref.kind
        val author = ref.author
        val cached = if (kind != null && author != null) eventRepo.findAddressableEvent(kind, author, ref.dTag) else null
        _directMatch.value = DirectMatch(
            target,
            isLoading = cached == null && kind != null,
            event = cached,
            pubkey = author,
            route = ref.toRoute()
        )
        if (cached != null || kind == null) return
        var found: NostrEvent? = null
        val filter = Filter(kinds = listOf(kind), authors = author?.let { listOf(it) }, dTags = listOf(ref.dTag), limit = 1)
        fetchDirect(subId, listOf(filter), ref.relays, relayPool) { event ->
            if (event.kind != kind) return@fetchDirect false
            // A relay may answer with a different addressable event of the same kind.
            if (author != null && event.pubkey != author) return@fetchDirect false
            if (event.tags.none { it.size >= 2 && it[0] == "d" && it[1] == ref.dTag }) return@fetchDirect false
            if (found == null || event.created_at > found!!.created_at) found = event
            false
        }
        found?.let { eventRepo.cacheEvent(it) }
        _directMatch.value = _directMatch.value?.copy(event = found, isLoading = false)
        found?.let { fetchAuthorProfile(it.pubkey, ref.relays, "$subId-p", relayPool, eventRepo) }
    }

    private suspend fun lookupHex(
        target: DirectTarget.Hex,
        subId: String,
        relayPool: RelayPool,
        eventRepo: EventRepository
    ) {
        eventRepo.getEvent(target.hex)?.let { cached ->
            _directMatch.value = DirectMatch(target, isLoading = false, event = cached,
                pubkey = cached.pubkey, route = "thread/${cached.id}")
            return
        }
        _directMatch.value = DirectMatch(target)
        val filters = listOf(
            Filter(ids = listOf(target.hex)),
            Filter(kinds = listOf(0), authors = listOf(target.hex), limit = 1)
        )
        fetchDirect(subId, filters, emptyList(), relayPool) { event ->
            when {
                event.id == target.hex -> {
                    eventRepo.cacheEvent(event)
                    _directMatch.value = DirectMatch(target, isLoading = false, event = event,
                        pubkey = event.pubkey, route = "thread/${event.id}")
                    true
                }
                event.kind == 0 && event.pubkey == target.hex -> {
                    eventRepo.cacheEvent(event)
                    _directMatch.value = DirectMatch(target, isLoading = false, pubkey = event.pubkey,
                        profile = ProfileData.fromEvent(event), route = "profile/${event.pubkey}")
                    true
                }
                else -> false
            }
        }
        val match = _directMatch.value
        if (match?.event != null) {
            fetchAuthorProfile(match.event.pubkey, emptyList(), "$subId-p", relayPool, eventRepo)
        } else if (match?.isLoading == true) {
            _directMatch.value = match.copy(isLoading = false)
        }
    }

    private suspend fun fetchAuthorProfile(
        pubkey: String,
        hints: List<String>,
        subId: String,
        relayPool: RelayPool,
        eventRepo: EventRepository
    ) {
        eventRepo.getProfileData(pubkey)?.let { cached ->
            _directMatch.value = _directMatch.value?.copy(profile = cached)
            return
        }
        fetchDirect(subId, listOf(Filter(kinds = listOf(0), authors = listOf(pubkey), limit = 1)), hints, relayPool) { event ->
            if (event.kind != 0 || event.pubkey != pubkey) return@fetchDirect false
            eventRepo.cacheEvent(event)
            _directMatch.value = _directMatch.value?.copy(profile = ProfileData.fromEvent(event))
            true
        }
    }

    /**
     * Send [filters] to relay hints, the user's read relays and the search relays,
     * passing each event to [onEvent] until it returns true, every relay has sent
     * EOSE, or [DIRECT_TIMEOUT_MS] passes.
     */
    private suspend fun fetchDirect(
        subId: String,
        filters: List<Filter>,
        hints: List<String>,
        relayPool: RelayPool,
        onEvent: (NostrEvent) -> Boolean
    ) {
        val searchRelays = when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> listOf(DEFAULT_SEARCH_RELAY)
            RelayOption.ALL_RELAYS -> _searchRelays.value
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }
        val targets = (hints.take(MAX_HINT_RELAYS) + relayPool.getReadRelayUrls() + searchRelays)
            .map { it.trim().trimEnd('/') }
            .distinct()
        val req = ClientMessage.req(subId, filters)
        val done = CompletableDeferred<Unit>()
        coroutineScope {
            // Subscribe before sending so a fast relay can't answer ahead of the collector.
            var sentCount = Int.MAX_VALUE
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == subId && onEvent(relayEvent.event)) done.complete(Unit)
                }
            }
            val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                var eoseCount = 0
                relayPool.eoseSignals.collect { id ->
                    if (id == subId && ++eoseCount >= sentCount) done.complete(Unit)
                }
            }
            sentCount = targets.count { relayPool.sendToRelayOrEphemeral(it, req) }
            if (sentCount > 0) withTimeoutOrNull(DIRECT_TIMEOUT_MS) { done.await() }
            eventJob.cancel()
            eoseJob.cancel()
        }
        relayPool.closeOnAllRelays(subId)
    }

    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.nostrarchives.com"
        // NIP-50 relays often take several seconds; results stream in meanwhile.
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val METRICS_TIMEOUT_MS = 5000L
        private const val DIRECT_TIMEOUT_MS = 6000L
        private const val MAX_HINT_RELAYS = 4
    }

    private fun closeSubscriptions(relayPool: RelayPool) {
        relayPool.closeOnAllRelays(userSubId)
        relayPool.closeOnAllRelays(noteSubId)
        relayPool.closeOnAllRelays(authorSubId)
        relayPool.closeOnAllRelays(metricsSubId)
        relayPool.closeOnAllRelays(directSubId)
    }
}
