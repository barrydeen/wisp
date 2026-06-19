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
 * server-side `authors` filter). Members + replies are deferred (Phase 3 /
 * later).
 *
 * Filtering is mute-only (blocked author or muted word) — matching the
 * proven `HashtagFeedViewModel` and the web foodstr feed, neither of which
 * runs a spam classifier. (v1 dropped NSpam: running `score()` inside the
 * relay collector both over-filtered hashtag/link-heavy food posts at the
 * `>= 0.7` threshold and risked an exception cancelling the whole stream.
 * Re-adding spam filtering correctly is a tracked follow-up — see build doc.)
 *
 * Pagination is web-style time-windowed (global 7-day initial, following
 * 3-day; older windows on scroll via `until = oldest - 1`). The subscription
 * lifecycle mirrors [HashtagFeedViewModel] — REQ, collect, EOSE/timeout,
 * close — on the search relay.
 *
 * (Forward note, per review: outbox routing via OutboxRouter is the
 * completeness upgrade for following-mode if the single search relay is
 * sparse — not wired here.)
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
    private var subCounter = 0
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
        startFresh()
    }

    fun setMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        startFresh()
    }

    /** Infinite-scroll hook: page one window further back in time. */
    fun loadMore() {
        if (_isLoading.value || _isPaging.value || endReached) return
        val oldest = seen.values.minOfOrNull { it.created_at } ?: return
        val until = oldest - 1
        subscribe(since = until - windowSeconds(), until = until, initial = false)
    }

    private fun startFresh() {
        activeJob?.cancel()
        seen.clear()
        endReached = false
        _emptyFollows.value = false
        _notes.value = emptyList()
        // No `since` floor on the initial load — match the working
        // HashtagFeedViewModel. The search/archive relay has weak recent
        // coverage, so a 7-day `since` excluded everything; "newest 100, no
        // floor" is what actually returns posts. The window only matters for
        // pagination (loadMore), where `until` already bounds the query.
        subscribe(since = null, until = null, initial = true)
    }

    private fun subscribe(since: Long?, until: Long?, initial: Boolean) {
        val d = deps ?: return
        val mode = _mode.value

        val follows: Set<String>? = if (mode == Mode.FOLLOWING) {
            d.contactRepo.getFollowList().map { it.pubkey }.toSet()
        } else null
        if (follows != null && follows.isEmpty()) {
            _emptyFollows.value = true
            _isLoading.value = false
            return
        }

        if (initial) _isLoading.value = true else _isPaging.value = true
        val base = "onlyfood-${subCounter++}"
        var received = 0

        activeJob = viewModelScope.launch {
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
                    d.relayPool.sendToRelayOrEphemeral(
                        SearchViewModel.DEFAULT_SEARCH_RELAY,
                        ClientMessage.req(base, filter),
                    )
                } else {
                    // Server-side authors filter. A REQ replaces any same-subId
                    // sub on a relay, so each author chunk gets its own subId
                    // under the shared `base` prefix the collector matches.
                    follows.toList().chunked(AUTHOR_CHUNK).forEachIndexed { i, chunk ->
                        d.relayPool.sendToRelayOrEphemeral(
                            SearchViewModel.DEFAULT_SEARCH_RELAY,
                            ClientMessage.req("$base-$i", filter.copy(authors = chunk)),
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
                closeAll(d, base)
            }
        }
    }

    private fun accept(event: NostrEvent, d: Deps, follows: Set<String>?): Boolean {
        if (follows != null && event.pubkey !in follows) return false
        if (d.muteRepo.isBlocked(event.pubkey)) return false
        if (d.muteRepo.containsMutedWord(event.content)) return false
        return true
    }

    private fun closeAll(d: Deps, base: String) {
        // Close the global sub and any author-chunk subs under this base.
        d.relayPool.closeOnAllRelays(base)
        for (i in 0 until AUTHOR_CHUNKS_MAX) d.relayPool.closeOnAllRelays("$base-$i")
    }

    private fun publish() {
        _notes.value = seen.values.sortedByDescending { it.created_at }
    }

    private fun windowSeconds(): Long =
        if (_mode.value == Mode.FOLLOWING) THREE_DAYS else SEVEN_DAYS

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }

    companion object {
        private const val THREE_DAYS = 3L * 24 * 60 * 60
        private const val SEVEN_DAYS = 7L * 24 * 60 * 60
        private const val AUTHOR_CHUNK = 500
        private const val AUTHOR_CHUNKS_MAX = 40 // close-all bound (≈20k follows)
    }
}
