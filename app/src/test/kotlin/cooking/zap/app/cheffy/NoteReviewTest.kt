package cooking.zap.app.cheffy

import cooking.zap.app.api.NoteReviewResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NoteReview.phaseForResult] — the pure core of the modal's state
 * machine, mirroring the web `noteReview.ts` `phaseForResult` tests and
 * pinning the copy pools verbatim.
 */
class NoteReviewTest {

    @Test
    fun deadEndLinesAreTheWebPoolVerbatim() {
        assertEquals(
            listOf(
                "Cheffy couldn't get a good look at that photo. It might be a broken link — or just not clearly a dish.",
                "That photo's playing hard to get — Cheffy can't quite make out a dish in it.",
                "Cheffy squinted, but couldn't spot a dish in there. The photo may not have come through.",
                "Hmm — Cheffy couldn't see this one clearly. The link may be stale, or the dish is camera-shy.",
            ),
            NoteReview.DEAD_END_LINES,
        )
    }

    @Test
    fun success_mapsToDraft() {
        val next = NoteReview.phaseForResult(NoteReviewResult.Success("A draft", 2))
        assertEquals(NoteReview.Phase.DRAFT, next.phase)
        assertEquals("", next.message)
    }

    @Test
    fun notMember_mapsToUpsell() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.UPSELL, ""),
            NoteReview.phaseForResult(NoteReviewResult.NotMember),
        )
    }

    @Test
    fun deadEnd_mapsToDeadEnd_withALineFromThePool() {
        val next = NoteReview.phaseForResult(NoteReviewResult.DeadEnd)
        assertEquals(NoteReview.Phase.DEAD_END, next.phase)
        assertTrue(next.message in NoteReview.DEAD_END_LINES)
    }

    @Test
    fun deadEnd_rotatesAwayFromThePreviousLine() {
        // pickLine guarantees the avoided line is never returned for
        // pools with more than one entry — try every line as "previous".
        for (previous in NoteReview.DEAD_END_LINES) {
            repeat(20) {
                val next = NoteReview.phaseForResult(NoteReviewResult.DeadEnd, avoidDeadEndLine = previous)
                assertNotEquals(previous, next.message)
                assertTrue(next.message in NoteReview.DEAD_END_LINES)
            }
        }
    }

    @Test
    fun signFailed_mapsToError_withTheSignFailedLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.SIGN_FAILED_LINE),
            NoteReview.phaseForResult(NoteReviewResult.SignFailed),
        )
    }

    @Test
    fun membershipUnavailable_isRetryableError_neverUpsell() {
        // Deviation D5: the endpoint fails closed on a membership-service
        // outage — an upsell here would dun a member over our outage.
        val next = NoteReview.phaseForResult(NoteReviewResult.MembershipUnavailable)
        assertEquals(NoteReview.Phase.ERROR, next.phase)
        assertNotEquals(NoteReview.Phase.UPSELL, next.phase)
        assertEquals(NoteReview.MEMBERSHIP_UNAVAILABLE_LINE, next.message)
    }

    @Test
    fun rateLimited_mapsToError_withTheBreatherLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.RATE_LIMITED_LINE),
            NoteReview.phaseForResult(NoteReviewResult.RateLimited(1800)),
        )
    }

    @Test
    fun error_mapsToError_passingTheMessageThrough() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, "Network error"),
            NoteReview.phaseForResult(NoteReviewResult.Error("Network error")),
        )
    }

    @Test
    fun blankErrorMessage_fallsBackToTheGenericLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.GENERIC_ERROR_LINE),
            NoteReview.phaseForResult(NoteReviewResult.Error("  ")),
        )
    }
}
