package com.wisp.app.viewmodel

import com.wisp.app.repo.NwcRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup flow verifies a pasted NWC URI with one short round-trip before
 * declaring success, so a revoked or offline connection alerts within seconds
 * instead of dismissing into a silently broken dashboard. These pin the
 * user-facing wording per verification outcome. Ported from wisp-ios
 * NwcSetupVerificationTests.
 */
class NwcSetupVerificationTest {

    @Test
    fun `unauthorized refusal names revocation`() {
        val message = WalletViewModel.nwcSetupFailureMessage(
            NwcRepository.VerifyOutcome.Refused("UNAUTHORIZED", "bad secret")
        )
        assertTrue(message.contains("revoked"))
    }

    @Test
    fun `other refusal carries wallet message`() {
        val message = WalletViewModel.nwcSetupFailureMessage(
            NwcRepository.VerifyOutcome.Refused("INTERNAL", "boom")
        )
        assertEquals("The wallet rejected the request: boom.", message)
    }

    @Test
    fun `other refusal without message shows code`() {
        val message = WalletViewModel.nwcSetupFailureMessage(
            NwcRepository.VerifyOutcome.Refused("QUOTA_EXCEEDED", null)
        )
        assertEquals("The wallet rejected the request (QUOTA_EXCEEDED).", message)
    }

    @Test
    fun `unresponsive explains silence`() {
        val message = WalletViewModel.nwcSetupFailureMessage(
            NwcRepository.VerifyOutcome.Unresponsive
        )
        assertTrue(message.contains("No response"))
        assertTrue(message.contains("revoked") || message.contains("offline"))
    }

    @Test
    fun `confirmed is not a failure`() {
        assertEquals(
            "Connected",
            WalletViewModel.nwcSetupFailureMessage(NwcRepository.VerifyOutcome.Confirmed)
        )
    }
}
