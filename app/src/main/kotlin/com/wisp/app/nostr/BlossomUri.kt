package com.wisp.app.nostr

/**
 * A BUD-10 `blossom:` media reference — `blossom:<sha256>[.<ext>][?params]`.
 * Content-addressed, so it names no server: resolution hints ride in the
 * query — repeatable `xs` (servers that hold the blob), `as` (author hex
 * pubkey, whose kind:10063 list may hold more servers), and `sz` (blob size
 * in bytes). Kept Android-free so the JVM suite can pin the format.
 */
data class BlossomUri(
    val sha256: String,
    val ext: String?,
    val serverHints: List<String>,
    val authors: List<String>,
    val sizeBytes: Long?,
) {
    /**
     * The BUD-01 blob route on [server] — `https://<host>/<sha256>`, without
     * the extension: the bare hash is the canonical GET, and some servers 404
     * the extended path. A hint that already carries a scheme is used as-is.
     */
    fun candidateUrls(server: String): String {
        val base = if ("://" in server) server.trimEnd('/') else "https://$server".trimEnd('/')
        return "$base/$sha256"
    }

    companion object {
        const val SCHEME = "blossom:"

        private val HASH_REGEX = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): BlossomUri? {
            if (!raw.startsWith(SCHEME, ignoreCase = true)) return null
            val reference = raw.substring(SCHEME.length)
            val hashPart = reference.substringBefore('?')
            val query = if ('?' in reference) reference.substringAfter('?') else null

            val hashSegments = hashPart.split('.', limit = 2)
            val sha256 = hashSegments[0]
            if (!HASH_REGEX.matches(sha256)) return null
            val ext = hashSegments.getOrNull(1)
                ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
                ?.lowercase()

            val hints = mutableListOf<String>()
            val authors = mutableListOf<String>()
            var sizeBytes: Long? = null
            if (query != null) {
                for (pair in query.split('&')) {
                    val sep = pair.indexOf('=')
                    if (sep <= 0) continue
                    val value = pair.substring(sep + 1)
                    when (pair.substring(0, sep)) {
                        "xs" -> if (value.isNotEmpty()) hints.add(value)
                        "as" -> if (HASH_REGEX.matches(value)) authors.add(value.lowercase())
                        "sz" -> sizeBytes = value.toLongOrNull()
                    }
                }
            }
            return BlossomUri(sha256.lowercase(), ext, hints, authors, sizeBytes)
        }
    }
}
