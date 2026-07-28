package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.NourishComputeRequest
import cooking.zap.app.api.NourishComputeResult
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.NourishRepository
import cooking.zap.app.repo.RecipePublisher
import cooking.zap.app.repo.RecipeRepository
import cooking.zap.app.repo.recipeCoordinate
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

    /**
     * Nourish section state (concern 2.4a read + 2.4b compute). Pantry scores
     * are readable anonymously; compute still needs a signing key.
     * READ_ONLY with no score ⇒ [NourishUi.Hidden] (can't offer compute).
     */
    sealed interface NourishUi {
        data object Hidden : NourishUi          // nothing to show (e.g. READ_ONLY, no score)
        data object Loading : NourishUi         // 2.4a read in flight
        data class Scored(val score: NourishScore) : NourishUi
        data object NotScored : NourishUi       // read done, no score, signing account
        data object Computing : NourishUi        // 2.4b compute in flight
        data object MembersOnly : NourishUi      // compute returned 403
        data class Error(val message: String) : NourishUi
    }

    private val _nourishUi = MutableStateFlow<NourishUi>(NourishUi.Hidden)
    val nourishUi: StateFlow<NourishUi> = _nourishUi

    /**
     * Delete-a-recipe state (author only). [Deleted] is terminal — the screen
     * leaves on it, so nothing resets it back to [Idle].
     */
    sealed interface DeleteState {
        data object Idle : DeleteState
        data object Deleting : DeleteState
        data object Deleted : DeleteState
        data class Error(val message: String) : DeleteState
    }

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState

    private var loadedKey: String? = null

    fun load(
        author: String,
        dTag: String,
        recipeRepo: RecipeRepository,
        nourishRepo: NourishRepository,
        hasSigningKey: Boolean,
    ) {
        val key = "$author:$dTag"
        if (loadedKey == key && _recipe.value != null) return
        loadedKey = key
        _isLoading.value = true
        _nourishUi.value = NourishUi.Loading
        viewModelScope.launch {
            val resolved = recipeRepo.requestRecipe(author, dTag)
            if (loadedKey != key) return@launch // a newer recipe was requested — drop stale result
            _recipe.value = resolved
            // Per-format dispatch (not a hardcoded 30023 lookup) so a future
            // second-format recipe resolves its raw event here too.
            _event.value = recipeRepo.findRecipeEvent(author, dTag)
            _isLoading.value = false
        }
        // Nourish read runs independently (pantry REQ; anonymous OK) — never
        // blocks the recipe, never surfaces an error.
        viewModelScope.launch {
            val score = runCatching { nourishRepo.fetchScore(author, dTag, hasSigningKey) }.getOrNull()
            if (loadedKey != key) return@launch // drop stale result on fast navigation
            _nourishUi.value = when {
                score != null -> NourishUi.Scored(score)
                hasSigningKey -> NourishUi.NotScored // signing account, no score → offer compute
                else -> NourishUi.Hidden // READ_ONLY / no key, and no published score
            }
        }
    }

    /**
     * Delete this recipe via [RecipePublisher.delete] (the shared recipe write
     * surface — same relays the publish reached). Author-only; the publisher
     * re-checks that against the signer, so a screen that offered the action by
     * mistake still can't publish someone else's tombstone.
     *
     * Guarded against a double tap: a second call while [DeleteState.Deleting]
     * is a no-op.
     *
     * Two local evictions, and they are different jobs. The publisher applies
     * the kind-5 through [EventRepository.addEvent] — the same inbound path a
     * relay round-trip would take — which clears the note feed and the event
     * cache. That does **not** reach [RecipeRepository]'s grids, which key on
     * the coordinate and have no deletion observer, so [RecipeRepository.removeRecipe]
     * is what stops the member landing back on a grid that still shows the
     * recipe they just deleted. Only on success: a failed delete must leave the
     * recipe exactly where it was.
     */
    fun delete(publisher: RecipePublisher, recipeRepo: RecipeRepository, signer: NostrSigner?) {
        val event = _event.value ?: return
        if (_deleteState.value == DeleteState.Deleting) return
        _deleteState.value = DeleteState.Deleting
        viewModelScope.launch {
            _deleteState.value = when (val r = publisher.delete(event, signer)) {
                RecipePublisher.DeleteResult.Deleted -> {
                    recipeRepo.removeRecipe(recipeCoordinate(event))
                    DeleteState.Deleted
                }
                is RecipePublisher.DeleteResult.Error -> DeleteState.Error(r.message)
            }
        }
    }

    /** Dismiss a failed delete so the menu can be used again. */
    fun clearDeleteError() {
        if (_deleteState.value is DeleteState.Error) _deleteState.value = DeleteState.Idle
    }

    /**
     * Compute the Nourish score for this recipe (member-gated). Optimistic:
     * any signing account may try; a 403 → [NourishUi.MembersOnly]. Uses the
     * compute response directly (no pantry re-read); the server publishes to
     * pantry for future viewers.
     */
    fun computeNourish(api: ZapCookingApi, signer: NostrSigner?) {
        val event = _event.value ?: return
        val recipe = _recipe.value ?: return
        val pubkey = signer?.pubkeyHex ?: return // READ_ONLY — no compute
        if (_nourishUi.value == NourishUi.Computing) return
        val key = loadedKey
        _nourishUi.value = NourishUi.Computing
        viewModelScope.launch {
            val request = NourishComputeRequest(
                pubkey = pubkey,
                eventId = event.id,
                title = recipe.title ?: "",
                ingredients = recipe.content.ingredients,
                tags = recipe.hashtags,
                servings = recipe.content.details.servings ?: "",
                recipePubkey = recipe.author,
                recipeDTag = recipe.dTag,
                // Byte-exact with the server: SHA-256 over the raw event content (UTF-8), no trim.
                contentHash = Nip98.sha256Hex(event.content.toByteArray(Charsets.UTF_8)),
            )
            val result = api.computeNourish(request)
            if (loadedKey != key) return@launch // navigated to another recipe — drop stale result
            _nourishUi.value = when (result) {
                is NourishComputeResult.Success -> NourishUi.Scored(result.score)
                NourishComputeResult.MembersOnly -> NourishUi.MembersOnly
                is NourishComputeResult.Error -> NourishUi.Error(result.message)
            }
        }
    }
}
