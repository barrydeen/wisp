package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentBud10Test {

    private val hash = "b1674191a88ec5cdd733e4240a81803105dc412d6c6708d53ab94fc248f4f553"

    @Test
    fun `blossom uri with server hint becomes an image`() {
        val segments = parseContent("look blossom:$hash.png?xs=cdn.example.com&sz=10 nice")
        val image = segments.filterIsInstance<ContentSegment.ImageSegment>().single()
        assertEquals("https://cdn.example.com/$hash.png", image.meta.url)
    }

    @Test
    fun `imeta keyed by blossom uri is kept with the resolved url`() {
        val uri = "blossom:$hash.jpg?xs=cdn.example.com"
        val segments = parseContent(uri, imetaMap = mapOf(uri to MediaMeta(url = uri, dimension = "800x600")))
        val image = segments.filterIsInstance<ContentSegment.ImageSegment>().single()
        assertEquals("https://cdn.example.com/$hash.jpg", image.meta.url)
        assertEquals("800x600", image.meta.dimension)
    }

    @Test
    fun `blossom uri without server hint stays text`() {
        val segments = parseContent("blossom:$hash.png")
        assertTrue(segments.none { it is ContentSegment.ImageSegment })
    }
}
