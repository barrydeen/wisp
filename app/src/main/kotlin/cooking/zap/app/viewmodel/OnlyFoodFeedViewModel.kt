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
 * (concern 1.6). Two modes (v1): [Mode.GLOBAL] (all matching notes) and
 * [Mode.FOLLOWING] (matching notes from the user's kind-3 contacts, via a
 * server-side `authors` filter). Members + replies are deferred.
 *
 * Filtering is mute-only (blocked author or muted word) — matching the proven
 * `HashtagFeedViewModel` and the web foodstr feed, neither of which runs a
 * spam classifier (re-adding NSpam correctly is a tracked follow-up).
 *
 * **Subscription discipline (why this isn't a plain REQ/collect/close).** The
 * search relay throttles a churning connection. Three things keep churn down:
 *  1. **One sub at a time, serialized.** Every load — initial, mode toggle,
 *     pagination — goes through [submit], which `cancelAndJoin`s the previous
 *     job (so its teardown CLOSEs run to completion) *before* the next REQ.
 *     No overlapping/orphaned jobs racing the relay.
 *  2. **Close only what was opened.** A teardown CLOSEs exactly the subIds it
 *     sent (1 for global, the real chunk count for following) — not a blind
 *     `base-0..base-39` sweep, each of which `RelayPool` fans to every
 *     connection (~450 stray CLOSE frames/teardown → relay throttle).
 *  3. **Process-wide unique subIds** ([SUB_SEQ]) so an old instance's CLOSE
 *     can never target a new instance's sub.
 */
class OnlyFoodFeedViewModel : ViewModel() {

    enum class Mode { GLOBAL, FOLLOWING }

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _mode = MutableStateFlow(Mode.GLOBAL)
    val mode: StateFlow<Mode> = _mode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging

    /** No follows in FOLLOWING mode → screen shows a "follow people" prompt. */
    private val _emptyFollows = MutableStateFlow(false)
    val emptyFollows: StateFlow<Boolean> = _emptyFollows

    private val seen = LinkedHashMap<String, NostrEvent>()
    private var deps: Deps? = null
    private var activeJob: Job? = null
    private var endReached = false

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
        reload()
    }

    fun setMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        reload()
    }

    /** Infinite-scroll hook: page one window further back in time. */
    fun loadMore() {
        if (_isLoading.value || _isPaging.value || endReached) return
        val oldest = seen.values.minOfOrNull { it.created_at } ?: return
        val until = oldest - 1
        // reset=false → append older notes to the existing feed.
        submit(reset = false, initial = false, since = until - windowSeconds(), until = until)
    }

    /** Fresh load (initial / mode switch): no `since` floor — newest 100. */
    private fun reload() {
        endReached = false
        submit(reset = true, initial = true, since = null, until = null)
    }

    /**
     * The single serialized entry point. Chains off the previous job and
     * `cancelAndJoin`s it first, so the prior subscription's CLOSEs finish
     * before this one's REQ — deterministic teardown, no connection churn.
     */
    private fun submit(reset: Boolean, initial: Boolean, since: Long?, until: Long?) {
        val previous = activeJob
        activeJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val d = deps ?: return@launch

            val follows: Set<String>? = if (_mode.value == Mode.FOLLOWING) {
                d.contactRepo.getFollowList().map { it.pubkey }.toSet()
            } else null
            if (follows != null && follows.isEmpty()) {
                if (reset) { seen.clear(); _notes.value = emptyList() }
                _emptyFollows.value = true
                _isLoading.value = false
                _isPaging.value = false
                return@launch
            }
            _emptyFollows.value = false
            if (reset) { seen.clear(); _notes.value = emptyList() }
            if (initial) _isLoading.value = true else _isPaging.value = true

            val base = "onlyfood-${SUB_SEQ.incrementAndGet()}"
            val opened = mutableListOf<String>()
            var received = 0

            try {
                val collector = launch {
                    d.relayPool.relayEvents.collect { relayEvent ->
                        if (!relayEvent.subscriptionId.startsWith(base)) return@collect
                        val event = relayEvent.event
                        if (event.kind != 1 || event.id in seen) return@collect
                        if (!accept(event, d, follows)) return@collect
                        received++
                        seen[event.id] = event
                        d.eventRepo.cacheEvent(event)
                        d.eventRepo.requestProfileIfMissing(event.pubkey)
                        publish()
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
                    // Server-side authors filter. A REQ replaces any same-subId
                    // sub on a relay, so each author chunk gets its own subId
                    // under the shared `base` prefix the collector matches.
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
                if (!initial && received == 0) endReached = true
                _isLoading.value = false
                _isPaging.value = false
                delay(6_000) // collect stragglers, then tear down
                collector.cancel()
            } finally {
                // Close ONLY the subIds we actually opened (not a base-0..39 sweep).
                for (subId in opened) d.relayPool.closeOnAllRelays(subId)
            }
        }
    }

    private fun accept(event: NostrEvent, d: Deps, follows: Set<String>?): Boolean {
        if (follows != null && event.pubkey !in follows) return false
        if (d.muteRepo.isBlocked(event.pubkey)) return false
        if (d.muteRepo.containsMutedWord(event.content)) return false
        return true
    }

    private fun publish() {
        _notes.value = seen.values.sortedByDescending { it.created_at }
    }

    private fun windowSeconds(): Long =
        if (_mode.value == Mode.FOLLOWING) THREE_DAYS else SEVEN_DAYS

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
