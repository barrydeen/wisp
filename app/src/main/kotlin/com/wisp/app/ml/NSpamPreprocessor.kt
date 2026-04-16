package com.wisp.app.ml

import java.text.Normalizer

data class Prepared(
    val text: String,
    val rawText: String,
    val zeroWidthN: Int
)

object NSpamPreprocessor {
    val INVISIBLE_CHARS = setOf(
        '\u180E', '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2060', '\u2061', '\u2062', '\u2063', '\u2064',
        '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF'
    )

    private val URL_PATTERN = Regex("""https?://([^\s/]+)(/\S*)?""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_COLLAPSE = Regex("""\s+""")

    fun countInvisibleChars(text: String): Int =
        text.count { it in INVISIBLE_CHARS }

    fun preprocess(text: String): Prepared {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val zw = countInvisibleChars(nfkc)

        var stripped = nfkc.filter { it !in INVISIBLE_CHARS }
        stripped = URL_PATTERN.replace(stripped) { m -> "http://${m.groupValues[1].lowercase()}" }
        stripped = stripped.lowercase()
        stripped = WHITESPACE_COLLAPSE.replace(stripped, " ").trim()

        return Prepared(text = stripped, rawText = nfkc, zeroWidthN = zw)
    }
}
