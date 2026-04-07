package com.antz.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// ── Emoji shortcode autocomplete detection ───────────────────────────────

data class EmojiAutocompleteState(
    val triggerIndex: Int,  // index of ':' in the text
    val query: String       // text after ':' up to cursor
)

/** Walk backwards from cursor to detect an active `:` shortcode trigger. */
fun detectEmojiAutocomplete(tfv: TextFieldValue): EmojiAutocompleteState? {
    val text = tfv.text
    val cursor = tfv.selection.start
    if (cursor == 0 || text.isEmpty()) return null

    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        when {
            c == ':' -> {
                val query = text.substring(i + 1, cursor)
                if (!query.contains(' ') && !query.contains('\n') && !query.contains(':')) {
                    return EmojiAutocompleteState(i, query)
                }
                return null
            }
            c.isWhitespace() || c == '\n' -> return null
        }
        i--
    }
    return null
}

/** Replace the trigger + partial query with the selected `:shortcode:` completion. */
fun insertEmojiShortcode(tfv: TextFieldValue, triggerIndex: Int, shortcode: String): TextFieldValue {
    val text = tfv.text
    val cursor = tfv.selection.start
    val before = text.substring(0, triggerIndex)
    val after = if (cursor < text.length) text.substring(cursor) else ""
    val insertion = ":$shortcode:"
    val newText = "$before$insertion $after"
    return TextFieldValue(newText, TextRange(triggerIndex + insertion.length + 1))
}

// ── Composable popup ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiShortcodePopup(
    query: String,
    resolvedEmojis: Map<String, String>,
    onSelect: (shortcode: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val matches = remember(query, resolvedEmojis) {
        val q = query.lowercase()
        resolvedEmojis.entries
            .filter { (shortcode, _) -> q.isEmpty() || shortcode.contains(q) }
            .take(12)
    }
    if (matches.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        FlowRow(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            matches.forEach { (shortcode, url) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onSelect(shortcode) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(6.dp)
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = shortcode,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = shortcode,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }
        }
    }
}
