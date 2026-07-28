package cooking.zap.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReportOutcome] — the classification a reporter is shown, and the invariant that the reported
 * message may only disappear on a send the relay actually took.
 *
 * The surrounding result path (GroupRoomViewModel.report → GroupRoomScreen toast) is not covered
 * here: `GroupRoomViewModel` is an `AndroidViewModel` and this module has no Robolectric, so the
 * wiring needs the device pass.
 */
class ReportOutcomeTest {

    @Test
    fun `no signing key is NEEDS_KEY regardless of relay state`() {
        assertEquals(
            ReportOutcome.NEEDS_KEY,
            ReportOutcome.of(hasSigner = false, hasRelay = true, relayAccepted = true)
        )
        assertEquals(
            ReportOutcome.NEEDS_KEY,
            ReportOutcome.of(hasSigner = false, hasRelay = false, relayAccepted = false)
        )
    }

    @Test
    fun `an uninitialised relay pool is a failure, not a missing key`() {
        assertEquals(
            ReportOutcome.FAILED,
            ReportOutcome.of(hasSigner = true, hasRelay = false, relayAccepted = false)
        )
    }

    @Test
    fun `a relay that refuses the event is a failure`() {
        assertEquals(
            ReportOutcome.FAILED,
            ReportOutcome.of(hasSigner = true, hasRelay = true, relayAccepted = false)
        )
    }

    @Test
    fun `SENT requires a signer, a relay, and acceptance`() {
        assertEquals(
            ReportOutcome.SENT,
            ReportOutcome.of(hasSigner = true, hasRelay = true, relayAccepted = true)
        )
    }

    @Test
    fun `only a sent report hides the reported message`() {
        assertTrue(ReportOutcome.SENT.hidesReportedMessage)
        assertFalse(ReportOutcome.FAILED.hidesReportedMessage)
        assertFalse(ReportOutcome.NEEDS_KEY.hidesReportedMessage)
    }

    @Test
    fun `every outcome is reachable from of`() {
        val produced = listOf(
            ReportOutcome.of(hasSigner = false, hasRelay = true, relayAccepted = true),
            ReportOutcome.of(hasSigner = true, hasRelay = true, relayAccepted = false),
            ReportOutcome.of(hasSigner = true, hasRelay = true, relayAccepted = true),
        ).toSet()
        assertEquals(ReportOutcome.entries.toSet(), produced)
    }
}
