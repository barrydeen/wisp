package com.wisp.app.nostr

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * NIP-98 HTTP Auth (kind 27235).
 *
 * Signs a short-lived event bound to a specific URL, method, and
 * (optionally) request-body hash, then encodes it for the
 * `Authorization: Nostr <base64(event)>` header.
 *
 * This is a byte-for-byte port of the zap.cooking frontend reference so
 * the server verifier accepts our headers:
 *   - client: frontend `src/lib/nip98.ts` (`signNip98AuthHeader`)
 *   - verifier: frontend `src/lib/nip98.server.ts` (`verifyNip98`)
 *
 * Footguns the verifier is strict about (both sides run the SAME
 * normalization, so a mismatch is a real bug, not a normalization quirk):
 *   - **URL**: the `u` tag is `origin + pathname` ONLY — the query string
 *     and fragment are dropped, and a trailing slash is stripped on
 *     non-root paths. (The build spec's "query included" note is wrong vs.
 *     the reference; corrected in ZAPCOOKING_ANDROID_BUILD.md §1.)
 *   - **method**: upper-cased.
 *   - **payload**: lowercase-hex SHA-256 of the exact body bytes sent.
 *
 * Sign via the [NostrSigner] abstraction. This fork is `LocalSigner`-only;
 * `READ_ONLY` accounts have no key and cannot produce a header — callers
 * must gate signing-dependent features on "account has a signing key."
 */
object Nip98 {
    const val KIND = 27235

    /**
     * Canonicalize a URL for the `u` tag. Must match `normalizeUrl` in
     * nip98.ts: `origin + pathname`, query/fragment dropped, trailing
     * slash stripped on non-root paths.
     *
     * Note: `java.net.URI.path` is "" for a URL with no path (e.g.
     * `https://zap.cooking`), whereas JS `URL.pathname` is "/". We coerce
     * empty to "/" so both implementations agree.
     */
    fun normalizeUrl(url: String): String {
        val u = URI(url)
        val portPart = if (u.port != -1) ":${u.port}" else ""
        val origin = "${u.scheme}://${u.host}$portPart"
        var pathname = u.rawPath ?: ""
        if (pathname.isEmpty()) pathname = "/"
        if (pathname.length > 1 && pathname.endsWith("/")) {
            pathname = pathname.dropLast(1)
        }
        return "$origin$pathname"
    }

    /** Lowercase-hex SHA-256 of raw bytes — NIP-98 `payload` tag format. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * Build the NIP-98 tags for a request. When [bodyString] is non-null,
     * a `payload` tag binds the signature to those exact bytes.
     */
    fun buildTags(method: String, url: String, bodyString: String?): List<List<String>> {
        val tags = mutableListOf(
            listOf("u", normalizeUrl(url)),
            listOf("method", method.uppercase())
        )
        if (bodyString != null) {
            tags.add(listOf("payload", sha256Hex(bodyString.toByteArray(Charsets.UTF_8))))
        }
        return tags
    }

    /**
     * Encode an already-signed kind-27235 event into the header value.
     * The canonical JSON key order (`id, pubkey, created_at, kind, tags,
     * content, sig`) and compact formatting must match the verifier's
     * `atob(...)` → `JSON.parse` path exactly. We hand-build the JSON
     * because [NostrEvent.toJson] serializes `created_at` as a *string*
     * (LongAsStringSerializer), but the reference emits it as a number.
     */
    fun encodeAuthHeader(event: NostrEvent): String {
        val obj = buildJsonObject {
            put("id", event.id)
            put("pubkey", event.pubkey)
            put("created_at", event.created_at) // Number → unquoted, matches JSON.stringify
            put("kind", event.kind)
            putJsonArray("tags") {
                event.tags.forEach { tag ->
                    addJsonArray { tag.forEach { add(it) } }
                }
            }
            put("content", event.content)
            put("sig", event.sig)
        }
        val canonical = obj.toString() // compact, insertion-ordered, JSON-escaped
        val b64 = Base64.getEncoder().encodeToString(canonical.toByteArray(Charsets.UTF_8))
        return "Nostr $b64"
    }

    /**
     * Sign and encode a NIP-98 `Authorization` header for a single
     * request. Returns the full header value (with the `Nostr ` prefix).
     */
    suspend fun authHeader(
        signer: NostrSigner,
        method: String,
        url: String,
        bodyString: String? = null
    ): String {
        val event = signer.signEvent(KIND, "", buildTags(method, url, bodyString))
        return encodeAuthHeader(event)
    }
}
