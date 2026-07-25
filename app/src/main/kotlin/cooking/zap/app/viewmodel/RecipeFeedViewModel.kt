package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.RecipeRepository
import cooking.zap.app.repo.RecipeTrendCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Recipes feed — recipe cards ONLY (kind 30023 `#t zapcooking`/
 * `nostrcooking`). A thin observer over [RecipeRepository] (concern 1.2),
 * which owns the `ARTICLES_RELAYS` union read and the `kind:author:dTag`
 * newest-wins dedup.
 *
 * Social `#foodstr` notes are deliberately NOT here — they live in the
 * OnlyFood feed ([OnlyFoodFeedViewModel], concern 1.6). Recipes and notes are
 * separate feeds so the same post never appears in two places. (Until 1.6
 * this screen also merged notes in; that merge was removed in the un-merge.)
 */
class RecipeFeedViewModel : ViewModel() {

    private val _recipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val recipes: StateFlow<List<RecipeParser.Recipe>> = _recipes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /**
     * The header trend pill's state — its own flow, never merged into the four
     * feed flows above. See [loadTrend] for the isolation contract.
     *
     * Seeded synchronously from the process-scoped cache, so this starts at
     * [RecipeTrendState.Loading] only on a genuinely cold cache. This
     * ViewModel is scoped to the Recipes back-stack entry and is recreated on
     * navigate-away-and-back; without the seed, every return from a recipe
     * detail would re-enter Loading and flash the header's reserved slot
     * before a cache hit resolved through a coroutine.
     */
    private val _trend = MutableStateFlow(seedFrom(RecipeTrendCache.shared))
    val trend: StateFlow<RecipeTrendState> = _trend

    private var repo: RecipeRepository? = null
    private var started = false

    private var trendApi: ZapCookingApi? = null
    private var trendCache: RecipeTrendCache = RecipeTrendCache.shared
    private var trendStarted = false

    fun load(recipeRepo: RecipeRepository) {
        if (started) return
        started = true
        repo = recipeRepo
        viewModelScope.launch { recipeRepo.recipes.collect { _recipes.value = it } }
        viewModelScope.launch { recipeRepo.isLoading.collect { _isLoading.value = it } }
        viewModelScope.launch { recipeRepo.isLoadingMore.collect { _isLoadingMore.value = it } }
        viewModelScope.launch { recipeRepo.exhausted.collect { _exhausted.value = it } }
        viewModelScope.launch { recipeRepo.isRefreshing.collect { _isRefreshing.value = it } }
        recipeRepo.loadFeed()
    }

    /**
     * Fetch the next (older) page. Safe to call repeatedly from the grid's
     * scroll-end trigger — the repository is single-flight and no-ops once
     * exhausted.
     */
    fun loadMore() {
        repo?.loadMore()
    }

    /**
     * Start observing the weekly new-recipe stats. Separate from [load] on
     * purpose: that method's `started` guard is the feed's one-shot lifecycle,
     * and folding the trend into it would tie a decorative signal to the feed's
     * fate in both directions.
     *
     * The isolation contract, which the rest of this class must not break: the
     * trend never touches [isLoading], [isLoadingMore], [exhausted] or
     * [isRefreshing], and no trend request can block, delay or fail recipe
     * loading. Every failure resolves to [RecipeTrendState.Hidden] (or holds
     * the last good value) — nothing is surfaced as an error.
     *
     * **One-shot by design, with no retry.** A first composition while offline
     * leaves the pill hidden for the rest of this ViewModel's life; recovery is
     * pull-to-refresh (which forces a fetch) or re-entry (which recreates this
     * ViewModel). Connectivity-aware retry would need a lifecycle observer,
     * backoff and cancellation semantics for a decorative element — and the
     * only clean trigger available is watching the feed succeed, which is
     * exactly the coupling the isolation contract above forbids.
     *
     * [cache] is injectable for tests only; production always gets the
     * process-wide instance, which is what makes this safe to call again after
     * a back-nav recreates this ViewModel — any successful fetch anywhere in
     * the process seeds every later entry.
     */
    fun loadTrend(api: ZapCookingApi, cache: RecipeTrendCache = RecipeTrendCache.shared) {
        if (trendStarted) return
        trendStarted = true
        trendApi = api
        trendCache = cache
        // Seed again from the cache actually in use: the construction-time
        // seed can only see the process-wide instance.
        if (_trend.value is RecipeTrendState.Loading) _trend.value = seedFrom(cache)
        fetchTrend(force = false)
    }

    /**
     * Project the cache into [trend]. Its own coroutine, awaited by nobody —
     * the feed never waits on this and this never waits on the feed. A no-op
     * until [loadTrend] has supplied an api.
     */
    private fun fetchTrend(force: Boolean) {
        val api = trendApi ?: return
        viewModelScope.launch {
            val weeks = trendCache.weeks(api, force = force)
            _trend.value = RecipeTrendState.reduce(weeks, _trend.value)
        }
    }

    private companion object {
        /**
         * Initial trend state for a given cache: [RecipeTrendState.Visible]
         * when a good value is already retained, otherwise
         * [RecipeTrendState.Loading].
         *
         * A cold cache must stay [RecipeTrendState.Loading] rather than
         * reducing straight to [RecipeTrendState.Hidden] — that is the state
         * the header reserves space on, and collapsing before the first fetch
         * has even run would reintroduce the reflow the reservation exists to
         * prevent.
         */
        fun seedFrom(cache: RecipeTrendCache): RecipeTrendState =
            cache.peek()
                ?.let { RecipeTrendState.reduce(it, RecipeTrendState.Loading) }
                ?: RecipeTrendState.Loading
    }

    /** Pull-to-refresh: re-pull the newest window; new recipes surface at top. */
    fun refresh() {
        repo?.refresh()
        // Refreshed alongside the feed, not as part of it: an explicit user
        // refresh bypasses the trend's TTL, but the two run independently and
        // neither awaits the other.
        fetchTrend(force = true)
    }
}
