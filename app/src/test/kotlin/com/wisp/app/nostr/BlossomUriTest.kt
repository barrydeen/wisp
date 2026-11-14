package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the BUD-10 `blossom:` reference parser — including the exact URI
 * shape seen in the wild (note 000006b1… by 1ec454…, "This is a test of
 * BUD-10"): hash + extension, three repeatable `xs` server hints, an `as`
 * author pubkey, and an `sz` size.
 */
class BlossomUriTest {

    private val fieldUri =
        "blossom:d819b922fbbf3a47e606dab12a6c316b9a0445ff4aa69d749d638ce83884cf1c.jpg" +
            "?xs=blossom.laantungir.net&xs=cdn.satellite.earth&xs=blossom.primal.net" +
            "&as=1ec454734dcbf6fe54901ce25c0c7c6bca5edd89443416761fadc321d38df139&sz=117475"

    @Test
    fun `parses the field URI - hash, extension, hints, author, size`() {
        val uri = BlossomUri.parse(fieldUri)!!
        assertEquals("d819b922fbbf3a47e606dab12a6c316b9a0445ff4aa69d749d638ce83884cf1c", uri.sha256)
        assertEquals("jpg", uri.ext)
        assertEquals(
            listOf("blossom.laantungir.net", "cdn.satellite.earth", "blossom.primal.net"),
            uri.serverHints
        )
        assertEquals(
            listOf("1ec454734dcbf6fe54901ce25c0c7c6bca5edd89443416761fadc321d38df139"),
            uri.authors
        )
        assertEquals(117475L, uri.sizeBytes)
    }

    @Test
    fun `parses a bare hash with no extension or query`() {
        val uri = BlossomUri.parse("blossom:" + "a".repeat(64))!!
        assertEquals("a".repeat(64), uri.sha256)
        assertNull(uri.ext)
        assertEquals(emptyList<String>(), uri.serverHints)
        assertNull(uri.sizeBytes)
    }

    @Test
    fun `rejects non-blossom and malformed references`() {
        assertNull(BlossomUri.parse("https://cdn.satellite.earth/" + "a".repeat(64) + ".jpg"))
        assertNull(BlossomUri.parse("blossom:xyz.jpg"))
        assertNull(BlossomUri.parse("blossom:"))
        assertNull(BlossomUri.parse("blossom:d819.jpg"))
    }

    @Test
    fun `candidate urls use the bare hash on https`() {
        val uri = BlossomUri.parse(fieldUri)!!
        assertEquals(
            "https://blossom.laantungir.net/d819b922fbbf3a47e606dab12a6c316b9a0445ff4aa69d749d638ce83884cf1c",
            uri.candidateUrls("blossom.laantungir.net")
        )
        // A hint that already carries a scheme is used as-is; trailing slashes are trimmed.
        assertEquals(
            "https://cdn.satellite.earth/d819b922fbbf3a47e606dab12a6c316b9a0445ff4aa69d749d638ce83884cf1c",
            uri.candidateUrls("https://cdn.satellite.earth/")
        )
    }
}
