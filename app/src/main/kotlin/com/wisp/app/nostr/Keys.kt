package com.wisp.app.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

object Keys {
    private val secp256k1 = Secp256k1.get()
    private val random = SecureRandom()

    data class Keypair(val privkey: ByteArray, val pubkey: ByteArray) {
        override fun equals(other: Any?) = other is Keypair && privkey.contentEquals(other.privkey)
        override fun hashCode() = pubkey.contentHashCode()
        override fun toString() = "Keypair(pubkey=${pubkey.toHex()})"

        fun wipe() {
            privkey.wipe()
        }
    }

    fun generate(): Keypair {
        val privkey = ByteArray(32).also { random.nextBytes(it) }
        val pubkey = xOnlyPubkey(privkey)
        return Keypair(privkey, pubkey)
    }

    fun fromPrivkey(privkey: ByteArray): Keypair {
        require(privkey.size == 32) { "Private key must be 32 bytes" }
        return Keypair(privkey, xOnlyPubkey(privkey))
    }

    fun xOnlyPubkey(privkey: ByteArray): ByteArray {
        val full = secp256k1.pubkeyCreate(privkey)
        val compressed = secp256k1.pubKeyCompress(full)
        return compressed.copyOfRange(1, 33)
    }

    fun sign(privkey: ByteArray, message: ByteArray): ByteArray {
        require(message.size == 32) { "Message must be 32 bytes (SHA-256 hash)" }
        return secp256k1.signSchnorr(message, privkey, null)
    }

    /**
     * Convert x-only pubkey (32 bytes) to compressed form (33 bytes) by prepending 0x02.
     * Used for ECDH in NIP-44.
     */
    fun pubkeyToCompressed(xOnly: ByteArray): ByteArray {
        require(xOnly.size == 32) { "X-only pubkey must be 32 bytes" }
        return byteArrayOf(0x02) + xOnly
    }

    /**
     * Perform ECDH: returns the raw 32-byte x-coordinate of the shared point.
     *
     * Note: secp256k1.ecdh() applies SHA256 to the output, which is wrong for
     * NIP-04 and NIP-44 (both require the unhashed x-coordinate). Instead we
     * use pubKeyTweakMul(pubkey, privkey) which is the same EC point
     * multiplication but returns the compressed public key (33 bytes), then
     * strip the 0x02/0x03 prefix to get the raw x-coordinate.
     */
    fun ecdh(privkey: ByteArray, pubkeyCompressed: ByteArray): ByteArray {
        val sharedPoint = secp256k1.pubKeyTweakMul(pubkeyCompressed, privkey)
        return sharedPoint.copyOfRange(1, 33)
    }
}

/** Zero out sensitive byte arrays to minimize key exposure in memory. */
fun ByteArray.wipe() = fill(0)
