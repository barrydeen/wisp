package cooking.zap.app.nostr

/**
 * JVM-only [NostrSigner] for NIP-98 tests. Builds a real unsigned event
 * ([NostrEvent.createUnsigned] — pure sha256, no native secp256k1) and
 * stamps a dummy signature; the client never verifies signatures, so
 * header construction, caching, and retry behavior are fully exercisable.
 *
 * Each sign gets a distinct, monotonically increasing `created_at`, so a
 * re-sign always yields different header bytes — tests distinguish
 * "cache hit" from "fresh sign" by comparing header strings and
 * [signCount].
 */
internal class FakeNip98Signer(
    override val pubkeyHex: String = "ab".repeat(32),
) : NostrSigner {
    var signCount = 0
        private set

    /** When set, [signEvent] throws it instead of signing (rejection tests). */
    var failure: Exception? = null

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent {
        failure?.let { throw it }
        signCount++
        return NostrEvent
            .createUnsigned(pubkeyHex, kind, content, tags, createdAt = 1_700_000_000L + signCount)
            .withSignature("0".repeat(128))
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String =
        throw UnsupportedOperationException("not used in NIP-98 tests")

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String =
        throw UnsupportedOperationException("not used in NIP-98 tests")
}
