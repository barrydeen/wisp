package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.RecipeTag
import cooking.zap.app.nostr.RecipeTagCatalog
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.MuteRepository
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Recipes first — recipe-first, mirroring the web's submit priority.
enum class SearchFilter { RECIPES, TAGS, PEOPLE, NOTES }

enum class RelayOption {
    DEFAULT,
    ALL_RELAYS,
    INDIVIDUAL
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(SearchFilter.RECIPES)
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

    private val _recipeResults = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val recipeResults: StateFlow<List<RecipeParser.Recipe>> = _recipeResults

    private val _tagResults = MutableStateFlow<List<RecipeTag>>(emptyList())
    val tagResults: StateFlow<List<RecipeTag>> = _tagResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private var searchJob: Job? = null
    private var authorSearchJob: Job? = null
    private var relayPool: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var muteRepoRef: MuteRepository? = null
    private var recipeRepoRef: RecipeRepository? = null
    private var autoSearchJob: Job? = null
    private var searchCounter = 0

    private var userSubId = "search-users-0"
    private var noteSubId = "search-notes-0"
    private var recipeSubId = "search-recipes-0"
    private var authorSubId = "search-author-0"
    private var metricsSubId = "engage-search-0"

    fun selectFilter(filter: SearchFilter) {
        _filter.value = filter
        if (filter == SearchFilter.TAGS) {
            stopNetworkSearchForTags()
            _tagResults.value = RecipeTagCatalog.search(_query.value)
        }
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

    fun initSearchRefs(
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository?,
        recipeRepo: RecipeRepository? = null,
    ) {
        if (this.relayPool == null) this.relayPool = relayPool
        if (this.eventRepoRef == null) this.eventRepoRef = eventRepo
        if (this.muteRepoRef == null) this.muteRepoRef = muteRepo
        if (this.recipeRepoRef == null && recipeRepo != null) this.recipeRepoRef = recipeRepo
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        val normalized = newQuery.trim().removePrefix("nostr:")
        _tagResults.value = RecipeTagCatalog.search(normalized)
        if (_filter.value == SearchFilter.TAGS) {
            stopNetworkSearchForTags()
            return
        }
        // Debounced auto-search: trigger after 500ms of no typing
        autoSearchJob?.cancel()
        val pool = relayPool ?: return
        val repo = eventRepoRef ?: return
        if (normalized.length < 2) return
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
        recipeSubId = "search-recipes-$searchCounter"
        metricsSubId = "engage-search-$searchCounter"

        _query.value = trimmed
        _isSearching.value = true
        _users.value = emptyList()
        _notes.value = emptyList()
        _recipeResults.value = emptyList()
        _tagResults.value = emptyList()

        val activeFilter = _filter.value
        if (activeFilter == SearchFilter.TAGS) {
            _tagResults.value = RecipeTagCatalog.search(trimmed)
            _isSearching.value = false
            return
        }
        val relaysToQuery = when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> listOf(DEFAULT_SEARCH_RELAY)
            RelayOption.ALL_RELAYS -> _searchRelays.value
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }

        // Recipe search merges (1) an instant client-side baseline over loaded
        // recipes + (2) streaming NIP-50 network results, deduped by addressable
        // coordinate ("author:dTag"). Mutated only on the main dispatcher.
        val recipeByCoord = LinkedHashMap<String, RecipeParser.Recipe>()

        val activeSubId = when (activeFilter) {
            SearchFilter.RECIPES -> {
                // OPPORTUNISTIC NETWORK NIP-50 via the registry searchFilter (no
                // hardcoded 30023). NOT required for results — the guaranteed path
                // is the persisted-catalog baseline + deep fill seeded in the
                // coroutine below. Default relay path intentionally sends NO
                // recipe NIP-50 REQ (confirmed dead for kind-30023); keep
                // opportunistic NIP-50 only for user-selected relays.
                val recipeNetworkRelays = when (_selectedRelayOption.value) {
                    RelayOption.DEFAULT -> emptyList()
                    RelayOption.ALL_RELAYS, RelayOption.INDIVIDUAL -> relaysToQuery
                }
                if (recipeNetworkRelays.isNotEmpty()) {
                    val recipeFilters = RecipeFormats.active.map { it.searchFilter(trimmed, 50) }
                    val recipeReq = ClientMessage.req(recipeSubId, recipeFilters)
                    for (url in recipeNetworkRelays.distinct()) {
                        relayPool.sendToRelayOrEphemeral(url, recipeReq)
                    }
                }
                recipeSubId
            }
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
            SearchFilter.TAGS -> return
        }

        val seenUserPubkeys = mutableSetOf<String>()
        val seenNoteIds = mutableSetOf<String>()

        searchJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != activeSubId) return@collect
                    when (activeFilter) {
                        SearchFilter.RECIPES -> {
                            val event = relayEvent.event
                            // Parse via the registry (format-agnostic); skip non-recipes.
                            val recipe = RecipeFormats.forEvent(event)?.parse(event) ?: return@collect
                            // Same newest-wins coordinate merge as the baseline/feed
                            // paths (kind-30023 is replaceable). Only on a real change
                            // do we cache, fetch the author, and re-publish.
                            if (mergeRecipe(recipeByCoord, recipe)) {
                                eventRepo.cacheEvent(event)
                                eventRepo.requestProfileIfMissing(recipe.author)
                                _recipeResults.value = recipeByCoord.values.toList()
                            }
                        }
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
                        SearchFilter.TAGS -> return@collect
                    }
                }
            }

            // RECIPES: the GUARANTEED results path (the network NIP-50 fan-out
            // above is only opportunistic). Seed an instant baseline from the
            // FULL persisted catalog (ObjectBox), kick off a bounded background
            // deep fill so old recipes ("Mai Tai") land in the catalog, and
            // re-filter live as the feed / deep fill populate.
            if (activeFilter == SearchFilter.RECIPES) {
                val cached = recipeRepoRef?.searchCachedRecipes(trimmed).orEmpty()
                var changed = false
                cached.forEach { if (mergeRecipe(recipeByCoord, it)) changed = true }
                if (changed) _recipeResults.value = recipeByCoord.values.toList()
                recipeRepoRef?.preloadCatalog()
                // Deliberately NOT cancelled at the 5s network timeout below —
                // preloadCatalog() pages for longer than that, so this collector
                // must stay live to surface deep-fill results as they land. It is
                // a child of searchJob, so the next search()/clear() (which
                // cancels searchJob) tears it down when the query changes.
                launch {
                    recipeRepoRef?.recipes?.collect { feed ->
                        var merged = false
                        feed.asSequence()
                            .filter { recipeMatches(it, trimmed) }
                            .forEach { if (mergeRecipe(recipeByCoord, it)) merged = true }
                        if (merged) _recipeResults.value = recipeByCoord.values.toList()
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

            // Opportunistic network search times out after 5s: stop the spinner,
            // close the NIP-50 subs, and cancel the network collectors. The
            // recipe live-refilter collector is intentionally left running (see
            // above) so deep-fill results keep landing until the query changes.
            delay(5000)
            _isSearching.value = false
            closeSubscriptions(relayPool)
            eventJob.cancel()
            eoseJob.cancel()
        }
    }

    /**
     * Replaceable-event newest-wins (mirrors RecipeRepository.preferNewer): a
     * higher effective timestamp wins; on a tie the lexicographically lower id
     * is kept. [RecipeParser.Recipe.publishedAt] is `published_at` if present,
     * else the event `created_at` (absent on live zapcooking recipes, so it ==
     * created_at there).
     */
    private fun isNewerRecipe(incoming: RecipeParser.Recipe, existing: RecipeParser.Recipe): Boolean = when {
        incoming.publishedAt != existing.publishedAt -> incoming.publishedAt > existing.publishedAt
        else -> incoming.id < existing.id
    }

    /**
     * Merge [recipe] into [into] keyed by addressable coordinate ("author:dTag"),
     * keeping the newest per coordinate ([isNewerRecipe]). Returns true iff the
     * map changed — so callers only re-publish [_recipeResults] on a real update.
     * Used by all three recipe inputs (persisted baseline, live feed, network).
     */
    private fun mergeRecipe(
        into: LinkedHashMap<String, RecipeParser.Recipe>,
        recipe: RecipeParser.Recipe,
    ): Boolean {
        val key = "${recipe.author}:${recipe.dTag}"
        val existing = into[key]
        return if (existing == null || isNewerRecipe(recipe, existing)) {
            into[key] = recipe
            true
        } else {
            false
        }
    }

    /** True iff the recipe's title or summary contains [query] (case-insensitive). */
    private fun recipeMatches(recipe: RecipeParser.Recipe, query: String): Boolean {
        val q = query.lowercase()
        return recipe.title?.lowercase()?.contains(q) == true ||
            recipe.summary?.lowercase()?.contains(q) == true
    }

    fun clear() {
        searchJob?.cancel()
        authorSearchJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _query.value = ""
        _users.value = emptyList()
        _notes.value = emptyList()
        _recipeResults.value = emptyList()
        _tagResults.value = emptyList()
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
        relayPool.closeOnAllRelays(recipeSubId)
        relayPool.closeOnAllRelays(authorSubId)
        relayPool.closeOnAllRelays(metricsSubId)
    }

    private fun stopNetworkSearchForTags() {
        autoSearchJob?.cancel()
        searchJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _isSearching.value = false
    }
}
