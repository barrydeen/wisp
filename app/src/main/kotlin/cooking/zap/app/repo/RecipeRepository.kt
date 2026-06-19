package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
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

    private val _recipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    /** The deduped, `publishedAt`-desc recipe feed. Single source of truth. */
    val recipes: StateFlow<List<RecipeParser.Recipe>> = _recipes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadJob: Job? = null
    private var subCounter = 0

    /**
     * Fan out the recipe feed filter to the `articles` relay union, collect
     * matching 30023 recipes until EOSE/timeout, dedup by coordinate, and
     * publish to [recipes]. Cancels any in-flight load first.
     */
    fun loadFeed(limit: Int = 100) {
        loadJob?.cancel()
        val subId = "recipe-feed-${subCounter++}"
        _isLoading.value = true
        loadJob = scope.launch(processingContext) {
            val seenIds = mutableSetOf<String>()
            val collector = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val event = relayEvent.event
                    if (event.id in seenIds) return@collect
                    if (!RecipeParser.isRecipe(event)) return@collect
                    seenIds.add(event.id)
                    eventRepo.cacheEvent(event)
                    eventRepo.requestProfileIfMissing(event.pubkey)
                    if (acceptEvent(event)) emitRecipes()
                }
            }
            val filter = Filter(
                kinds = listOf(RecipeParser.RECIPE_KIND),
                tTags = RecipeParser.RECIPE_HASHTAGS,
                limit = limit,
            )
            val req = ClientMessage.req(subId, filter)
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
     * Resolve a single recipe by its addressable coordinate (the `naddr`
     * 1.3 navigates to). Cache-first; on a miss, REQ the `articles` union —
     * NOT [EventRepository.requestAddressableEvent], which routes to general
     * top relays where recipes may not live. Returns null if not found.
     */
    suspend fun requestRecipe(author: String, dTag: String): RecipeParser.Recipe? {
        eventRepo.findAddressableEvent(RecipeParser.RECIPE_KIND, author, dTag)?.let {
            return RecipeParser.parse(it)
        }
        val subId = "recipe-one-${subCounter++}"
        var best: NostrEvent? = null
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.kind != RecipeParser.RECIPE_KIND || event.pubkey != author) return@collect
                if (event.tags.none { it.size >= 2 && it[0] == "d" && it[1] == dTag }) return@collect
                eventRepo.cacheEvent(event)
                best = best?.let { preferNewer(it, event) } ?: event
            }
        }
        val filter = Filter(
            kinds = listOf(RecipeParser.RECIPE_KIND),
            authors = listOf(author),
            dTags = listOf(dTag),
            limit = 1,
        )
        val req = ClientMessage.req(subId, filter)
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
        return best?.let { RecipeParser.parse(it) }
    }

    /** Drop the in-memory feed (e.g. on account switch). */
    fun clear() {
        loadJob?.cancel()
        byCoordinate.clear()
        _recipes.value = emptyList()
        _isLoading.value = false
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
        _recipes.value = byCoordinate.values
            .map { RecipeParser.parse(it) }
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
