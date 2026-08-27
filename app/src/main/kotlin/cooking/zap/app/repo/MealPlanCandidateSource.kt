package cooking.zap.app.repo

import android.util.Log
import cooking.zap.app.mealplan.MealPlanCandidates
import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip23RecipeFormat
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * I/O half of Cheffy candidate discovery. Pure event→candidate mapping lives
 * in [MealPlanCandidates]; this class runs the dedicated bounded queries so
 * Cheffy's set does not silently depend on whether the user opened Recipes
 * or My Recipes, and so [RecipeRepository.loadAuthoredRecipes] is never
 * called (that cancels the tab's `loadJob` and flips its spinner).
 *
 * Owned by [cooking.zap.app.viewmodel.FeedViewModel]; [cooking.zap.app.viewmodel.CheffyPlanViewModel]
 * is the first caller.
 */
class MealPlanCandidateSource(
    private val recipeRepo: RecipeRepository,
    private val eventRepo: EventRepository,
    private val bookmarkRepo: RecipeBookmarkRepository,
    private val relayPool: RelayPool,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
    private val userReadRelaysProvider: () -> List<String> = { emptyList() },
) {
    companion object {
        private const val TAG = "CheffyPlan"
        private const val EOSE_GRACE_MS = 2_000L
    }

    private val subCounter = AtomicInteger(0)

    /**
     * Discover wire-shaped candidates for [source]. [limit] is applied after
     * source-specific caps (explore 150, my-recipes 200). Phase 5 should pass
     * a generous limit and let [MealPlanGeneration.filterRecipeCandidates]
     * cap at 48 — truncating here would feed Cheffy an arbitrary slice.
     */
    suspend fun collect(
        source: MealPlanGeneration.RecipeSource,
        userPubkey: String,
        limit: Int,
    ): List<MealPlanCandidates.Candidate> {
        if (limit <= 0) return emptyList()
        val found = when (source) {
            MealPlanGeneration.RecipeSource.MY_RECIPES -> loadMyRecipes(userPubkey)
            MealPlanGeneration.RecipeSource.SAVED -> loadSaved()
            MealPlanGeneration.RecipeSource.EXPLORE -> loadExplore()
            MealPlanGeneration.RecipeSource.ALL -> MealPlanCandidates.mergeLoaders(
                listOf(
                    { loadMyRecipes(userPubkey) },
                    { loadSaved() },
                    { loadExplore() },
                ),
            )
        }
        return found.take(limit)
    }

    /**
     * Dedicated authored query (kind 30023, #t recipe tags, authors =
     * [userPubkey], limit 200, 8s EOSE + grace). Cache-first from ObjectBox.
     * Does **not** touch [RecipeRepository.loadAuthoredRecipes].
     */
    private suspend fun loadMyRecipes(userPubkey: String): List<MealPlanCandidates.Candidate> {
        val author = userPubkey.trim()
        if (author.isEmpty()) return emptyList()
        return loadWindow(
            cache = {
                eventRepo.eventPersistence
                    ?.getEventsByAuthorAndKind(author, RecipeParser.RECIPE_KIND, MealPlanCandidates.MY_RECIPES_LIMIT)
                    .orEmpty()
                    .filter { it.pubkey == author }
            },
            filters = listOf(
                Nip23RecipeFormat.authorFeedFilter(author, MealPlanCandidates.MY_RECIPES_LIMIT),
            ),
            subPrefix = "cheffy-authored",
            cap = MealPlanCandidates.MY_RECIPES_LIMIT,
            authorGuard = author,
        )
    }

    /**
     * Dedicated explore query ([Nip23RecipeFormat.feedFilter], limit 150).
     * Does **not** read [RecipeRepository.recipes] — that corpus is the live
     * feed window plus paging, not a stable Cheffy set.
     */
    private suspend fun loadExplore(): List<MealPlanCandidates.Candidate> = loadWindow(
        cache = {
            eventRepo.eventPersistence
                ?.getEventsByKind(RecipeParser.RECIPE_KIND, MealPlanCandidates.EXPLORE_LIMIT)
                .orEmpty()
        },
        filters = listOf(Nip23RecipeFormat.feedFilter(MealPlanCandidates.EXPLORE_LIMIT)),
        subPrefix = "cheffy-explore",
        cap = MealPlanCandidates.EXPLORE_LIMIT,
        authorGuard = null,
    )

    /**
     * Saved lists currently in memory. Does not call
     * [RecipeBookmarkRepository.load] — that would cancel an in-flight
     * cookbook tab query the same way authored load would. Phase 5 can
     * pass already-loaded lists; login already paints them.
     *
     * Cache lookup is [RecipeRepository.findRecipeEventByCoordinate]: the
     * in-memory path already applies [RecipeFormats.forEvent] AND the deleted
     * filter; the ObjectBox fallback applies deleted but not the format gate.
     * We re-apply format + deleted + [HiddenRecipes] on every hit.
     */
    private suspend fun loadSaved(): List<MealPlanCandidates.Candidate> {
        return MealPlanCandidates.resolveSaved(
            lists = bookmarkRepo.lists.value,
            cacheLookup = { kind, author, dTag ->
                recipeRepo.findRecipeEventByCoordinate(kind, author, dTag)
            },
            networkFetch = { kind, author, dTag ->
                recipeRepo.requestRecipeEventByCoordinate(kind, author, dTag)
            },
            isUsable = ::isUsable,
            onDrop = { a -> Log.w(TAG, "Dropped unresolved saved coordinate $a") },
        )
    }

    private suspend fun loadWindow(
        cache: () -> List<NostrEvent>,
        filters: List<Filter>,
        subPrefix: String,
        cap: Int,
        authorGuard: String?,
    ): List<MealPlanCandidates.Candidate> = withContext(processingContext) {
        val byA = LinkedHashMap<String, NostrEvent>()
        fun consider(event: NostrEvent) {
            if (authorGuard != null && event.pubkey != authorGuard) return
            if (!isUsable(event)) return
            val key = MealPlanCandidates.coordinateFromEvent(event) ?: return
            val current = byA[key]
            if (current == null || event.created_at > current.created_at ||
                (event.created_at == current.created_at && event.id < current.id)
            ) {
                byA[key] = event
            }
        }
        cache().forEach(::consider)

        val subId = "$subPrefix-${subCounter.getAndIncrement()}"
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (!isUsable(event)) return@collect
                eventRepo.cacheEvent(event)
                consider(event)
            }
        }
        val req = ClientMessage.req(subId, filters)
        var sent = 0
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
        }
        try {
            if (sent > 0) {
                subManager.awaitEoseCount(
                    subId,
                    expectedCount = sent,
                    timeoutMs = MealPlanCandidates.FETCH_TIMEOUT_MS,
                )
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancelAndJoin()
            subManager.closeSubscription(subId)
        }
        byA.values
            .sortedByDescending { it.created_at }
            .take(cap)
            .mapNotNull { MealPlanCandidates.candidateFromEvent(it) }
    }

    /**
     * Format gate + deleted-event filter + HiddenRecipes. Applied on every
     * path, including cache-first reads: these candidates are written into a
     * signed encrypted plan, so a stale hidden/deleted cache hit is worse
     * here than in a feed.
     *
     * Explore ObjectBox (`getEventsByKind`) does not apply any of the three
     * on its own — all three are added here. Saved cache-first via
     * [RecipeRepository.findRecipeEventByCoordinate] already applies format
     * (in-memory path) and deleted (both paths); we still re-check all three
     * so the ObjectBox fallback cannot leak a non-recipe or a hidden coord.
     */
    private fun isUsable(event: NostrEvent): Boolean {
        if (RecipeFormats.forEvent(event) == null) return false
        if (eventRepo.deletedEventsRepo?.isEventDeleted(event) == true) return false
        return MealPlanCandidates.coordinateFromEvent(event) != null
    }

    /** Same widened recipe read union as [RecipeRepository] (copied, not called). */
    private fun readRelays(): List<String> {
        val union = LinkedHashSet<String>()
        fun add(url: String) { union.add(url.trim().trimEnd('/')) }
        RelayConfig.ARTICLES_RELAYS.forEach(::add)
        RelayConfig.DEFAULT_INDEXER_RELAYS.forEach(::add)
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }
}
