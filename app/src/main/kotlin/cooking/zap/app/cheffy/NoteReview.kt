package cooking.zap.app.cheffy

import cooking.zap.app.api.CreditStatus
import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.api.NoteReviewResult
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.hexToByteArray
import kotlin.random.Random

/**
 * Cheffy Note Photo Review — phase machine + copy pools
 * (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 2). Port of the web
 * `src/lib/noteReview.ts` state-machine core: the line pools are verbatim
 * and [phaseForResult] mirrors the web `phaseForResult`, adapted to the
 * Android API layer's sealed [NoteReviewResult] (which already collapsed
 * `NOT_FOOD`/`IMAGE_UNREADABLE` into a message-less
 * [NoteReviewResult.DeadEnd] and typed the other failures).
 *
 * Pure (strings + mapping) so the modal's tested core runs on the plain
 * JVM, like the web module's vitest suite.
 */
object NoteReview {

    /**
     * Modal phases (`paying` is Phase 5). `MEMBERSHIP_UNAVAILABLE`
     * deliberately maps into [ERROR] (retryable "try again shortly"),
     * never [UPSELL] — the endpoint fails closed on a membership-service
     * outage, and an upsell would dun a member over our outage.
     */
    enum class Phase { CHOOSE, SIGNING, LOADING, DRAFT, POSTING, POST_TIMEOUT, POSTED, DEAD_END, UPSELL, PAYING, ERROR }

    /**
     * The only phase a publish may start from — the double-post guard
     * (invariant D1/web `canPost`). Enforced in the ViewModel, not just
     * button state.
     */
    fun canPost(phase: Phase): Boolean = phase == Phase.DRAFT

    data class PhaseAndMessage(val phase: Phase, val message: String)

    /**
     * Hedged dead-end copy pool — verbatim from `noteReview.ts`
     * `DEAD_END_LINES`. The server's NOT_FOOD path also fires for CDN
     * fallback images served in place of dead links (e.g. nostr.build
     * never 404s), so every line stays in the "couldn't get a good look"
     * register — never a confident "that's not food", and the model's
     * line is never echoed (structurally impossible here: the API layer's
     * DeadEnd carries no message). Rotated via [Cheffy.pickLine] so
     * consecutive dead-ends don't repeat verbatim.
     */
    val DEAD_END_LINES = listOf(
        "Cheffy couldn't get a good look at that photo. It might be a broken link — or just not clearly a dish.",
        "That photo's playing hard to get — Cheffy can't quite make out a dish in it.",
        "Cheffy squinted, but couldn't spot a dish in there. The photo may not have come through.",
        "Hmm — Cheffy couldn't see this one clearly. The link may be stale, or the dish is camera-shy.",
    )

    /** Verbatim from `noteReview.ts` `SIGN_FAILED_LINE`. */
    const val SIGN_FAILED_LINE =
        "Cheffy couldn't get your signer's autograph. Check your signer and try again."

    /** Verbatim from `noteReview.ts` `GENERIC_ERROR_LINE`. */
    const val GENERIC_ERROR_LINE = "Cheffy could not finish that one. Please try again."

    /**
     * On web these two arrive as the server's `error` string through
     * `phaseForResult`'s default arm; the Android API layer types them
     * without a message, so the same copy is pinned here (server lines,
     * verbatim).
     */
    const val RATE_LIMITED_LINE =
        "Cheffy needs a breather — you've hit the photo-review limit for now."
    const val MEMBERSHIP_UNAVAILABLE_LINE =
        "Cheffy can't check your membership right now. Please try again shortly."

    /** Verbatim from `noteReview.ts` `POST_TIMEOUT_LINE` (Phase 3). */
    const val POST_TIMEOUT_LINE =
        "The relays are taking their time. Your reply is signed and may already be out there — give it another push, and Cheffy won't ask your signer twice."

    /** Verbatim from `noteReview.ts` `PUBLISH_FAILED_LINE` (Phase 3). */
    const val PUBLISH_FAILED_LINE =
        "The relays didn't take that one. Your draft is safe — give it another go."

    // --- Disclosure footer (Phase 4, port of the noteReview.ts block) ---

    /**
     * Appended at publish time when the member's toggle is on — NEVER
     * embedded in the editable draft (the modal shows it as a separate
     * non-editable preview). Exact string is part of the product spec.
     */
    const val DISCLOSURE_FOOTER = "⚡🍳 via Cheffy · zap.cooking"

    private val TRAILING_WHITESPACE = Regex("""\s+$""")

    /**
     * Build the publish content: the member's draft, untouched, plus the
     * footer on its own line after one blank line when the toggle is on.
     * Trailing whitespace on the draft is normalized so the footer never
     * drifts more than one blank line away. Verbatim port of
     * `withDisclosureFooter`.
     */
    fun withDisclosureFooter(draft: String, on: Boolean): String {
        if (!on) return draft
        return draft.replace(TRAILING_WHITESPACE, "") + "\n\n" + DISCLOSURE_FOOTER
    }

    /**
     * Per-mode defaults, deliberate (web `DISCLOSURE_DEFAULTS`): a recipe
     * is Cheffy's structured work product (attribution on), a comment is
     * the member's own voice (off).
     */
    fun defaultDisclosure(mode: NoteReviewMode): Boolean = mode == NoteReviewMode.RECIPE

    // --- Credits (Phase 5a, port of the noteReview.ts credit block) ---

    /** Product spec — 21 sats per draft. */
    const val CREDIT_PRICE_SATS = 21

    /** Verbatim from `noteReview.ts` `CREDITS_CROSS_DEVICE_LINE`. */
    const val CREDITS_CROSS_DEVICE_LINE = "Tied to your Nostr key — works on any device."

    /**
     * Verbatim from `noteReview.ts` `PAYMENT_CARD_EXAMPLE_DRAFT` — the
     * static example on the payment card so first-timers see output
     * quality before the 21-sat ask. Comment-mode length, hardcoded.
     */
    const val PAYMENT_CARD_EXAMPLE_DRAFT =
        "That crust has the kind of golden edge you only get from a properly hot pan — and the basil on top says you weren't rushing. Beautiful work."

    /** Verbatim from the web `beginPolling` expired branch. */
    const val INVOICE_EXPIRED_LINE = "That invoice expired — grab a fresh one below."

    /** Verbatim from the web paying-phase `hasInAppWallet` branch (Phase 5b). */
    const val WALLET_PAYING_LINE = "Paying from your wallet — drafting starts the moment it lands."

    /** Verbatim from the web paying-phase `inAppPayFailed` branch (Phase 5b). */
    const val WALLET_FALLBACK_LINE =
        "Your wallet didn't answer. The invoice is still good — pay it another way."

    /** Verbatim from the web `startPayment` mint-failure branch. */
    const val INVOICE_SETUP_FAILED_LINE = "Could not set up the payment. Please try again."

    /** What the 3s poll loop does with each status observation. */
    enum class PollAction { CREDITED, EXPIRED, CONTINUE }

    /** Port of the web `pollActionForStatus`. Pure — the poll's tested core. */
    fun pollActionForStatus(status: CreditStatus): PollAction = when (status) {
        CreditStatus.PAID -> PollAction.CREDITED
        CreditStatus.EXPIRED -> PollAction.EXPIRED
        CreditStatus.PENDING -> PollAction.CONTINUE
    }

    /**
     * The stored preference seeds the toggle only on the initial mode
     * selection (from the choose phase). Regenerate / try-again re-runs
     * must preserve the member's in-session toggle — the live toggle
     * state is the fresher signal. Port of `shouldSeedDisclosureFromPref`.
     */
    fun shouldSeedDisclosureFromPref(phase: Phase): Boolean = phase == Phase.CHOOSE

    /**
     * Map a request result to the modal phase and its display message —
     * the tested core of the modal's state machine (web `phaseForResult`).
     * [avoidDeadEndLine] is the previously shown dead-end line, so
     * consecutive dead-ends rotate.
     */
    /**
     * Thread-view identifier for a published reply — port of the web
     * `noteLinkFor`. House pattern: `nevent` with author + kind hints,
     * falling back to a bare `note1` encoding. (In-app navigation to
     * ThreadScreen uses the hex id; this is the canonical shareable form
     * and the Phase 6 share affordance's input.)
     */
    fun noteLinkFor(event: NostrEvent): String = try {
        Nip19.neventEncode(
            eventId = event.id.hexToByteArray(),
            author = event.pubkey.hexToByteArray(),
            kind = event.kind,
        )
    } catch (_: Exception) {
        Nip19.noteEncode(event.id.hexToByteArray())
    }

    fun phaseForResult(
        result: NoteReviewResult,
        avoidDeadEndLine: String? = null,
        random: Random = Random.Default,
    ): PhaseAndMessage = when (result) {
        is NoteReviewResult.Success -> PhaseAndMessage(Phase.DRAFT, "")
        NoteReviewResult.NotMember -> PhaseAndMessage(Phase.UPSELL, "")
        NoteReviewResult.DeadEnd ->
            PhaseAndMessage(Phase.DEAD_END, Cheffy.pickLine(DEAD_END_LINES, avoidDeadEndLine, random))
        NoteReviewResult.SignFailed -> PhaseAndMessage(Phase.ERROR, SIGN_FAILED_LINE)
        NoteReviewResult.MembershipUnavailable ->
            PhaseAndMessage(Phase.ERROR, MEMBERSHIP_UNAVAILABLE_LINE)
        is NoteReviewResult.RateLimited -> PhaseAndMessage(Phase.ERROR, RATE_LIMITED_LINE)
        is NoteReviewResult.Error ->
            PhaseAndMessage(Phase.ERROR, result.message.ifBlank { GENERIC_ERROR_LINE })
    }
}
