package cooking.zap.app.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.LocalSigner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live NIP-98 round-trip against the zap.cooking backend — the Concern 2
 * acceptance gate (a real request the backend accepts).
 *
 * This is an INTEGRATION test, deliberately in the `androidTest` source set
 * so it is EXCLUDED from the hermetic unit suite (`:app:testDebugUnitTest`).
 * It requires:
 *   - a connected device/emulator with network egress (also: signing uses
 *     the secp256k1 Android JNI, which only runs on-device), and
 *   - `MEMBERSHIP_ENABLED=true` on the server.
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest`
 *
 * No member key is needed: `check-status` returns `{ found:false, owner:true }`
 * for a verified signature from a non-member, so an ephemeral key proves the
 * backend accepted our NIP-98 — `owner == true` is the whole assertion.
 */
@RunWith(AndroidJUnit4::class)
class Nip98RoundTripTest {

    @Test
    fun checkStatus_acceptsEphemeralNip98Signature() = runBlocking {
        val keypair = Keys.generate()
        val signer = LocalSigner(keypair.privkey, keypair.pubkey)

        val status = ZapCookingApi().checkMembershipStatus(signer)

        assertTrue(
            "Expected owner==true (backend accepted the NIP-98 signature); got $status",
            status.owner
        )
    }
}
