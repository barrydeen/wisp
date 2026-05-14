package com.wisp.app.auth

import com.wisp.app.nostr.Nip44
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Backup encryption for the Google Drive nsec backup blob.
 *
 * The encryption key is derived from the Google ID token's `sub` claim, which is
 * stable per (Google account, OAuth client). This means the same Google account
 * always produces the same key, enabling passphrase-less restore. Tradeoff: anyone
 * with access to the Google account can decrypt the backup.
 *
 * The encrypted payload is just NIP-44 v2 over the hex-encoded nsec, with the
 * derived key in place of the usual ECDH conversation key. Reuses Nip44 verbatim
 * so we don't introduce new crypto code.
 */
object BackupCrypto {
    private const val SALT = "wisp-google-backup-v1"

    fun deriveBackupKey(sub: String): ByteArray {
        require(sub.isNotEmpty()) { "Google sub claim must not be empty" }
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
