package cooking.zap.app.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.ExtractAuthedResult
import cooking.zap.app.api.MembershipStatus
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.api.ZapCookingApiException
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.repo.RecipePublisher
import cooking.zap.app.souschef.SousChefImagePrep
import cooking.zap.app.souschef.SousChefMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sous Chef — AI recipe import. URL import is free and anonymous
 * (`/api/extract-recipe/public`); image/text import (Phase 3) is
 * member-gated behind NIP-98 on `/api/extract-recipe`. All three modes
 * drive the same State machine and land in the read-only [State.Preview].
 * Saving (publish to the user's account) mirrors the web's
 * "preview until sign-in" path.
 */
class SousChefViewModel : ViewModel() {

    sealed interface State {
        data object Idle : State
        /** [mode] selects the progress line shown in the CTA (web parity). */
        data class Loading(val mode: SousChefMode) : State
        data class Preview(val recipe: RecipeParser.Recipe) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * Membership status for the upsell banner. `null` = unknown (not yet
     * fetched, fetch failed, or signer declined) — the UI treats unknown as
     * non-member for banner DISPLAY but never blocks a tap on it; the server
     * check at extraction time is authoritative (web parity).
     */
    private val _membership = MutableStateFlow<MembershipStatus?>(null)
    val membership: StateFlow<MembershipStatus?> = _membership

    private var membershipFetched = false

    /** Save (publish) overlay state, distinct from the import [state]. */
    sealed interface SaveState {
        data object Idle : SaveState
        data object Saving : SaveState
        data class Saved(val author: String, val dTag: String) : SaveState
        data class Error(val message: String) : SaveState
    }

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState

    /**
     * Fetch membership once per screen entry (once per ViewModel instance).
     * Signing accounts use the NIP-98 `check-status` owner lookup; READ_ONLY
     * accounts fall back to the public read. Any failure — including a
     * declined signer prompt — leaves the state `null` (unknown).
     */
    fun fetchMembership(api: ZapCookingApi, signer: NostrSigner?, pubkeyHex: String?) {
        if (membershipFetched) return
        membershipFetched = true
        viewModelScope.launch {
            val status = try {
                when {
                    signer != null -> api.checkMembershipStatus(signer)
                    !pubkeyHex.isNullOrBlank() -> api.getPublicMembership(pubkeyHex)
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (status != null) _membership.value = status
        }
    }

    /** Member-gated image extraction: prepare (downsample/encode) then POST. */
    fun importImage(resolver: ContentResolver, uri: Uri, api: ZapCookingApi, signer: NostrSigner?) {
        if (_state.value is State.Loading) return
        if (signer == null) {
            // Screen gates on canSign; defensive only.
            _state.value = State.Error("Please sign in again to use Sous Chef.")
            return
        }
        _state.value = State.Loading(SousChefMode.IMAGE)
        viewModelScope.launch {
            // Preparation precedes the network phase; its failures are local
            // (read/oversize copy), not network errors.
            val prepared = SousChefImagePrep.prepareImageDataUrl(resolver, uri)
            _state.value = when (prepared) {
                is SousChefImagePrep.Result.Failed -> State.Error(prepared.message)
                is SousChefImagePrep.Result.Ready ->
                    runExtraction { api.extractRecipeFromImage(prepared.dataUrl, signer) }
            }
        }
    }

    /** Member-gated text extraction. */
    fun importText(rawText: String, api: ZapCookingApi, signer: NostrSigner?) {
        val text = rawText.trim()
        if (text.isEmpty() || _state.value is State.Loading) return
        if (signer == null) {
            _state.value = State.Error("Please sign in again to use Sous Chef.")
            return
        }
        _state.value = State.Loading(SousChefMode.TEXT)
        viewModelScope.launch {
            _state.value = runExtraction { api.extractRecipeFromText(text, signer) }
        }
    }

    /**
     * Shared authed-extraction outcome mapping. A 403 despite local member
     * state reverts to Idle with the banner forced non-member (web behavior:
     * show the upsell, open nothing) — one warn line, no error banner. A
     * declined/cancelled signer prompt is a user choice, not an error →
     * Idle.
     */
    private suspend fun runExtraction(call: suspend () -> ExtractAuthedResult): State =
        try {
            when (val result = call()) {
                is ExtractAuthedResult.Success -> State.Preview(result.recipe.toRecipePreview())
                ExtractAuthedResult.SignInRequired ->
                    State.Error("Please sign in again to use Sous Chef.")
                ExtractAuthedResult.MembersOnly -> {
                    Log.w(TAG, "Server returned 403 for image/text extraction despite local member state")
                    _membership.value = MembershipStatus(found = true, isActive = false)
                    State.Idle
                }
                is ExtractAuthedResult.Error -> State.Error(result.message)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SignerRejectedException) {
            State.Idle
        } catch (e: SignerCancelledException) {
            State.Idle
        }

    fun import(rawUrl: String, api: ZapCookingApi) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        _state.value = State.Loading(SousChefMode.URL)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation (e.g. leaving the screen)
            } catch (e: Exception) {
                State.Error("Network error — check your connection and try again.")
            }
        }
    }

    /**
     * Publish the previewed recipe to the user's account (Sous Chef Save).
     * Categories come from the imported recipe's tags (mapped into
     * `recipe.hashtags` by `toRecipePreview`). Requires a signing key.
     */
    fun save(publisher: RecipePublisher, signer: NostrSigner?, clientTagEnabled: Boolean) {
        val preview = _state.value as? State.Preview ?: return
        if (_saveState.value == SaveState.Saving) return
        if (signer == null) {
            _saveState.value = SaveState.Error("Sign in to save recipes.")
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            _saveState.value = when (
                val r = publisher.publish(
                    recipe = preview.recipe,
                    categories = preview.recipe.hashtags,
                    signer = signer,
                    includeClientTag = clientTagEnabled,
                )
            ) {
                is RecipePublisher.Result.Published -> SaveState.Saved(r.author, r.dTag)
                is RecipePublisher.Result.Error -> SaveState.Error(r.message)
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
        _saveState.value = SaveState.Idle
    }

    private companion object {
        const val TAG = "SousChefViewModel"
    }
}
