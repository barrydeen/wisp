package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blank-line pass in [parseContent]: surplus blank lines inside a post's prose.
 *
 * A post padded with blank lines, or paragraphs split by three or more newlines,
 * rendered every extra newline as a real empty line — a gap the reader can't
 * collapse and the author usually didn't ask for. One blank line is the paragraph
 * break and survives; single newlines are always left alone.
 *
 * The block-adjacency case (blank lines hugging an image / invoice / embed) is the
 * preceding pass's job and isn't covered here. Ported from wisp-ios
 * ContentParser pass 6.
 */
class RichContentBlankLineTest {

    private fun soleText(content: String): String {
        val segments = parseContent(content)
        assertEquals("expected a single text segment, got $segments", 1, segments.size)
        return (segments[0] as ContentSegment.TextSegment).text
    }

    // --- Surplus is removed ---

    @Test
    fun `trailing blank lines are dropped`() {
        assertEquals("gm", soleText("gm\n\n\n"))
        // trimEnd at the call site stops at the first space, so this shape only
        // survives because the pass handles whitespace-bearing blank lines.
        assertEquals("gm", soleText("gm\n  \n  "))
    }

    @Test
    fun `leading blank lines are dropped`() {
        assertEquals("gm", soleText("\n\n\ngm"))
    }

    @Test
    fun `surplus between paragraphs collapses to one blank line`() {
        assertEquals("first\n\nsecond", soleText("first\n\n\n\n\nsecond"))
    }

    @Test
    fun `blank lines carrying whitespace still collapse`() {
        assertEquals("first\n\nsecond", soleText("first\n   \n \t \nsecond"))
    }

    @Test
    fun `whitespace only post produces no text segment`() {
        assertTrue(parseContent("\n\n  \n").isEmpty())
    }

    // --- Meaningful structure survives ---

    @Test
    fun `single newlines are never touched`() {
        // A stanza / address / hand-made list: every break is intentional.
        assertEquals("one\ntwo\nthree", soleText("one\ntwo\nthree"))
    }

    @Test
    fun `one blank line paragraph break survives`() {
        assertEquals("first\n\nsecond", soleText("first\n\nsecond"))
    }

    @Test
    fun `indent after leading blank lines survives`() {
        // Only the newlines go — indentation on the first visible line stays.
        assertEquals("    indented", soleText("\n\n    indented"))
    }

    @Test
    fun `opting out leaves the text alone`() {
        val segments = parseContent("gm\n\n\n\nbye\n\n", trimBlankLines = false)
        assertEquals("gm\n\n\n\nbye\n\n", (segments[0] as ContentSegment.TextSegment).text)
    }
}
