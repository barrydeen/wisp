package cooking.zap.app.nostr

/**
 * Session circuit breaker for signer-side decrypt prompts — the Android port of
 * the web's `encryptionService` breaker. A NIP-55 remote signer (Amber) has no
 * silent-only decrypt path, so bulk-decrypting a user's grocery lists can turn
 * into a prompt storm; once the user denies [denialThreshold] prompts in a row,
 * the gate trips and every further attempt is refused for the rest of the
 * session (until [reset], e.g. on logout/account switch).
 *
 * Semantics ported from web:
 *  - only explicit user denials ([SignerRejectedException] /
 *    [SignerCancelledException]) count; other decrypt failures (bad ciphertext,
 *    wrong key) neither increment nor reset the counter;
 *  - one success resets the consecutive-denial count;
 *  - a ciphertext the user already denied is never re-attempted, even before
 *    the gate trips (web's per-ciphertext DECRYPT_DENIED sentinel).
 *
 * [LocalSigner] never prompts, so the gate is inert for local keys (no denials
 * can occur). Thread-safe; one instance per signing session.
 */
class SignerDecryptGate(
    private val denialThreshold: Int = DEFAULT_DENIAL_THRESHOLD,
) {
    companion object {
        /** Web parity: SIGNER_DENIAL_THRESHOLD = 2. */
        const val DEFAULT_DENIAL_THRESHOLD = 2

        // Denied ciphertexts are remembered by hashCode to bound memory; on
        // overflow the set is cleared (worst case: one extra prompt per list).
        private const val MAX_DENIED_PAYLOADS = 256
    }

    private val lock = Any()
    private var consecutiveDenials = 0
    private var tripped = false
    private val deniedPayloads = HashSet<Int>()

    val isTripped: Boolean get() = synchronized(lock) { tripped }

    /** False when the gate has tripped or this exact ciphertext was already denied. */
    fun canAttempt(ciphertext: String): Boolean = synchronized(lock) {
        !tripped && ciphertext.hashCode() !in deniedPayloads
    }

    fun recordSuccess(): Unit = synchronized(lock) {
        consecutiveDenials = 0
    }

    fun recordDenial(ciphertext: String): Unit = synchronized(lock) {
        if (deniedPayloads.size >= MAX_DENIED_PAYLOADS) deniedPayloads.clear()
        deniedPayloads.add(ciphertext.hashCode())
        consecutiveDenials += 1
        if (consecutiveDenials >= denialThreshold) tripped = true
    }

    /** Full reset — for logout / account switch, mirroring web's clearDecryptCache. */
    fun reset(): Unit = synchronized(lock) {
        consecutiveDenials = 0
        tripped = false
        deniedPayloads.clear()
    }

    /**
     * Run [op] (a signer decrypt of [ciphertext]) under the gate. Returns null
     * when the gate refuses the attempt or the user denies it; a success resets
     * the denial streak. Non-denial exceptions propagate untouched — they're
     * data problems, not prompt fatigue, and the caller decides what they mean.
     */
    suspend fun <T : Any> attempt(ciphertext: String, op: suspend () -> T): T? {
        if (!canAttempt(ciphertext)) return null
        return try {
            op().also { recordSuccess() }
        } catch (e: SignerRejectedException) {
            recordDenial(ciphertext)
            null
        } catch (e: SignerCancelledException) {
            recordDenial(ciphertext)
            null
        }
    }
}
