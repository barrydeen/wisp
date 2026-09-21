package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emission/read round-trip for the NIP-92 imeta `alt` slot. */
class Nip68Test {

    private fun event(tags: List<List<String>>) = NostrEvent(
        id = "x", pubkey = "p", created_at = 0L, kind = Nip68.KIND_PICTURE,
        tags = tags, content = "", sig = "s"
    )

    @Test
    fun `buildPictureTags emits url first then alt, trimmed`() {
        val tags = Nip68.buildPictureTags(
            title = null,
            media = listOf(
                Nip68.ImetaEntry(
                    url = "https://h/a.jpg",
                    mimeType = "image/jpeg",
                    dim = "100x200",
                    alt = "  TV test pattern  "
                )
            )
        )
        val imeta = tags.single { it.first() == "imeta" }
        assertEquals("url https://h/a.jpg", imeta[1]) // url stays the first slot
        assertEquals("m image/jpeg", imeta[2])
        assertEquals("dim 100x200", imeta[3])
        assertEquals("alt TV test pattern", imeta.last())
    }

    @Test
    fun `blank alt emits no alt slot and parse reads it back as absent`() {
        val tags = Nip68.buildPictureTags(
            title = null,
            media = listOf(Nip68.ImetaEntry(url = "https://h/a.jpg", alt = "   "))
        )
        val imeta = tags.single { it.first() == "imeta" }
        assertTrue(imeta.none { it.startsWith("alt ") })
        assertNull(Nip68.parseImetaEntries(event(tags)).single().alt)
    }

    @Test
    fun `round trip preserves alt and other slots`() {
        val entry = Nip68.ImetaEntry(
            url = "https://h/a.jpg",
            mimeType = "image/jpeg",
            thumbhash = "th",
            blurhash = "bh",
            dim = "1080x2340",
            alt = "TV test pattern",
            hash = "abcd",
            fallback = listOf("https://f/1.jpg", "https://f/2.jpg")
        )
        val tags = Nip68.buildPictureTags(title = null, media = listOf(entry))
        assertEquals(entry, Nip68.parseImetaEntries(event(tags)).single())
    }
}
