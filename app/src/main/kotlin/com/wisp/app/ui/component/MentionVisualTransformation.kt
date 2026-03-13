package com.wisp.app.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
private val HASHTAG_REGEX = Regex("""(?<!\w)#([a-zA-Z0-9_][a-zA-Z0-9_-]*)""")

private data class ReplacementRange(
    val origStart: Int,
    val origEnd: Int,
    val transStart: Int,
    val transEnd: Int
)

class ComposeHighlightTransformation(
    private val linkColor: Color,
    private val resolveDisplayName: (String) -> String?
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        data class Span(val start: Int, val end: Int, val replacement: String?)

        val spans = mutableListOf<Span>()

        for (match in NOSTR_URI_REGEX.findAll(raw)) {
            val bech32 = match.groupValues[1]
            val display = when {
                bech32.startsWith("npub1") || bech32.startsWith("nprofile1") -> {
                    val name = resolveDisplayName(bech32) ?: bech32.take(12) + "..."
                    "@$name"
                }
                bech32.startsWith("note1") -> "\uD83D\uDD17${bech32.take(12)}..."
                bech32.startsWith("nevent1") -> "\uD83D\uDD17${bech32.take(14)}..."
                else -> bech32.take(12) + "..."
            }
            spans.add(Span(match.range.first, match.range.last + 1, display))
        }

        for (match in HASHTAG_REGEX.findAll(raw)) {
            val overlaps = spans.any { it.replacement != null && it.start <= match.range.first && it.end > match.range.first }
            if (!overlaps) {
                spans.add(Span(match.range.first, match.range.last + 1, null))
            }
        }

        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        spans.sortBy { it.start }

        val linkStyle = SpanStyle(color = linkColor)
        val replacements = mutableListOf<ReplacementRange>()

        val result = buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (cursor < span.start) {
                    append(raw.substring(cursor, span.start))
                }

                val transStart = length
                if (span.replacement != null) {
                    withStyle(linkStyle) {
                        append(span.replacement)
                    }
                    replacements.add(ReplacementRange(span.start, span.end, transStart, transStart + span.replacement.length))
                } else {
                    withStyle(linkStyle) {
                        append(raw.substring(span.start, span.end))
                    }
                }
                cursor = span.end
            }
            if (cursor < raw.length) {
                append(raw.substring(cursor))
            }
        }

        val mapping = if (replacements.isEmpty()) {
            OffsetMapping.Identity
        } else {
            ComposeOffsetMapping(raw.length, result.length, replacements)
        }

        return TransformedText(result, mapping)
    }
}

private class ComposeOffsetMapping(
    private val originalLength: Int,
    private val transformedLength: Int,
    private val replacements: List<ReplacementRange>
) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        var shift = 0
        for (r in replacements) {
            if (offset <= r.origStart) break
            if (offset >= r.origEnd) {
                shift += (r.transEnd - r.transStart) - (r.origEnd - r.origStart)
            } else {
                return r.transEnd
            }
        }
        return (offset + shift).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        var shift = 0
        for (r in replacements) {
            if (offset <= r.transStart) break
            if (offset >= r.transEnd) {
                shift += (r.origEnd - r.origStart) - (r.transEnd - r.transStart)
            } else {
                return r.origEnd
            }
        }
        return (offset + shift).coerceIn(0, originalLength)
    }
}
