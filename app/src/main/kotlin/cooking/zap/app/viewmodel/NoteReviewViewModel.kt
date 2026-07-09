package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.api.NoteReviewResult
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.cheffy.NoteReview
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.DisclosurePreferences
import cooking.zap.app.repo.NoteReviewReplyPublisher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cheffy Note Photo Review — modal state (CHEFFY_NOTE_REVIEW_PLAN.md,
 * Phase 2: draft flow only; publish is Phase 3, credits Phase 5).
 *
 * Ports the web `CheffyNoteReview.svelte` script state onto the house
 * ViewModel pattern (SousChef precedent: plain ViewModel, entry points
 * take `api` + `signer`). The pure phase mapping lives in
 * [NoteReview.phaseForResult]; this class owns the session state around
 * it: the target note, the in-flight request, the editable draft, and
 * the rotation memory for dead-end/error lines.
 *
 * Invariant 7 (signing ≠ loading): [choose] enters [NoteReview.Phase.SIGNING]
 * and a delegating signer flips to LOADING the moment the kind-27235 sign
 * completes — the user sees whose turn it is (their signer vs. the
 * server). [regenerate] enters LOADING directly: within the NIP-98 header
 * cache's 30s TTL a regenerate performs no sign at all, and when it does
 * re-sign, an external signer shows its own approval UI anyway.
 */
class NoteReviewViewModel : ViewModel() {

    data class UiState(
        val phase: NoteReview.Phase = NoteReview.Phase.CHOOSE,
        /** Selected draft mode; retained across regenerates (web parity). */
        val mode: NoteReviewMode? = null,
        /** The editable draft. Owned here so edits survive recomposition. */
        val draft: String = "",
        /** Dead-end line, error sub-message, or post-timeout line. */
        val message: String = "",
        /** Rotating Cheffy-voice headline for the error phase. */
        val errorLine: String = "",
        /** Thinking/cooking line shown during LOADING; picked per run. */
        val loadingLine: String = "",
        /** Paid-draft balance from spends — display only (Phase 5 uses it). */
        val creditsRemaining: Int? = null,
        /** Publish failure line shown inside the draft phase (Phase 3). */
        val postError: String = "",
        /** The just-published reply — drives the POSTED link (Phase 3). */
        val postedEvent: NostrEvent? = null,
        /**
         * The SIGNED event a timed-out publish retained (invariant 2) —
         * retry re-broadcasts exactly this, same id, no re-sign.
         */
        val timeoutSignedEvent: NostrEvent? = null,
        /**
         * "Via Cheffy" disclosure toggle (Phase 4). Applied to the content
         * string ONLY at the [post] hand-off — never part of [draft].
         */
        val disclosureOn: Boolean = false,
        // Session target — set by [open], constant until the next open.
        val parent: NostrEvent? = null,
        /** All detected images, in note order (the Phase 4 picker strip). */
        val imageUrls: List<String> = emptyList(),
        /**
         * Picker selection. Persists across regenerates and Start over;
         * only [open] (a fresh sheet) resets it. One imageUrl per request
         * — selection is purely client-side.
         */
        val selectedImageIndex: Int = 0,
    ) {
        /** The image the next request sends — the selected one. */
        val imageUrl: String
            get() = imageUrls.getOrNull(selectedImageIndex) ?: imageUrls.firstOrNull() ?: ""
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var requestJob: Job? = null
    private var postJob: Job? = null

    /** Rotation memory so consecutive dead-ends never repeat verbatim. */
    private var lastDeadEndLine: String? = null

    /**
     * Configure the modal for a note and reset to CHOOSE. [parent] is the
     * kind-1 being reviewed — its content/id feed the draft request and it
     * anchors the Phase 3 reply. [imageUrls] are the note's detected
     * images in order; requests use the picker selection (index 0 until
     * the member picks another).
     */
    fun open(parent: NostrEvent, imageUrls: List<String>) {
        requestJob?.cancel()
        postJob?.cancel()
        _state.value = UiState(parent = parent, imageUrls = imageUrls)
    }

    /**
     * Pick which photo Cheffy looks at (Phase 4). Selection only ARMS the
     * next request — web parity: CheffyNoteReview.svelte:405 assigns
     * `selectedImageIndex` without calling `run()`, so an existing draft
     * stays until the member regenerates.
     */
    fun selectImage(index: Int) {
        _state.update {
            if (index in it.imageUrls.indices) it.copy(selectedImageIndex = index) else it
        }
    }

    /**
     * Toggle the "via Cheffy" disclosure and persist it immediately for
     * the current mode (web `toggleDisclosure`).
     */
    fun toggleDisclosure(prefs: DisclosurePreferences? = null) {
        val mode = _state.value.mode ?: return
        val next = !_state.value.disclosureOn
        _state.update { it.copy(disclosureOn = next) }
        prefs?.setDisclosureEnabled(mode, next)
    }

    /**
     * Initial mode selection from the CHOOSE phase. Seeds the disclosure
     * toggle from the stored per-mode preference — ONLY here (web
     * `shouldSeedDisclosureFromPref`): regenerates and error-retries go
     * through [regenerate], which preserves the in-session toggle.
     */
    fun choose(
        mode: NoteReviewMode,
        api: ZapCookingApi,
        signer: NostrSigner?,
        prefs: DisclosurePreferences? = null,
    ) {
        if (NoteReview.shouldSeedDisclosureFromPref(_state.value.phase)) {
            val seeded = prefs?.isDisclosureEnabled(mode) ?: NoteReview.defaultDisclosure(mode)
            _state.update { it.copy(disclosureOn = seeded) }
        }
        run(mode, api, signer, showSigning = true)
    }

    /**
     * Re-run with the SAME mode and image (draft phase "Regenerate" and
     * error phase "Try again" — web wires both to `run(mode)`). No-op
     * before any mode was chosen.
     */
    fun regenerate(api: ZapCookingApi, signer: NostrSigner?) {
        val mode = _state.value.mode ?: return
        run(mode, api, signer, showSigning = false)
    }

    fun updateDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /**
     * Back to CHOOSE for the same note, dropping the draft (web `reset`).
     * The picker selection survives (per the Phase 4 spec, only closing
     * the sheet — i.e. the next [open] — resets it); the disclosure
     * toggle re-seeds from the stored preference on the next [choose].
     */
    fun startOver() {
        requestJob?.cancel()
        postJob?.cancel()
        _state.update {
            UiState(
                parent = it.parent,
                imageUrls = it.imageUrls,
                selectedImageIndex = it.selectedImageIndex,
            )
        }
    }

    // --- Publish path (Phase 3) ---

    /**
     * Publish the member-edited draft as a NIP-10 reply to the parent.
     * Hard-gated on [NoteReview.canPost] — POSTING is entered
     * synchronously, so a second call (double-tap, re-entry) is a no-op
     * regardless of button state.
     */
    fun post(
        publisher: NoteReviewReplyPublisher,
        signer: NostrSigner?,
        clientTagEnabled: Boolean,
    ) {
        val s = _state.value
        if (!NoteReview.canPost(s.phase)) return
        val parent = s.parent ?: return
        val draft = s.draft.trim()
        if (draft.isEmpty()) return
        if (signer == null) {
            _state.update { it.copy(postError = NoteReview.SIGN_FAILED_LINE) }
            return
        }
        // The disclosure footer joins the content ONLY here, at the
        // hand-off to the publisher — never inside the editable draft.
        // It is thereby baked into the SIGNED event, so the PostTimeout
        // retry path (publishSigned) never touches it again.
        val content = NoteReview.withDisclosureFooter(draft, s.disclosureOn)
        _state.update { it.copy(phase = NoteReview.Phase.POSTING, postError = "") }
        postJob?.cancel()
        postJob = viewModelScope.launch {
            applyPublishOutcome(publisher.publish(content, parent, signer, clientTagEnabled))
        }
    }

    /**
     * Timeout recovery: re-broadcast the retained SIGNED event — same id
     * (relays dedupe), no second signer round trip (invariant 2). Only
     * legal from POST_TIMEOUT.
     */
    fun retryPost(publisher: NoteReviewReplyPublisher) {
        val s = _state.value
        if (s.phase != NoteReview.Phase.POST_TIMEOUT) return
        val signed = s.timeoutSignedEvent ?: return
        val parent = s.parent ?: return
        _state.update { it.copy(phase = NoteReview.Phase.POSTING) }
        postJob?.cancel()
        postJob = viewModelScope.launch {
            applyPublishOutcome(publisher.publishSigned(signed, parent))
        }
    }

    /**
     * Land a publish outcome. Internal so the POSTING → POSTED /
     * POST_TIMEOUT / back-to-DRAFT mapping is unit-testable without
     * relays.
     */
    internal fun applyPublishOutcome(outcome: NoteReviewReplyPublisher.Outcome) {
        _state.update { s ->
            when (outcome) {
                is NoteReviewReplyPublisher.Outcome.Published -> s.copy(
                    phase = NoteReview.Phase.POSTED,
                    postedEvent = outcome.event,
                    timeoutSignedEvent = null,
                    postError = "",
                    message = "",
                )
                is NoteReviewReplyPublisher.Outcome.Timeout -> s.copy(
                    phase = NoteReview.Phase.POST_TIMEOUT,
                    timeoutSignedEvent = outcome.signedEvent,
                    message = NoteReview.POST_TIMEOUT_LINE,
                )
                // Draft intact on both: publish-failed is a relay problem,
                // sign-rejected is the member's choice — neither destroys
                // their edited words.
                NoteReviewReplyPublisher.Outcome.Failed -> s.copy(
                    phase = NoteReview.Phase.DRAFT,
                    postError = NoteReview.PUBLISH_FAILED_LINE,
                )
                NoteReviewReplyPublisher.Outcome.SignRejected -> s.copy(
                    phase = NoteReview.Phase.DRAFT,
                    postError = NoteReview.SIGN_FAILED_LINE,
                )
            }
        }
    }

    private fun run(
        mode: NoteReviewMode,
        api: ZapCookingApi,
        signer: NostrSigner?,
        showSigning: Boolean,
    ) {
        // Defensive only — the trigger is gated on LocalCanSign, so a null
        // signer here is a wiring bug, surfaced in Cheffy voice.
        if (signer == null) {
            _state.update {
                it.copy(
                    phase = NoteReview.Phase.ERROR,
                    mode = mode,
                    message = NoteReview.SIGN_FAILED_LINE,
                    errorLine = Cheffy.pickLine(Cheffy.ERROR_LINES, it.errorLine),
                )
            }
            return
        }
        _state.update {
            it.copy(
                phase = if (showSigning) NoteReview.Phase.SIGNING else NoteReview.Phase.LOADING,
                mode = mode,
                postError = "",
                // Recipe drafts get the "cooking" pool, comments the
                // "thinking" pool (web parity); avoid the previous line.
                loadingLine = Cheffy.pickLine(
                    if (mode == NoteReviewMode.RECIPE) Cheffy.COOKING_LINES else Cheffy.THINKING_LINES,
                    it.loadingLine,
                ),
            )
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            // Flip SIGNING → LOADING the instant the NIP-98 sign completes
            // (invariant 7) without touching the merged Phase 1 API: the
            // signer itself reports completion.
            val notifyingSigner = OnSignedSigner(signer) {
                _state.update { s ->
                    if (s.phase == NoteReview.Phase.SIGNING) s.copy(phase = NoteReview.Phase.LOADING) else s
                }
            }
            val st = _state.value
            val result = api.requestNoteReview(
                imageUrl = st.imageUrl,
                mode = mode,
                noteText = st.parent?.content?.ifBlank { null },
                noteId = st.parent?.id,
                signer = notifyingSigner,
            )
            applyResult(result)
        }
    }

    /**
     * Land a request result in the state machine. Internal so the full
     * result → phase mapping is unit-testable without HTTP.
     */
    internal fun applyResult(result: NoteReviewResult) {
        val next = NoteReview.phaseForResult(result, avoidDeadEndLine = lastDeadEndLine)
        if (next.phase == NoteReview.Phase.DEAD_END) lastDeadEndLine = next.message
        _state.update { s ->
            val errorLine =
                if (next.phase == NoteReview.Phase.ERROR) Cheffy.pickLine(Cheffy.ERROR_LINES, s.errorLine)
                else s.errorLine
            when (result) {
                is NoteReviewResult.Success -> s.copy(
                    phase = next.phase,
                    message = "",
                    draft = result.output,
                    creditsRemaining = result.creditsRemaining ?: s.creditsRemaining,
                )
                else -> s.copy(phase = next.phase, message = next.message, errorLine = errorLine)
            }
        }
    }
}

/**
 * [NostrSigner] wrapper that reports each completed sign — how the modal
 * observes "the signer approved" (SIGNING → LOADING) without the API
 * layer growing a callback. Delegation keeps every other signer behavior
 * (silent variants, NIP-44) untouched.
 */
private class OnSignedSigner(
    private val delegate: NostrSigner,
    private val onSigned: () -> Unit,
) : NostrSigner by delegate {
    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent = delegate.signEvent(kind, content, tags, createdAt).also { onSigned() }
}
