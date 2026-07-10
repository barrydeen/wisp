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
 * A minted-but-unresolved 21-sat invoice, persisted for the resume flow.
 * [ownerPubkey] is the signer the invoice is server-bound to (audit B1):
 * the server credits only that pubkey, so no other account may ever be
 * shown — or pay — this bolt11.
 */
data class StoredInvoice(
    val invoiceId: String,
    val bolt11: String,
    val expiresAtMillis: Long,
    val ownerPubkey: String,
)

/**
 * Pending-invoice persistence seam (Phase 5b, replacing the web's
 * `zapcooking_note_review_pending_invoice` localStorage key). If the
 * payer closes between paying and the poll observing paid, the invoice
 * survives here; the next sheet open polls it once and credits them
 * (server metadata stays creditable for 48h). Cleared ONLY on an
 * observed paid or expired — a check failure never destroys a
 * potentially-paid invoice (invariant 6).
 *
 * Identity scoping (audit B1) is defense in depth: production storage is
 * a per-pubkey prefs file AND every record carries its owner, with
 * [loadPendingInvoice] refusing mismatches.
 */
interface PendingInvoiceStore {
    fun storePendingInvoice(invoice: StoredInvoice)

    /** Raw read of whatever is persisted, owner included. */
    fun loadPendingInvoiceRecord(): StoredInvoice?

    fun clearPendingInvoice()

    /**
     * Owned read — the only read the payment flow may use. A record whose
     * owner is not [ownerPubkey] reads as ABSENT (never deleted: the
     * owner may still resume it from their own session).
     */
    fun loadPendingInvoice(ownerPubkey: String): StoredInvoice? =
        loadPendingInvoiceRecord()?.takeIf { it.ownerPubkey == ownerPubkey }
}

/**
 * Cheffy Note Review preferences (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 4) —
 * house **per-account** SharedPreferences pattern (see [ZapPreferences]):
 * one file per pubkey, so neither the disclosure toggles nor the pending
 * invoice can leak across an account switch (audit B1/N10). Replaces the
 * web's `zapcooking_note_review_*` localStorage keys. Disclosure booleans
 * default per [NoteReview.defaultDisclosure] (comment OFF — the member's
 * own voice; recipe ON — Cheffy's structured work product).
 *
 * No migration from the pre-release global file: the feature never
 * shipped with it (flag was off), so there is no user data to carry.
 */
class NoteReviewPreferences(
    context: Context,
    pubkeyHex: String?,
) : DisclosurePreferences, PendingInvoiceStore {

    private val prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    override fun storePendingInvoice(invoice: StoredInvoice) {
        prefs.edit()
            .putString(KEY_PENDING_INVOICE_ID, invoice.invoiceId)
            .putString(KEY_PENDING_BOLT11, invoice.bolt11)
            .putLong(KEY_PENDING_EXPIRES_AT, invoice.expiresAtMillis)
            .putString(KEY_PENDING_OWNER, invoice.ownerPubkey)
            .apply()
    }

    override fun loadPendingInvoiceRecord(): StoredInvoice? {
        val id = prefs.getString(KEY_PENDING_INVOICE_ID, null) ?: return null
        val bolt11 = prefs.getString(KEY_PENDING_BOLT11, null) ?: return null
        val owner = prefs.getString(KEY_PENDING_OWNER, null) ?: return null
        return StoredInvoice(id, bolt11, prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L), owner)
    }

    override fun clearPendingInvoice() {
        prefs.edit()
            .remove(KEY_PENDING_INVOICE_ID)
            .remove(KEY_PENDING_BOLT11)
            .remove(KEY_PENDING_EXPIRES_AT)
            .remove(KEY_PENDING_OWNER)
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
        private fun prefsName(pubkeyHex: String?) =
            if (pubkeyHex != null) "note_review_prefs_$pubkeyHex" else "note_review_prefs"

        private const val KEY_DISCLOSURE_COMMENT = "disclosure_comment"
        private const val KEY_DISCLOSURE_RECIPE = "disclosure_recipe"
        private const val KEY_PENDING_INVOICE_ID = "pending_invoice_id"
        private const val KEY_PENDING_BOLT11 = "pending_invoice_bolt11"
        private const val KEY_PENDING_EXPIRES_AT = "pending_invoice_expires_at"
        private const val KEY_PENDING_OWNER = "pending_invoice_owner"
    }
}
