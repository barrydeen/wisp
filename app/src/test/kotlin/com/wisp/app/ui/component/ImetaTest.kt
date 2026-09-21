package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the NIP-92 imeta wire contract for alt text, including the real
 * Amethyst-published interop vector that is live on relays.
 */
class ImetaTest {

    /** Interop vector, published from Amethyst (id 3957043a41de5c28…). */
    private val amethystTags = listOf(
        listOf(
            "imeta",
            "url https://npub1sjvt6lzmhj66gc3tjc5l4g3uhxz5lhaf4tqe2c0n5m92a0amffxq7veejj.blossom.band/ae8469f64b830b6eed6b1040cbfc4aaedb463c05cb2535fde0a062b652f2bd8f.jpg",
            "x ae8469…",
            "size 78925",
            "m image/jpeg",
            "dim 1080x2340",
            "blurhash [57nB:…",
            "ox ae8469…",
            "alt TV test pattern"
        ),
        listOf("client", "Amethyst")
    )

    private val amethystUrl =
        "https://npub1sjvt6lzmhj66gc3tjc5l4g3uhxz5lhaf4tqe2c0n5m92a0amffxq7veejj.blossom.band/ae8469f64b830b6eed6b1040cbfc4aaedb463c05cb2535fde0a062b652f2bd8f.jpg"

    @Test
    fun `amethyst vector - alt resolved by exact url`() {
        val map = parseImetaTags(amethystTags)
        assertEquals("TV test pattern", map[amethystUrl]?.alt)
    }

    @Test
    fun `vector carries the other slots through`() {
        val meta = parseImetaTags(amethystTags).getValue(amethystUrl)
        assertEquals("image/jpeg", meta.mime)
        assertEquals("1080x2340", meta.dimension)
        assertNull(meta.image)
    }

    @Test
    fun `kind agnostic - same parse for note, comment and article tag shapes`() {
        // The parser only sees tags, so any kind's event parses identically.
        val tags = listOf(
            listOf("imeta", "url https://host/a.jpg", "alt A grey tabby asleep on a windowsill"),
            listOf("e", "someid"),
            listOf("imeta", "url https://host/b.png", "alt " + "x".repeat(2000))
        )
        val map = parseImetaTags(tags)
        assertEquals("A grey tabby asleep on a windowsill", map["https://host/a.jpg"]?.alt)
        assertEquals("x".repeat(2000), map["https://host/b.png"]?.alt)
    }

    @Test
    fun `alt value may contain spaces but splits on first space only`() {
        val map = parseImetaTags(listOf(listOf("imeta", "url https://h/i.jpg", "alt  leading and trailing  ")))
        assertEquals("leading and trailing", map["https://h/i.jpg"]?.alt)
    }

    @Test
    fun `missing or blank alt is absent, not empty`() {
        val map = parseImetaTags(
            listOf(
                listOf("imeta", "url https://h/1.jpg"),
                listOf("imeta", "url https://h/2.jpg", "alt    ")
            )
        )
        assertNull(map["https://h/1.jpg"]?.alt)
        assertNull(map["https://h/2.jpg"]?.alt)
    }

    @Test
    fun `imeta without url is ignored and non-imeta tags never match`() {
        val map = parseImetaTags(
            listOf(
                listOf("imeta", "alt orphan"),
                listOf("imeta"),
                listOf("client", "Amethyst")
            )
        )
        assertTrue(map.isEmpty())
    }

    @Test
    fun `sanitizeAltText trims caps and collapses blank to null`() {
        assertEquals("TV test pattern", sanitizeAltText("  TV test pattern\n"))
        assertEquals("x".repeat(2000), sanitizeAltText("x".repeat(2500)))
        assertNull(sanitizeAltText("   "))
        assertNull(sanitizeAltText(""))
    }

    @Test
    fun `multiple imeta tags map each url independently`() {
        val map = parseImetaTags(
            listOf(
                listOf("imeta", "url https://h/1.jpg", "alt first"),
                listOf("imeta", "url https://h/2.jpg", "alt second")
            )
        )
        assertEquals(2, map.size)
        assertEquals("first", map["https://h/1.jpg"]?.alt)
        assertEquals("second", map["https://h/2.jpg"]?.alt)
    }
}
