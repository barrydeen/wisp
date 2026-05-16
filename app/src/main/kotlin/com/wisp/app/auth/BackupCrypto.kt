package com.wisp.app.auth

import com.wisp.app.nostr.Nip44
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup encryption for the Google Drive nsec backup blob.
 *
 * Two factors gate decryption:
 *   1. The Google ID token's `sub` claim — stable per Google account, used
 *      only to derive a per-account salt. By itself it is not a secret; we
 *      assume Google can see the user's `sub`.
 *   2. A 4–8 digit numeric PIN the user sets at first sign-in. PBKDF2 with
 *      600k iterations stretches it. ~26 bits of raw PIN entropy is not
 *      enough on its own, but combined with Drive access control and the
 *      slow KDF an attacker needs ~weeks of compute *after* compromising
 *      the Google account.
 *
 * The encrypted payload is NIP-44 v2 over the hex-encoded nsec, with the
 * PBKDF2 output in place of the usual ECDH conversation key.
 */
object BackupCrypto {
    private const val SALT = "wisp-google-backup"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256

    fun isValidPin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it.isDigit() }

    fun deriveBackupKey(sub: String, pin: String): ByteArray {
        require(sub.isNotEmpty()) { "Google sub claim must not be empty" }
        require(isValidPin(pin)) { "PIN must be 4–8 digits" }

        val salt = perAccountSalt(sub)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun perAccountSalt(sub: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(sub.toByteArray(Charsets.UTF_8))
    }

    fun encryptNsec(nsec: ByteArray, key: ByteArray): String {
        require(nsec.size == 32) { "nsec must be 32 bytes" }
        require(key.size == 32) { "backup key must be 32 bytes" }
        return Nip44.encrypt(nsec.toHex(), key)
    }

    fun decryptNsec(payload: String, key: ByteArray): ByteArray {
        require(key.size == 32) { "backup key must be 32 bytes" }
        val hex = Nip44.decrypt(payload, key)
        require(hex.length == 64) { "decrypted backup is not a 32-byte hex string" }
        return hex.hexToByteArray()
    }
}
