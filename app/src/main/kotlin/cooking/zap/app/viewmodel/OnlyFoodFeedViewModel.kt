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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Source of truth: every accepted event (dedup by id), insertion-ordered.
        val seen = LinkedHashMap<String, NostrEvent>()
        // Display-order cache, maintained only for emission via [mergeFeedOrder].
        // While [settled] is false a flush rebuilds these from `seen` (full sort);
        // once it flips true at EOSE a flush only appends new arrivals to the tail.
        val ordered = ArrayList<NostrEvent>()
        val placedIds = HashSet<String>()
        var loaded = false
        var endReached = false
        var emptyFollows = false
        // false = still loading/refreshing → flush rebuilds order from `seen`.
        // true  = post-EOSE → flush appends, so a late straggler never reorders rows
        //         already on screen.
        var settled = false

        /**
         * Enter the unsettled (rebuild) state. Resets ONLY the display cache so the
         * next flush rebuilds from `seen` instead of appending onto stale entries —
         * the mid-build-reconnect invariant (Correction 1). `seen` is untouched, so
         * no events are lost.
         */
        fun unsettle() {
            settled = false
            ordered.clear()
            placedIds.clear()
        }
    }

    private enum class Load { INITIAL, PAGE, REFRESH }

    private val states = mapOf(Mode.GLOBAL to ModeState(), Mode.FOLLOWING to ModeState())
    private fun stateOf(mode: Mode) = states.getValue(mode)

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    /**
     * Coalescing signal — inserts call [flushSignal].trySend; one collector started
     * in [init] debounces a burst into a single emission per [SETTLE_WINDOW_MS]
     * window. Mirrors EventRepository's `feedInserted`. CONFLATED so rapid signals
     * collapse to at most one pending item.
     */
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

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
        // Coalesced emission collector. MUST run on viewModelScope (Main.immediate)
        // so it shares the event collector's single thread — `seen`/`ordered` are
        // plain (non-thread-safe) collections and a separate dispatcher would race
        // them. The settle window batches a burst into one emission.
        viewModelScope.launchFeedCoalescer(flushSignal, SETTLE_WINDOW_MS) { emitCurrentMode() }
        // Seed GLOBAL from cache so cached food renders before the live query.
        seedGlobalFromCache()
        // Auto-recover: re-issue when the search relay (re)connects while the
        // current mode hasn't reached a genuine EOSE. Started before the initial
        // submit so a connect that lands during the first query is observed.
        observeReconnect()
        val st = stateOf(_mode.value)
        if (!st.loaded) submit(_mode.value, st, Load.INITIAL, since = null, until = null)
    }

    /** Instant cache swap. Queries the target mode only if it's never loaded. */
    fun setMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        val st = stateOf(mode)
        // Instant cache swap through the shared compute path — no relay query, no
        // flush signal. A settled target appends-from-its-cache; a never-loaded one
        // rebuilds-from-(empty)-seen. Either way, the order matches a live flush.
        emitCurrentMode()
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
        val st = stateOf(mode)
        // A refresh re-opens paging: a prior `endReached` may have been a quiet
        // stretch or a throttle, and new posts may have arrived. `endReached` is
        // per-mode and resettable here; it is NOT the Phase-1 `loaded` latch.
        st.endReached = false
        submit(mode, st, Load.REFRESH, since = null, until = null)
    }

    /**
     * Infinite-scroll: page strictly backwards from the oldest loaded event,
     * appending to the cache. No `since` floor — `until` + `limit` walk backwards
     * through quiet stretches the standard Nostr way, so a single empty time
     * window no longer ends the feed. `endReached` then trips only on a genuine
     * zero-older-events EOSE.
     */
    fun loadMore() {
        val mode = _mode.value
        val st = stateOf(mode)
        if (_isLoading.value || _isPaging.value || _isRefreshing.value || st.endReached) return
        // Memory guardrail: stop paging once this mode's cache hits the retention cap so
        // an unbounded scroll session can't grow seen/ordered/placedIds without limit.
        // Evicting instead would corrupt the paging cursor (derived from the oldest
        // retained event) and disturb the viewport, so we cap rather than evict.
        if (st.seen.size >= MAX_RETAINED_EVENTS) {
            st.endReached = true
            return
        }
        val oldest = st.seen.values.minOfOrNull { it.created_at } ?: return
        val bounds = pageBoundsBehind(oldest)
        submit(mode, st, Load.PAGE, since = bounds.since, until = bounds.until)
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

            // A load that rebuilds the list from scratch must reset the display cache
            // so the next flush rebuilds from `seen` rather than appending onto stale
            // order (Correction 1 — the mid-build-reconnect interleave). PAGE is
            // append-only and keeps the existing settled order. Done after
            // cancelAndJoin so the previous load can't write into a just-reset cache.
            if (load == Load.INITIAL || load == Load.REFRESH) state.unsettle()

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

            val searchRelay = SearchViewModel.DEFAULT_SEARCH_RELAY
            // Pull-to-refresh must punch through a stale cooldown so it never
            // silently no-ops. INITIAL/auto-recover must NOT clear it — that would
            // fight a genuine 429 backoff from the rate-limit-prone search relay.
            if (load == Load.REFRESH) d.relayPool.clearCooldown(searchRelay)

            val base = "onlyfood-${SUB_SEQ.incrementAndGet()}"
            val opened = mutableListOf<String>()
            var received = 0
            var collector: Job? = null
            // Pin while the read is in flight so the LRU cap can't evict the
            // search ephemeral mid-handshake; unpinned in finally.
            d.relayPool.pinEphemeral(searchRelay)
            try {
                // Gate the REQ on a LIVE socket. Connect budget is separate from
                // the EOSE budget — we don't race one 8s timeout across a cold
                // connect AND the EOSE.
                val connected = d.relayPool.awaitRelayConnected(searchRelay, CONNECT_TIMEOUT_MS)
                if (!connected) {
                    // Connect timed out. A queued send would drain only after this
                    // collector is gone (relayEvents has replay=0) → events arrive
                    // into the void, recreating the original bug. So do NOT send;
                    // leave loaded=false for auto-recover/refresh to retry.
                    if (_mode.value == mode) clearIndicators()
                    return@launch
                }

                // Subscribe the event collector AND the EOSE awaiter, and wait
                // until BOTH are actively collecting before sending — relayEvents
                // and eoseSignals both have replay=0, so a send that races ahead of
                // subscription drops its events/EOSE on the floor.
                val collectorReady = CompletableDeferred<Unit>()
                collector = launch {
                    d.relayPool.relayEvents
                        .onSubscription { collectorReady.complete(Unit) }
                        .collect { relayEvent ->
                            if (!relayEvent.subscriptionId.startsWith(base)) return@collect
                            val event = relayEvent.event
                            if (event.kind != 1) return@collect
                            // Suspension-free per-event ingest (dedup → accept → insert →
                            // cache/profile → coalesced flush), extracted to the shared
                            // [ingestEvent] so the path the live feed runs is the one the
                            // concurrency test hammers. Still on viewModelScope/Main.immediate
                            // here — the thread move is PR 2 and does not touch this call.
                            val inserted = ingestEvent(
                                event = event,
                                seen = state.seen,
                                accept = { accept(it, d, follows) },
                                onAccepted = {
                                    d.eventRepo.cacheEvent(it)
                                    d.eventRepo.requestProfileIfMissing(it.pubkey)
                                },
                                // Coalesce: signal a flush instead of emitting per event.
                                // Only for the visible mode — a background load fills its
                                // `seen` and rebuilds its order lazily on toggle/EOSE.
                                signalFlush = { if (_mode.value == mode) flushSignal.trySend(Unit) },
                            )
                            if (inserted) received++
                        }
                }
                val eoseReady = CompletableDeferred<Unit>()
                val eoseAwaiter = async {
                    withTimeoutOrNull(EOSE_TIMEOUT_MS) {
                        d.relayPool.eoseSignals
                            .onSubscription { eoseReady.complete(Unit) }
                            .first { it.startsWith(base) }
                    }
                }
                collectorReady.await()
                eoseReady.await()

                val filter = Filter(
                    kinds = listOf(1),
                    tTags = FoodHashtags.ALL,
                    since = since,
                    until = until,
                    limit = 100,
                )
                var anySent = false
                if (follows == null) {
                    opened.add(base)
                    if (d.relayPool.sendToRelayOrEphemeral(searchRelay, ClientMessage.req(base, filter))) {
                        anySent = true
                    }
                } else {
                    follows.toList().chunked(AUTHOR_CHUNK).forEachIndexed { i, chunk ->
                        val subId = "$base-$i"
                        opened.add(subId)
                        if (d.relayPool.sendToRelayOrEphemeral(
                                searchRelay,
                                ClientMessage.req(subId, filter.copy(authors = chunk)),
                            )
                        ) {
                            anySent = true
                        }
                    }
                }

                if (!anySent) {
                    // Every send was dropped (cooldown / no capacity). Don't await
                    // an EOSE that can't arrive; leave loaded=false to retry.
                    eoseAwaiter.cancel()
                    if (_mode.value == mode) clearIndicators()
                    return@launch
                }

                // Latch ONLY on a genuine EOSE (even at 0 events). A timeout leaves
                // loaded=false so auto-recover/refresh can retry — no empty latch.
                // By here connected and anySent are both true (the paths above
                // early-returned otherwise); [shouldLatchLoaded] is the single
                // source of truth for the rule, unit-tested across all three cases.
                val eose = eoseAwaiter.await()
                val eoseFired = eose != null
                if (shouldLatchLoaded(connected = true, anySent = true, eoseFired = eoseFired)) {
                    state.loaded = true
                    // Gated on a genuine EOSE (a timeout never end-reaches): a PAGE
                    // that returned zero strictly-older events has hit the floor.
                    if (load == Load.PAGE && pageEndReached(received)) state.endReached = true
                }
                // INITIAL/REFRESH freeze: on a genuine EOSE, rebuild this load's display
                // cache from `seen` (full sort, unsettled semantics) so it is complete
                // and correctly ordered — for the visible AND a background mode
                // (Correction 3) — THEN flip `settled` so later stragglers append
                // instead of re-sorting rows already on screen.
                if (eoseFired && (load == Load.INITIAL || load == Load.REFRESH)) {
                    mergeFeedOrder(state.ordered, state.placedIds, state.seen.values, settled = false)
                    state.settled = true
                }
                // Single compute-display-list path (Correction 2): the direct post-EOSE
                // emit and the coalescer are both just callers of emitCurrentMode(), so
                // they can never disagree on order and flicker.
                if (_mode.value == mode) {
                    emitCurrentMode()
                    clearIndicators()
                }
                if (eose != null) delay(6_000) // collect stragglers, then tear down
            } finally {
                collector?.cancel()
                d.relayPool.unpinEphemeral(searchRelay)
                // Close ONLY the subIds we actually opened (not a base-0..39 sweep).
                for (subId in opened) d.relayPool.closeOnAllRelays(subId)
            }
        }
    }

    /**
     * Cache-seed the GLOBAL mode from [EventRepository.cachedFoodNotes] so cached
     * food renders immediately instead of a blank empty-state. Mute-filtered via
     * [accept] (follows=null), inserted into GLOBAL's `seen` — NOT into [_notes],
     * which is rebuilt from `seen` on the first live snapshot. Does NOT set
     * `loaded`, so the live query still runs and EOSE still drives the latch.
     * GLOBAL only: FOLLOWING needs the follow set first.
     */
    private fun seedGlobalFromCache() {
        val d = deps ?: return
        viewModelScope.launch {
            val global = stateOf(Mode.GLOBAL)
            if (global.seen.isNotEmpty()) return@launch
            val cached = withContext(Dispatchers.IO) { d.eventRepo.cachedFoodNotes() }
            var added = false
            for (event in cached) {
                if (event.kind != 1 || event.id in global.seen) continue
                if (!accept(event, d, follows = null)) continue
                global.seen[event.id] = event
                added = true
            }
            // GLOBAL is unsettled at seed time, so the flush rebuilds order from
            // `seen` (seeded cache + anything already streamed). Coalesced like the
            // live path so the seed + first REQ burst share a settle window.
            if (added && _mode.value == Mode.GLOBAL) flushSignal.trySend(Unit)
        }
    }

    /**
     * Auto-recover: each [RelayPool.connectedCount] change is a cheap "re-check"
     * tick. Re-derive `isRelayConnected(searchRelay)` fresh each time (the search
     * ephemeral is evicted/recreated, so a held per-URL flow would observe a dead
     * Relay). Re-issue ONLY when the search relay is connected, the current mode
     * has NOT reached a genuine EOSE (`!loaded`), and nothing is in flight.
     *
     * Keying on `!loaded` (not `notes.isEmpty()`) is deliberate: a legitimately
     * empty-but-loaded GLOBAL window, a FOLLOWING mode whose follows don't post
     * food, and the `emptyFollows` fast-path all set `loaded = true`, so they are
     * NOT re-queried on unrelated relay reconnects — the throttle protection the
     * per-mode cache exists for. `loaded` flips true after the first EOSE, so this
     * self-limits with no false→true edge tracking.
     */
    private fun observeReconnect() {
        val d = deps ?: return
        viewModelScope.launch {
            d.relayPool.connectedCount.collect {
                if (!d.relayPool.isRelayConnected(SearchViewModel.DEFAULT_SEARCH_RELAY)) return@collect
                val mode = _mode.value
                val st = stateOf(mode)
                if (st.loaded || activeJob?.isActive == true) return@collect
                submit(mode, st, Load.INITIAL, since = null, until = null)
            }
        }
    }

    /**
     * The single "compute the display list" path (Correction 2). Both the coalesced
     * flush collector and the direct post-EOSE/toggle emits call this, so they can
     * never disagree on order. Emits the CURRENT mode's list, computed by
     * [mergeFeedOrder] from that mode's `settled` flag. StateFlow's value-equality
     * dedup drops a flush that produced no change.
     */
    private fun emitCurrentMode() {
        val st = stateOf(_mode.value)
        _notes.value = mergeFeedOrder(st.ordered, st.placedIds, st.seen.values, st.settled)
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

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }

    companion object {
        /** Process-wide subId sequence — unique across all VM instances. */
        private val SUB_SEQ = java.util.concurrent.atomic.AtomicLong(0)
        /** Emission settle window — matches EventRepository's `feedInserted`. */
        private const val SETTLE_WINDOW_MS = 50L
        private const val AUTHOR_CHUNK = 500
        /** Per-mode in-memory retention cap; paging stops here to bound memory. */
        private const val MAX_RETAINED_EVENTS = 3000
        /** Budget to bring the search ephemeral's socket up before sending. */
        private const val CONNECT_TIMEOUT_MS = 8_000L
        /** Budget to await EOSE, measured only AFTER the socket is connected. */
        private const val EOSE_TIMEOUT_MS = 8_000L
    }
}

/**
 * Ingest one collected event into the OnlyFood source-of-truth [seen] map — the
 * single, suspension-free per-event path shared by the live collector and the
 * ingestion test. Behavior-identical extraction of the former inline collector
 * body: dedup against [seen] → [accept] gate → insert → [onAccepted] side-effects
 * (cache + profile request) → [signalFlush] coalesced emission. No logic change.
 *
 * Suspension-free by construction — every collaborator is a plain, non-suspending
 * lambda — so under single-thread confinement the `id in seen` check and the
 * insert can never interleave with another ingest: no dupes, no lost events. This
 * is the invariant PR 2 relies on when it swaps the confinement thread from
 * Main.immediate to a serial background dispatcher; this function is unchanged
 * there.
 *
 * @param accept the mute/block/follows predicate ([OnlyFoodFeedViewModel.accept]).
 * @param onAccepted side-effects for a newly accepted event (cache + profile
 *   request); runs exactly once per newly inserted event.
 * @param signalFlush request a coalesced emission (caller gates it to the visible
 *   mode); runs exactly once per newly inserted event.
 * @return true iff the event was newly accepted and inserted, so the caller can
 *   count it toward paging exhaustion ([pageEndReached]).
 */
internal inline fun ingestEvent(
    event: NostrEvent,
    seen: MutableMap<String, NostrEvent>,
    accept: (NostrEvent) -> Boolean,
    onAccepted: (NostrEvent) -> Unit,
    signalFlush: () -> Unit,
): Boolean {
    if (event.id in seen) return false
    if (!accept(event)) return false
    seen[event.id] = event
    onAccepted(event)
    signalFlush()
    return true
}

/**
 * Compute the OnlyFood display order — the single source of truth for "what list
 * to show", shared by the coalesced flush and the direct post-EOSE/toggle emits.
 *
 * - [settled] == false (loading / refreshing): REBUILD [ordered]/[placedIds] from
 *   [seen] by a full descending-`created_at` sort, discarding any prior contents.
 *   This enforces the mid-build-reconnect invariant (Correction 1) — an unsettled
 *   flush never appends onto stale display state, so a reconnect re-submit on the
 *   same state produces a from-scratch order, not an append onto pre-drop entries.
 * - [settled] == true (post-EOSE): APPEND only the [seen] events not already in
 *   [placedIds], sorted within this batch, to the tail of [ordered]. Rows already
 *   on screen keep their position, so a late straggler can't reorder them.
 *
 * Mutates [ordered]/[placedIds] in place and returns a fresh list to emit.
 */
internal fun mergeFeedOrder(
    ordered: MutableList<NostrEvent>,
    placedIds: MutableSet<String>,
    seen: Collection<NostrEvent>,
    settled: Boolean,
): List<NostrEvent> {
    if (!settled) {
        ordered.clear()
        placedIds.clear()
        for (event in seen.sortedByDescending { it.created_at }) {
            ordered.add(event)
            placedIds.add(event.id)
        }
    } else {
        val fresh = seen.filter { it.id !in placedIds }.sortedByDescending { it.created_at }
        for (event in fresh) {
            ordered.add(event)
            placedIds.add(event.id)
        }
    }
    return ArrayList(ordered)
}

/**
 * Launch the coalescing collector that mirrors EventRepository's `feedInserted`
 * settle window: drain the conflated [signal], wait [settleMs] for the burst to
 * settle, then [emit] once. A burst of N rapid signals collapses to ~one emission
 * per window. Extracted top-level so it can be driven by `runTest` virtual time.
 */
internal fun CoroutineScope.launchFeedCoalescer(
    signal: ReceiveChannel<Unit>,
    settleMs: Long,
    emit: () -> Unit,
): Job = launch {
    // consumeEach drains the channel without binding an (unused) loop variable.
    signal.consumeEach {
        delay(settleMs)
        emit()
    }
}

/**
 * The OnlyFood per-mode latch rule, extracted pure for unit testing. A mode is
 * "loaded" (so it won't be re-queried on toggle or auto-recover) ONLY when the
 * socket was [connected], at least one REQ was actually [anySent], AND a genuine
 * EOSE arrived ([eoseFired]). EOSE-with-zero-events still latches (the window is
 * genuinely empty); a connect/send failure or an EOSE timeout does NOT latch, so
 * the transient failure can be retried instead of latching a blank feed.
 */
internal fun shouldLatchLoaded(connected: Boolean, anySent: Boolean, eoseFired: Boolean): Boolean =
    connected && anySent && eoseFired

/** Bounds for one backward infinite-scroll page. */
internal data class PageBounds(val since: Long?, val until: Long)

/**
 * The bounds for the next page strictly older than [oldestCreatedAt]. NO `since`
 * floor (the Phase-2 fix): `until` + `limit` walk backwards through quiet
 * stretches instead of a fixed time window ending the feed at the first gap.
 * `until = oldestCreatedAt - 1` excludes the boundary second, so each non-empty
 * page strictly lowers `oldest` → the next `until` strictly decreases → no two
 * page queries are identical (search-relay throttle-safe).
 */
internal fun pageBoundsBehind(oldestCreatedAt: Long): PageBounds =
    PageBounds(since = null, until = oldestCreatedAt - 1)

/**
 * Paging exhaustion rule: a backward page has hit the floor ONLY when it added
 * zero strictly-older events. Because `until = oldest - 1` excludes everything at
 * or above `oldest`, every returned event is genuinely new, so [receivedNew] == 0
 * means the relay holds nothing older — a real end, not an empty intermediate
 * window. Always evaluated behind a genuine EOSE (a timeout never end-reaches).
 */
internal fun pageEndReached(receivedNew: Int): Boolean = receivedNew == 0
