package cooking.zap.app.viewmodel

import cooking.zap.app.auth.DriveAuthorizationException
import cooking.zap.app.auth.GoogleSignInException
import cooking.zap.app.auth.claimsSignInFailed
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one sentence the sign-in error card asserts on the member's behalf:
 * "Google sign-in didn't go through."
 *
 * `GoogleAuthViewModel.State.Error` is raised from six places, and the card
 * renders all six. Two of them sit in `beginSignIn`, which is where the claim
 * has to be decided, and deciding it takes two facts because two things can be
 * false about that sentence:
 *
 *  - **Phase** — `beginSignIn` runs `signIn()` and then the Drive *listing*
 *    under one `try`, so a listing failure is a failure after signing in.
 *  - **Half** — `signIn()` is itself credentials *then* the Drive *grant*, and
 *    both run under `SigningIn`. The state cannot separate those; only
 *    [DriveAuthorizationException] can. This is the common path, not a corner:
 *    the Drive consent screen always appears the first time an account grants
 *    the scope, and declining it is one gesture — so the member most likely to
 *    see this card is one whose sign-in demonstrably worked.
 *
 * What has no JVM seam and is therefore unverified here: the card itself
 * (Compose, no UI test dependency in this module), `signInPending()`, which
 * needs an `AndroidViewModel` instance, and the wrapper in
 * `getDriveAccessToken` that re-types what `authorize()` raises.
 */
class GoogleAuthErrorClaimTest {

    @Test
    fun `an error does not claim sign-in failed unless its producer says so`() {
        assertFalse(GoogleAuthViewModel.State.Error("Failed to restore account.").duringSignIn)
    }

    @Test
    fun `a producer can mark an error as a sign-in failure`() {
        assertTrue(
            GoogleAuthViewModel.State.Error(
                "Google sign-in cancelled or unavailable: No credentials available",
                duringSignIn = true
            ).duringSignIn
        )
    }

    @Test
    fun `a credential failure while signing in is a sign-in failure`() {
        assertTrue(
            claimsSignInFailed(
                signInPending = true,
                error = GoogleSignInException(
                    "Google sign-in cancelled or unavailable: No credentials available"
                )
            )
        )
    }

    @Test
    fun `a declined Drive consent is not a sign-in failure`() {
        // The member picked their account, it worked, and they backed out of
        // the drive.appdata grant. Telling them sign-in didn't go through is
        // the one thing on this card that would be flatly false.
        assertFalse(
            claimsSignInFailed(
                signInPending = true,
                error = DriveAuthorizationException("Authorization resolution failed: null")
            )
        )
    }

    @Test
    fun `a missing Drive access token is not a sign-in failure`() {
        assertFalse(
            claimsSignInFailed(
                signInPending = true,
                error = DriveAuthorizationException("No access token returned")
            )
        )
    }

    @Test
    fun `a missing resolution intent is not a sign-in failure`() {
        assertFalse(
            claimsSignInFailed(
                signInPending = true,
                error = DriveAuthorizationException(
                    "Authorization required but no pending intent provided"
                )
            )
        )
    }

    @Test
    fun `nothing after the sign-in phase is a sign-in failure`() {
        // The Drive listing, which shares beginSignIn's try but runs under
        // CheckingDrive.
        assertFalse(claimsSignInFailed(signInPending = false, error = IOException("timeout")))
    }

    @Test
    fun `a generic failure after the sign-in phase is not a sign-in failure`() {
        assertFalse(
            claimsSignInFailed(
                signInPending = false,
                error = GoogleSignInException("Google sign-in failed.")
            )
        )
    }

    @Test
    fun `an unrecognised failure while signing in is still a sign-in failure`() {
        // beginSignIn's second catch is untyped; anything that is not
        // identifiably the Drive half is treated as the sign-in half.
        assertTrue(claimsSignInFailed(signInPending = true, error = IllegalStateException("?")))
    }

    @Test
    fun `the Drive exception is a GoogleSignInException`() {
        // It narrows rather than sits beside, so no existing catch changes
        // meaning. If that ever inverts, the ViewModel's first catch stops
        // seeing these at all and this test says so.
        assertTrue(
            DriveAuthorizationException("No access token returned") is GoogleSignInException
        )
    }
}
