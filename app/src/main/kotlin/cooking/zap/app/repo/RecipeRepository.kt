package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.dedupeAcrossFormats
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Reads zap.cooking recipes (NIP-23 `kind 30023` tagged `#t zapcooking` /
 * legacy `nostrcooking`) from a **widened read union** ([readRelays]): the
 * `articles` aggregators ([RelayConfig.ARTICLES_RELAYS]) ∪ indexer/discovery
 * relays ([RelayConfig.DEFAULT_INDEXER_RELAYS]) ∪ the [RelayConfig.DEFAULTS]
 * read relays ∪ the signed-in user's own kind-10002 read relays. Coverage is
 * uneven across any single relay (a Step-0 live probe found `nostr.wine`
 * returned 0 for `#t zapcooking` while primal/nos.lol/eden carried the same
 * recipes), so every read fans the SAME registry filter to the whole union and
 * the results are de-duplicated by addressable coordinate. The union is
 * de-duped (trailing-slash normalized) so no relay is queried twice.
 *
 * Unlike [cooking.zap.app.viewmodel.HashtagFeedViewModel] — which keeps its
 * note list in the ViewModel — this repository OWNS the recipe flow. Both
 * the 1.5 home feed and the 1.3 recipe-detail screen read recipes, and both
 * need the same `kind:author:dTag` newest-wins dedup; owning the flow here
 * keeps that merge logic in one place instead of duplicating it per consumer.
 *
 * Premium recipes (`kind 35000`) are intentionally NOT handled — that is
 * Phase 3 (membership), and the kind is squatted, so the feed filter stays
 * `kinds:[30023]` only (build doc §1, §Phase 3).
 *
 * The subscription lifecycle is load-then-close: fan out a REQ, collect
 * until EOSE (or timeout), then close on ALL relays in a `finally` — no
 * persistent live tail is held.
 */
/**
 * Grace window kept open after a newest-window query reaches EOSE (or times
 * out) — lets a slow aggregator's recent recipes still land before the
 * collector is cancelled. Collection-window only; does NOT affect paging or
 * exhaustion (those live in [RecipeRepository.loadMore]).
 */
private const val EOSE_GRACE_MS = 2_000L

class RecipeRepository(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
    /** Resolves the signed-in user's kind-10002 READ relays for the read union. */
    private val userReadRelaysProvider: () -> List<String> = { emptyList() },
) {
    /** Newest event per addressable coordinate ("kind:author:dTag"). */
    private val byCoordinate = LinkedHashMap<String, NostrEvent>()

    /**
     * Serializes ALL [byCoordinate] reads/writes. `loadFeed` and `loadMore`
     * each run a collector on the [processingContext] thread pool, and the
     * pagination cursor reads the same map — without this lock those could
     * race / throw ConcurrentModificationException.
     */
    private val coordMutex = Mutex()

    private val _recipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    /** The deduped, `publishedAt`-desc recipe feed. Single source of truth. */
    val recipes: StateFlow<List<RecipeParser.Recipe>> = _recipes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    /** A follow-up (older) page is in flight. */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _exhausted = MutableStateFlow(false)
    /** True once a full-EOSE page returned nothing older — stop paging. */
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    /** A pull-to-refresh (newest-window re-pull) is in flight. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var refreshJob: Job? = null
    /**
     * Bumped on every reload/refresh. A [loadMore] started under a previous
     * epoch must NOT flip [exhausted] after a refresh has reset it. `@Volatile`
     * so the guard read on a [processingContext] thread sees main-thread writes.
     */
    @Volatile
    private var epoch = 0L
    private var subCounter = 0

    /**
     * The widened recipe read union (coverage). Recipes are discovered by
     * hashtag — not author write relays; the web does the same — so we fan the
     * SAME registry feedFilter to a UNION of:
     *   - [RelayConfig.ARTICLES_RELAYS] — the curated article aggregators
     *   - [RelayConfig.DEFAULT_INDEXER_RELAYS] — broad discovery/indexer relays
     *   - [RelayConfig.DEFAULTS] read relays — where recipes commonly land
     *   - the signed-in user's own kind-10002 READ relays
     * De-duplicated (trailing slash normalized) so no relay is queried twice.
     * Only WHERE we read widens — paging, dedup, and display are unchanged.
     */
    private fun readRelays(): List<String> {
        val union = LinkedHashSet<String>()
        fun add(url: String) { union.add(url.trim().trimEnd('/')) }
        RelayConfig.ARTICLES_RELAYS.forEach(::add)
        RelayConfig.DEFAULT_INDEXER_RELAYS.forEach(::add)
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }

    /**
     * Shared "newest window" query used by [loadFeed] and [refresh]: fan the
     * feedFilter (no `until`) to [readRelays], collect through the SAME
     * acceptEvent/emitRecipes path, and — for newest-window completeness — keep
     * collecting for a short [EOSE_GRACE_MS] grace after EOSE/timeout so a slow
     * aggregator's recent recipes still land.
     */
    private fun launchNewestWindowQuery(limit: Int, loadingFlag: MutableStateFlow<Boolean>): Job {
        val subId = "recipe-feed-${subCounter++}"
        loadingFlag.value = true
        return scope.launch(processingContext) {
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (RecipeFormats.forEvent(event) == null) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    coordMutex.withLock {
                        if (acceptEvent(event)) emitRecipes()
                    }
                }
            }
            // One filter per active format (NIP-23 only today); fanned to the
            // widened union. No `until` — this is the newest window.
            val filters = RecipeFormats.active.map { it.feedFilter(limit) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in readRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                if (sent > 0) {
                    subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    // Don't hard-cancel the instant EOSE is hit — give a slow
                    // aggregator's recent recipes a short grace to arrive.
                    delay(EOSE_GRACE_MS)
                }
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId) // closeOnAllRelays — no leaked sub
                loadingFlag.value = false
            }
        }
    }

    /**
     * Fan the recipe feed filter to the widened read union ([readRelays]),
     * collect the newest window, dedup by coordinate, and publish to [recipes].
     * Cancels any in-flight load/refresh/page and resets pagination.
     */
    fun loadFeed(limit: Int = 100) {
        loadJob?.cancel()
        refreshJob?.cancel()
        // A fresh feed resets pagination state and invalidates any in-flight page.
        loadMoreJob?.cancel()
        epoch++
        _isLoadingMore.value = false
        _isRefreshing.value = false
        _exhausted.value = false
        loadJob = launchNewestWindowQuery(limit, _isLoading)
    }

    /**
     * Pull-to-refresh: re-pull the newest window (no `until`) WITHOUT clearing
     * the grid. New recipes merge in via [byCoordinate] (newest-wins) and
     * surface at the top after the publishedAt sort. Bumps [epoch] so a stale
     * in-flight [loadMore] can't flip [exhausted] after the reset, and cancels
     * any in-flight page.
     */
    fun refresh(limit: Int = 100) {
        if (_isRefreshing.value) return
        // Supersede any in-flight initial load — both are newest-window queries;
        // running two concurrently would duplicate the fanout. Its finally sets
        // _isLoading=false on cancel.
        loadJob?.cancel()
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        epoch++
        _isLoadingMore.value = false
        _exhausted.value = false
        refreshJob = launchNewestWindowQuery(limit, _isRefreshing)
    }

    /**
     * Page backwards in time: fetch recipes older than the oldest currently
     * loaded (`until = oldestCreatedAt - 1`) and append. Single-flight (guarded
     * by [isLoadingMore]); a no-op once [exhausted] or while no recipes are
     * loaded yet. New events funnel through the SAME [acceptEvent]/[emitRecipes]
     * path as [loadFeed], so coordinate/format dedup is identical.
     *
     * Exhaustion is slow-relay-safe: it flips [exhausted] ONLY when a full EOSE
     * round (every queried relay replied) added zero new recipes. A timeout
     * (partial EOSE) with zero new does NOT exhaust — a slow relay may still
     * hold older recipes — so the next scroll retries.
     */
    fun loadMore(pageSize: Int = 50) {
        // Don't overlap the initial load (it shares byCoordinate), don't
        // double-page, and stop once exhausted.
        if (_isLoading.value || _isRefreshing.value || _isLoadingMore.value || _exhausted.value) return
        _isLoadingMore.value = true
        // Snapshot the epoch: if a refresh bumps it mid-flight, this page must
        // not flip exhausted afterwards (its EOSE round is stale).
        val startedEpoch = epoch
        val subId = "recipe-more-${subCounter++}"
        loadMoreJob = scope.launch(processingContext) {
            // Cursor = the oldest event created_at we hold (NIP-01 `until` is on
            // the event timestamp, not the display `publishedAt`). Read under the
            // lock; no data yet → nothing to page from.
            val oldest = coordMutex.withLock { byCoordinate.values.minOfOrNull { it.created_at } }
            if (oldest == null) {
                _isLoadingMore.value = false
                return@launch
            }
            val until = oldest - 1
            // Under an `until` filter every returned event is older than anything
            // loaded, so acceptEvent() == true marks a genuinely new coordinate.
            var newCoordinates = 0
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (RecipeFormats.forEvent(event) == null) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    coordMutex.withLock {
                        if (acceptEvent(event)) {
                            newCoordinates++
                            emitRecipes()
                        }
                    }
                }
            }
            // Page over the SAME widened union as the newest-window query.
            val filters = RecipeFormats.active.map { it.feedFilter(pageSize, until) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in readRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                // sent == 0 (no relay accepted) → don't wait the full timeout for
                // EOSEs that can't arrive; just clean up and let the next scroll
                // retry. Don't exhaust — we never actually asked.
                if (sent > 0) {
                    // NOTE: no grace window here — paging keeps the exact EOSE
                    // semantics, so exhaustion stays a real full-EOSE signal.
                    val eoseCount = subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    // Full round (all relays EOSE'd) with nothing new ⇒ exhausted.
                    // Partial (timeout) ⇒ leave the trigger live to retry. The
                    // epoch guard drops a page invalidated by an interleaved refresh.
                    if (epoch == startedEpoch && eoseCount >= sent && newCoordinates == 0) {
                        _exhausted.value = true
                    }
                }
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId)
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Resolve a single recipe by its addressable coordinate (the `naddr`
     * 1.3 navigates to). Cache-first; on a miss, REQ the `articles` union —
     * NOT [EventRepository.requestAddressableEvent], which routes to general
     * top relays where recipes may not live. Returns null if not found.
     */
    suspend fun requestRecipe(author: String, dTag: String): RecipeParser.Recipe? {
        findRecipeEvent(author, dTag)?.let { cached ->
            return RecipeFormats.forEvent(cached)?.parse(cached)
        }
        val subId = "recipe-one-${subCounter++}"
        val matches = mutableListOf<NostrEvent>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.pubkey != author) return@collect
                // Must be a recipe in some active format AND address this coordinate.
                if (RecipeFormats.forEvent(event) == null) return@collect
                if (event.tags.none { it.size >= 2 && it[0] == "d" && it[1] == dTag }) return@collect
                eventRepo.cacheEvent(event)
                matches.add(event)
            }
        }
        // One coordinate filter per active format (queries each format's kind).
        val filters = RecipeFormats.active.map { it.coordinateFilter(author, dTag) }
        val req = ClientMessage.req(subId, filters)
        var sent = 0
        for (url in RelayConfig.ARTICLES_RELAYS) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
        }
        try {
            subManager.awaitEoseCount(subId, expectedCount = sent.coerceAtLeast(1), timeoutMs = 8_000)
        } finally {
            collector.cancel()
            subManager.closeSubscription(subId)
        }
        // Canonical-pick across formats (and across relay duplicates within one).
        val winner = dedupeAcrossFormats(matches) { RecipeFormats.rankOf(it) }.firstOrNull()
        return winner?.let { RecipeFormats.forEvent(it)?.parse(it) }
    }

    /**
     * The raw recipe event for a coordinate, from cache only, dispatched across
     * **all active formats** (the detail screen's engagement bar needs the raw
     * event; a future second-format recipe must resolve here too, not just in
     * the feed). Canonical-pick when the coordinate exists in more than one
     * format. Identical to a single NIP-23 cache lookup while one format is active.
     */
    fun findRecipeEvent(author: String, dTag: String): NostrEvent? {
        val cached = RecipeFormats.active.mapNotNull { eventRepo.findAddressableEvent(it.kind, author, dTag) }
        return dedupeAcrossFormats(cached) { RecipeFormats.rankOf(it) }.firstOrNull()
    }

    /** Drop the in-memory feed (e.g. on account switch). */
    fun clear() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob?.cancel()
        epoch++
        byCoordinate.clear()
        _recipes.value = emptyList()
        _isLoading.value = false
        _isLoadingMore.value = false
        _isRefreshing.value = false
        _exhausted.value = false
    }

    /** Merge [event] into [byCoordinate]; return true iff it became the winner. */
    private fun acceptEvent(event: NostrEvent): Boolean {
        val key = recipeCoordinate(event)
        val current = byCoordinate[key]
        val winner = if (current == null) event else preferNewer(current, event)
        if (winner === current) return false
        byCoordinate[key] = winner
        return true
    }

    private fun emitRecipes() {
        // Stage 1 (byCoordinate): newest-wins per "kind:author:dTag" — collapses
        // relay duplicates of the same replaceable event. Stage 2
        // (dedupeAcrossFormats): collapse the SAME logical recipe across formats
        // by RecipeKey(author, slug). With one format active, Stage 2 is a
        // pass-through, so the feed is byte-for-byte what it was.
        _recipes.value = dedupeAcrossFormats(byCoordinate.values) { RecipeFormats.rankOf(it) }
            .mapNotNull { RecipeFormats.forEvent(it)?.parse(it) }
            .sortedByDescending { it.publishedAt }
    }
}

// ---- Pure merge helpers (no Android deps — unit-tested directly) ----------

/** Addressable coordinate "kind:pubkey:dTag" for a replaceable event (NIP-01). */
internal fun recipeCoordinate(event: NostrEvent): String {
    val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
    return "${event.kind}:${event.pubkey}:$d"
}

/**
 * NIP-01 replaceable-event winner: the higher `created_at` wins; on an EQUAL
 * `created_at` the lexicographically lower event id is kept. Returns the
 * argument reference that wins (so callers can use identity to detect change).
 */
internal fun preferNewer(a: NostrEvent, b: NostrEvent): NostrEvent = when {
    a.created_at != b.created_at -> if (a.created_at > b.created_at) a else b
    else -> if (a.id <= b.id) a else b
}

/** Collapse events to the newest one per addressable coordinate. */
internal fun dedupeNewestPerCoordinate(events: Iterable<NostrEvent>): List<NostrEvent> {
    val byCoord = LinkedHashMap<String, NostrEvent>()
    for (event in events) {
        val key = recipeCoordinate(event)
        val current = byCoord[key]
        byCoord[key] = if (current == null) event else preferNewer(current, event)
    }
    return byCoord.values.toList()
}
