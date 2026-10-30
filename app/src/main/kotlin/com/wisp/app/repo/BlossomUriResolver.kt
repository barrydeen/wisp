package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.BlossomUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns a BUD-10 `blossom:` URI into a concrete https URL by fetching the
 * blob from the servers that might hold it — the URI's own `xs` hints first,
 * then the signed-in account's configured Blossom servers — on the BUD-01
 * route (`https://<server>/<sha256>`), and verifying each download's SHA-256
 * against the URI's hash before trusting it: the URI is content-addressed,
 * so a server answering 200 with the wrong bytes (a "404 image", or a
 * planted blob) is rejected and the next candidate is tried. Candidates
 * are tried sequentially so a bad server costs one download, not N.
 * Resolutions are cached per URI — Coil, the media pager, and the
 * full-screen viewer can each ask for the same URI in one render — and
 * misses for five minutes, so an unreachable blob doesn't re-fetch on
 * every scroll.
 */
class BlossomUriResolver(
    private val httpClient: OkHttpClient,
    private val accountServers: () -> List<String>,
) {
    private val resolved = LruCache<String, String>(CACHE_SIZE)
    private val misses = LruCache<String, Long>(CACHE_SIZE)

    suspend fun resolve(rawUri: String): String? {
        resolved.get(rawUri)?.let { return it }
        misses.get(rawUri)?.let { at -> if (System.currentTimeMillis() - at < MISS_TTL_MS) return null }
        val uri = BlossomUri.parse(rawUri) ?: return null

        val servers = (uri.serverHints + accountServers()).distinct()
        val hit = withContext(Dispatchers.IO) {
            for (server in servers) {
                fetchVerified(uri.candidateUrls(server), uri.sha256)?.let { return@withContext it }
            }
            null
        }
        if (hit != null) {
            resolved.put(rawUri, hit)
        } else {
            misses.put(rawUri, System.currentTimeMillis())
        }
        return hit
    }

    /** GETs the blob and returns the URL only when the bytes hash to [sha256]. */
    private fun fetchVerified(url: String, sha256: String): String? = runCatching {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            if (Blossom.sha256Hex(bytes).equals(sha256, ignoreCase = true)) url else null
        }
    }.getOrNull()

    private companion object {
        const val CACHE_SIZE = 200
        const val MISS_TTL_MS = 5 * 60 * 1000L
    }
}
