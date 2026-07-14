package cooking.zap.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the web encryptionService circuit-breaker semantics ported by
 * [SignerDecryptGate]: 2 consecutive denials trip for the session, a success
 * resets the streak, a denied ciphertext is never re-attempted, and non-denial
 * failures are not prompt fatigue.
 */
class SignerDecryptGateTest {

    @Test
    fun twoConsecutiveDenials_tripForTheSession() = runBlocking {
        val gate = SignerDecryptGate()
        var attempts = 0
        val deny: suspend () -> String = { attempts++; throw SignerRejectedException("no") }

        assertNull(gate.attempt("ct1", deny))
        assertFalse(gate.isTripped)
        assertNull(gate.attempt("ct2", deny))
        assertTrue(gate.isTripped)
        assertEquals(2, attempts)

        // Tripped: further attempts are refused without touching the signer.
        assertNull(gate.attempt("ct3") { attempts++; "plain" })
        assertEquals(2, attempts)
        assertFalse(gate.canAttempt("ct3"))
    }

    @Test
    fun successResetsTheDenialStreak() = runBlocking {
        val gate = SignerDecryptGate()
        assertNull(gate.attempt("ct1") { throw SignerRejectedException("no") })
        assertEquals("ok", gate.attempt("ct2") { "ok" })
        // Streak reset — one more denial must NOT trip.
        assertNull(gate.attempt("ct3") { throw SignerCancelledException("dismissed") })
        assertFalse(gate.isTripped)
        assertTrue(gate.canAttempt("ct4"))
    }

    @Test
    fun deniedCiphertextIsNeverRetried_evenBeforeTripping() = runBlocking {
        val gate = SignerDecryptGate()
        var attempts = 0
        assertNull(gate.attempt("same-ct") { attempts++; throw SignerCancelledException("no") })
        // Web parity: the DECRYPT_DENIED sentinel blocks this exact payload…
        assertNull(gate.attempt("same-ct") { attempts++; "would succeed now" })
        assertEquals(1, attempts)
        // …while other payloads are still attempted.
        assertEquals("ok", gate.attempt("other-ct") { "ok" })
    }

    @Test
    fun nonDenialFailures_propagateAndDontCount() = runBlocking {
        val gate = SignerDecryptGate()
        var thrown = false
        try {
            gate.attempt("ct1") { throw IllegalStateException("bad ciphertext") }
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
        // Not a denial: streak untouched, same ciphertext still attemptable.
        assertFalse(gate.isTripped)
        assertTrue(gate.canAttempt("ct1"))
        // And two real denials are still needed to trip.
        assertNull(gate.attempt("ct2") { throw SignerRejectedException("no") })
        assertFalse(gate.isTripped)
    }

    @Test
    fun resetClearsEverything() = runBlocking {
        val gate = SignerDecryptGate()
        assertNull(gate.attempt("ct1") { throw SignerRejectedException("no") })
        assertNull(gate.attempt("ct2") { throw SignerRejectedException("no") })
        assertTrue(gate.isTripped)

        gate.reset()
        assertFalse(gate.isTripped)
        assertTrue(gate.canAttempt("ct1")) // denied-sentinel cleared too
        assertEquals("ok", gate.attempt("ct1") { "ok" })
    }
}
