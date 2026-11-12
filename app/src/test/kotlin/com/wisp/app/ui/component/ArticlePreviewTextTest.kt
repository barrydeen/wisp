package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticlePreviewTextTest {

    @Test
    fun `uses a summary that adds something`() {
        assertEquals("Why it matters", articlePreviewText("Why it matters", "BUD-10", "body"))
    }

    @Test
    fun `falls back to opening prose when the summary repeats the title`() {
        val content = """
            # BUD-10 Client Adoption Status

            | Client | Status |
            | --- | --- |
            | Amethyst | Working |

            Most clients **still** miss [BUD-10](https://example.com/bud10). ![chart](https://x/y.png)
            - Amethyst works
        """.trimIndent()
        assertEquals(
            "Most clients still miss BUD-10. Amethyst works",
            articlePreviewText("BUD-10 Client Adoption Status", "BUD-10 client adoption status", content)
        )
    }

    @Test
    fun `skips code blocks and caps the length`() {
        val content = "```\nval x = 1\n```\n" + "word ".repeat(100)
        val preview = articlePreviewText(null, "T", content, maxChars = 20)!!
        assertEquals("word word word word…", preview)
    }

    @Test
    fun `table-only article previews its first rows`() {
        val content = """
            # T

            | Client | Platform | Status | Evidence |
            | --- | --- | --- | --- |
            | **Amethyst** | Android | ✅ Working | [1](https://x) |
            | Wisp | Android | ❌ Not yet | none |
        """.trimIndent()
        assertEquals(
            "Amethyst \u00b7 Android \u00b7 ✅ Working\nWisp \u00b7 Android \u00b7 ❌ Not yet",
            articlePreviewText("T", "T", content)
        )
    }

    @Test
    fun `null when there is nothing to show`() {
        assertNull(articlePreviewText("", "T", "# T\n\n---"))
    }
}
