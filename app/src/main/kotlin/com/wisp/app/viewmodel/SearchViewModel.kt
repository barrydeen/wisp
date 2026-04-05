package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.MuteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SearchFilter { PEOPLE, NOTES }

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

    private var searchJob: Job? = null
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

    fun selectFilter(filter: SearchFilter) {
        _filter.value = filter
    }

    fun selectDefaultRelay() {
        _selectedRelayOption.value = RelayOption.DEFAULT
        _selectedRelayUrl.value = null
    }

    fun selectAllRelays() {
        _selectedRelayOption.value = RelayOption.ALL_RELAYS
        _selectedRelayUrl.value = null
    }

    fun selectRelay(url: String) {
        _selectedRelayOption.value = RelayOption.INDIVIDUAL
        _selectedRelayUrl.value = url
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
        val trimmed = newQuery.trim()
        if (trimmed.length < 2) return
        autoSearchJob = viewModelScope.launch {
            delay(500)
            search(newQuery, pool, repo, muteRepoRef)
        }
    }

    fun setAuthorFilter(profile: ProfileData) {
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
    }

    fun clearAuthorFilter() {
        _authorFilter.value = null
    }

    fun prepareAuthorSearch(profile: ProfileData) {
        _filter.value = SearchFilter.NOTES
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
    }

    fun searchAuthors(query: String, relayPool: RelayPool, eventRepo: EventRepository) {
        val trimmed = query.trim()
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
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }

        searchJob?.cancel()
        autoSearchJob?.cancel()
        this.relayPool = relayPool
        this.eventRepoRef = eventRepo
        this.muteRepoRef = muteRepo

        closeSubscriptions(relayPool)

        searchCounter++
        userSubId = "search-users-$searchCounter"
        noteSubId = "search-notes-$searchCounter"
        metricsSubId = "engage-search-$searchCounter"

        _query.value = trimmed
        _isSearching.value = true
        _users.value = emptyList()
        _notes.value = emptyList()

        val activeFilter = _filter.value
        val relaysToQuery = when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> listOf(DEFAULT_SEARCH_RELAY)
            RelayOption.ALL_RELAYS -> _searchRelays.value
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }

        val activeSubId = when (activeFilter) {
            SearchFilter.PEOPLE -> {
                val userFilter = Filter(kinds = listOf(0), search = trimmed, limit = 20)
                val userReq = ClientMessage.req(userSubId, userFilter)
                for (url in relaysToQuery) {
                    relayPool.sendToRelayOrEphemeral(url, userReq)
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
                    relayPool.sendToRelayOrEphemeral(url, noteReq)
                }
                noteSubId
            }
        }

        val seenUserPubkeys = mutableSetOf<String>()
        val seenNoteIds = mutableSetOf<String>()

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

            val eoseJob = launch {
                relayPool.eoseSignals.collect { subId ->
                    if (subId == activeSubId) {
                        _isSearching.value = false
                        // After notes arrive, fetch reactions + reposts from read relays.
                        // Search relays (NIP-50) only store notes, not engagement events,
                        // so we must query the user's regular read relay pool.
                        if (activeFilter == SearchFilter.NOTES) {
                            val noteIds = seenNoteIds.toList()
                            if (noteIds.isNotEmpty()) {
                                noteIds.chunked(100).forEachIndexed { i, chunk ->
                                    val chunkSubId = if (i == 0) metricsSubId else "$metricsSubId-$i"
                                    val engageFilter = Filter(
                                        kinds = listOf(7, 6, 9735),
                                        eTags = chunk,
                                        limit = 500
                                    )
                                    relayPool.sendToReadRelays(ClientMessage.req(chunkSubId, engageFilter))
                                }
                            }
                        }
                    }
                }
            }

            delay(5000)
            _isSearching.value = false
            closeSubscriptions(relayPool)
            eventJob.cancel()
            eoseJob.cancel()
        }
    }

    fun clear() {
        searchJob?.cancel()
        authorSearchJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _query.value = ""
        _users.value = emptyList()
        _notes.value = emptyList()
        _isSearching.value = false
        _authorFilter.value = null
        _authorSearchResults.value = emptyList()
        _isAuthorSearching.value = false
    }

    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.nostrarchives.com"
    }

    private fun closeSubscriptions(relayPool: RelayPool) {
        relayPool.closeOnAllRelays(userSubId)
        relayPool.closeOnAllRelays(noteSubId)
        relayPool.closeOnAllRelays(authorSubId)
        relayPool.closeOnAllRelays(metricsSubId)
    }
}
