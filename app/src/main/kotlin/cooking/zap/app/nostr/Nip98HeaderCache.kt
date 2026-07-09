package cooking.zap.app.nostr

/**
 * TTL cache for NIP-98 `Authorization` headers (Phase 0 decision 1,
 * CHEFFY_NOTE_REVIEW_PLAN.md).
 *
 * The zap.cooking verifier (frontend `src/lib/nip98.server.ts`) accepts a
 * signed kind-27235 event while `|now - created_at| <= 60s` and keeps no
 * replay state, so one signed header may legally authenticate any number
 * of requests inside that window. Re-signing every request is wasted work
 * for [LocalSigner] and, for [RemoteSigner] (Amber), up to one approval
 * prompt per request — fatal for the 3s credit-status poll. This cache
 * reduces both to at most one sign per TTL window.
 *
 * Keyed on `(method, normalized URL, payload hash)`. The URL is
 * canonicalized with [Nip98.normalizeUrl] — the same normalization the
 * `u` tag uses — so requests that differ only in query string (e.g.
 * credit-status polls with different `?id=` values) share one header,
 * which is exactly what the verifier permits. A body-bearing request is
 * keyed on its payload hash, so a cached header can never be replayed
 * against different body bytes.
 *
 * The TTL is 30s — half the verifier window — so a cached header is never
 * presented during its clock-skew-sensitive final seconds.
 */
class Nip98HeaderCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A header value plus whether it was served from cache (drives the 401 retry rule). */
    data class CachedHeader(val header: String, val fromCache: Boolean)

    private data class Key(val method: String, val normalizedUrl: String, val payloadHash: String?)
    private data class Entry(val header: String, val signedAtMillis: Long)

    private val lock = Any()
    private val entries = HashMap<Key, Entry>()

    /**
     * Return a cached header for this request shape, or sign a fresh one.
     * Signing happens outside the lock (it may suspend on an external
     * signer); two concurrent misses may sign twice, which is harmless.
     */
    suspend fun authHeader(
        signer: NostrSigner,
        method: String,
        url: String,
        bodyString: String? = null,
    ): CachedHeader {
        val key = keyFor(method, url, bodyString)
        synchronized(lock) {
            entries[key]?.takeIf { clock() - it.signedAtMillis < ttlMillis }
        }?.let { return CachedHeader(it.header, fromCache = true) }

        val header = Nip98.authHeader(signer, method, url, bodyString)
        synchronized(lock) { entries[key] = Entry(header, clock()) }
        return CachedHeader(header, fromCache = false)
    }

    /** Drop the cached header for this request shape (if any). */
    fun invalidate(method: String, url: String, bodyString: String? = null) {
        val key = keyFor(method, url, bodyString)
        synchronized(lock) { entries.remove(key) }
    }

    /**
     * Run [send] with a cached-or-fresh header. When [isUnauthorized] is
     * true for the result AND the header came from the cache, invalidate,
     * silently re-sign once, and retry once — a cached header can be
     * rejected for staleness the client clock can't see. A rejection of a
     * freshly signed header is returned as-is: re-signing cannot fix it.
     */
    suspend fun <T> withAuthHeader(
        signer: NostrSigner,
        method: String,
        url: String,
        bodyString: String? = null,
        isUnauthorized: (T) -> Boolean,
        send: suspend (authHeader: String) -> T,
    ): T {
        val first = authHeader(signer, method, url, bodyString)
        val result = send(first.header)
        if (!first.fromCache || !isUnauthorized(result)) return result
        invalidate(method, url, bodyString)
        return send(authHeader(signer, method, url, bodyString).header)
    }

    private fun keyFor(method: String, url: String, bodyString: String?) = Key(
        method = method.uppercase(),
        normalizedUrl = Nip98.normalizeUrl(url),
        payloadHash = bodyString?.let { Nip98.sha256Hex(it.toByteArray(Charsets.UTF_8)) },
    )

    companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
    }
}
