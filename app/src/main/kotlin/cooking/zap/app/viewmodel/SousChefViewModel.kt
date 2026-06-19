package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.api.ZapCookingApiException
import cooking.zap.app.nostr.RecipeParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sous Chef — AI recipe import (concern 2.1). v1 is **URL import, preview
 * only**: POST the URL to the free, anon `/api/extract-recipe/public`, map the
 * structured response to a [RecipeParser.Recipe], and show a read-only
 * preview. Saving (publish to the user's account) is deferred to concern 2.2 —
 * this mirrors the web's anon "preview until sign-in" path exactly.
 */
class SousChefViewModel : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Preview(val recipe: RecipeParser.Recipe) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun import(rawUrl: String, api: ZapCookingApi) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                val resp = api.extractRecipeFromUrl(url)
                val recipe = resp.recipe
                if (resp.success && recipe != null) {
                    State.Preview(recipe.toRecipePreview())
                } else {
                    State.Error(resp.error ?: "Couldn't import a recipe from that link.")
                }
            } catch (e: ZapCookingApiException) {
                State.Error(
                    when (e.code) {
                        429 -> "Too many imports right now — try again in a bit."
                        400 -> api.parseError(e.body) ?: "Couldn't read a recipe from that link."
                        else -> "Import failed (${e.code})."
                    }
                )
            } catch (e: Exception) {
                State.Error("Network error — check your connection and try again.")
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
    }
}
