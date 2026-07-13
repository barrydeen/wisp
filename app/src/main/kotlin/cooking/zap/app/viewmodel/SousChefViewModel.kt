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
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipePublisher
import cooking.zap.app.souschef.SousChefImagePrep
import cooking.zap.app.souschef.SousChefMode
import cooking.zap.app.souschef.SousChefPublishConfirm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sous Chef — AI recipe import. URL import is free and anonymous
 * (`/api/extract-recipe/public`); image/text import (Phase 3) is
 * member-gated behind NIP-98 on `/api/extract-recipe`. All three modes
 * drive the same State machine and land in the read-only [State.Preview].
 * From preview the user can Publish, Save to My Recipes (publish + Saved list),
 * Edit (Cheffy [pendingComposeMarkdown] hand-off), or Discard.
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

    /** Publish overlay state, distinct from the import [state]. */
    sealed interface PublishState {
        data object Idle : PublishState
        data object Publishing : PublishState
        /**
         * @param savedToCookbook true when this publish was the Cookbook-save
         * path (publish + default Saved list), so the UI can toast accordingly.
         */
        data class Published(
            val author: String,
            val dTag: String,
            val savedToCookbook: Boolean = false,
        ) : PublishState
        data class Error(val message: String) : PublishState
    }

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState

    /**
     * Fetch membership once per screen entry (once per ViewModel instance)
     * via the public, unauthenticated read for ALL accounts — no signer
     * interaction may happen before the user taps the CTA (a RemoteSigner
     * would otherwise prompt just for opening the screen). The banner only
     * needs `isActive`, which the public shape carries; the server's 403 at
     * extraction time remains the authoritative correction. Failure leaves
     * the state `null` (unknown).
     */
    fun fetchMembership(api: ZapCookingApi, pubkeyHex: String?) {
        // No-pubkey calls don't consume the once-flag — a later call (e.g.
        // after sign-in state changes) can still fetch.
        if (membershipFetched || pubkeyHex.isNullOrBlank()) return
        membershipFetched = true
        viewModelScope.launch {
            val status = try {
                api.getPublicMembership(pubkeyHex)
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
            } catch (e: java.io.InterruptedIOException) {
                // SocketTimeoutException is an InterruptedIOException. Server-side
                // URL extraction (page fetch + LLM parse) can outlast even the
                // compute client's read timeout on a slow/heavy site — that's a
                // "try again" case, not a connectivity failure.
                State.Error("That site is taking too long — try again in a moment.")
            } catch (e: Exception) {
                State.Error("Network error — check your connection and try again.")
            }
        }
    }

    /**
     * Publish the previewed recipe as a signed kind-30023 to the user's
     * account. Categories come from the imported recipe's tags (mapped into
     * `recipe.hashtags` by `toRecipePreview`). Requires a signing key.
     * The UI confirms before calling this.
     */
    fun publish(publisher: RecipePublisher, signer: NostrSigner?, clientTagEnabled: Boolean) {
        runPublish(publisher, signer, clientTagEnabled, saveToCookbook = null)
    }

    /**
     * Publish then add the recipe's a-coordinate to the canonical Cookbook
     * **Saved** list ([RecipeBookmarkRepository.addCoordinates]). Cookbook
     * membership requires a published event — there is no local-only bookmark
     * for an unpublished extraction. The UI confirms with
     * [cooking.zap.app.souschef.SousChefPublishConfirm.COOKBOOK_SAVE_MESSAGE].
     */
    fun saveToCookbook(
        publisher: RecipePublisher,
        bookmarkRepo: RecipeBookmarkRepository,
        signer: NostrSigner?,
        clientTagEnabled: Boolean,
    ) {
        runPublish(publisher, signer, clientTagEnabled, saveToCookbook = bookmarkRepo)
    }

    private fun runPublish(
        publisher: RecipePublisher,
        signer: NostrSigner?,
        clientTagEnabled: Boolean,
        saveToCookbook: RecipeBookmarkRepository?,
    ) {
        val preview = _state.value as? State.Preview ?: return
        if (_publishState.value == PublishState.Publishing) return
        if (signer == null) {
            _publishState.value = PublishState.Error(
                if (saveToCookbook != null) "Sign in to save recipes to My Kitchen."
                else "Sign in to publish recipes.",
            )
            return
        }
        _publishState.value = PublishState.Publishing
        viewModelScope.launch {
            when (
                val r = publisher.publish(
                    recipe = preview.recipe,
                    categories = preview.recipe.hashtags,
                    signer = signer,
                    includeClientTag = clientTagEnabled,
                )
            ) {
                is RecipePublisher.Result.Error ->
                    _publishState.value = PublishState.Error(r.message)
                is RecipePublisher.Result.Published -> {
                    if (saveToCookbook != null) {
                        val coord = SousChefPublishConfirm.cookbookCoordinate(
                            kind = RecipeFormats.primary.kind,
                            author = r.author,
                            dTag = r.dTag,
                        )
                        try {
                            saveToCookbook.addCoordinates(listOf(coord))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Publish succeeded; Cookbook write is best-effort —
                            // still land on the published recipe.
                        }
                        _publishState.value = PublishState.Published(
                            author = r.author,
                            dTag = r.dTag,
                            savedToCookbook = true,
                        )
                    } else {
                        _publishState.value = PublishState.Published(r.author, r.dTag)
                    }
                }
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
        _publishState.value = PublishState.Idle
    }

    private companion object {
        const val TAG = "SousChefViewModel"
    }
}
