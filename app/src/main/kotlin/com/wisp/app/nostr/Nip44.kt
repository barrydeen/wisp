package com.wisp.app.nostr

import android.util.Base64
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Nip44 {
    private const val VERSION: Byte = 0x02
    private val random = SecureRandom()

    /**
     * Derive conversation key from privkey + pubkey via ECDH + HKDF.
     * This key is symmetric: same result regardless of who is sender/receiver.
     */
    fun getConversationKey(privkey: ByteArray, pubkey: ByteArray): ByteArray {
        val compressed = Keys.pubkeyToCompressed(pubkey)
        val sharedSecret = Keys.ecdh(privkey, compressed)
        // HKDF-Extract(salt=empty, ikm=sharedSecret) -> PRK
        val prk = hkdfExtract(ByteArray(32), sharedSecret)
        // HKDF-Expand(prk, info="nip44-v2", len=32) -> conversation key
        return hkdfExpand(prk, "nip44-v2".toByteArray(Charsets.UTF_8), 32)
    }

    /**
     * Encrypt plaintext using NIP-44 v2.
     * Returns base64-encoded payload: version(1) || nonce(32) || ciphertext(padded) || hmac(32)
     */
    fun encrypt(plaintext: String, conversationKey: ByteArray): String {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        require(plaintextBytes.size in 1..65535) { "Plaintext must be 1-65535 bytes" }

        val nonce = ByteArray(32).also { random.nextBytes(it) }
        return encryptWithNonce(plaintextBytes, conversationKey, nonce)
    }

    internal fun encryptWithNonce(plaintextBytes: ByteArray, conversationKey: ByteArray, nonce: ByteArray): String {
        val padded = pad(plaintextBytes)

        // Derive per-message keys: chacha_key(32) + chacha_nonce(12) + hmac_key(32) = 76 bytes
        val messageKeys = deriveMessageKeys(conversationKey, nonce)
        val chachaKey = messageKeys.copyOfRange(0, 32)
        val chaChaNonce = messageKeys.copyOfRange(32, 44)
        val hmacKey = messageKeys.copyOfRange(44, 76)

        // ChaCha20 encrypt
        val ciphertext = chacha20Encrypt(chachaKey, chaChaNonce, padded)

        // HMAC-SHA256 over nonce || ciphertext
        val hmacInput = nonce + ciphertext
        val mac = hmacSha256(hmacKey, hmacInput)

        // Assemble payload: version || nonce || ciphertext || hmac
        val payload = byteArrayOf(VERSION) + nonce + ciphertext + mac
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * Decrypt NIP-44 v2 payload.
     * Input: base64-encoded payload.
     */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val data = Base64.decode(payload, Base64.DEFAULT)
        require(data.size >= 99) { "Payload too short" } // 1 + 32 + 32(min padded) + 2(len prefix) + 32

        val version = data[0]
        require(version == VERSION) { "Unsupported NIP-44 version: $version" }

        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        // Derive per-message keys
        val messageKeys = deriveMessageKeys(conversationKey, nonce)
        val chachaKey = messageKeys.copyOfRange(0, 32)
        val chaChaNonce = messageKeys.copyOfRange(32, 44)
        val hmacKey = messageKeys.copyOfRange(44, 76)

        // Verify HMAC before decryption (encrypt-then-MAC)
        val expectedMac = hmacSha256(hmacKey, nonce + ciphertext)
        require(constantTimeEquals(mac, expectedMac)) { "HMAC verification failed" }

        // Decrypt
        val padded = chacha20Encrypt(chachaKey, chaChaNonce, ciphertext)
        return unpad(padded)
    }

    // --- Padding ---

    internal fun pad(plaintext: ByteArray): ByteArray {
        val len = plaintext.size
        require(len in 1..65535)
        val paddedLen = calcPaddedLen(len)
        val result = ByteArray(2 + paddedLen)
        result[0] = ((len shr 8) and 0xFF).toByte()
        result[1] = (len and 0xFF).toByte()
        System.arraycopy(plaintext, 0, result, 2, len)
        // remaining bytes are already 0
        return result
    }

    internal fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "Padded data too short" }
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(len > 0 && len <= padded.size - 2) { "Invalid padding length: $len" }
        val expectedPaddedLen = calcPaddedLen(len)
        require(padded.size == 2 + expectedPaddedLen) { "Invalid padded size" }
        // Verify zero padding
        for (i in (2 + len) until padded.size) {
            require(padded[i] == 0.toByte()) { "Non-zero padding byte" }
        }
        return String(padded, 2, len, Charsets.UTF_8)
    }

    internal fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPow2 = Integer.highestOneBit(unpaddedLen - 1) shl 1
        val chunk = maxOf(32, nextPow2 / 8)
        return ((unpaddedLen + chunk - 1) / chunk) * chunk
    }

    // --- HKDF ---

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        return hmacSha256(salt, ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val n = (length + 31) / 32
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(32, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }
        return okm
    }

    private fun deriveMessageKeys(conversationKey: ByteArray, nonce: ByteArray): ByteArray {
        val prk = hkdfExtract(conversationKey, nonce)
        return hkdfExpand(prk, "nip44-v2".toByteArray(Charsets.UTF_8), 76)
    }

    // --- Crypto Primitives ---

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun chacha20Encrypt(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val output = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, output, 0)
        return output
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
