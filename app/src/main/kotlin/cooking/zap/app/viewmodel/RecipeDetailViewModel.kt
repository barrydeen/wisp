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

    /**
     * Nourish section state (concern 2.4a read + 2.4b compute). READ_ONLY ⇒
     * always [NourishUi.Hidden] (no key to auth-read or compute).
     */
    sealed interface NourishUi {
        data object Hidden : NourishUi          // READ_ONLY, or nothing to show
        data object Loading : NourishUi         // 2.4a read in flight
        data class Scored(val score: NourishScore) : NourishUi
        data object NotScored : NourishUi       // read done, no score, signing account
        data object Computing : NourishUi        // 2.4b compute in flight
        data object MembersOnly : NourishUi      // compute returned 403
        data class Error(val message: String) : NourishUi
    }

    private val _nourishUi = MutableStateFlow<NourishUi>(NourishUi.Hidden)
    val nourishUi: StateFlow<NourishUi> = _nourishUi

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
        _nourishUi.value = if (hasSigningKey) NourishUi.Loading else NourishUi.Hidden
        viewModelScope.launch {
            val resolved = recipeRepo.requestRecipe(author, dTag)
            if (loadedKey != key) return@launch // a newer recipe was requested — drop stale result
            _recipe.value = resolved
            // Per-format dispatch (not a hardcoded 30023 lookup) so a future
            // second-format recipe resolves its raw event here too.
            _event.value = recipeRepo.findRecipeEvent(author, dTag)
            _isLoading.value = false
        }
        // Nourish read runs independently (auth'd Pantry round-trip) — never
        // blocks the recipe, never surfaces an error.
        viewModelScope.launch {
            val score = runCatching { nourishRepo.fetchScore(author, dTag, hasSigningKey) }.getOrNull()
            if (loadedKey != key) return@launch // drop stale result on fast navigation
            _nourishUi.value = when {
                !hasSigningKey -> NourishUi.Hidden
                score != null -> NourishUi.Scored(score)
                else -> NourishUi.NotScored // signing account, no score → offer compute
            }
        }
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
