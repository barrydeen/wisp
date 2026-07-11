package cooking.zap.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.repo.NourishRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Nourish Explore — ranked/filtered discovery of analyzed recipes.
 * Ports web `/nourish/explore` behavior: client-side sort, AND filter chips,
 * SWR cache paint, never-clobber empty revalidate.
 *
 * Chip toggles always refetch with the **post-toggle** label set (web's
 * `toggleChip` → `loadRecipes()`). Sort is the only client-side-only path.
 */
class NourishExploreViewModel(
    /**
     * Optional test seam — when non-null, [init] is unnecessary and loads go
     * through this fetcher. Production uses the no-arg ctor + [init].
     */
    private val fetchRankedOverride: (
        suspend (
            sortBy: NourishDiscovery.SortDimension,
            limit: Int,
            filters: List<String>,
        ) -> NourishDiscovery.DiscoveryResult
    )? = null,
) : ViewModel() {

    data class UiState(
        val recipes: List<NourishDiscovery.RankedRecipe> = emptyList(),
        val sortBy: NourishDiscovery.SortDimension = NourishDiscovery.SortDimension.OVERALL,
        val activeChipIds: Set<String> = emptySet(),
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val error: Boolean = false,
        val degraded: Boolean = false,
        val shownCacheKey: String = "",
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var fetchRanked: (
        suspend (
            sortBy: NourishDiscovery.SortDimension,
            limit: Int,
            filters: List<String>,
        ) -> NourishDiscovery.DiscoveryResult
    )? = fetchRankedOverride

    private var loadJob: Job? = null
    private var loadGen = 0
    private var started = false

    fun init(nourishRepo: NourishRepository) {
        if (fetchRanked == null) {
            fetchRanked = { sortBy, limit, filters ->
                nourishRepo.fetchRankedRecipes(sortBy, limit, filters)
            }
        }
        if (!started) {
            started = true
            loadRecipes(chipIds = _ui.value.activeChipIds)
        }
    }

    /** Test helper — wire a fetcher and mark initialized without a repository. */
    internal fun initForTest(
        fetch: suspend (
            sortBy: NourishDiscovery.SortDimension,
            limit: Int,
            filters: List<String>,
        ) -> NourishDiscovery.DiscoveryResult,
        autoLoad: Boolean = false,
    ) {
        fetchRanked = fetch
        if (autoLoad && !started) {
            started = true
            loadRecipes(chipIds = _ui.value.activeChipIds)
        } else {
            started = true
        }
    }

    fun setSort(dim: NourishDiscovery.SortDimension) {
        if (_ui.value.sortBy == dim) return
        _ui.update { it.copy(sortBy = dim, recipes = sortRecipes(it.recipes, dim)) }
    }

    fun toggleChip(chipId: String) {
        val current = _ui.value.activeChipIds
        val next = if (chipId in current) current - chipId else current + chipId
        // Commit chip state, then refetch with the *same* post-toggle set.
        // Do not re-derive labels from StateFlow inside the coroutine — that
        // raced and could send an unfiltered REQ (or skip) while the chips
        // already looked selected.
        _ui.update { it.copy(activeChipIds = next) }
        logD("toggleChip id=$chipId → active=$next")
        loadRecipes(chipIds = next)
    }

    fun retry() = loadRecipes(chipIds = _ui.value.activeChipIds)

    /**
     * @param chipIds post-toggle (or current) chip id set — source of truth
     *   for the filter labels of this load. Callers that just flipped a chip
     *   must pass the new set explicitly.
     */
    fun loadRecipes(chipIds: Set<String> = _ui.value.activeChipIds) {
        val fetch = fetchRanked ?: run {
            logW("loadRecipes skipped — fetcher not initialized")
            return
        }
        val gen = ++loadGen
        loadJob?.cancel()

        // Capture labels for THIS generation — immutable snapshot.
        val labels = NourishDiscovery.labelsFromChipIds(chipIds)
        val cacheKey = NourishDiscovery.filterCacheKey(labels)
        logD(
            "loadRecipes gen=$gen chips=$chipIds labels=$labels key='${cacheKey.ifEmpty { "(none)" }}'",
        )

        // SWR: paint cached results for THIS filter set immediately.
        val cached = NourishDiscovery.peekDiscoveryCache(labels)
        if (cached != null && cached.recipes.isNotEmpty()) {
            _ui.update {
                it.copy(
                    recipes = sortRecipes(cached.recipes, it.sortBy),
                    degraded = cached.degraded,
                    shownCacheKey = cacheKey,
                    loading = false,
                    refreshing = true,
                    error = false,
                )
            }
        } else {
            _ui.update {
                val clearPrior = it.shownCacheKey != cacheKey
                it.copy(
                    recipes = if (clearPrior) emptyList() else it.recipes,
                    degraded = if (clearPrior) false else it.degraded,
                    shownCacheKey = cacheKey,
                    loading = clearPrior || it.recipes.isEmpty(),
                    refreshing = !clearPrior && it.recipes.isNotEmpty(),
                    error = false,
                )
            }
        }

        loadJob = viewModelScope.launch {
            try {
                val result = fetch(
                    NourishDiscovery.SortDimension.OVERALL,
                    40,
                    labels,
                )
                if (gen != loadGen) {
                    logD("loadRecipes gen=$gen dropped (stale; current=$loadGen)")
                    return@launch
                }
                // Drop if the user toggled chips again to a different set.
                val stillActive = NourishDiscovery.filterCacheKey(labels) ==
                    NourishDiscovery.filterCacheKey(
                        NourishDiscovery.labelsFromChipIds(_ui.value.activeChipIds),
                    )
                if (!stillActive) {
                    logD("loadRecipes gen=$gen dropped (chip set changed)")
                    return@launch
                }

                logD(
                    "loadRecipes gen=$gen done count=${result.recipes.size} " +
                        "degraded=${result.degraded} refreshMiss=${result.refreshMiss}",
                )
                _ui.update {
                    it.copy(
                        recipes = sortRecipes(result.recipes, it.sortBy),
                        degraded = result.degraded,
                        shownCacheKey = cacheKey,
                        loading = false,
                        refreshing = false,
                        error = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != loadGen) return@launch
                logE("loadRecipes gen=$gen failed labels=$labels", e)
                _ui.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = it.recipes.isEmpty(),
                    )
                }
            }
        }
    }

    private fun sortRecipes(
        recipes: List<NourishDiscovery.RankedRecipe>,
        sortBy: NourishDiscovery.SortDimension,
    ): List<NourishDiscovery.RankedRecipe> =
        recipes.sortedWith(
            compareByDescending<NourishDiscovery.RankedRecipe> {
                NourishDiscovery.getDimensionScore(it.score, sortBy)
            }.thenByDescending { it.createdAt },
        )

    companion object {
        private const val TAG = "NourishExplore"

        /** android.util.Log is unmocked on the JVM unit classpath — swallow. */
        private fun logD(msg: String) {
            try {
                Log.d(TAG, msg)
            } catch (_: RuntimeException) {
            }
        }

        private fun logW(msg: String) {
            try {
                Log.w(TAG, msg)
            } catch (_: RuntimeException) {
            }
        }

        private fun logE(msg: String, e: Exception) {
            try {
                Log.e(TAG, msg, e)
            } catch (_: RuntimeException) {
            }
        }
    }
}
