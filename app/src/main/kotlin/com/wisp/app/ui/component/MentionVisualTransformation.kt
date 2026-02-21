package com.wisp.app.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")

class MentionVisualTransformation(
    private val accentColor: Color,
    private val resolveDisplayName: (String) -> String?
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val matches = NOSTR_URI_REGEX.findAll(original).toList()

        if (matches.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val builder = AnnotatedString.Builder()
        val mentionStyle = SpanStyle(color = accentColor, fontWeight = FontWeight.Medium)

        data class Replacement(val origStart: Int, val origEnd: Int, val displayText: String)

        val replacements = mutableListOf<Replacement>()

        for (match in matches) {
            val bech32 = match.groupValues[1]
            val display = when {
                bech32.startsWith("npub1") || bech32.startsWith("nprofile1") -> {
                    val name = resolveDisplayName(bech32) ?: bech32.take(12) + "..."
                    "@$name"
                }
                bech32.startsWith("note1") -> "\uD83D\uDD17${bech32.take(12)}..."  // 🔗
                bech32.startsWith("nevent1") -> "\uD83D\uDD17${bech32.take(14)}..."  // 🔗
                else -> bech32.take(12) + "..."
            }
            replacements.add(Replacement(match.range.first, match.range.last + 1, display))
        }

        // Build transformed string
        var lastEnd = 0
        for (r in replacements) {
            if (r.origStart > lastEnd) {
                builder.append(original.substring(lastEnd, r.origStart))
            }
            builder.pushStyle(mentionStyle)
            builder.append(r.displayText)
            builder.pop()
            lastEnd = r.origEnd
        }
        if (lastEnd < original.length) {
            builder.append(original.substring(lastEnd))
        }

        val transformedText = builder.toAnnotatedString()

        // Build offset mapping
        val offsetMapping = MentionOffsetMapping(original.length, transformedText.length, replacements.map {
            Triple(it.origStart, it.origEnd, it.displayText.length)
        })

        return TransformedText(transformedText, offsetMapping)
    }
}

private class MentionOffsetMapping(
    private val originalLength: Int,
    private val transformedLength: Int,
    private val replacements: List<Triple<Int, Int, Int>> // (origStart, origEnd, displayLen)
) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        var shift = 0
        for ((origStart, origEnd, displayLen) in replacements) {
            if (offset <= origStart) break
            if (offset < origEnd) {
                // Cursor is inside a mention — map to end of display text
                return origStart + shift + displayLen
            }
            shift += displayLen - (origEnd - origStart)
        }
        return (offset + shift).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        var shift = 0
        for ((origStart, origEnd, displayLen) in replacements) {
            val transStart = origStart + shift
            val transEnd = transStart + displayLen
            if (offset <= transStart) break
            if (offset <= transEnd) {
                // Cursor is inside a display mention — map to end of original mention
                return origEnd
            }
            shift += displayLen - (origEnd - origStart)
        }
        return (offset - shift).coerceIn(0, originalLength)
    }
}
