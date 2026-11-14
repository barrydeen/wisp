package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTML character references in OG card text ([unescapeHtml]).
 *
 * bitcoinship.show serves og:title with the zero-padded numeric form
 * (`you&#039;re building`); the old fixed replacement list only knew the
 * unpadded `&#39;` and `&#x27;`, so cards rendered raw entities. The decoder
 * runs in a single left-to-right pass so escaped source like `&amp;#039;`
 * decodes once, to the literal `&#039;`.
 */
class RichContentUnescapeHtmlTest {

    // --- numeric character references ---

    @Test
    fun `zero-padded decimal apostrophe decodes`() {
        assertEquals("you're building", unescapeHtml("you&#039;re building"))
    }

    @Test
    fun `decimal and hex forms decode`() {
        assertEquals("'", unescapeHtml("&#39;"))
        assertEquals("'", unescapeHtml("&#x27;"))
        assertEquals("'", unescapeHtml("&#X27;"))
        assertEquals("\u2019", unescapeHtml("&#8217;"))
        assertEquals("\u2019", unescapeHtml("&#x2019;"))
        assertEquals("\uD83D\uDE00", unescapeHtml("&#x1F600;"))
    }

    // --- named entities ---

    @Test
    fun `named entities decode`() {
        assertEquals("a & b <c> \"q\" 'x'", unescapeHtml("a &amp; b &lt;c&gt; &quot;q&quot; &apos;x&apos;"))
        assertEquals("BTC \u2013 Lightning\u2026", unescapeHtml("BTC &ndash; Lightning&hellip;"))
        assertEquals("it\u2019s \u201Con\u201D", unescapeHtml("it&rsquo;s &ldquo;on&rdquo;"))
    }

    // --- single-pass decoding ---

    @Test
    fun `amp decodes only once`() {
        assertEquals("&#039;", unescapeHtml("&amp;#039;"))
        assertEquals("&amp;", unescapeHtml("&amp;amp;"))
    }

    // --- literal passthrough ---

    @Test
    fun `stray ampersands and unknown entities stay literal`() {
        assertEquals("fish & chips", unescapeHtml("fish & chips"))
        assertEquals("a &notanentity; b", unescapeHtml("a &notanentity; b"))
        assertEquals("&#; &#x; &#zz;", unescapeHtml("&#; &#x; &#zz;"))
        assertEquals("no entities here", unescapeHtml("no entities here"))
    }

    @Test
    fun `out of range scalar refs stay literal`() {
        assertEquals("&#xD800;", unescapeHtml("&#xD800;"))
        assertEquals("&#x110000;", unescapeHtml("&#x110000;"))
    }
}
