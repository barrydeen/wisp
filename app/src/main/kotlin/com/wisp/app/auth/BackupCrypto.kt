package com.wisp.app.auth

import com.wisp.app.nostr.Nip44
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives backup keys from the Google ID token's stable `sub` claim and
 * encrypts the nsec for publication to Nostr relays.
 *
 * Two distinct keys come out of one `sub`:
 *   - `encryptionKey`  — symmetric key for the NIP-44 payload that carries the nsec.
 *   - `signingPrivkey` — secp256k1 private key that signs the kind 30078 backup
 *                        event published to relays. Its pubkey is also the
 *                        author filter when looking up backups on restore.
 *
 * Tradeoff (deliberate): anyone with access to the user's Google account can
 * recompute both keys and recover every backup. In exchange the user never
 * has to remember a passphrase.
 */
object BackupCrypto {
    private const val SALT = "wisp-nostr-backup-v1"

    data class DerivedKeys(
        val encryptionKey: ByteArray,
        val signingPrivkey: ByteArray
    )

    fun deriveKeys(sub: String): DerivedKeys {
        require(sub.isNotEmpty()) { "Google sub claim must not be empty" }
        val prk = hmacSha256(SALT.toByteArray(Charsets.UTF_8), sub.toByteArray(Charsets.UTF_8))
        return DerivedKeys(
            encryptionKey = hkdfExpand(prk, "wisp-enc".toByteArray(Charsets.UTF_8), 32),
            signingPrivkey = hkdfExpand(prk, "wisp-sig".toByteArray(Charsets.UTF_8), 32)
        )
    }

    fun encryptNsec(nsec: ByteArray, key: ByteArray): String {
        require(nsec.size == 32) { "nsec must be 32 bytes" }
        require(key.size == 32) { "encryption key must be 32 bytes" }
        return Nip44.encrypt(nsec.toHex(), key)
    }

    fun decryptNsec(payload: String, key: ByteArray): ByteArray {
        require(key.size == 32) { "encryption key must be 32 bytes" }
        val hex = Nip44.decrypt(payload, key)
        require(hex.length == 64) { "decrypted backup is not a 32-byte hex string" }
        return hex.hexToByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
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
}
