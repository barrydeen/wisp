package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.repo.NourishRepository
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
 */
class NourishExploreViewModel : ViewModel() {

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

    private var nourishRepo: NourishRepository? = null
    private var loadJob: Job? = null
    private var loadGen = 0
    private var started = false

    fun init(nourishRepo: NourishRepository) {
        this.nourishRepo = nourishRepo
        if (!started) {
            started = true
            loadRecipes()
        }
    }

    fun setSort(dim: NourishDiscovery.SortDimension) {
        if (_ui.value.sortBy == dim) return
        _ui.update { it.copy(sortBy = dim, recipes = sortRecipes(it.recipes, dim)) }
    }

    fun toggleChip(chipId: String) {
        val next = _ui.value.activeChipIds.toMutableSet()
        if (!next.add(chipId)) next.remove(chipId)
        _ui.update { it.copy(activeChipIds = next) }
        loadRecipes()
    }

    fun retry() = loadRecipes()

    fun loadRecipes() {
        val repo = nourishRepo ?: return
        val gen = ++loadGen
        loadJob?.cancel()

        val labels = NourishDiscovery.labelsFromChipIds(_ui.value.activeChipIds)
        val cacheKey = NourishDiscovery.filterCacheKey(labels)

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
                // Re-read chips after any await — a newer toggle may have changed them.
                val latestLabels = NourishDiscovery.labelsFromChipIds(_ui.value.activeChipIds)
                val result = repo.fetchRankedRecipes(
                    sortBy = NourishDiscovery.SortDimension.OVERALL,
                    limit = 40,
                    filters = latestLabels,
                )
                if (gen != loadGen) return@launch
                val stillSame =
                    NourishDiscovery.filterCacheKey(latestLabels) ==
                        NourishDiscovery.filterCacheKey(
                            NourishDiscovery.labelsFromChipIds(_ui.value.activeChipIds),
                        )
                if (!stillSame) return@launch

                _ui.update {
                    it.copy(
                        recipes = sortRecipes(result.recipes, it.sortBy),
                        degraded = result.degraded,
                        shownCacheKey = NourishDiscovery.filterCacheKey(latestLabels),
                        loading = false,
                        refreshing = false,
                        error = false,
                    )
                }
            } catch (_: Exception) {
                if (gen != loadGen) return@launch
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
}
