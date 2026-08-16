package cooking.zap.app.nostr

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.text.Normalizer

/**
 * NIP-49 — password-encrypted private keys (`ncryptsec1…`).
 *
 * The payload is 91 raw bytes, bech32-encoded with the `ncryptsec` HRP:
 *
 *     version(1) || log_n(1) || salt(16) || nonce(24) || key_security(1) || ciphertext(48)
 *
 * The symmetric key is `scrypt(NFKC(password), salt, N=2^log_n, r=8, p=1, dkLen=32)` and
 * the ciphertext is XChaCha20-Poly1305 over the raw 32-byte private key, with the
 * key-security byte as associated data (so it is authenticated even though it travels
 * in the clear).
 *
 * Both [encrypt] and [decrypt] run scrypt, which is deliberately slow and memory-hard —
 * 64 MiB and a few hundred milliseconds at the default log_n of 16. Never call them on
 * the main thread; the callers in this app go through `Dispatchers.Default`.
 */
object Nip49 {
    const val HRP = "ncryptsec"

    /** log_n=16 → 64 MiB / ~100 ms on a fast machine. The value most clients emit. */
    const val DEFAULT_LOG_N = 16

    /** Anything above this cannot be allocated on a phone (log_n=21 already wants 2 GiB). */
    private const val MAX_LOG_N = 22

    private const val VERSION: Byte = 0x02
    private const val PAYLOAD_SIZE = 91
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 24
    private const val PRIVKEY_SIZE = 32
    private const val MAC_BITS = 128

    private val random = SecureRandom()

    /**
     * How carefully the key being encrypted has been handled, per NIP-49. This app does
     * not track key handling, so exports carry [UNKNOWN]; the other two are recognised on
     * decrypt because keys arrive from clients that do track it.
     */
    enum class KeySecurity(val byte: Byte) {
        /** 0x00 — the key is known to have been handled insecurely. */
        INSECURE(0x00),

        /** 0x01 — the key is not known to have been handled insecurely. */
        SECURE(0x01),

        /** 0x02 — the client does not track this. */
        UNKNOWN(0x02);

        companion object {
            fun fromByte(b: Byte): KeySecurity = entries.firstOrNull { it.byte == b } ?: UNKNOWN
        }
    }

    /** Everything [decrypt] can fail with, separated so callers can word each one. */
    sealed class Nip49Error(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** Not an ncryptsec, bad bech32, wrong length, or an unsupported version byte. */
        class Malformed(message: String, cause: Throwable? = null) : Nip49Error(message, cause)

        /** Poly1305 tag mismatch — the password is wrong (or the payload was tampered with). */
        class WrongPassword : Nip49Error("Wrong password")

        /** The payload's log_n asks for more memory than this device can allocate. */
        class TooExpensive(val logN: Int) :
            Nip49Error("This key was encrypted with log_n=$logN, which needs more memory than this device has")
    }

    fun isNcryptsec(value: String): Boolean =
        value.trim().lowercase().startsWith("ncryptsec1")

    /**
     * Encrypt a raw 32-byte private key into an `ncryptsec1…` string.
     *
     * @param logN scrypt work factor as a power of two; memory cost is `128 * 8 * 2^logN` bytes.
     */
    fun encrypt(
        privkey: ByteArray,
        password: String,
        logN: Int = DEFAULT_LOG_N,
        keySecurity: KeySecurity = KeySecurity.UNKNOWN,
    ): String {
        require(privkey.size == PRIVKEY_SIZE) { "Private key must be 32 bytes" }
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(logN in 1..MAX_LOG_N) { "log_n must be between 1 and $MAX_LOG_N" }

        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        return encryptWith(privkey, password, logN, keySecurity, salt, nonce)
    }

    /** Deterministic encrypt seam — tests pin the salt and nonce, callers use [encrypt]. */
    internal fun encryptWith(
        privkey: ByteArray,
        password: String,
        logN: Int,
        keySecurity: KeySecurity,
        salt: ByteArray,
        nonce: ByteArray,
    ): String {
        val symmetricKey = deriveKey(password, salt, logN)
        val ciphertext = try {
            xChaCha20Poly1305(
                encrypt = true,
                key = symmetricKey,
                nonce = nonce,
                aad = byteArrayOf(keySecurity.byte),
                input = privkey,
            )
        } finally {
            symmetricKey.wipe()
        }

        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = VERSION
        payload[1] = logN.toByte()
        salt.copyInto(payload, 2)
        nonce.copyInto(payload, 2 + SALT_SIZE)
        payload[2 + SALT_SIZE + NONCE_SIZE] = keySecurity.byte
        ciphertext.copyInto(payload, 3 + SALT_SIZE + NONCE_SIZE)

        return Nip19.bech32Encode(HRP, payload)
    }

    /**
     * Decrypt an `ncryptsec1…` string back to the raw 32-byte private key.
     *
     * @throws Nip49Error on malformed input, a wrong password, or an unaffordable log_n.
     */
    fun decrypt(ncryptsec: String, password: String): ByteArray =
        decryptWithMetadata(ncryptsec, password).privkey

    /** Decrypted key plus the metadata the payload carried, for callers that want it. */
    data class Decrypted(val privkey: ByteArray, val logN: Int, val keySecurity: KeySecurity) {
        override fun equals(other: Any?) = other is Decrypted && privkey.contentEquals(other.privkey)
        override fun hashCode() = privkey.contentHashCode()
        override fun toString() = "Decrypted(logN=$logN, keySecurity=$keySecurity)"
    }

    fun decryptWithMetadata(ncryptsec: String, password: String): Decrypted {
        val trimmed = ncryptsec.trim()
        require(password.isNotEmpty()) { "Password must not be empty" }

        val payload = try {
            val (hrp, data) = Nip19.bech32Decode(trimmed)
            if (hrp != HRP) throw Nip49Error.Malformed("Expected an ncryptsec, got $hrp")
            data
        } catch (e: Nip49Error) {
            throw e
        } catch (e: Exception) {
            throw Nip49Error.Malformed("Not a valid ncryptsec", e)
        }

        if (payload.size != PAYLOAD_SIZE) {
            throw Nip49Error.Malformed("ncryptsec payload must be $PAYLOAD_SIZE bytes, got ${payload.size}")
        }
        if (payload[0] != VERSION) {
            throw Nip49Error.Malformed("Unsupported ncryptsec version: ${payload[0].toInt() and 0xFF}")
        }

        val logN = payload[1].toInt() and 0xFF
        if (logN !in 1..MAX_LOG_N) throw Nip49Error.TooExpensive(logN)

        val salt = payload.copyOfRange(2, 2 + SALT_SIZE)
        val nonce = payload.copyOfRange(2 + SALT_SIZE, 2 + SALT_SIZE + NONCE_SIZE)
        val keySecurityByte = payload[2 + SALT_SIZE + NONCE_SIZE]
        val ciphertext = payload.copyOfRange(3 + SALT_SIZE + NONCE_SIZE, PAYLOAD_SIZE)

        val symmetricKey = try {
            deriveKey(password, salt, logN)
        } catch (e: OutOfMemoryError) {
            throw Nip49Error.TooExpensive(logN)
        }

        val privkey = try {
            xChaCha20Poly1305(
                encrypt = false,
                key = symmetricKey,
                nonce = nonce,
                aad = byteArrayOf(keySecurityByte),
                input = ciphertext,
            )
        } catch (e: InvalidCipherTextException) {
            // "mac check in ChaCha20Poly1305 failed" — raised identically for a wrong
            // password and for a tampered payload; they are indistinguishable by design,
            // and "wrong password" is the useful reading for a user.
            throw Nip49Error.WrongPassword()
        } catch (e: Exception) {
            throw Nip49Error.Malformed("Could not decrypt this ncryptsec", e)
        } finally {
            symmetricKey.wipe()
        }

        if (privkey.size != PRIVKEY_SIZE) {
            privkey.wipe()
            throw Nip49Error.Malformed("Decrypted key is ${privkey.size} bytes, expected $PRIVKEY_SIZE")
        }
        return Decrypted(privkey, logN, KeySecurity.fromByte(keySecurityByte))
    }

    // --- Crypto primitives ---

    /**
     * scrypt(NFKC(password) as UTF-8, salt, N=2^logN, r=8, p=1) → 32 bytes.
     *
     * NFKC normalisation is required by the spec so a password typed on another device —
     * where the IME may emit a different but canonically equivalent byte sequence —
     * derives the same key.
     */
    private fun deriveKey(password: String, salt: ByteArray, logN: Int): ByteArray {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFKC)
        val passwordBytes = normalized.toByteArray(Charsets.UTF_8)
        return try {
            SCrypt.generate(passwordBytes, salt, 1 shl logN, 8, 1, 32)
        } finally {
            passwordBytes.wipe()
        }
    }

    /**
     * XChaCha20-Poly1305: derive a subkey with HChaCha20 over the first 16 nonce bytes,
     * then run IETF ChaCha20-Poly1305 with nonce `00000000 || nonce[16..24]`.
     */
    private fun xChaCha20Poly1305(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "XChaCha20 nonce must be 24 bytes" }
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val innerNonce = ByteArray(12)
        nonce.copyInto(innerNonce, 4, 16, 24)

        return try {
            val aead = ChaCha20Poly1305()
            aead.init(encrypt, AEADParameters(KeyParameter(subkey), MAC_BITS, innerNonce, aad))
            val output = ByteArray(aead.getOutputSize(input.size))
            var written = aead.processBytes(input, 0, input.size, output, 0)
            written += aead.doFinal(output, written)
            if (written == output.size) output else output.copyOfRange(0, written)
        } finally {
            subkey.wipe()
        }
    }

    /**
     * HChaCha20 (draft-irtf-cfrg-xchacha §2.2): the ChaCha20 permutation applied to
     * `constants || key || nonce`, returning the first and last four state words —
     * without the feed-forward addition of a normal ChaCha20 block.
     */
    internal fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "HChaCha20 key must be 32 bytes" }
        require(nonce16.size == 16) { "HChaCha20 nonce must be 16 bytes" }

        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        for (i in 0 until 4) state[12 + i] = leInt(nonce16, i * 4)

        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        val out = ByteArray(32)
        for (i in 0 until 4) putLeInt(out, i * 4, state[i])
        for (i in 0 until 4) putLeInt(out, 16 + i * 4, state[12 + i])
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
