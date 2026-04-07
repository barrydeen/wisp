package com.antz.app.nostr

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * NIP-13: Proof of Work
 *
 * Mining a nonce that makes the event ID hash start with a target number
 * of leading zero bits, used as a spam-deterrence signal.
 */
object Nip13 {

    data class MineResult(val tags: List<List<String>>, val createdAt: Long)

    /**
     * Count leading zero bits from a hex event ID.
     * Each '0' hex char = 4 zero bits. First non-zero nibble contributes
     * partial bits via leading zeros of that nibble.
     */
    fun countLeadingZeroBits(eventIdHex: String): Int {
        var bits = 0
        for (c in eventIdHex) {
            val nibble = Character.digit(c, 16)
            if (nibble == 0) {
                bits += 4
            } else {
                // Count leading zeros of the 4-bit nibble
                bits += when {
                    nibble < 2 -> 3  // 0001
                    nibble < 4 -> 2  // 001x
                    nibble < 8 -> 1  // 01xx
                    else -> 0        // 1xxx
                }
                break
            }
        }
        return bits
    }

    /**
     * Extract the committed difficulty from a nonce tag: ["nonce", "<value>", "<difficulty>"]
     * Returns null if no valid nonce tag is present.
     */
    fun getCommittedDifficulty(event: NostrEvent): Int? {
        val nonceTag = event.tags.firstOrNull { it.size >= 3 && it[0] == "nonce" }
            ?: return null
        return nonceTag[2].toIntOrNull()
    }

    /**
     * Verify proof of work on an event. Returns actual leading zero bits
     * if the event has a nonce tag and meets its committed difficulty, else 0.
     */
    fun verifyDifficulty(event: NostrEvent): Int {
        val committed = getCommittedDifficulty(event) ?: return 0
        val actual = countLeadingZeroBits(event.id)
        return if (actual >= committed) actual else 0
    }

    /**
     * Mine a nonce that produces an event ID with at least [targetDifficulty] leading zero bits.
     *
     * Iterates nonce values, computes event IDs via [NostrEvent.computeId], and checks
     * leading zeros. Supports cancellation via coroutine cancellation (checks every 1024 iterations).
     * Calls [onProgress] every 10,000 iterations with the current attempt count.
     *
     * @return [MineResult] with final tags (including winning nonce) and the pinned createdAt.
     */
    suspend fun mine(
        pubkeyHex: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        targetDifficulty: Int,
        createdAt: Long = System.currentTimeMillis() / 1000,
        onProgress: ((attempts: Long) -> Unit)? = null
    ): MineResult {
        var nonce = 0L
        while (true) {
            // Check cancellation periodically
            if (nonce % 1024 == 0L) {
                coroutineContext.ensureActive()
            }
            if (nonce % 10_000 == 0L && nonce > 0) {
                onProgress?.invoke(nonce)
            }

            val mineTags = tags + listOf(listOf("nonce", nonce.toString(), targetDifficulty.toString()))
            val id = NostrEvent.computeId(pubkeyHex, createdAt, kind, mineTags, content)
            val bits = countLeadingZeroBits(id)

            if (bits >= targetDifficulty) {
                return MineResult(tags = mineTags, createdAt = createdAt)
            }

            nonce++
        }
    }
}
