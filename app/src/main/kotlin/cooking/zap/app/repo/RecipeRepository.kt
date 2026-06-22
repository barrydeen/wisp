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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Reads zap.cooking recipes (NIP-23 `kind 30023` tagged `#t zapcooking` /
 * legacy `nostrcooking`) from the **`articles` relay set**
 * ([RelayConfig.ARTICLES_RELAYS]) — deliberately NOT [RelayConfig.DEFAULTS]
 * and NOT the general top-relay router. Recipes live on the public article
 * aggregators (build doc §1), and a Step-0 live probe confirmed coverage is
 * uneven across them (the same recipe shows up on primal/nos.lol/eden but
 * not nostr.wine/noswhere), so every read fans out to the whole set as a
 * **union** and the results are de-duplicated by addressable coordinate.
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
class RecipeRepository(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
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

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var subCounter = 0

    /**
     * Fan out the recipe feed filter to the `articles` relay union, collect
     * matching 30023 recipes until EOSE/timeout, dedup by coordinate, and
     * publish to [recipes]. Cancels any in-flight load first.
     */
    fun loadFeed(limit: Int = 100) {
        loadJob?.cancel()
        // A fresh feed resets pagination state.
        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        _exhausted.value = false
        val subId = "recipe-feed-${subCounter++}"
        _isLoading.value = true
        loadJob = scope.launch(processingContext) {
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
            // One filter per active format (NIP-23 only today). A single REQ
            // carries them all; with one format this is byte-identical to the
            // previous single-filter REQ.
            val filters = RecipeFormats.active.map { it.feedFilter(limit) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in RelayConfig.ARTICLES_RELAYS) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                subManager.awaitEoseCount(subId, expectedCount = sent.coerceAtLeast(1), timeoutMs = 8_000)
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId) // closeOnAllRelays — no leaked sub
                _isLoading.value = false
            }
        }
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
        if (_isLoading.value || _isLoadingMore.value || _exhausted.value) return
        _isLoadingMore.value = true
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
            val filters = RecipeFormats.active.map { it.feedFilter(pageSize, until) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in RelayConfig.ARTICLES_RELAYS) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                // sent == 0 (no relay accepted) → don't wait the full timeout for
                // EOSEs that can't arrive; just clean up and let the next scroll
                // retry. Don't exhaust — we never actually asked.
                if (sent > 0) {
                    val eoseCount = subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    // Full round (all relays EOSE'd) with nothing new ⇒ exhausted.
                    // Partial (timeout) ⇒ leave the trigger live to retry.
                    if (eoseCount >= sent && newCoordinates == 0) {
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
        byCoordinate.clear()
        _recipes.value = emptyList()
        _isLoading.value = false
        _isLoadingMore.value = false
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
