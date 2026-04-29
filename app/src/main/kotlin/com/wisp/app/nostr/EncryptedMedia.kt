package com.wisp.app.nostr

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM file encryption for NIP-17 Kind 15 encrypted file messages.
 * Interoperable with Amethyst's implementation.
 */
object EncryptedMedia {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 32       // 256-bit key
    private const val NONCE_SIZE = 12     // 96-bit nonce (standard GCM)
    private const val TAG_BITS = 128      // 128-bit auth tag
    const val ALGORITHM = "aes-gcm"

    private val random = SecureRandom()

    data class EncryptedFileResult(
        val encryptedBytes: ByteArray,
        val keyHex: String,
        val nonceHex: String,
        val originalSha256Hex: String,
        val encryptedSha256Hex: String
    )

    data class EncryptedFileMetadata(
        val fileUrl: String,
        val mimeType: String,
        val algorithm: String,
        val keyHex: String,
        val nonceHex: String,
        val encryptedHash: String,
        val originalHash: String,
        val size: Long?,
        val dimensions: String?,
        val thumbhash: String?,
        val blurhash: String?
    )

    fun encryptFile(plainBytes: ByteArray): EncryptedFileResult {
        val key = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val encryptedBytes = cipher.doFinal(plainBytes)

        val sha256 = MessageDigest.getInstance("SHA-256")
        val originalHash = sha256.digest(plainBytes).toHex()
        sha256.reset()
        val encryptedHash = sha256.digest(encryptedBytes).toHex()

        val result = EncryptedFileResult(
            encryptedBytes = encryptedBytes,
            keyHex = key.toHex(),
            nonceHex = nonce.toHex(),
            originalSha256Hex = originalHash,
            encryptedSha256Hex = encryptedHash
        )

        // Wipe the raw key from memory
        key.fill(0)

        return result
    }

    fun decryptFile(encryptedBytes: ByteArray, keyHex: String, nonceHex: String): ByteArray {
        val key = keyHex.hexToByteArray()
        val nonce = nonceHex.hexToByteArray()

        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            return cipher.doFinal(encryptedBytes)
        } finally {
            key.fill(0)
        }
    }

    fun buildKind15Tags(
        mimeType: String,
        keyHex: String,
        nonceHex: String,
        encryptedHash: String,
        originalHash: String,
        size: Long,
        dimensions: String? = null
    ): List<List<String>> {
        val tags = mutableListOf(
            listOf("file-type", mimeType),
            listOf("encryption-algorithm", ALGORITHM),
            listOf("decryption-key", keyHex),
            listOf("decryption-nonce", nonceHex),
            listOf("x", encryptedHash),
            listOf("ox", originalHash),
            listOf("size", size.toString())
        )
        if (dimensions != null) tags.add(listOf("dim", dimensions))
        return tags
    }

    fun parseKind15Tags(tags: List<List<String>>, fileUrl: String): EncryptedFileMetadata? {
        val tagMap = mutableMapOf<String, String>()
        for (tag in tags) {
            if (tag.size >= 2) tagMap[tag[0]] = tag[1]
        }

        val mimeType = tagMap["file-type"] ?: return null
        val algorithm = tagMap["encryption-algorithm"] ?: return null
        val keyHex = tagMap["decryption-key"] ?: return null
        val nonceHex = tagMap["decryption-nonce"] ?: return null

        return EncryptedFileMetadata(
            fileUrl = fileUrl,
            mimeType = mimeType,
            algorithm = algorithm,
            keyHex = keyHex,
            nonceHex = nonceHex,
            encryptedHash = tagMap["x"] ?: "",
            originalHash = tagMap["ox"] ?: "",
            size = tagMap["size"]?.toLongOrNull(),
            dimensions = tagMap["dim"],
            thumbhash = tagMap["thumbhash"],
            blurhash = tagMap["blurhash"]
        )
    }
}
