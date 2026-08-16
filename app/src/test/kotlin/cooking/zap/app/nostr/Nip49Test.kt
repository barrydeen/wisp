package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * NIP-49 (`ncryptsec`) conformance.
 *
 * The load-bearing case is [decrypt_officialTestVector]: the spec's published
 * ncryptsec, decrypted with the spec's password to the spec's private key. It
 * exercises scrypt, HChaCha20, ChaCha20-Poly1305, the AAD binding and the bech32
 * layout end to end, so a break anywhere in that chain fails it. Everything else
 * here is a round-trip or an error-path guard.
 *
 * Round-trip cases use log_n = 8 to stay fast; only the official vector pays the
 * 64 MiB / log_n = 16 cost. Keys are raw random bytes rather than
 * `Keys.generate()` because `Keys` triggers secp256k1 JNI init at class load,
 * which is unavailable in the JVM unit suite (see SparkDerivationTest).
 */
class Nip49Test {

    private val random = SecureRandom()

    private fun randomPrivkey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    @Test
    fun decrypt_officialTestVector() {
        val decrypted = Nip49.decryptWithMetadata(OFFICIAL_NCRYPTSEC, OFFICIAL_PASSWORD)

        assertEquals(OFFICIAL_PRIVKEY_HEX, decrypted.privkey.toHex())
        assertEquals(16, decrypted.logN)
    }

    @Test
    fun decrypt_officialTestVector_toleratesSurroundingWhitespace() {
        val privkey = Nip49.decrypt("  $OFFICIAL_NCRYPTSEC\n", OFFICIAL_PASSWORD)

        assertEquals(OFFICIAL_PRIVKEY_HEX, privkey.toHex())
    }

    @Test
    fun decrypt_officialTestVector_rejectsNearMissPassword() {
        assertThrows(Nip49.Nip49Error.WrongPassword::class.java) {
            Nip49.decrypt(OFFICIAL_NCRYPTSEC, "nostr ")
        }
    }

    @Test
    fun encrypt_roundTrips() {
        val privkey = randomPrivkey()

        val ncryptsec = Nip49.encrypt(privkey, "correct horse battery staple", logN = FAST_LOG_N)
        assertTrue(ncryptsec.startsWith("ncryptsec1"))
        assertTrue(Nip49.isNcryptsec(ncryptsec))

        assertBytesEqual(privkey, Nip49.decrypt(ncryptsec, "correct horse battery staple"))
    }

    @Test
    fun encrypt_isNonDeterministic() {
        val privkey = randomPrivkey()

        val first = Nip49.encrypt(privkey, "pw", logN = FAST_LOG_N)
        val second = Nip49.encrypt(privkey, "pw", logN = FAST_LOG_N)

        // Fresh salt + nonce per call — two exports of one key must not be linkable.
        assertNotEquals(first, second)
        assertBytesEqual(privkey, Nip49.decrypt(first, "pw"))
        assertBytesEqual(privkey, Nip49.decrypt(second, "pw"))
    }

    @Test
    fun encrypt_payloadHasSpecLayout() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(24) { (it + 100).toByte() }

        val ncryptsec = Nip49.encryptWith(
            privkey = randomPrivkey(),
            password = "pw",
            logN = FAST_LOG_N,
            keySecurity = Nip49.KeySecurity.SECURE,
            salt = salt,
            nonce = nonce,
        )
        val (hrp, payload) = Nip19.bech32Decode(ncryptsec)

        assertEquals("ncryptsec", hrp)
        assertEquals(91, payload.size)
        assertEquals(0x02.toByte(), payload[0])
        assertEquals(FAST_LOG_N.toByte(), payload[1])
        assertBytesEqual(salt, payload.copyOfRange(2, 18))
        assertBytesEqual(nonce, payload.copyOfRange(18, 42))
        assertEquals(0x01.toByte(), payload[42])
        // 32-byte key + 16-byte Poly1305 tag.
        assertEquals(48, payload.size - 43)
    }

    @Test
    fun keySecurityByte_survivesRoundTrip() {
        val privkey = randomPrivkey()

        for (security in Nip49.KeySecurity.entries) {
            val ncryptsec = Nip49.encrypt(privkey, "pw", logN = FAST_LOG_N, keySecurity = security)
            assertEquals(security, Nip49.decryptWithMetadata(ncryptsec, "pw").keySecurity)
        }
    }

    @Test
    fun keySecurityByte_isAuthenticated() {
        val ncryptsec = Nip49.encrypt(
            randomPrivkey(),
            "pw",
            logN = FAST_LOG_N,
            keySecurity = Nip49.KeySecurity.SECURE,
        )

        // Flip the key-security byte — it is associated data, so the tag must reject it.
        val payload = Nip19.bech32Decode(ncryptsec).second
        payload[42] = 0x00

        assertThrows(Nip49.Nip49Error.WrongPassword::class.java) {
            Nip49.decrypt(Nip19.bech32Encode("ncryptsec", payload), "pw")
        }
    }

    @Test
    fun password_isNfkcNormalized() {
        // NIP-49 "Password Unicode Normalization": U+212B U+2126 U+1E9B U+0323 and
        // U+00C5 U+03A9 U+1E69 are different byte sequences that NFKC folds together,
        // so a password typed on another device must still open the key.
        val asTyped = "ÅΩẛ̣"
        val normalized = "ÅΩṩ"
        assertNotEquals(asTyped, normalized)

        val privkey = randomPrivkey()
        val ncryptsec = Nip49.encrypt(privkey, asTyped, logN = FAST_LOG_N)

        assertBytesEqual(privkey, Nip49.decrypt(ncryptsec, normalized))
        assertBytesEqual(privkey, Nip49.decrypt(ncryptsec, asTyped))
    }

    @Test
    fun decrypt_wrongPasswordFails() {
        val ncryptsec = Nip49.encrypt(randomPrivkey(), "hunter2", logN = FAST_LOG_N)

        assertThrows(Nip49.Nip49Error.WrongPassword::class.java) {
            Nip49.decrypt(ncryptsec, "hunter3")
        }
    }

    @Test
    fun decrypt_rejectsNonNcryptsecBech32() {
        val nsec = Nip19.nsecEncode(randomPrivkey())

        assertThrows(Nip49.Nip49Error.Malformed::class.java) { Nip49.decrypt(nsec, "pw") }
    }

    @Test
    fun decrypt_rejectsGarbage() {
        assertThrows(Nip49.Nip49Error.Malformed::class.java) { Nip49.decrypt("not-a-key", "pw") }
        assertThrows(Nip49.Nip49Error.Malformed::class.java) {
            Nip49.decrypt("ncryptsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq", "pw")
        }
    }

    @Test
    fun decrypt_rejectsUnsupportedVersion() {
        val ncryptsec = Nip49.encrypt(randomPrivkey(), "pw", logN = FAST_LOG_N)
        val payload = Nip19.bech32Decode(ncryptsec).second
        payload[0] = 0x03

        val error = assertThrows(Nip49.Nip49Error.Malformed::class.java) {
            Nip49.decrypt(Nip19.bech32Encode("ncryptsec", payload), "pw")
        }
        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun decrypt_rejectsUnaffordableLogN() {
        val ncryptsec = Nip49.encrypt(randomPrivkey(), "pw", logN = FAST_LOG_N)
        val payload = Nip19.bech32Decode(ncryptsec).second
        payload[1] = 30 // 128 * 8 * 2^30 bytes — no phone can allocate that.

        assertThrows(Nip49.Nip49Error.TooExpensive::class.java) {
            Nip49.decrypt(Nip19.bech32Encode("ncryptsec", payload), "pw")
        }
    }

    @Test
    fun encrypt_rejectsEmptyPasswordAndBadKeyLength() {
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.encrypt(randomPrivkey(), "", logN = FAST_LOG_N)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.encrypt(ByteArray(31), "pw", logN = FAST_LOG_N)
        }
    }

    @Test
    fun isNcryptsec_recognisesOnlyNcryptsec() {
        assertTrue(Nip49.isNcryptsec("  NCRYPTSEC1abc  "))
        assertFalse(Nip49.isNcryptsec(Nip19.nsecEncode(randomPrivkey())))
        assertFalse(Nip49.isNcryptsec(""))
    }

    @Test
    fun hChaCha20_matchesRfcDraftVector() {
        // draft-irtf-cfrg-xchacha-03 §2.2.1.
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
        val nonce = "000000090000004a0000000031415927".hexToByteArray()

        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            Nip49.hChaCha20(key, nonce).toHex(),
        )
    }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toHex(), actual.toHex())

    private companion object {
        const val FAST_LOG_N = 8

        // NIP-49 "Test Data" → Decryption.
        const val OFFICIAL_NCRYPTSEC =
            "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lsp" +
                "w8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
        const val OFFICIAL_PRIVKEY_HEX =
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
        const val OFFICIAL_PASSWORD = "nostr"
    }
}
