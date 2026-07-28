package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.MembershipStatus
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Backs the About screen's membership readout (concern: about-screen).
 *
 * ## Source: the NIP-98 owner read, and only that
 *
 * Membership comes from [ZapCookingApi.checkMembershipStatus] — the signed
 * owner lookup — because it is the only endpoint that returns
 * `subscription_end`, and a row that cannot say *until when* is not worth
 * showing. An account with no signer (READ_ONLY) cannot make this call at all;
 * it renders **nothing** ([MembershipRow.Hidden]).
 *
 * There is deliberately **no fallback to the public batch read**
 * ([ZapCookingApi.getPublicMembership]): its `tier` carries the same defect
 * described below, and its `expiresAt` is a per-pubkey disclosure the Founders
 * work is removing. An unauthenticated guess is worse than silence here.
 *
 * ## What this class refuses to render
 *
 * **`tier` is not trustworthy and is never read.** Traced end to end in
 * `RESEARCH/ANDROID_LAUNCH_ITEMS_VERIFICATION_2026_07_27.md`: member-relay's
 * `GET /api/members/:pubkey` does not select `payment_id`, so the frontend's
 * `normalizeRelayTier` cannot reach its `genesis_`/founder branch, and the
 * stored value (always `'standard'`) matches no branch either — every real
 * member's response therefore says `tier: 'open'`, the name of the **free**
 * tier. Rendering it would tell someone who paid $49 that they are on the free
 * plan. `payment_method` is unusable for the same reason (not selected → always
 * null).
 *
 * So the row asserts **a state and a date, no tier noun**, and branches on
 * nothing but `isActive`. That also means **no "Lifetime" branch**: the
 * Founders lifetime bump on the web is gated on `tier === 'founders'` and
 * therefore never fires, so Founders carry a raw stored `subscription_end`.
 * We render that date plainly. Under-claiming to four people we can name beats
 * over-claiming to everyone.
 *
 * This screen is a **readout only**: no purchase path, no membership deep link.
 * It is therefore flavor-neutral and independent of `MEMBERSHIP_LINKOUT_ENABLED`.
 */
class AboutViewModel : ViewModel() {

    /** What the membership row shows. Rendered by `AboutScreen`. */
    sealed interface MembershipRow {
        /**
         * No signer — signed out, or a READ_ONLY account that cannot sign
         * NIP-98. The section renders nothing at all rather than a claim it
         * has no authenticated basis for.
         */
        data object Hidden : MembershipRow
        /** Fetch in flight. */
        data object Checking : MembershipRow
        /** Server verified our NIP-98 and answered: an active membership. */
        data class Active(val expiry: Expiry) : MembershipRow
        /** Server verified our NIP-98 and answered: no active membership. */
        data object Inactive : MembershipRow
        /** Fetch failed, or the server never confirmed ownership — say so. */
        data object Unavailable : MembershipRow
    }

    /**
     * Expiry of an active membership, resolved but not yet formatted — the date
     * formatting is the UI's job (it is locale-dependent).
     */
    sealed interface Expiry {
        /** No usable `subscription_end` on the record. */
        data object Unknown : Expiry
        data class On(val epochMillis: Long) : Expiry
    }

    // Starts Hidden, not Checking: [fetch] runs from a LaunchedEffect after the
    // first composition, so a Checking default would flash a "Membership /
    // Checking…" header for one frame at every signer-less entry and then
    // remove it.
    private val _row = MutableStateFlow<MembershipRow>(MembershipRow.Hidden)
    val row: StateFlow<MembershipRow> = _row

    private var fetched = false

    /**
     * Fetch once per screen entry (once per ViewModel instance).
     *
     * A failure lands on [MembershipRow.Unavailable] — distinct from
     * [MembershipRow.Inactive], because telling a paying member "no membership"
     * because the network blipped is the confusion this screen exists to
     * remove. A declined external-signer prompt takes the same path: it is a
     * user choice, not a membership fact.
     *
     * The [fetched] latch means this is **not** a retry entry point: a second
     * call on a live instance returns without touching [row], so a retry
     * control wired straight to it would do nothing and look like it had
     * worked. Adding retry is a change to this guard first and a button
     * second, in that order. The recovery that exists today is leaving and
     * re-entering the screen — this ViewModel is scoped to the `ABOUT`
     * backstack entry, so that is a fresh instance.
     */
    fun fetch(api: ZapCookingApi, signer: NostrSigner?) {
        if (fetched) return
        fetched = true
        if (signer == null) {
            _row.value = MembershipRow.Hidden
            return
        }
        _row.value = MembershipRow.Checking
        viewModelScope.launch {
            _row.value = try {
                toRow(api.checkMembershipStatus(signer))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MembershipRow.Unavailable
            }
        }
    }

    companion object {

        /**
         * Pure: [MembershipStatus] → the row to render. Unit-tested.
         *
         * [MembershipStatus.owner] is the gate. An absent, invalid, or
         * mismatched NIP-98 signature does **not** error — `check-status`
         * silently degrades to the public shape — so `owner` is our only proof
         * the server answered *us about us*. Without it we say we could not
         * check, rather than render an unauthenticated shape as if it were the
         * member's own record.
         */
        fun toRow(status: MembershipStatus): MembershipRow = when {
            !status.owner -> MembershipRow.Unavailable
            status.isActive -> MembershipRow.Active(expiry(status.member?.subscription_end))
            else -> MembershipRow.Inactive
        }

        /**
         * Pure: the ISO-8601 `subscription_end` the server sends (e.g.
         * `2027-01-01T00:00:00.000Z`) → [Expiry]. Missing or unparseable yields
         * [Expiry.Unknown] rather than throwing; a membership row is never
         * worth crashing a settings screen over.
         */
        fun expiry(subscriptionEnd: String?): Expiry {
            if (subscriptionEnd.isNullOrBlank()) return Expiry.Unknown
            return try {
                Expiry.On(Instant.parse(subscriptionEnd).toEpochMilli())
            } catch (e: DateTimeParseException) {
                Expiry.Unknown
            }
        }
    }
}
