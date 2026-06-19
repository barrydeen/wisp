package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs [cooking.zap.app.ui.screen.RecipeDetailScreen]. Resolves a single
 * recipe via [RecipeRepository.requestRecipe] (the 1.2 articles-union
 * resolver) and exposes both the parsed [RecipeParser.Recipe] (for the recipe
 * layout) and the raw [NostrEvent] (which the reused engagement bar needs for
 * zap/react/repost actions and counts).
 */
class RecipeDetailViewModel : ViewModel() {

    private val _recipe = MutableStateFlow<RecipeParser.Recipe?>(null)
    val recipe: StateFlow<RecipeParser.Recipe?> = _recipe

    /** The raw event behind [recipe] — engagement actions/counts key off this. */
    private val _event = MutableStateFlow<NostrEvent?>(null)
    val event: StateFlow<NostrEvent?> = _event

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var loadedKey: String? = null

    fun load(author: String, dTag: String, recipeRepo: RecipeRepository, eventRepo: EventRepository) {
        val key = "$author:$dTag"
        if (loadedKey == key && _recipe.value != null) return
        loadedKey = key
        _isLoading.value = true
        viewModelScope.launch {
            val resolved = recipeRepo.requestRecipe(author, dTag)
            _recipe.value = resolved
            _event.value = eventRepo.findAddressableEvent(RecipeParser.RECIPE_KIND, author, dTag)
            _isLoading.value = false
        }
    }
}
