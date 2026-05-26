package com.wisp.app.ui.component

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import com.wisp.app.viewmodel.Mention

// Mentions are now stored in the compose text as plain `@Name`, tracked out-of-band by
// ComposeViewModel and spliced to nostr:nprofile URIs at publish time. The OutputTransformation
// only still abbreviates pasted note/nevent quote URIs, where identity cursor mapping isn't a
// concern (atomic paste, no interleaved typing).
private val NOSTR_URI_REGEX = Regex("nostr:(note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
private val SHORTCODE_REGEX = Regex(":([a-zA-Z0-9_-]+):")

class MentionOutputTransformation(
    private val resolveDisplayName: (String) -> String?,
    private val resolvedEmojis: Map<String, String> = emptyMap()
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val original = asCharSequence().toString()

        data class Replacement(val start: Int, val end: Int, val display: String)
        val replacements = mutableListOf<Replacement>()

        for (match in NOSTR_URI_REGEX.findAll(original)) {
            val bech32 = match.groupValues[1]
            val display = when {
                bech32.startsWith("note1") -> "🔗${bech32.take(12)}..."
                bech32.startsWith("nevent1") -> "🔗${bech32.take(14)}..."
                else -> bech32.take(12) + "..."
            }
            replacements.add(Replacement(match.range.first, match.range.last + 1, display))
        }

        // Emoji shortcode replacements
        if (resolvedEmojis.isNotEmpty()) {
            for (match in SHORTCODE_REGEX.findAll(original)) {
                val shortcode = match.groupValues[1]
                if (resolvedEmojis.containsKey(shortcode)) {
                    // Check this range doesn't overlap with a quote URI replacement
                    val start = match.range.first
                    val end = match.range.last + 1
                    val overlaps = replacements.any { it.start < end && it.end > start }
                    if (!overlaps) {
                        replacements.add(Replacement(start, end, "⬡$shortcode"))
                    }
                }
            }
        }

        if (replacements.isEmpty()) return

        // Apply in reverse order to preserve indices
        for (r in replacements.sortedByDescending { it.start }) {
            replace(r.start, r.end, r.display)
        }
    }
}

/**
 * VisualTransformation for OutlinedTextField that replaces `:shortcode:` with `⬡shortcode`.
 */
class EmojiVisualTransformation(
    private val resolvedEmojis: Map<String, String>
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (resolvedEmojis.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val original = text.text
        val matches = SHORTCODE_REGEX.findAll(original)
            .filter { resolvedEmojis.containsKey(it.groupValues[1]) }
            .toList()
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        // Build a new AnnotatedString via Builder so any incoming SpanStyles (e.g. mention pills)
        // are preserved across the transformation. Appending AnnotatedString sub-sequences carries
        // their spans; appending raw replacement strings (like "⬡name") leaves them unstyled.
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        var lastEnd = 0
        data class Range(val origStart: Int, val origEnd: Int, val transStart: Int, val transEnd: Int)
        val ranges = mutableListOf<Range>()

        for (match in matches) {
            val shortcode = match.groupValues[1]
            if (match.range.first > lastEnd) {
                builder.append(text.subSequence(lastEnd, match.range.first))
            }
            val transStart = builder.length
            val display = "⬡$shortcode"
            builder.append(display)
            ranges.add(Range(match.range.first, match.range.last + 1, transStart, builder.length))
            lastEnd = match.range.last + 1
        }
        if (lastEnd < original.length) {
            builder.append(text.subSequence(lastEnd, original.length))
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var delta = 0
                for (r in ranges) {
                    if (offset <= r.origStart) break
                    if (offset >= r.origEnd) {
                        delta += (r.transEnd - r.transStart) - (r.origEnd - r.origStart)
                    } else {
                        // Inside a replacement — clamp to start of replacement
                        return r.transStart + (offset - r.origStart).coerceAtMost(r.transEnd - r.transStart)
                    }
                }
                return offset + delta
            }

            override fun transformedToOriginal(offset: Int): Int {
                var delta = 0
                for (r in ranges) {
                    if (offset <= r.transStart) break
                    if (offset >= r.transEnd) {
                        delta += (r.origEnd - r.origStart) - (r.transEnd - r.transStart)
                    } else {
                        return r.origStart + (offset - r.transStart).coerceAtMost(r.origEnd - r.origStart)
                    }
                }
                return offset + delta
            }
        }

        return TransformedText(builder.toAnnotatedString(), offsetMapping)
    }
}

private val COMPOSE_HASHTAG_REGEX = Regex("(?:^|(?<=\\s))#([a-zA-Z0-9_]+)")
private val COMPOSE_URL_REGEX = Regex("""https?://\S+""")

/**
 * Builds an [AnnotatedString] from [text] with three kinds of inline highlight:
 *  - tracked [mentions] render as pills (background + foreground colors)
 *  - `#hashtag` tokens render in [linkColor]
 *  - `http(s)://…` URLs render in [linkColor]
 *
 * Highlight ranges are merged and applied in left-to-right order. Mentions win when ranges
 * overlap; URLs win over hashtags when a URL contains a `#` fragment.
 */
fun buildMentionAnnotatedString(
    text: String,
    mentions: List<Mention>,
    pillBackground: Color,
    pillForeground: Color,
    defaultColor: Color,
    linkColor: Color = pillForeground
): AnnotatedString = buildAnnotatedString {
    data class Highlight(val start: Int, val end: Int, val style: SpanStyle)

    val pillStyle = SpanStyle(
        background = pillBackground,
        color = pillForeground,
        fontWeight = FontWeight.Medium
    )
    val linkStyle = SpanStyle(color = linkColor)

    val highlights = mutableListOf<Highlight>()

    for (m in mentions) {
        if (m.start in 0..text.length && m.end in m.start..text.length && m.start < m.end) {
            highlights += Highlight(m.start, m.end, pillStyle)
        }
    }

    fun overlapsExisting(start: Int, end: Int) =
        highlights.any { it.start < end && it.end > start }

    for (match in COMPOSE_URL_REGEX.findAll(text)) {
        val s = match.range.first
        val e = match.range.last + 1
        if (!overlapsExisting(s, e)) highlights += Highlight(s, e, linkStyle)
    }

    for (match in COMPOSE_HASHTAG_REGEX.findAll(text)) {
        val s = match.range.first
        val e = match.range.last + 1
        if (!overlapsExisting(s, e)) highlights += Highlight(s, e, linkStyle)
    }

    highlights.sortBy { it.start }

    var lastEnd = 0
    for (h in highlights) {
        if (h.start < lastEnd) continue // skip any range we already covered
        if (h.start > lastEnd) {
            withStyle(SpanStyle(color = defaultColor)) {
                append(text, lastEnd, h.start)
            }
        }
        withStyle(h.style) {
            append(text, h.start, h.end)
        }
        lastEnd = h.end
    }
    if (lastEnd < text.length) {
        withStyle(SpanStyle(color = defaultColor)) {
            append(text, lastEnd, text.length)
        }
    }
}
