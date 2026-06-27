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
import kotlinx.coroutines.withContext
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
     * Independent state for the **authored** ("My Recipes") feed — the user's
     * OWN published recipes, scoped to one `authors` pubkey. Separate map/flow
     * from the global feed so the two never cross-contaminate.
     */
    private val authoredByCoordinate = LinkedHashMap<String, NostrEvent>()
    private val authoredCoordMutex = Mutex()
    private val _authoredRecipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val authoredRecipes: StateFlow<List<RecipeParser.Recipe>> = _authoredRecipes.asStateFlow()
    private val _isAuthoredLoading = MutableStateFlow(false)
    val isAuthoredLoading: StateFlow<Boolean> = _isAuthoredLoading.asStateFlow()
    private var authoredAuthor: String? = null
    private var authoredLoadJob: Job? = null

    /** Independent state for the shared recipe-by-tag feed surface. */
    private val tagByCoordinate = LinkedHashMap<String, NostrEvent>()
    private val tagCoordMutex = Mutex()
    private val _tagRecipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val tagRecipes: StateFlow<List<RecipeParser.Recipe>> = _tagRecipes.asStateFlow()
    private val _isTagLoading = MutableStateFlow(false)
    val isTagLoading: StateFlow<Boolean> = _isTagLoading.asStateFlow()
    private val _isTagLoadingMore = MutableStateFlow(false)
    val isTagLoadingMore: StateFlow<Boolean> = _isTagLoadingMore.asStateFlow()
    private val _isTagRefreshing = MutableStateFlow(false)
    val isTagRefreshing: StateFlow<Boolean> = _isTagRefreshing.asStateFlow()
    private val _tagExhausted = MutableStateFlow(false)
    val tagExhausted: StateFlow<Boolean> = _tagExhausted.asStateFlow()
    @Volatile
    private var tagEpoch = 0L
    private var activeTag: String? = null
    private var tagLoadJob: Job? = null
    private var tagLoadMoreJob: Job? = null
    private var tagRefreshJob: Job? = null
    /**
     * Bumped on every reload/refresh. A [loadMore] started under a previous
     * epoch must NOT flip [exhausted] after a refresh has reset it. `@Volatile`
     * so the guard read on a [processingContext] thread sees main-thread writes.
     */
    @Volatile
    private var epoch = 0L
    /**
     * Subscription-id sequence. `AtomicInteger` because ids are minted from
     * multiple dispatchers — [collectRecipePage] runs on [processingContext]
     * while [loadFeed]/[loadMore] mint from the main thread — and a plain `++`
     * across threads is a data race that can yield duplicate (mis-routed) ids.
     */
    private val subCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Single-flight guard for [preloadCatalog]: the background deep fill runs at
     * most once per process (recipe search triggers it). `AtomicBoolean` so the
     * check-and-flip is atomic — two concurrent callers can't both observe
     * `false` and each launch a job.
     */
    private val catalogPreloaded = java.util.concurrent.atomic.AtomicBoolean(false)
    private var preloadJob: Job? = null

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
        val subId = "recipe-feed-${subCounter.getAndIncrement()}"
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
        val subId = "recipe-more-${subCounter.getAndIncrement()}"
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
        val subId = "recipe-one-${subCounter.getAndIncrement()}"
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
     * Cache-first coordinate lookup for one concrete recipe kind.
     * Falls back to ObjectBox author+kind scan to recover cold-start events.
     */
    fun findRecipeEventByCoordinate(kind: Int, author: String, dTag: String): NostrEvent? {
        val normalizedAuthor = author.trim()
        val normalizedDTag = dTag.trim()
        if (normalizedAuthor.isBlank() || normalizedDTag.isBlank()) return null
        if (RecipeFormats.active.none { it.kind == kind }) return null

        eventRepo.findAddressableEvent(kind, normalizedAuthor, normalizedDTag)?.let { cached ->
            if (RecipeFormats.forEvent(cached) != null) return cached
        }
        val persistence = eventRepo.eventPersistence ?: return null
        val fromDb = persistence.getEventsByAuthorAndKind(normalizedAuthor, kind, limit = 200)
            .filter { eventHasDTag(it, normalizedDTag) }
        return dedupeNewestPerCoordinate(fromDb).firstOrNull()
    }

    /**
     * Resolve one recipe event by exact coordinate (kind+author+d) from the same
     * widened recipe read union used by feed/tag queries.
     */
    suspend fun requestRecipeEventByCoordinate(kind: Int, author: String, dTag: String): NostrEvent? {
        val normalizedAuthor = author.trim()
        val normalizedDTag = dTag.trim()
        if (normalizedAuthor.isBlank() || normalizedDTag.isBlank()) return null
        if (RecipeFormats.active.none { it.kind == kind }) return null

        val cached = findRecipeEventByCoordinate(kind, normalizedAuthor, normalizedDTag)
        if (cached != null) return cached

        val subId = "recipe-coordinate-${subCounter.getAndIncrement()}"
        val matches = mutableListOf<NostrEvent>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.kind != kind || event.pubkey != normalizedAuthor) return@collect
                if (!eventHasDTag(event, normalizedDTag)) return@collect
                if (RecipeFormats.forEvent(event) == null) return@collect
                eventRepo.cacheEvent(event)
                eventRepo.requestProfileIfMissing(event.pubkey)
                matches.add(event)
            }
        }

        val req = ClientMessage.req(
            subId,
            cooking.zap.app.nostr.Filter(
                kinds = listOf(kind),
                authors = listOf(normalizedAuthor),
                dTags = listOf(normalizedDTag),
                limit = 1,
            )
        )
        var sent = 0
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
        }
        try {
            if (sent > 0) {
                subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancel()
            subManager.closeSubscription(subId)
        }

        return dedupeNewestPerCoordinate(matches).firstOrNull()
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

    /**
     * Search the **FULL persisted recipe catalog** (ObjectBox), not just the
     * grid window. Reads every persisted event of each active format's kind
     * (`kind` is `@Index` — cheap, bounded by [limit]), collapses to one event
     * per logical recipe (coordinate newest-wins → cross-format canonical pick,
     * the same two-stage dedup as the live feed), parses via the format
     * registry, and keeps those whose title or summary contains [query]
     * (case-insensitive). Format-agnostic: no hardcoded kind. Newest first.
     *
     * Returns empty when persistence is unavailable or the query is blank.
     */
    suspend fun searchCachedRecipes(
        query: String,
        limit: Int = 2_000,
    ): List<RecipeParser.Recipe> = withContext(processingContext) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        val persistence = eventRepo.eventPersistence ?: return@withContext emptyList()
        val events = RecipeFormats.active.flatMap { persistence.getEventsByKind(it.kind, limit) }
        dedupeAcrossFormats(events) { RecipeFormats.rankOf(it) }
            .mapNotNull { RecipeFormats.forEvent(it)?.parse(it) }
            .filter { recipe ->
                recipe.title?.contains(needle, ignoreCase = true) == true ||
                    recipe.summary?.contains(needle, ignoreCase = true) == true
            }
            .sortedByDescending { it.publishedAt }
    }

    /**
     * Background **deep fill** for recipe search: page the recipe feed all the
     * way back so old recipes (the "Mai Tai" case) land in ObjectBox and become
     * findable by [searchCachedRecipes]. Runs at most ONCE per process
     * (single-flight via [catalogPreloaded]) and is bounded — it stops on
     * genuine exhaustion (a full EOSE round adding nothing new), after two
     * consecutive empty pages (slow-relay stall), or at [maxPages] — so it can
     * never run unbounded. Events persist through the normal [cacheEvent] path;
     * the feed [recipes] flow also fills in as pages land.
     */
    fun preloadCatalog(maxPages: Int = 40, perPage: Int = 100) {
        // Atomic check-and-flip: the first caller wins and launches; any
        // concurrent or later caller sees `true` and returns. Never reset, so
        // the deep fill runs exactly once per process.
        if (!catalogPreloaded.compareAndSet(false, true)) return
        preloadJob = scope.launch(processingContext) {
            var consecutiveEmpty = 0
            var pages = 0
            while (pages < maxPages) {
                // Cursor = oldest event we hold; null on the first page fetches the
                // newest window and seeds the cursor. NIP-01 `until` is on the
                // event timestamp, so page from created_at (not publishedAt).
                val oldest = coordMutex.withLock { byCoordinate.values.minOfOrNull { it.created_at } }
                val until = oldest?.minus(1)
                val result = collectRecipePage(until, perPage)
                pages++
                if (result.newCoordinates == 0) {
                    // Real exhaustion (full EOSE, nothing new) → stop immediately.
                    // A timed-out empty page (slow relay) → allow one retry, then stop.
                    if (result.fullEose || ++consecutiveEmpty >= 2) break
                } else {
                    consecutiveEmpty = 0
                }
            }
        }
    }

    /** One backward page for [preloadCatalog]; same accept/emit path as [loadMore]. */
    private suspend fun collectRecipePage(until: Long?, pageSize: Int): PageResult {
        val subId = "recipe-preload-${subCounter.getAndIncrement()}"
        var newCoordinates = 0
        val seenIds = mutableSetOf<String>()
        val collector = scope.launch(processingContext) {
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
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
        }
        var fullEose = false
        try {
            if (sent > 0) {
                val eoseCount = subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                fullEose = eoseCount >= sent
            }
        } finally {
            collector.cancel()
            subManager.closeSubscription(subId)
        }
        return PageResult(newCoordinates, fullEose)
    }

    /** Outcome of one [collectRecipePage]: new coordinates added + full-EOSE flag. */
    private data class PageResult(val newCoordinates: Int, val fullEose: Boolean)

    /**
     * Load the recipes **authored by** [pubkey] — the user's OWN published
     * recipes (the "My Recipes" sub-tab), distinct from saved recipes. This is
     * the LIVE author query: fan the format-agnostic [RecipeFormat.authorFeedFilter]
     * over the SAME widened read union ([readRelays]) as the main feed, paint
     * cache-first from ObjectBox, then fill with an EOSE-grace window, deduping by
     * coordinate (newest-wins → cross-format canonical pick).
     *
     * **Not** the kind-30004 `zapcooking-my-recipes` pack — that list is an
     * export/share artifact, never the source of truth for display.
     *
     * Switching authors (account switch) clears the prior author's grid; a reload
     * for the same author keeps it painted while the fresh window merges in.
     */
    fun loadAuthoredRecipes(pubkey: String, limit: Int = 200) {
        val author = pubkey.trim()
        authoredLoadJob?.cancel()
        if (author.isBlank()) {
            authoredAuthor = null
            authoredByCoordinate.clear()
            _authoredRecipes.value = emptyList()
            _isAuthoredLoading.value = false
            return
        }
        if (authoredAuthor != author) {
            authoredByCoordinate.clear()
            _authoredRecipes.value = emptyList()
        }
        authoredAuthor = author
        _isAuthoredLoading.value = true

        authoredLoadJob = scope.launch(processingContext) {
            // Cache-first paint: the user's own recipes persist in ObjectBox, so a
            // cold open shows them instantly before the relay round-trip.
            val cached = cachedAuthoredEvents(author)
            if (cached.isNotEmpty() && authoredAuthor == author) {
                authoredCoordMutex.withLock {
                    cached.forEach { acceptAuthored(author, it) }
                    emitAuthored()
                }
            }

            val subId = "recipe-authored-${subCounter.getAndIncrement()}"
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (event.pubkey != author) return@collect
                    if (RecipeFormats.forEvent(event) == null) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    authoredCoordMutex.withLock {
                        if (acceptAuthored(author, event)) emitAuthored()
                    }
                }
            }
            // One author-scoped filter per active format (NIP-23 only today),
            // fanned to the SAME widened union as the main feed.
            val filters = RecipeFormats.active.map { it.authorFeedFilter(author, limit) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in readRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                if (sent > 0) {
                    subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    delay(EOSE_GRACE_MS)
                }
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId)
                _isAuthoredLoading.value = false
            }
        }
    }

    private suspend fun cachedAuthoredEvents(author: String, limit: Int = 2_000): List<NostrEvent> {
        val persistence = eventRepo.eventPersistence ?: return emptyList()
        val events = RecipeFormats.active.flatMap {
            persistence.getEventsByAuthorAndKind(author, it.kind, limit)
        }
        return dedupeAcrossFormats(events) { RecipeFormats.rankOf(it) }
            .filter { it.pubkey == author }
    }

    /** Merge [event] into [authoredByCoordinate] (author-guarded); true iff it became the winner. */
    private fun acceptAuthored(author: String, event: NostrEvent): Boolean {
        if (event.pubkey != author) return false
        val key = recipeCoordinate(event)
        val current = authoredByCoordinate[key]
        val winner = if (current == null) event else preferNewer(current, event)
        if (winner === current) return false
        authoredByCoordinate[key] = winner
        return true
    }

    private fun emitAuthored() {
        _authoredRecipes.value = dedupeAcrossFormats(authoredByCoordinate.values) { RecipeFormats.rankOf(it) }
            .mapNotNull { RecipeFormats.forEvent(it)?.parse(it) }
            .sortedByDescending { it.publishedAt }
    }

    /**
     * Cache-first + union-backed category feed (e.g. "italian"), shared by
     * chips/search tag taps. Results are recipe cards only and dedup newest-wins
     * by replaceable coordinate, then canonicalized across formats.
     */
    fun loadTagFeed(tag: String, limit: Int = 100) {
        val normalizedTag = tag.trim().lowercase()
        if (normalizedTag.isBlank()) return

        tagLoadJob?.cancel()
        tagRefreshJob?.cancel()
        tagLoadMoreJob?.cancel()
        tagEpoch++
        activeTag = normalizedTag
        _isTagLoadingMore.value = false
        _isTagRefreshing.value = false
        _tagExhausted.value = false
        _isTagLoading.value = true
        tagByCoordinate.clear()
        _tagRecipes.value = emptyList()

        // Cache-first: ObjectBox by kind, then Kotlin tag match.
        scope.launch(processingContext) {
            val cached = cachedTagEvents(normalizedTag)
            if (cached.isNotEmpty() && activeTag == normalizedTag) {
                tagCoordMutex.withLock {
                    cached.forEach { acceptTagEvent(it) }
                    emitTagRecipes()
                }
            }
        }
        preloadCatalog()
        tagLoadJob = launchTagNewestWindowQuery(normalizedTag, limit, _isTagLoading)
    }

    /** Pull-to-refresh for the active tag feed. */
    fun refreshTagFeed(limit: Int = 100) {
        val tag = activeTag ?: return
        if (_isTagRefreshing.value) return
        tagLoadJob?.cancel()
        tagRefreshJob?.cancel()
        tagLoadMoreJob?.cancel()
        tagEpoch++
        _isTagLoadingMore.value = false
        _tagExhausted.value = false
        tagRefreshJob = launchTagNewestWindowQuery(tag, limit, _isTagRefreshing)
    }

    /** Backward pagination for the active tag feed. */
    fun loadMoreTagFeed(pageSize: Int = 50) {
        val tag = activeTag ?: return
        if (_isTagLoading.value || _isTagRefreshing.value || _isTagLoadingMore.value || _tagExhausted.value) return
        _isTagLoadingMore.value = true
        val startedEpoch = tagEpoch
        val subId = "recipe-tag-more-${subCounter.getAndIncrement()}"

        tagLoadMoreJob = scope.launch(processingContext) {
            val oldest = tagCoordMutex.withLock { tagByCoordinate.values.minOfOrNull { it.created_at } }
            if (oldest == null) {
                _isTagLoadingMore.value = false
                return@launch
            }

            val until = oldest - 1
            var newCoordinates = 0
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (RecipeFormats.forEvent(event) == null) return@collect
                    if (!eventMatchesCategoryTag(event, tag)) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    tagCoordMutex.withLock {
                        if (acceptTagEvent(event)) {
                            newCoordinates++
                            emitTagRecipes()
                        }
                    }
                }
            }

            val filters = RecipeFormats.active.map { it.tagFeedFilter(tag, pageSize, until) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in readRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            try {
                if (sent > 0) {
                    val eoseCount = subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    if (tagEpoch == startedEpoch && eoseCount >= sent && newCoordinates == 0) {
                        _tagExhausted.value = true
                    }
                }
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId)
                _isTagLoadingMore.value = false
            }
        }
    }

    /**
     * Search the persisted recipe catalog by one recipe category tag.
     * Cache-only and format-agnostic: no hardcoded kind.
     */
    suspend fun searchCachedRecipesByTag(
        tag: String,
        limit: Int = 2_000,
    ): List<RecipeParser.Recipe> = withContext(processingContext) {
        cachedTagEvents(tag, limit)
            .mapNotNull { RecipeFormats.forEvent(it)?.parse(it) }
            .sortedByDescending { it.publishedAt }
    }

    private fun launchTagNewestWindowQuery(
        tag: String,
        limit: Int,
        loadingFlag: MutableStateFlow<Boolean>,
    ): Job {
        val subId = "recipe-tag-feed-${subCounter.getAndIncrement()}"
        loadingFlag.value = true
        return scope.launch(processingContext) {
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (RecipeFormats.forEvent(event) == null) return@collect
                    if (!eventMatchesCategoryTag(event, tag)) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    tagCoordMutex.withLock {
                        if (acceptTagEvent(event)) emitTagRecipes()
                    }
                }
            }

            val filters = RecipeFormats.active.map { it.tagFeedFilter(tag, limit) }
            val req = ClientMessage.req(subId, filters)
            var sent = 0
            for (url in readRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }

            try {
                if (sent > 0) {
                    subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
                    delay(EOSE_GRACE_MS)
                }
            } finally {
                collector.cancel()
                subManager.closeSubscription(subId)
                loadingFlag.value = false
            }
        }
    }

    private suspend fun cachedTagEvents(tag: String, limit: Int = 2_000): List<NostrEvent> {
        val normalizedTag = tag.trim().lowercase()
        if (normalizedTag.isBlank()) return emptyList()
        val persistence = eventRepo.eventPersistence ?: return emptyList()
        val events = RecipeFormats.active.flatMap { persistence.getEventsByKind(it.kind, limit) }
        return dedupeAcrossFormats(events) { RecipeFormats.rankOf(it) }
            .filter { eventMatchesCategoryTag(it, normalizedTag) }
    }

    private fun eventMatchesCategoryTag(event: NostrEvent, tag: String): Boolean {
        val normalizedTag = tag.trim().lowercase()
        if (normalizedTag.isBlank()) return false
        // Fast-path current NIP-23 recipes: derive category from tags only.
        if (event.kind == RecipeParser.RECIPE_KIND && matchesNip23CategoryTag(event, normalizedTag)) {
            return true
        }
        val recipe = RecipeFormats.forEvent(event)?.parse(event) ?: return false
        return recipe.categories.any { it.equals(normalizedTag, ignoreCase = true) }
    }

    private fun eventHasDTag(event: NostrEvent, dTag: String): Boolean {
        val needle = dTag.trim()
        return event.tags.any { it.size >= 2 && it[0] == "d" && it[1].trim() == needle }
    }

    private fun matchesNip23CategoryTag(event: NostrEvent, normalizedTag: String): Boolean {
        val hashtags = event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "t" }
            .map { it[1].trim().lowercase() }
            .toList()
        val root = hashtags.firstOrNull { it in RecipeParser.RECIPE_HASHTAGS } ?: return false
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.trim().orEmpty().lowercase()
        val slugTag = "$root-$dTag"
        val expected = "$root-$normalizedTag"
        return hashtags.any { it != root && it != slugTag && it == expected }
    }

    private fun acceptTagEvent(event: NostrEvent): Boolean {
        val key = recipeCoordinate(event)
        val current = tagByCoordinate[key]
        val winner = if (current == null) event else preferNewer(current, event)
        if (winner === current) return false
        tagByCoordinate[key] = winner
        return true
    }

    private fun emitTagRecipes() {
        _tagRecipes.value = dedupeAcrossFormats(tagByCoordinate.values) { RecipeFormats.rankOf(it) }
            .mapNotNull { RecipeFormats.forEvent(it)?.parse(it) }
            .sortedByDescending { it.publishedAt }
    }

    /** Drop the in-memory feed (e.g. on account switch). */
    fun clear() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob?.cancel()
        preloadJob?.cancel()
        authoredLoadJob?.cancel()
        tagLoadJob?.cancel()
        tagLoadMoreJob?.cancel()
        tagRefreshJob?.cancel()
        epoch++
        tagEpoch++
        byCoordinate.clear()
        authoredByCoordinate.clear()
        tagByCoordinate.clear()
        authoredAuthor = null
        activeTag = null
        _recipes.value = emptyList()
        _authoredRecipes.value = emptyList()
        _tagRecipes.value = emptyList()
        _isLoading.value = false
        _isLoadingMore.value = false
        _isRefreshing.value = false
        _exhausted.value = false
        _isAuthoredLoading.value = false
        _isTagLoading.value = false
        _isTagLoadingMore.value = false
        _isTagRefreshing.value = false
        _tagExhausted.value = false
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
