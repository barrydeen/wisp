package com.wisp.app.ui.component

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// Mentions are stored in the compose text as plain `@Name`, tracked out-of-band by
// ComposeViewModel and spliced to nostr:nprofile URIs at publish time.
private val NOSTR_URI_REGEX = Regex("nostr:(note1[a-z0-9]{58}|nevent1[a-z0-9]+)")
private val SHORTCODE_REGEX = Regex(":([a-zA-Z0-9_-]+):")

/**
 * OutputTransformation for the state-based BasicTextField (group chat input,
 * notifications composer): abbreviates pasted note/nevent quote URIs and
 * swaps resolved emoji shortcodes for their sigil. The main composer uses
 * [ComposerVisualTransformation] instead — it needs span-styled pills,
 * which the state API's output can't carry before Compose 1.8.
 */
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

        if (resolvedEmojis.isNotEmpty()) {
            for (match in SHORTCODE_REGEX.findAll(original)) {
                val shortcode = match.groupValues[1]
                if (resolvedEmojis.containsKey(shortcode)) {
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
 * The composer editor's transformation, on the value-based `BasicTextField`:
 * pasted note/nevent quote URIs abbreviate to `🔗…` chips, resolved emoji
 * shortcodes render their `⬡shortcode` sigil, and the tracked `@Name` mention
 * tokens style as pills (accent text over a subtle fill — iOS parity). The
 * text itself is unchanged for mentions; pills are pure spans, so the
 * caret walks the buffer exactly as typed.
 */
class ComposerVisualTransformation(
    private val mentionRanges: List<Pair<Int, Int>> = emptyList(),
    private val pillTextColor: Color = Color.Unspecified,
    private val pillFillColor: Color = Color.Unspecified,
    private val resolvedEmojis: Map<String, String> = emptyMap()
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val stylePills = mentionRanges.isNotEmpty() && pillTextColor != Color.Unspecified

        data class Replacement(val origStart: Int, val origEnd: Int, val display: String)
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

        if (resolvedEmojis.isNotEmpty()) {
            for (match in SHORTCODE_REGEX.findAll(original)) {
                val shortcode = match.groupValues[1]
                if (resolvedEmojis.containsKey(shortcode)) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    val overlaps = replacements.any { it.origStart < end && it.origEnd > start }
                    if (!overlaps) {
                        replacements.add(Replacement(start, end, "⬡$shortcode"))
                    }
                }
            }
        }

        if (replacements.isEmpty() && !stylePills) return TransformedText(text, OffsetMapping.Identity)

        // Build the transformed string, tracking each replacement's mapped range.
        val sb = StringBuilder()
        data class Mapped(val origStart: Int, val origEnd: Int, val transStart: Int, val transEnd: Int)
        val mapped = mutableListOf<Mapped>()
        var lastEnd = 0
        for (r in replacements.sortedBy { it.origStart }) {
            sb.append(original, lastEnd, r.origStart)
            val transStart = sb.length
            sb.append(r.display)
            mapped.add(Mapped(r.origStart, r.origEnd, transStart, sb.length))
            lastEnd = r.origEnd
        }
        sb.append(original, lastEnd, original.length)

        // Mention pill spans, mapped from original coordinates; a pill that a
        // replacement overlapped (can't happen in practice) is skipped.
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        if (stylePills) {
            val pillStyle = SpanStyle(color = pillTextColor, background = pillFillColor)
            for ((start, end) in mentionRanges) {
                if (start < 0 || end <= start || end > original.length) continue
                if (mapped.any { it.origStart < end && start < it.origEnd }) continue
                val delta = mapped.filter { it.origEnd <= start }
                    .sumOf { (it.transEnd - it.transStart) - (it.origEnd - it.origStart) }
                spans.add(AnnotatedString.Range(pillStyle, start + delta, end + delta))
            }
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var delta = 0
                for (r in mapped) {
                    if (offset <= r.origStart) break
                    if (offset >= r.origEnd) {
                        delta += (r.transEnd - r.transStart) - (r.origEnd - r.origStart)
                    } else {
                        return r.transStart + (offset - r.origStart).coerceAtMost(r.transEnd - r.transStart)
                    }
                }
                return offset + delta
            }

            override fun transformedToOriginal(offset: Int): Int {
                var delta = 0
                for (r in mapped) {
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

        return TransformedText(AnnotatedString(sb.toString(), spans), offsetMapping)
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
            val display = "⬡$shortcode"
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
