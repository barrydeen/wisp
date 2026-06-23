package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Shared recipe-by-tag feed state used by both:
 * - Recipes grid category chips
 * - Search Tags tab results
 */
class RecipeTagFeedViewModel : ViewModel() {

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

    private var repo: RecipeRepository? = null
    private var collectorsStarted = false
    private var loadedTag: String? = null

    fun load(tag: String, recipeRepo: RecipeRepository) {
        repo = recipeRepo
        if (!collectorsStarted) {
            collectorsStarted = true
            viewModelScope.launch { recipeRepo.tagRecipes.collect { _recipes.value = it } }
            viewModelScope.launch { recipeRepo.isTagLoading.collect { _isLoading.value = it } }
            viewModelScope.launch { recipeRepo.isTagLoadingMore.collect { _isLoadingMore.value = it } }
            viewModelScope.launch { recipeRepo.tagExhausted.collect { _exhausted.value = it } }
            viewModelScope.launch { recipeRepo.isTagRefreshing.collect { _isRefreshing.value = it } }
        }
        val normalizedTag = tag.trim().lowercase()
        if (loadedTag == normalizedTag) return
        loadedTag = normalizedTag
        recipeRepo.loadTagFeed(normalizedTag)
    }

    fun refresh() {
        repo?.refreshTagFeed()
    }

    fun loadMore() {
        repo?.loadMoreTagFeed()
    }
}
