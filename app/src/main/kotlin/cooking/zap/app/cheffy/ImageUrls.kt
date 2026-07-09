package cooking.zap.app.cheffy

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Shared image-URL detection — a **strict parity port of the web
 * `src/lib/imageUrls.ts`** (Phase 0 decision 4). That module is the single
 * source of truth: the `/api/zappy/note-review` server validates incoming
 * `imageUrl`s with the SAME code, so an Android detector that is looser
 * than web would surface a trigger the server then 400s, and one that is
 * stricter would hide triggers web shows. Any behavioral change must land
 * in both repos with mirrored tests (`imageUrls.test.ts` ↔ [ImageUrlsTest]).
 *
 * Deliberate consequences of parity (finding 0.5):
 *  - `heic`/`heif` are NOT images here even though [RichContent] renders
 *    them — the server would reject them (decision 4).
 *  - Blossom-style bare-hash paths (64-hex, no extension) do NOT match
 *    unless the host is on the rescue list — same as web.
 *  - Only `https?://` tokens are extracted; scheme-less bare domains and
 *    `wss://` (which RichContent's renderer tokenizes) are ignored.
 *
 * URL parsing uses OkHttp's [okhttp3.HttpUrl], whose WHATWG-ish behavior
 * matches the browser `URL` the web module relies on where `java.net.URI`
 * does not: hostnames are lowercased and browser-tolerated characters
 * don't throw. Invalid URLs are never images, exactly like web's
 * `try { new URL(url) } catch { return false }`.
 */
object ImageUrls {

    // Verbatim from imageUrls.ts. Tested against the parsed pathname, so
    // the `(\?.*)?$` arm is vestigial (a pathname never contains `?`) —
    // kept byte-identical to the reference anyway.
    private val IMAGE_EXTENSIONS =
        Regex("""\.(jpg|jpeg|png|gif|webp|svg|bmp|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    // Bare https?:// URL matcher for raw note content. Trailing punctuation
    // that commonly follows a pasted URL in prose is stripped after the match.
    private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""")
    private val TRAILING_PROSE_PUNCTUATION = Regex("""[.,;:!?]+$""")

    // Hosts that commonly serve images WITHOUT a file extension. This list
    // only rescues extensionless URLs — the extension test already admits
    // any host, so it is a detection heuristic, not a trust boundary (our
    // infra never fetches these URLs; OpenAI does).
    private val EXTENSIONLESS_IMAGE_HOSTS = listOf(
        "image.nostr.build",
        "imgur.com",
        "primal.b-cdn.net",
        "media.tenor.com",
        "i.ibb.co",
    )

    /**
     * Exact domain or subdomain-of match. Never substring — `imgur.com`
     * must not match `imgur.com.evil.example` or `notimgur.com`.
     */
    private fun matchesHost(hostname: String, domain: String): Boolean =
        hostname == domain || hostname.endsWith(".$domain")

    /**
     * True when the URL plausibly points at an image: the pathname carries
     * an image extension, or the host is a known image CDN whose URLs often
     * omit one. Invalid URLs are never images.
     */
    fun isImageUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val hostname = parsed.host
        val pathname = parsed.encodedPath
        if (IMAGE_EXTENSIONS.containsMatchIn(pathname)) return true
        if (EXTENSIONLESS_IMAGE_HOSTS.any { matchesHost(hostname, it) }) return true
        if (matchesHost(hostname, "nostr.build") && pathname.contains("/i/")) return true
        // Nostr clients run imgproxy instances under their own domains
        // (imgproxy.iris.to, imgproxy.snort.social, …) — match the host
        // label, not a substring.
        if (hostname.split(".").contains("imgproxy")) return true
        return false
    }

    /**
     * Filter candidate URLs down to image URLs, preserving first-occurrence
     * order and deduplicating. Dedup is load-bearing on web for lightbox
     * index math and will be for the Phase 4 thumbnail picker here.
     */
    fun filterImageUrls(urls: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (url in urls) {
            if (url.isNotEmpty() && url !in seen && isImageUrl(url)) {
                seen.add(url)
                result.add(url)
            }
        }
        return result
    }

    /**
     * Extract image URLs from raw note content (kind-1 text), in order of
     * appearance, deduplicated. Works on the raw string — no parser needed.
     */
    fun extractImageUrls(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val candidates = URL_REGEX.findAll(content)
            .map { it.value.replace(TRAILING_PROSE_PUNCTUATION, "") }
            .toList()
        return filterImageUrls(candidates)
    }
}
