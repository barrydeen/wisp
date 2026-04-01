package com.wisp.app.nostr

import java.security.MessageDigest

/**
 * NIP-78: Arbitrary custom app data (kind 30078).
 * Used for encrypted wallet backup — mnemonic is NIP-44 encrypted to the user's
 * own pubkey and published as an addressable event to relays.
 *
 * Compatible with addy's spark-wallet-backup format for cross-app restore.
 */
object Nip78 {
    const val KIND = 30078
    private const val D_TAG_PREFIX = "spark-wallet-backup"

    /** Normalize mnemonic: trim, lowercase, collapse whitespace. */
    fun normalizeMnemonic(mnemonic: String): String =
        mnemonic.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Deterministic wallet ID from mnemonic — SHA-256 first 16 hex chars. */
    fun computeWalletId(mnemonic: String): String {
        val normalized = normalizeMnemonic(mnemonic)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hash.toHex().substring(0, 16)
    }

    fun buildBackupDTag(walletId: String): String = "$D_TAG_PREFIX:$walletId"

    /**
     * Create a kind 30078 backup event with NIP-44 encrypted mnemonic.
     * Uses [NostrSigner] so it works with both local keys and NIP-55 remote signers.
     */
    suspend fun createBackupEvent(signer: NostrSigner, mnemonic: String): NostrEvent {
        val normalized = normalizeMnemonic(mnemonic)
        val walletId = computeWalletId(normalized)
        val dTag = buildBackupDTag(walletId)

        // Encrypt to self (own pubkey)
        val encrypted = signer.nip44Encrypt(normalized, signer.pubkeyHex)

        val tags = listOf(
            listOf("d", dTag),
            listOf("client", "wisp"),
            listOf("encryption", "nip44")
        )

        return signer.signEvent(kind = KIND, content = encrypted, tags = tags)
    }

    /**
     * Decrypt a backup event's content and validate as a mnemonic.
     * Returns the mnemonic or null if decryption/validation fails.
     */
    suspend fun decryptBackup(signer: NostrSigner, event: NostrEvent): String? {
        if (isDeletedBackup(event)) return null
        return try {
            val decrypted = signer.nip44Decrypt(event.content, event.pubkey)
            val words = decrypted.trim().split(Regex("\\s+"))
            if (words.size in listOf(12, 15, 18, 21, 24)) decrypted.trim() else null
        } catch (_: Exception) {
            null
        }
    }

    /** Check if a backup event has been deleted. */
    fun isDeletedBackup(event: NostrEvent): Boolean {
        if (event.content.isBlank()) return true
        return event.tags.any { it.size >= 2 && it[0] == "deleted" && it[1] == "true" }
    }

    /** Extract wallet ID from a backup event's d tag, or null. */
    fun extractWalletId(event: NostrEvent): String? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
            ?: return null
        return if (dTag.startsWith("$D_TAG_PREFIX:")) {
            dTag.removePrefix("$D_TAG_PREFIX:")
        } else if (dTag == D_TAG_PREFIX) {
            null // legacy format without wallet ID
        } else {
            null
        }
    }

    /**
     * Build a filter to fetch spark wallet backups for a pubkey.
     * When [knownDTags] is provided, filters to those exact d-tags.
     * Otherwise fetches all kind 30078 for the author (relay-side prefix
     * filtering isn't supported by NIP-01, so client-side filtering is needed).
     */
    fun backupFilter(pubkeyHex: String, knownDTags: List<String>? = null): Filter = Filter(
        kinds = listOf(KIND),
        authors = listOf(pubkeyHex),
        dTags = knownDTags
    )

    /** Build a filter targeting a specific wallet's d-tag for per-relay status checks. */
    fun backupFilterForDTag(pubkeyHex: String, mnemonic: String): Filter {
        val walletId = computeWalletId(mnemonic)
        val dTag = buildBackupDTag(walletId)
        return Filter(
            kinds = listOf(KIND),
            authors = listOf(pubkeyHex),
            dTags = listOf(dTag)
        )
    }

    /**
     * Create a tombstone event that marks a backup as deleted.
     * Publishes a kind 30078 with empty content and a "deleted" tag, same d-tag as the backup.
     */
    suspend fun createDeleteEvent(signer: NostrSigner, mnemonic: String): NostrEvent {
        val normalized = normalizeMnemonic(mnemonic)
        val walletId = computeWalletId(normalized)
        val dTag = buildBackupDTag(walletId)
        return createDeleteEventForDTag(signer, dTag)
    }

    /** Create a tombstone for a specific d-tag value. */
    suspend fun createDeleteEventForDTag(signer: NostrSigner, dTag: String): NostrEvent {
        val tags = listOf(
            listOf("d", dTag),
            listOf("deleted", "true")
        )
        return signer.signEvent(kind = KIND, content = "", tags = tags)
    }

    /** Extract the d-tag value from an event, or null. */
    fun extractDTag(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
}
