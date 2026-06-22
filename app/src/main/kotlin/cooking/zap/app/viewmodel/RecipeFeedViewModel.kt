package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.RecipeRepository
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

    private var repo: RecipeRepository? = null
    private var started = false

    fun load(recipeRepo: RecipeRepository) {
        if (started) return
        started = true
        repo = recipeRepo
        viewModelScope.launch { recipeRepo.recipes.collect { _recipes.value = it } }
        viewModelScope.launch { recipeRepo.isLoading.collect { _isLoading.value = it } }
        viewModelScope.launch { recipeRepo.isLoadingMore.collect { _isLoadingMore.value = it } }
        viewModelScope.launch { recipeRepo.exhausted.collect { _exhausted.value = it } }
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
}
