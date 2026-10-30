package com.wisp.app.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks down the gifs.nostr.build contract the picker publishes against: an
 * attached URL goes straight into a note, so only https gif/webp shapes with
 * a usable preview and dimensions survive, paging never asks past the API's
 * last offset, and queries are encoded before they hit the URL.
 */
class GifSearchTest {

    /** Builds the previews object; each slot holds the URLs the API sent (null = absent). */
    private fun previewsOf(
        w240Animated: String? = null,
        w240Still: String? = null,
        mediumAnimated: String? = null,
        mediumStill: String? = null
    ): JsonObject {
        fun slot(animated: String?, still: String?): JsonObject {
            val map = mutableMapOf<String, JsonElement>()
            if (animated != null) map["animated"] = JsonPrimitive(animated)
            if (still != null) map["still"] = JsonPrimitive(still)
            return JsonObject(map)
        }
        return JsonObject(
            mapOf(
                "w240" to slot(w240Animated, w240Still),
                "medium" to slot(mediumAnimated, mediumStill)
            )
        )
    }

    private val defaultPreviews =
        previewsOf(w240Animated = "https://image.nostr.build/w240.gif", mediumStill = "https://image.nostr.build/medium.gif")

    /** Builds one API item; null fields are left out, like the real API omits them. */
    private fun item(
        url: String? = "https://image.nostr.build/a.gif",
        format: String? = "gif",
        width: Double? = 480.0,
        height: Double? = 270.0,
        previews: JsonObject? = defaultPreviews
    ): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        if (url != null) map["url"] = JsonPrimitive(url)
        if (format != null) map["format"] = JsonPrimitive(format)
        if (width != null) map["width"] = JsonPrimitive(width)
        if (height != null) map["height"] = JsonPrimitive(height)
        if (previews != null) map["previews"] = previews
        return JsonObject(map)
    }

    private fun previewsOf(vararg entries: Pair<String, Pair<String, String>?>): JsonObject =
        JsonObject(
            entries.mapNotNull { (key, value) ->
                value?.let { (animated, still) ->
                    key to JsonObject(
                        buildMap {
                            if (animated != null) put("animated", JsonPrimitive(animated))
                            if (still != null) put("still", JsonPrimitive(still))
                        }
                    )
                }
            }.toMap()
        )

    private fun page(offset: Int, count: Int, items: List<JsonElement>): JsonObject =
        JsonObject(
            mapOf(
                "offset" to JsonPrimitive(offset),
                "count" to JsonPrimitive(count),
                "items" to JsonArray(items)
            )
        )

    // --- gifFromItem: what the picker keeps ---

    @Test
    fun `keeps an https gif with a preview and dimensions`() {
        val gif = gifFromItem(item())!!
        assertEquals("https://image.nostr.build/a.gif", gif.url)
        assertEquals("https://image.nostr.build/w240.gif", gif.preview)
        assertEquals(480.0, gif.width, 0.0)
        assertEquals(270.0, gif.height, 0.0)
        assertEquals("", gif.title)
    }

    @Test
    fun `webp is publishable like gif`() {
        assertEquals(
            "https://image.nostr.build/a.webp",
            gifFromItem(item(url = "https://image.nostr.build/a.webp", format = "webp"))!!.url
        )
    }

    @Test
    fun `keeps the title when one is sent`() {
        val gif = gifFromItem(item().let { JsonObject(it + ("title" to JsonPrimitive("  gm  "))) })!!
        assertEquals("gm", gif.title)
    }

    // --- gifFromItem: dropped shapes ---

    @Test
    fun `drops http links`() {
        assertNull(gifFromItem(item(url = "http://image.nostr.build/a.gif")))
    }

    @Test
    fun `drops mp4 renditions`() {
        assertNull(gifFromItem(item(format = "mp4")))
    }

    @Test
    fun `drops unknown formats`() {
        assertNull(gifFromItem(item(format = "png")))
    }

    @Test
    fun `drops items without a format`() {
        assertNull(gifFromItem(item(format = null)))
    }

    @Test
    fun `drops missing dimensions`() {
        assertNull(gifFromItem(item(width = null)))
        assertNull(gifFromItem(item(height = null)))
    }

    @Test
    fun `drops non-positive dimensions`() {
        assertNull(gifFromItem(item(width = 0.0)))
        assertNull(gifFromItem(item(height = -1.0)))
    }

    @Test
    fun `drops items without a usable preview url`() {
        assertNull(gifFromItem(item(previews = previewsOf(w240Animated = "http://image.nostr.build/w240.gif"))))
    }

    @Test
    fun `drops items without previews`() {
        assertNull(gifFromItem(item(previews = null)))
    }

    @Test
    fun `w240 falls through to medium when it has no usable link`() {
        val previews = previewsOf(
            w240Animated = "http://image.nostr.build/w240.gif",
            mediumStill = "https://image.nostr.build/medium.gif"
        )
        assertEquals("https://image.nostr.build/medium.gif", gifFromItem(item(previews = previews))!!.preview)
    }

    @Test
    fun `falls back to a still preview when animated is missing`() {
        val previews = previewsOf(w240Still = "https://image.nostr.build/w240-still.gif")
        assertEquals(
            "https://image.nostr.build/w240-still.gif",
            gifFromItem(item(previews = previews))!!.preview
        )
    }

    @Test
    fun `drops non-object items`() {
        assertNull(gifFromItem(null))
        assertNull(gifFromItem(JsonPrimitive("https://image.nostr.build/a.gif")))
        assertNull(gifFromItem(JsonArray(emptyList())))
    }

    // --- parseGifPage: paging math and the offset ceiling ---

    @Test
    fun `next counts every item the api sent, shown or not`() {
        val result = parseGifPage(page(0, 10, listOf(item(), item(url = "http://drop.me/x.gif"))))
        assertEquals(1, result.gifs.size)
        assertEquals(2, result.next)
    }

    @Test
    fun `paging stops at the end of the list`() {
        val result = parseGifPage(page(0, 3, listOf(item(), item(), item())))
        assertEquals(3, result.gifs.size)
        assertNull(result.next)
    }

    @Test
    fun `an empty page has no next`() {
        assertNull(parseGifPage(page(24, 100, emptyList())).next)
    }

    @Test
    fun `the last offset is 199`() {
        // 198 + 1 = 199 is still askable; 199 + 1 = 200 is past the ceiling.
        assertEquals(199, parseGifPage(page(198, 1000, listOf(item()))).next)
        assertNull(parseGifPage(page(199, 1000, listOf(item()))).next)
        assertNull(parseGifPage(page(196, 1000, listOf(item(), item(), item(), item()))).next)
    }

    @Test
    fun `malformed bodies parse as empty pages`() {
        assertNull(parseGifPage(null).next)
        assertNull(parseGifPage(JsonArray(emptyList())).next)
        assertEquals(0, parseGifPage(JsonObject(emptyMap())).gifs.size)
        // count only steers paging; items are still parsed
        assertEquals(1, parseGifPage(page(0, 0, listOf(item()))).gifs.size)
        assertNull(parseGifPage(page(0, 0, listOf(item()))).next)
    }

    // --- parseGifSuggestions ---

    @Test
    fun `suggestions are trimmed, deduped and capped`() {
        val body = JsonObject(
            mapOf(
                "terms" to JsonArray(
                    listOf(
                        JsonObject(mapOf("term" to JsonPrimitive(" bitcoin "))),
                        JsonObject(mapOf("term" to JsonPrimitive("bitcoin"))),
                        JsonObject(mapOf("term" to JsonPrimitive(""))),
                        JsonObject(mapOf("term" to JsonPrimitive("nostr")))
                    )
                )
            )
        )
        assertEquals(listOf("bitcoin", "nostr"), parseGifSuggestions(body))

        val many = JsonObject(
            mapOf(
                "terms" to JsonArray(
                    (1..10).map { JsonObject(mapOf("term" to JsonPrimitive("t$it"))) }
                )
            )
        )
        assertEquals(GIF_SUGGEST_LIMIT, parseGifSuggestions(many).size)
    }

    @Test
    fun `malformed suggestion bodies parse as empty`() {
        assertEquals(emptyList<String>(), parseGifSuggestions(null))
        assertEquals(emptyList<String>(), parseGifSuggestions(JsonObject(emptyMap())))
    }

    // --- request URLs ---

    @Test
    fun `search url encodes the query and spells out safe`() {
        assertEquals(
            "https://api.gifs.nostr.build/search?q=good+morning&limit=24&offset=24&safe=1",
            gifSearchUrl("https://api.gifs.nostr.build", "good morning", 24)
        )
    }

    @Test
    fun `query punctuation is encoded so it cannot change the request`() {
        assertEquals(
            "https://api.gifs.nostr.build/search?q=a%26limit%3D1&limit=24&offset=0&safe=1",
            gifSearchUrl("https://api.gifs.nostr.build", "a&limit=1", 0)
        )
    }

    @Test
    fun `query is clipped to 500 characters`() {
        assertEquals(
            "https://api.gifs.nostr.build/search?q=${"a".repeat(GIF_QUERY_MAX)}&limit=24&offset=0&safe=1",
            gifSearchUrl("https://api.gifs.nostr.build", "a".repeat(600), 0)
        )
        assertEquals("nostr", clipQuery(" nostr "))
    }

    @Test
    fun `offsets are clamped to zero`() {
        assertEquals(
            "https://api.gifs.nostr.build/search?q=gm&limit=24&offset=0&safe=1",
            gifSearchUrl("https://api.gifs.nostr.build", "gm", -5)
        )
    }

    @Test
    fun `suggest url uses the suggestion limit`() {
        assertEquals(
            "https://api.gifs.nostr.build/suggest?q=gm&limit=6&safe=1",
            gifSuggestUrl("https://api.gifs.nostr.build", "gm")
        )
    }

    // --- topic chips ---

    @Test
    fun `gm leads in the day and gn leads in the evening`() {
        assertEquals("gm", gifTopicsFor(9).first())
        assertEquals("gn", gifTopicsFor(20).first())
        assertEquals("gn", gifTopicsFor(3).first())
        assertEquals(GIF_TOPICS.size, gifTopicsFor(9).size)
        assertEquals(GIF_TOPICS.toSet(), gifTopicsFor(12).toSet())
    }

    // --- paging guards ---

    @Test
    fun `a page advanced only when the cursor moved forward`() {
        assertEquals(false, pageAdvanced(0, null))
        assertEquals(false, pageAdvanced(24, 24))
        assertEquals(true, pageAdvanced(0, 24))
    }

    @Test
    fun `load more fires within the threshold of the end`() {
        assertEquals(false, shouldLoadMore(loading = true, nextOffset = 24, lastVisibleIndex = 23, totalCount = 24))
        assertEquals(false, shouldLoadMore(loading = false, nextOffset = null, lastVisibleIndex = 23, totalCount = 24))
        assertEquals(false, shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = 23, totalCount = 0))
        assertEquals(true, shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = 17, totalCount = 24))
        assertEquals(false, shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = 16, totalCount = 24))
    }

    // --- error mapping ---

    @Test
    fun `key and availability failures are not retryable`() {
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(401))
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(403))
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(503))
        assertEquals(GifErrorKind.RATE_LIMITED, gifErrorKind(429))
        assertEquals(GifErrorKind.CONNECTION, gifErrorKind(500))
    }
}
