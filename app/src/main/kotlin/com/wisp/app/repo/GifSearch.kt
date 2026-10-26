package com.wisp.app.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder

/**
 * GIF search against gifs.nostr.build, nostr.build's GIF index — ported from
 * the web client's src/lib/gifSearch.ts (frontend PR #827), itself ported
 * from Sidecar's composer (the reference implementation of this feature).
 *
 * A native app is one of the two client kinds the gifs.nostr.build guide
 * allows to send an Authorization header, so requests go direct with a key
 * in BuildConfig — the web client's server proxy exists only because a key
 * shipped in a browser bundle is public.
 *
 * Every result is already hosted on a Nostr media host (image.nostr.build),
 * so picking a GIF attaches its URL and nothing is uploaded. The URL goes
 * into a published note, so results that cannot be published as-is (http
 * links, mp4 renditions, unknown shapes) are dropped rather than shown.
 *
 * Kept Android-free, like ComposerAttachments, so the hermetic JVM suite
 * can lock the contract down.
 */

const val GIF_PAGE_SIZE = 24

/** A query's list is at most 200 long, and the API rejects an offset past 199. */
const val GIF_LAST_OFFSET = 199

const val GIF_QUERY_MAX = 500

const val GIF_SUGGEST_LIMIT = 6

/** The grid packs columns about this wide (dp), two to five, shortest column first. */
const val GIF_COL_WIDTH_DP = 170

/**
 * There is no trending list to open on, so the picker opens on topic chips —
 * search terms, not interface text: they are what the index is tagged with.
 */
val GIF_TOPICS = listOf("gm", "gn", "pv", "zap", "bitcoin", "coffee", "lfg", "wow")

/**
 * The chips in the order the picker offers them: gm first through the day,
 * gn first in the evening and overnight, by the device's own clock. Nothing
 * is searched until one is tapped, so the clock only decides which comes first.
 */
fun gifTopicsFor(hourOfDay: Int): List<String> {
    val first = if (hourOfDay in 4..17) "gm" else "gn"
    return listOf(first) + GIF_TOPICS.filter { it != first }
}

/** One result the picker can show and publish as it is. */
data class Gif(
    val url: String,
    val preview: String,
    val width: Double,
    val height: Double,
    val title: String
)

/** A page of results, and the offset of the next page or null at the end. */
data class GifPage(
    val gifs: List<Gif>,
    val next: Int?
)

fun clipQuery(query: String): String = query.trim().take(GIF_QUERY_MAX)

/**
 * safe=1 is the API's default, spelled out: adult GIFs stay out of a picker
 * anyone can open.
 */
fun gifSearchUrl(baseUrl: String, query: String, offset: Int): String =
    "$baseUrl/search?q=${encode(clipQuery(query))}&limit=$GIF_PAGE_SIZE&offset=${offset.coerceAtLeast(0)}&safe=1"

fun gifSuggestUrl(baseUrl: String, query: String): String =
    "$baseUrl/suggest?q=${encode(clipQuery(query))}&limit=$GIF_SUGGEST_LIMIT&safe=1"

private fun encode(q: String): String = URLEncoder.encode(q, "UTF-8")

private fun isHttps(u: String?): Boolean = u != null && Regex("^https://\\S+$").matches(u)

private val GIF_FORMATS = setOf("gif", "webp")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.positiveNumber(key: String): Double? =
    ((this[key] as? JsonPrimitive)?.doubleOrNull)?.takeIf { it > 0 }

/**
 * One result, or null when it cannot be shown and posted as it is. The URL
 * goes into a published note, so it has to be an https link in one of the two
 * formats the index serves. The grid shows the w240 preview, the size the API
 * documents for column grids, animated when it can be and its first frame
 * when the GIF is too big to animate.
 */
fun gifFromItem(item: JsonElement?): Gif? {
    val obj = item as? JsonObject ?: return null
    val url = obj.string("url")
    val format = obj.string("format")
    if (url == null || !isHttps(url) || format == null || format !in GIF_FORMATS) return null
    // animated → still, w240 before medium: each candidate URL is checked in
    // turn, so a w240 without a usable link still falls through to medium.
    val previews = obj["previews"] as? JsonObject
    val preview = listOf("w240", "medium")
        .mapNotNull { previews?.get(it) as? JsonObject }
        .flatMap { listOf(it.string("animated"), it.string("still")) }
        .firstOrNull(::isHttps)
    val width = obj.positiveNumber("width")
    val height = obj.positiveNumber("height")
    if (preview == null || width == null || height == null) return null
    return Gif(
        url = url,
        preview = preview,
        width = width,
        height = height,
        title = obj.string("title")?.trim() ?: ""
    )
}

/**
 * A page of results, and where the next one starts. `count` is the length of
 * the query's whole list — and the offset counts every item the API sent,
 * shown or not, or the next page repeats one — so paging stops at count or at
 * the API's last offset, whichever comes first.
 */
fun parseGifPage(body: JsonElement?): GifPage {
    val obj = body as? JsonObject
    val items = obj?.get("items") as? JsonArray ?: JsonArray(emptyList())
    val offset = (obj?.get("offset") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    val count = (obj?.get("count") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    val gifs = items.mapNotNull(::gifFromItem)
    val next = offset + items.size
    return GifPage(
        gifs = gifs,
        next = if (items.isNotEmpty() && next < count && next <= GIF_LAST_OFFSET) next else null
    )
}

fun parseGifSuggestions(body: JsonElement?): List<String> {
    val terms = (body as? JsonObject)?.get("terms") as? JsonArray ?: return emptyList()
    val out = mutableListOf<String>()
    for (entry in terms) {
        val term = (entry as? JsonObject)?.string("term")?.trim() ?: ""
        if (term.isNotEmpty() && term !in out) out.add(term)
    }
    return out.take(GIF_SUGGEST_LIMIT)
}

/** What the picker says when a request fails — UI maps each to its own string. */
enum class GifErrorKind { UNAVAILABLE, RATE_LIMITED, CONNECTION }

/**
 * 401 and 403 are the API refusing the key and 503 is search being down or
 * unconfigured — nothing anyone at the keyboard can fix, so the picker says so
 * rather than suggesting a retry.
 */
fun gifErrorKind(status: Int): GifErrorKind = when (status) {
    401, 403, 503 -> GifErrorKind.UNAVAILABLE
    429 -> GifErrorKind.RATE_LIMITED
    else -> GifErrorKind.CONNECTION
}

/** Items from the end of the grid within which the next page loads. */
const val LOAD_MORE_THRESHOLD = 6

/**
 * Whether the grid should load its next page now — the LazyStaggeredGrid
 * equivalent of the web's 160px-from-the-bottom check.
 */
fun shouldLoadMore(loading: Boolean, nextOffset: Int?, lastVisibleIndex: Int, totalCount: Int): Boolean {
    if (loading || nextOffset == null || totalCount == 0) return false
    return lastVisibleIndex >= totalCount - 1 - LOAD_MORE_THRESHOLD
}

/**
 * Whether a page moved the cursor forward. The picker keeps paging only then,
 * so a cursor that doesn't move can't loop on the same page.
 */
fun pageAdvanced(offset: Int, next: Int?): Boolean = next != null && next > offset
