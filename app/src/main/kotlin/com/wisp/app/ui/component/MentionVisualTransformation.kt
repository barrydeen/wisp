package com.wisp.app.ui.component

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
private val SHORTCODE_REGEX = Regex(":([a-zA-Z0-9_-]+):")

class MentionOutputTransformation(
    private val resolveDisplayName: (String) -> String?,
    private val resolvedEmojis: Map<String, String> = emptyMap()
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val original = asCharSequence().toString()

        // Collect all replacements (mention + emoji) then apply in reverse order
        data class Replacement(val start: Int, val end: Int, val display: String)
        val replacements = mutableListOf<Replacement>()

        // Mention replacements
        for (match in NOSTR_URI_REGEX.findAll(original)) {
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
            replacements.add(Replacement(match.range.first, match.range.last + 1, display))
        }

        // Emoji shortcode replacements
        if (resolvedEmojis.isNotEmpty()) {
            for (match in SHORTCODE_REGEX.findAll(original)) {
                val shortcode = match.groupValues[1]
                if (resolvedEmojis.containsKey(shortcode)) {
                    // Check this range doesn't overlap with a mention replacement
                    val start = match.range.first
                    val end = match.range.last + 1
                    val overlaps = replacements.any { it.start < end && it.end > start }
                    if (!overlaps) {
                        replacements.add(Replacement(start, end, "\u2B21$shortcode"))
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

        val sb = StringBuilder()
        // Maps: original index -> transformed index offset
        // Each replacement changes `:shortcode:` (len = shortcode.length + 2) to `⬡shortcode` (len = shortcode.length + 1)
        // So each replacement removes 1 character
        var lastEnd = 0
        data class Range(val origStart: Int, val origEnd: Int, val transStart: Int, val transEnd: Int)
        val ranges = mutableListOf<Range>()

        for (match in matches) {
            val shortcode = match.groupValues[1]
            sb.append(original, lastEnd, match.range.first)
            val transStart = sb.length
            val display = "\u2B21$shortcode"
            sb.append(display)
            ranges.add(Range(match.range.first, match.range.last + 1, transStart, sb.length))
            lastEnd = match.range.last + 1
        }
        sb.append(original, lastEnd, original.length)

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

        return TransformedText(AnnotatedString(sb.toString()), offsetMapping)
    }
}
