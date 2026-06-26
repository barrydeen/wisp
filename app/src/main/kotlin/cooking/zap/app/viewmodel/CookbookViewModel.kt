package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the **Cookbook → Saved** sub-tab (A14 PR 3b-i). It does not own the
 * recipe-list data — those live in [RecipeBookmarkRepository] (PR 3a), already
 * loaded at login — it just re-exposes them and lazily resolves a cover image
 * per list via [CookbookCovers] (which reuses the existing recipe lookups).
 *
 * Read-only: no writes here. List management (rename/delete/cover) is PR 3b-iii.
 */
class CookbookViewModel : ViewModel() {
    private val _lists = MutableStateFlow<List<CookbookList>>(emptyList())
    /** The user's recipe collections — default Saved list first (from PR 3a). */
    val lists: StateFlow<List<CookbookList>> = _lists

    private val _covers = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** Resolved cover URL per list `d`-tag; absent until resolved, null when none resolves. */
    val covers: StateFlow<Map<String, String?>> = _covers

    private var bound = false

    /**
     * Mirror [bookmarkRepo]'s lists into [lists] and resolve covers as they
     * arrive. Idempotent — safe to call from a `LaunchedEffect` on every entry.
     */
    fun bind(bookmarkRepo: RecipeBookmarkRepository, recipeRepo: RecipeRepository) {
        if (bound) return
        bound = true
        viewModelScope.launch {
            bookmarkRepo.lists.collect { lists ->
                _lists.value = lists
                lists.forEach { list -> resolveCover(list, recipeRepo) }
            }
        }
    }

    /**
     * Resolve [list]'s cover once. Re-attempts only when the prior result was
     * null (e.g. the cover recipe wasn't cached yet) so a later relay-fill can
     * still surface it, without re-fetching covers that already resolved.
     */
    private fun resolveCover(list: CookbookList, recipeRepo: RecipeRepository) {
        if (_covers.value[list.dTag] != null) return
        viewModelScope.launch {
            val url = CookbookCovers.resolve(list, recipeRepo)
            _covers.value = _covers.value + (list.dTag to url)
        }
    }
}
