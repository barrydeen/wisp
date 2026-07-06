package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure gate for the background delete-sign failure policy ([signerDeleteFailureAction]). The live
 * retry (silent ContentResolver sign) and the toast need Android and are exercised on-device; the
 * classification that decides retry-vs-give-up is pure and pinned here.
 *
 * The distinction matters on RemoteSigner: a user who dismissed the prompt must NOT be re-prompted
 * (that popup is exactly what we're eliminating), whereas a rejection may be an automatic
 * permissions auto-reject, so it's worth one silent retry.
 */
class SignerDeleteFailureActionTest {

    @Test
    fun `cancel gives up (never re-prompts)`() {
        assertEquals(
            SignerFailureAction.GIVE_UP,
            signerDeleteFailureAction(SignerCancelledException("user dismissed"))
        )
    }

    @Test
    fun `reject retries via the silent path`() {
        assertEquals(
            SignerFailureAction.RETRY_SILENT,
            signerDeleteFailureAction(SignerRejectedException("auto-reject"))
        )
    }

    @Test
    fun `other exceptions give up`() {
        assertEquals(SignerFailureAction.GIVE_UP, signerDeleteFailureAction(RuntimeException("boom")))
        assertEquals(SignerFailureAction.GIVE_UP, signerDeleteFailureAction(java.io.IOException("io")))
    }
}
