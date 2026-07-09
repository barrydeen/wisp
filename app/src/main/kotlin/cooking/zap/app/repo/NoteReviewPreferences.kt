package cooking.zap.app.repo

import android.content.Context
import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.cheffy.NoteReview

/**
 * Seam for the Note Review disclosure preference so the ViewModel's
 * seed/persist logic is unit-testable without Android (the
 * [WalletProvider] precedent). Production is [NoteReviewPreferences].
 */
interface DisclosurePreferences {
    fun isDisclosureEnabled(mode: NoteReviewMode): Boolean
    fun setDisclosureEnabled(mode: NoteReviewMode, enabled: Boolean)
}

/**
 * Cheffy Note Review preferences (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 4) —
 * house SharedPreferences pattern (see [ZapPreferences]). Replaces the
 * web's `zapcooking_note_review_disclosure_{comment,recipe}` localStorage
 * keys: one per-mode boolean for the "via Cheffy" disclosure toggle,
 * defaulting per [NoteReview.defaultDisclosure] (comment OFF — the
 * member's own voice; recipe ON — Cheffy's structured work product).
 * Phase 5 adds the pending-invoice keys here.
 */
class NoteReviewPreferences(context: Context) : DisclosurePreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isDisclosureEnabled(mode: NoteReviewMode): Boolean =
        prefs.getBoolean(disclosureKey(mode), NoteReview.defaultDisclosure(mode))

    override fun setDisclosureEnabled(mode: NoteReviewMode, enabled: Boolean) {
        prefs.edit().putBoolean(disclosureKey(mode), enabled).apply()
    }

    private fun disclosureKey(mode: NoteReviewMode): String = when (mode) {
        NoteReviewMode.COMMENT -> KEY_DISCLOSURE_COMMENT
        NoteReviewMode.RECIPE -> KEY_DISCLOSURE_RECIPE
    }

    companion object {
        private const val PREFS_NAME = "note_review_prefs"
        private const val KEY_DISCLOSURE_COMMENT = "disclosure_comment"
        private const val KEY_DISCLOSURE_RECIPE = "disclosure_recipe"
    }
}
