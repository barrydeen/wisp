package com.wisp.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer

private val NOSTR_URI_REGEX = Regex("nostr:(npub1[a-z0-9]{58}|nprofile1[a-z0-9]+|note1[a-z0-9]{58}|nevent1[a-z0-9]+)")

@OptIn(ExperimentalFoundationApi::class)
class MentionOutputTransformation(
    private val resolveDisplayName: (String) -> String?
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val original = asCharSequence().toString()
        val matches = NOSTR_URI_REGEX.findAll(original).toList()
        if (matches.isEmpty()) return

        for (match in matches.asReversed()) {
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
            replace(match.range.first, match.range.last + 1, display)
        }
    }
}
