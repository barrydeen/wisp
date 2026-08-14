package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one thing about the sign-in error card that can be tested off a
 * device: an error does not claim sign-in failed unless its producer says so.
 *
 * `GoogleAuthViewModel.State.Error` is raised from six places. Two of them sit
 * in `beginSignIn` and may or may not be a sign-in failure — the Drive listing
 * runs inside the same try. The other four (PIN check, PIN setup, restore,
 * create) all run after the member has signed in, and headlining their failures
 * "Google sign-in didn't go through" would put a false sentence in front of
 * them. So the flag defaults to the claim we cannot make, and a producer added
 * later that forgets it gets the card as it renders today rather than a wrong
 * headline.
 *
 * What has no JVM seam and is therefore unverified here: the card itself
 * (Compose, no UI test dependency in this module) and `signInPending()`, which
 * reads the view model's own state and needs an `AndroidViewModel` instance.
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
}
