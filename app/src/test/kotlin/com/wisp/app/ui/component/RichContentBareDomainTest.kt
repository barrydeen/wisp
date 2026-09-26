package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bare-domain linkification (`see zap.cooking/pow` -> tappable link).
 *
 * Ports the cross-platform contract from the web fix (zapcooking/frontend
 * "linkify bare domains on the full IANA TLD list"):
 * - the TLD gate is the full IANA list, not a hand-trimmed set
 * - a bare match is an inference -> always [ContentSegment.InlineLinkSegment]
 *   with a synthesized `https://`, never a [ContentSegment.LinkSegment]
 *   preview card or media embed
 * - explicit URLs and nostr refs claim their spans first
 * - trailing punctuation stays out of the href
 */
class RichContentBareDomainTest {

    private fun inlineLinks(content: String): List<String> =
        parseContent(content).mapNotNull { (it as? ContentSegment.InlineLinkSegment)?.url }

    @Test
    fun `bare domain with path links whole thing`() {
        assertEquals(listOf("https://zap.cooking/pow"), inlineLinks("see zap.cooking/pow for more"))
    }

    @Test
    fun `modern gTLDs linkify`() {
        assertEquals(listOf("https://jumble.social/notes"), inlineLinks("check jumble.social/notes"))
        assertEquals(listOf("https://oven.to"), inlineLinks("photos at oven.to"))
        assertEquals(listOf("https://something.blossom.band/img.png"), inlineLinks("hosted at something.blossom.band/img.png"))
    }

    @Test
    fun `www prefixed domain links`() {
        assertEquals(listOf("https://www.zap.cooking"), inlineLinks("go to www.zap.cooking now"))
    }

    @Test
    fun `bare match alone on its line is never a preview card`() {
        val segments = parseContent("zap.cooking/pow")
        assertEquals(ContentSegment.InlineLinkSegment("https://zap.cooking/pow"), segments[0])
        assertFalse(segments.any { it is ContentSegment.LinkSegment })
    }

    @Test
    fun `bare match is never a media embed`() {
        val segments = parseContent("files.example.com/pic.jpg")
        assertTrue(segments.any { it is ContentSegment.InlineLinkSegment })
        assertFalse(segments.any { it is ContentSegment.ImageSegment })
    }

    @Test
    fun `explicit url is not double claimed`() {
        val linkish = parseContent("see https://zap.cooking/pow").filter {
            it is ContentSegment.InlineLinkSegment || it is ContentSegment.LinkSegment
        }
        assertEquals(1, linkish.size)
    }

    @Test
    fun `non TLD lookalikes stay plain text`() {
        for (content in listOf("bake at 350.degreesf", "add 1.5 cups flour")) {
            val segments = parseContent(content)
            assertEquals(content, 1, segments.size)
            assertTrue(content, segments[0] is ContentSegment.TextSegment)
        }
    }

    @Test
    fun `trailing punctuation stays out of the href`() {
        assertEquals(listOf("https://jumble.social/notes"), inlineLinks("see jumble.social/notes, please"))
        assertEquals(listOf("https://jumble.social"), inlineLinks("see jumble.social, please"))
    }

    @Test
    fun `nostr ref and bare domain coexist`() {
        val content = "npub1hjlev3xn736aqr4ecmjxwwzuu9k523kp5fpz9n862s4lwah2h22sm2zg68 and zap.cooking/pow"
        val segments = parseContent(content)
        assertTrue(segments.any { it is ContentSegment.NostrProfileSegment })
        assertEquals(listOf("https://zap.cooking/pow"), inlineLinks(content))
    }

    @Test
    fun `schemeless blossom url stays one whole link`() {
        val content = "npub1hjlev3xn736aqr4ecmjxwwzuu9k523kp5fpz9n862s4lwah2h22sm2zg68.blossom.band/img.png"
        assertEquals(listOf("https://$content"), inlineLinks(content))
    }
}
