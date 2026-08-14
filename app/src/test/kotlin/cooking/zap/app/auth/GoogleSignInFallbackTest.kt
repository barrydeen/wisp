package cooking.zap.app.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one decision in the sign-in escalation: which Credential Manager
 * failures are worth a second prompt.
 *
 * The bug this guards against is a member with a Google account on the device
 * being told "No credentials available" and having nowhere to go — the one-tap
 * sheet declines to offer a credential and the flow ends there. The escalation
 * to the branded button flow exists for exactly that case.
 *
 * The mirror-image bug is escalating on a cancellation: the member closed the
 * sheet on purpose and would get a second dialog for their trouble. Everything
 * that is not [NoCredentialException] is a real answer and must be left alone.
 *
 * The rest of [GoogleSignInManager] needs a live `ComponentActivity` and Play
 * Services, so it has no JVM seam — this is the piece that can be tested off a
 * device, and it is the piece that carries the policy.
 */
class GoogleSignInFallbackTest {

    @Test
    fun `no credential escalates to the button flow`() {
        assertTrue(shouldFallBackToButtonFlow(NoCredentialException()))
    }

    @Test
    fun `no credential escalates regardless of the provider's message`() {
        assertTrue(shouldFallBackToButtonFlow(NoCredentialException("No credentials available")))
    }

    @Test
    fun `cancellation does not escalate`() {
        assertFalse(shouldFallBackToButtonFlow(GetCredentialCancellationException()))
    }

    @Test
    fun `interruption does not escalate`() {
        assertFalse(shouldFallBackToButtonFlow(GetCredentialInterruptedException()))
    }

    @Test
    fun `unknown failures do not escalate`() {
        assertFalse(shouldFallBackToButtonFlow(GetCredentialUnknownException()))
    }

    @Test
    fun `unsupported providers do not escalate`() {
        assertFalse(shouldFallBackToButtonFlow(GetCredentialUnsupportedException()))
    }

    @Test
    fun `provider misconfiguration does not escalate`() {
        assertFalse(shouldFallBackToButtonFlow(GetCredentialProviderConfigurationException()))
    }
}
