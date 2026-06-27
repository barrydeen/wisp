package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.CookbookMemberRecipe
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the **Cookbook** tab's two sub-tabs:
 *  - **Saved** (PR 3b-i) — re-exposes the user's kind-30001 recipe lists from
 *    [RecipeBookmarkRepository] (PR 3a, already loaded at login) and lazily
 *    resolves a cover image per list via [CookbookCovers].
 *  - **My Recipes** (PR 3b-ii) — the user's OWN published recipes via the LIVE
 *    author query ([RecipeRepository.loadAuthoredRecipes]); kicked off lazily the
 *    first time the sub-tab is shown.
 *
 * Also exposes the Saved-list management writes (PR 3b-iii: rename / description /
 * cover / delete) as thin pass-throughs to [RecipeBookmarkRepository]. The UI
 * gates these behind a signing key (LocalCanSign).
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
    private var bookmarkRepo: RecipeBookmarkRepository? = null
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
        this.bookmarkRepo = bookmarkRepo
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

    // ---- Management actions (PR 3b-iii) -----------------------------------
    // Thin pass-throughs to the repo write methods (off the main thread). The
    // repo republishes optimistically, so `lists`/`covers` update via the bound
    // collectors — no extra refresh needed here.

    /** Rename a named collection (no-op on the default list — its title is locked). */
    fun renameList(dTag: String, newTitle: String) = launchWrite { it.renameList(dTag, newTitle) }

    /** Set/clear a list's description (`summary`). */
    fun setDescription(dTag: String, summary: String) = launchWrite { it.setListDescription(dTag, summary) }

    /** Set the cover to a member recipe's a-coordinate. */
    fun setCover(dTag: String, coverCoord: String) = launchWrite { it.setListCover(dTag, coverCoord) }

    /** Delete a named collection (NIP-09 kind-5; no-op on the default list). */
    fun deleteList(dTag: String) = launchWrite { it.deleteList(dTag) }

    private inline fun launchWrite(crossinline block: suspend (RecipeBookmarkRepository) -> Unit) {
        val repo = bookmarkRepo ?: return
        viewModelScope.launch(Dispatchers.Default) { block(repo) }
    }

    /**
     * Resolve [list]'s member recipes for the cover-picker — cache-first, then a
     * network fill per coordinate (reuses the existing recipe lookups). An
     * unresolved member still appears, labelled by its `d`-tag, so it remains
     * pickable. Order follows the list's coordinates.
     */
    suspend fun memberRecipes(list: CookbookList): List<CookbookMemberRecipe> {
        val repo = recipeRepo ?: return emptyList()
        // Off the main thread: the cache lookup scans persistence and the fill
        // waits on relays — neither should run on the caller's UI dispatcher.
        return withContext(Dispatchers.IO) {
            list.coordinates.mapNotNull { raw ->
                val c = CookbookCovers.parseCoordinate(raw) ?: return@mapNotNull null
                val event = repo.findRecipeEventByCoordinate(c.kind, c.author, c.dTag)
                    ?: repo.requestRecipeEventByCoordinate(c.kind, c.author, c.dTag)
                val recipe = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
                CookbookMemberRecipe(
                    coord = raw,
                    title = recipe?.title?.takeIf { it.isNotBlank() } ?: c.dTag,
                    image = recipe?.image,
                )
            }
        }
    }
}
