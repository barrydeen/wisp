package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.FoodHashtags
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.MuteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OnlyFood 🍳 — a kind-1 social food feed over the expanded [FoodHashtags] set
 * (concern 1.6). Modes (v1): [Mode.GLOBAL] and [Mode.FOLLOWING] (server-side
 * `authors` filter from the kind-3 contacts). Members + replies deferred.
 * Filtering is mute-only (matches the web + `HashtagFeedViewModel`).
 *
 * **Per-mode cache — DON'T re-query on toggle.** `search.nostrarchives.com`
 * rate-limits repeated queries per connection: the first identical query
 * returns ~99 events, a repeat ~12s later returns 0. So each mode is queried
 * **once** and its results cached in a [ModeState]; toggling [setMode] swaps
 * the visible list to the target mode's cache **instantly, with no relay
 * query**. The only path that re-queries a loaded mode is explicit
 * [refresh] (pull-to-refresh). A mode that legitimately returns 0 still gets
 * `loaded = true`, so it isn't re-queried (and re-throttled) on every toggle.
 *
 * Churn hygiene (less throttle pressure): all loads serialize through one
 * [submit] that `cancelAndJoin`s the previous job first, and teardown CLOSEs
 * only the subIds actually opened.
 */
class OnlyFoodFeedViewModel : ViewModel() {

    enum class Mode { GLOBAL, FOLLOWING }

    private class ModeState {
        val seen = LinkedHashMap<String, NostrEvent>()
        var loaded = false
        var endReached = false
        var emptyFollows = false
        fun snapshot(): List<NostrEvent> = seen.values.sortedByDescending { it.created_at }
    }

    private enum class Load { INITIAL, PAGE, REFRESH }

    private val states = mapOf(Mode.GLOBAL to ModeState(), Mode.FOLLOWING to ModeState())
    private fun stateOf(mode: Mode) = states.getValue(mode)

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _mode = MutableStateFlow(Mode.GLOBAL)
    val mode: StateFlow<Mode> = _mode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _emptyFollows = MutableStateFlow(false)
    val emptyFollows: StateFlow<Boolean> = _emptyFollows

    private var deps: Deps? = null
    private var activeJob: Job? = null

    private class Deps(
        val relayPool: RelayPool,
        val eventRepo: EventRepository,
        val muteRepo: MuteRepository,
        val contactRepo: ContactRepository,
    )

    fun init(
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository,
        contactRepo: ContactRepository,
    ) {
        if (deps != null) return
        deps = Deps(relayPool, eventRepo, muteRepo, contactRepo)
        val st = stateOf(_mode.value)
        if (!st.loaded) submit(_mode.value, st, Load.INITIAL, since = null, until = null)
    }

    /** Instant cache swap. Queries the target mode only if it's never loaded. */
    fun setMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        val st = stateOf(mode)
        _notes.value = st.snapshot()
        _emptyFollows.value = st.emptyFollows
        _isPaging.value = false
        _isRefreshing.value = false
        if (st.loaded) {
            _isLoading.value = false
        } else {
            submit(mode, st, Load.INITIAL, since = null, until = null)
        }
    }

    /** The ONLY path that re-queries a loaded mode. Merges newest into cache. */
    fun refresh() {
        val mode = _mode.value
        submit(mode, stateOf(mode), Load.REFRESH, since = null, until = null)
    }

    /** Infinite-scroll: page one window further back, appending to the cache. */
    fun loadMore() {
        val mode = _mode.value
        val st = stateOf(mode)
        if (_isLoading.value || _isPaging.value || _isRefreshing.value || st.endReached) return
        val oldest = st.seen.values.minOfOrNull { it.created_at } ?: return
        val until = oldest - 1
        submit(mode, st, Load.PAGE, since = until - windowSeconds(mode), until = until)
    }

    /**
     * Single serialized entry point. Captures [mode]/[state] at call-time (so
     * a mid-flight mode switch can't mis-route results), `cancelAndJoin`s the
     * previous job before the next REQ, and merges results into [state]'s
     * cache — updating the visible [_notes] only while [mode] is current.
     */
    private fun submit(mode: Mode, state: ModeState, load: Load, since: Long?, until: Long?) {
        val previous = activeJob
        activeJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val d = deps ?: return@launch

            val follows: Set<String>? = if (mode == Mode.FOLLOWING) {
                d.contactRepo.getFollowList().map { it.pubkey }.toSet()
            } else null
            if (follows != null && follows.isEmpty()) {
                state.loaded = true
                state.emptyFollows = true
                if (_mode.value == mode) {
                    _emptyFollows.value = true
                    clearIndicators()
                }
                return@launch
            }
            state.emptyFollows = false
            if (_mode.value == mode) {
                _emptyFollows.value = false
                when (load) {
                    Load.INITIAL -> _isLoading.value = true
                    Load.PAGE -> _isPaging.value = true
                    Load.REFRESH -> _isRefreshing.value = true
                }
            }

            val base = "onlyfood-${SUB_SEQ.incrementAndGet()}"
            val opened = mutableListOf<String>()
            var received = 0
            try {
                val collector = launch {
                    d.relayPool.relayEvents.collect { relayEvent ->
                        if (!relayEvent.subscriptionId.startsWith(base)) return@collect
                        val event = relayEvent.event
                        if (event.kind != 1 || event.id in state.seen) return@collect
                        if (!accept(event, d, follows)) return@collect
                        received++
                        state.seen[event.id] = event
                        d.eventRepo.cacheEvent(event)
                        d.eventRepo.requestProfileIfMissing(event.pubkey)
                        if (_mode.value == mode) _notes.value = state.snapshot()
                    }
                }
                val filter = Filter(
                    kinds = listOf(1),
                    tTags = FoodHashtags.ALL,
                    since = since,
                    until = until,
                    limit = 100,
                )
                if (follows == null) {
                    opened.add(base)
                    d.relayPool.sendToRelayOrEphemeral(
                        SearchViewModel.DEFAULT_SEARCH_RELAY,
                        ClientMessage.req(base, filter),
                    )
                } else {
                    follows.toList().chunked(AUTHOR_CHUNK).forEachIndexed { i, chunk ->
                        val subId = "$base-$i"
                        opened.add(subId)
                        d.relayPool.sendToRelayOrEphemeral(
                            SearchViewModel.DEFAULT_SEARCH_RELAY,
                            ClientMessage.req(subId, filter.copy(authors = chunk)),
                        )
                    }
                }
                withTimeoutOrNull(8_000) { d.relayPool.eoseSignals.first { it.startsWith(base) } }
                state.loaded = true // loaded even on 0 events → no re-query on toggle
                if (load == Load.PAGE && received == 0) state.endReached = true
                if (_mode.value == mode) {
                    _notes.value = state.snapshot()
                    clearIndicators()
                }
                delay(6_000) // collect stragglers, then tear down
                collector.cancel()
            } finally {
                // Close ONLY the subIds we actually opened (not a base-0..39 sweep).
                for (subId in opened) d.relayPool.closeOnAllRelays(subId)
            }
        }
    }

    private fun clearIndicators() {
        _isLoading.value = false
        _isPaging.value = false
        _isRefreshing.value = false
    }

    private fun accept(event: NostrEvent, d: Deps, follows: Set<String>?): Boolean {
        if (follows != null && event.pubkey !in follows) return false
        if (d.muteRepo.isBlocked(event.pubkey)) return false
        if (d.muteRepo.containsMutedWord(event.content)) return false
        return true
    }

    private fun windowSeconds(mode: Mode): Long =
        if (mode == Mode.FOLLOWING) THREE_DAYS else SEVEN_DAYS

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }

    companion object {
        /** Process-wide subId sequence — unique across all VM instances. */
        private val SUB_SEQ = java.util.concurrent.atomic.AtomicLong(0)
        private const val THREE_DAYS = 3L * 24 * 60 * 60
        private const val SEVEN_DAYS = 7L * 24 * 60 * 60
        private const val AUTHOR_CHUNK = 500
    }
}
