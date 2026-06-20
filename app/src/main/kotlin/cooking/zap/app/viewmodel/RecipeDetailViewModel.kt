package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.NourishRepository
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

    /** Nourish health score (concern 2.4a). Null = none / READ_ONLY / miss → quiet absence. */
    private val _nourish = MutableStateFlow<NourishScore?>(null)
    val nourish: StateFlow<NourishScore?> = _nourish

    private var loadedKey: String? = null

    fun load(
        author: String,
        dTag: String,
        recipeRepo: RecipeRepository,
        eventRepo: EventRepository,
        nourishRepo: NourishRepository,
        hasSigningKey: Boolean,
    ) {
        val key = "$author:$dTag"
        if (loadedKey == key && _recipe.value != null) return
        loadedKey = key
        _isLoading.value = true
        _nourish.value = null
        viewModelScope.launch {
            val resolved = recipeRepo.requestRecipe(author, dTag)
            if (loadedKey != key) return@launch // a newer recipe was requested — drop stale result
            _recipe.value = resolved
            _event.value = eventRepo.findAddressableEvent(RecipeParser.RECIPE_KIND, author, dTag)
            _isLoading.value = false
        }
        // Nourish read runs independently (auth'd Pantry round-trip) — never
        // blocks the recipe, never surfaces an error. Null → render nothing.
        viewModelScope.launch {
            val score = runCatching { nourishRepo.fetchScore(author, dTag, hasSigningKey) }.getOrNull()
            if (loadedKey != key) return@launch // drop stale Nourish result on fast navigation
            _nourish.value = score
        }
    }
}
