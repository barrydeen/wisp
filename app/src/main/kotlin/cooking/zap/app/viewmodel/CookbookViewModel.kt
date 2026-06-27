package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the **Cookbook** tab's two sub-tabs:
 *  - **Saved** (PR 3b-i) — re-exposes the user's kind-30001 recipe lists from
 *    [RecipeBookmarkRepository] (PR 3a, already loaded at login) and lazily
 *    resolves a cover image per list via [CookbookCovers].
 *  - **My Recipes** (PR 3b-ii) — the user's OWN published recipes via the LIVE
 *    author query ([RecipeRepository.loadAuthoredRecipes]); kicked off lazily the
 *    first time the sub-tab is shown.
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

    private val _authoredRecipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    /** The signed-in user's OWN published recipes (My Recipes), mirrored from the repo. */
    val authoredRecipes: StateFlow<List<RecipeParser.Recipe>> = _authoredRecipes

    private val _isAuthoredLoading = MutableStateFlow(false)
    val isAuthoredLoading: StateFlow<Boolean> = _isAuthoredLoading

    private var bound = false

    // Set on bind() to drive the lazy My Recipes query. The pubkey is read
    // through a PROVIDER (not captured) so a late sign-in / account switch is
    // always reflected — bind() can't re-capture a value (collectors set once).
    private var recipeRepo: RecipeRepository? = null
    private var userPubkeyProvider: () -> String? = { null }
    /** Pubkey the authored query last ran for; reload when it changes (account switch). */
    private var lastRequestedPubkey: String? = null

    /**
     * `d`-tags with an in-flight cover resolution. Guards against a fresh `lists`
     * emission launching a duplicate coroutine (and duplicate relay fetch) for a
     * cover that's still resolving. Only touched on the viewModelScope (main)
     * dispatcher, so a plain set is safe.
     */
    private val resolvingCovers = mutableSetOf<String>()

    /**
     * Mirror [bookmarkRepo]'s lists into [lists] (resolving covers as they
     * arrive) and the repo's authored-recipe flows into [authoredRecipes] /
     * [isAuthoredLoading]. [userPubkeyProvider] is read live (not captured) so a
     * late sign-in / account switch is reflected. Re-binding refreshes the
     * provider/repo; the flow collectors are wired exactly once.
     */
    fun bind(
        bookmarkRepo: RecipeBookmarkRepository,
        recipeRepo: RecipeRepository,
        userPubkeyProvider: () -> String?,
    ) {
        this.recipeRepo = recipeRepo
        this.userPubkeyProvider = userPubkeyProvider
        if (bound) return
        bound = true
        viewModelScope.launch {
            bookmarkRepo.lists.collect { lists ->
                _lists.value = lists
                lists.forEach { list -> resolveCover(list, recipeRepo) }
            }
        }
        viewModelScope.launch {
            recipeRepo.authoredRecipes.collect { _authoredRecipes.value = it }
        }
        viewModelScope.launch {
            recipeRepo.isAuthoredLoading.collect { _isAuthoredLoading.value = it }
        }
    }

    /**
     * Kick off the LIVE author query for the current user when My Recipes is
     * shown (lazy — Saved is the default landing, so this avoids a query the
     * user may never need). No-op when signed-out (no pubkey) or already loaded
     * for this pubkey; reloads when the pubkey changed (account switch).
     */
    fun requestMyRecipes() {
        val pubkey = userPubkeyProvider().orEmpty().trim().takeIf { it.isNotEmpty() } ?: return
        val repo = recipeRepo ?: return
        if (pubkey == lastRequestedPubkey) return
        lastRequestedPubkey = pubkey
        repo.loadAuthoredRecipes(pubkey)
    }

    /**
     * Resolve [list]'s cover. Skips if a URL already resolved, or if a resolution
     * for this `d`-tag is already in-flight (so a re-emission of `lists` can't
     * launch a duplicate coroutine / relay fetch). A prior null result is allowed
     * to re-attempt — a later relay-fill of the cover recipe can then surface it.
     */
    private fun resolveCover(list: CookbookList, recipeRepo: RecipeRepository) {
        val dTag = list.dTag
        if (_covers.value[dTag] != null) return
        if (!resolvingCovers.add(dTag)) return
        viewModelScope.launch {
            try {
                val url = CookbookCovers.resolve(list, recipeRepo)
                _covers.value = _covers.value + (dTag to url)
            } finally {
                resolvingCovers.remove(dTag)
            }
        }
    }
}
