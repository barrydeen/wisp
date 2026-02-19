package com.wisp.app.nostr

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object Nip04 {
    private val random = SecureRandom()

    /**
     * Compute the NIP-04 shared secret from a private key and public key.
     * Uses ECDH to get a 32-byte shared point x-coordinate, used directly as AES key.
     */
    fun computeSharedSecret(privkey: ByteArray, pubkey: ByteArray): ByteArray {
        val compressed = Keys.pubkeyToCompressed(pubkey)
        return Keys.ecdh(privkey, compressed)
    }

    /**
     * Encrypt plaintext using NIP-04: AES-256-CBC with random IV.
     * Returns: base64(ciphertext) + "?iv=" + base64(iv)
     */
    fun encrypt(plaintext: String, sharedSecret: ByteArray): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val key = SecretKeySpec(sharedSecret, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        return "$ctB64?iv=$ivB64"
    }

    /**
     * Decrypt NIP-04 content: base64(ciphertext) + "?iv=" + base64(iv)
     */
    fun decrypt(content: String, sharedSecret: ByteArray): String {
        val parts = content.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 content format" }
        val ciphertext = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)
        val key = SecretKeySpec(sharedSecret, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
