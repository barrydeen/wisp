package cooking.zap.app.viewmodel

import cooking.zap.app.api.MembershipStatus
import cooking.zap.app.viewmodel.AboutViewModel.Expiry
import cooking.zap.app.viewmodel.AboutViewModel.MembershipRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The About screen's membership row, exercised through the pure decision
 * functions ([AboutViewModel.toRow] / [AboutViewModel.expiry]).
 *
 * The load-bearing assertion in this file is a **negative** one: no input
 * produces a tier noun, because the tier the server sends is wrong for every
 * real member (it normalizes to `'open'` — the *free* tier — see
 * `RESEARCH/ANDROID_LAUNCH_ITEMS_VERIFICATION_2026_07_27.md`). [MembershipRow]
 * carries no tier field at all, so that guarantee is structural; these tests
 * pin the rest of the behaviour that keeps it honest.
 */
class AboutMembershipRowTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun status(body: String) =
        json.decodeFromString(MembershipStatus.serializer(), body)

    /**
     * The real owner response for a paying member — note `"tier":"open"`,
     * which is exactly what the endpoint sends for someone who paid $49.
     * The row must say Active plus the date and nothing more.
     */
    @Test
    fun `active owner yields state and date`() {
        val row = AboutViewModel.toRow(
            status(
                """
                {"found":true,"isActive":true,"isExpired":false,"owner":true,
                 "member":{"pubkey":"ab","tier":"open","status":"active",
                 "subscription_end":"2027-01-15T00:00:00.000Z",
                 "subscription_start":"2026-01-15T00:00:00.000Z","payment_method":null}}
                """.trimIndent()
            )
        )
        assertEquals(
            MembershipRow.Active(Expiry.On(Instant.parse("2027-01-15T00:00:00.000Z").toEpochMilli())),
            row,
        )
    }

    /** An owner whose membership lapsed: "no active membership", not an error. */
    @Test
    fun `inactive owner yields inactive`() {
        val row = AboutViewModel.toRow(
            status(
                """
                {"found":true,"isActive":false,"isExpired":true,"owner":true,
                 "member":{"tier":"open","status":"expired",
                 "subscription_end":"2026-01-15T00:00:00.000Z"}}
                """.trimIndent()
            )
        )
        assertEquals(MembershipRow.Inactive, row)
    }

    /** An owner the relay has no record of — `{found:false, owner:true}`. */
    @Test
    fun `owner with no record yields inactive`() {
        assertEquals(
            MembershipRow.Inactive,
            AboutViewModel.toRow(status("""{"found":false,"owner":true}""")),
        )
    }

    /**
     * `check-status` does NOT error on a bad or absent NIP-98 header — it
     * silently degrades to the public shape, which has no `owner` flag. Without
     * that proof we never render a membership claim, even though this body
     * carries `isActive:true`: it is an unauthenticated answer, and the screen's
     * whole job is to be trustworthy about entitlement.
     */
    @Test
    fun `public degraded shape yields unavailable, not a claim`() {
        assertEquals(
            MembershipRow.Unavailable,
            AboutViewModel.toRow(
                status("""{"found":true,"isActive":true,"member":{"tier":"member"}}""")
            ),
        )
    }

    /**
     * Founders carry a raw stored `subscription_end` — the web's lifetime bump
     * is gated on `tier === 'founders'` and never fires. We render that date
     * plainly rather than inventing a "Lifetime" label: under-claiming to four
     * people we can name beats over-claiming. A far-future date is therefore
     * just a date, with no special branch.
     */
    @Test
    fun `far future expiry is still just a date`() {
        assertEquals(
            Expiry.On(Instant.parse("2036-07-27T00:00:00.000Z").toEpochMilli()),
            AboutViewModel.expiry("2036-07-27T00:00:00.000Z"),
        )
    }

    /**
     * A missing or malformed date degrades to [Expiry.Unknown] — the row then
     * says "Active" alone. A membership line is never worth throwing on.
     */
    @Test
    fun `missing or unparseable expiry is unknown`() {
        assertEquals(Expiry.Unknown, AboutViewModel.expiry(null))
        assertEquals(Expiry.Unknown, AboutViewModel.expiry(""))
        assertEquals(Expiry.Unknown, AboutViewModel.expiry("2027-01-15 00:00:00"))
    }
}
