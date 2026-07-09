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

/** A minted-but-unresolved 21-sat invoice, persisted for the resume flow. */
data class StoredInvoice(val invoiceId: String, val bolt11: String, val expiresAtMillis: Long)

/**
 * Pending-invoice persistence seam (Phase 5b, replacing the web's
 * `zapcooking_note_review_pending_invoice` localStorage key). If the
 * payer closes between paying and the poll observing paid, the invoice
 * survives here; the next sheet open polls it once and credits them
 * (server metadata stays creditable for 48h). Cleared ONLY on an
 * observed paid or expired — a check failure never destroys a
 * potentially-paid invoice (invariant 6).
 */
interface PendingInvoiceStore {
    fun storePendingInvoice(invoice: StoredInvoice)
    fun loadPendingInvoice(): StoredInvoice?
    fun clearPendingInvoice()
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
class NoteReviewPreferences(context: Context) : DisclosurePreferences, PendingInvoiceStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun storePendingInvoice(invoice: StoredInvoice) {
        prefs.edit()
            .putString(KEY_PENDING_INVOICE_ID, invoice.invoiceId)
            .putString(KEY_PENDING_BOLT11, invoice.bolt11)
            .putLong(KEY_PENDING_EXPIRES_AT, invoice.expiresAtMillis)
            .apply()
    }

    override fun loadPendingInvoice(): StoredInvoice? {
        val id = prefs.getString(KEY_PENDING_INVOICE_ID, null) ?: return null
        val bolt11 = prefs.getString(KEY_PENDING_BOLT11, null) ?: return null
        return StoredInvoice(id, bolt11, prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L))
    }

    override fun clearPendingInvoice() {
        prefs.edit()
            .remove(KEY_PENDING_INVOICE_ID)
            .remove(KEY_PENDING_BOLT11)
            .remove(KEY_PENDING_EXPIRES_AT)
            .apply()
    }

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
        private const val KEY_PENDING_INVOICE_ID = "pending_invoice_id"
        private const val KEY_PENDING_BOLT11 = "pending_invoice_bolt11"
        private const val KEY_PENDING_EXPIRES_AT = "pending_invoice_expires_at"
    }
}
