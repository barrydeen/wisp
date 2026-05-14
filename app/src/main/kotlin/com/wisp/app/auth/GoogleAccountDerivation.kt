package com.wisp.app.auth

import com.wisp.app.nostr.Keys
import java.security.MessageDigest

/**
 * Deterministically derives Nostr keypairs from a Google ID token's `sub`
 * claim. Account #0 is the first identity, #1 is the second, and so on —
 * each one is independent and recoverable from the same Google login alone.
 *
 *   privkey = SHA-256("wisp-account-v1:" || sub || ":" || accountIndex)
 *
 * Properties:
 *   - Stable: the same Google account always produces the same Nostr keypair.
 *   - No backup: nothing to store anywhere; signing in regenerates the keys.
 *   - Auditable: the formula above is the entire derivation. Anyone can
 *     reproduce it on any device.
 *
 * Security: bounded by the security of the user's Google account. Anyone
 * who can sign in to the account can recompute every nsec derived from it.
 * This is the same trade-off any "Google sign-in = identity" scheme makes,
 * accepted in exchange for not requiring the user to remember a passphrase.
 */
object GoogleAccountDerivation {
    private const val DERIVATION_PREFIX = "wisp-account-v1"

    fun deriveAccountKeypair(sub: String, accountIndex: Int): Keys.Keypair {
        require(sub.isNotEmpty()) { "Google sub claim must not be empty" }
        require(accountIndex >= 0) { "accountIndex must be non-negative" }
        val input = "$DERIVATION_PREFIX:$sub:$accountIndex".toByteArray(Charsets.UTF_8)
        val privkey = MessageDigest.getInstance("SHA-256").digest(input)
        return Keys.fromPrivkey(privkey)
    }
}
