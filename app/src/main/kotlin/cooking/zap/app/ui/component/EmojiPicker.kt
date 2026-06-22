package cooking.zap.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
/**
 * Default quick-reaction pack \u2014 mirrors the web's curated reaction set
 * (QUICK_EMOJIS) plus food extras. Standard unicode (NOT NIP-30 custom emoji);
 * neutral/yellow base for the hand/person emoji (no skin-tone modifier).
 * Overridden by a user's configured unicodeEmojis when set.
 *
 * \u2764\uFE0F \uD83D\uDD25 \uD83D\uDC4D \uD83E\uDD19 \uD83D\uDE0B \uD83E\uDD24 \uD83E\uDD29 \uD83D\uDCAF \uD83D\uDE4F \uD83C\uDF73 \uD83E\uDD0C \uD83E\uDDD1\u200D\uD83C\uDF73 \uD83D\uDE0D \uD83C\uDF36\uFE0F \uD83E\uDD57 \uD83C\uDF7D\uFE0F
 */
private val DEFAULT_UNICODE_EMOJIS = listOf(
    "\u2764\uFE0F", "\uD83D\uDD25", "\uD83D\uDC4D", "\uD83E\uDD19",
    "\uD83D\uDE0B", "\uD83E\uDD24", "\uD83E\uDD29", "\uD83D\uDCAF",
    "\uD83D\uDE4F", "\uD83C\uDF73", "\uD83E\uDD0C", "\uD83E\uDDD1\u200D\uD83C\uDF73",
    "\uD83D\uDE0D", "\uD83C\uDF36\uFE0F", "\uD83E\uDD57", "\uD83C\uDF7D\uFE0F",
)

/**
 * Bridge for passing the pending reaction callback from EmojiReactionPopup
 * to EmojiLibrarySheet. When the user opens the emoji library via "+" in the
 * reaction popup, this holds the react callback so the library can both add
 * the emoji AND send the reaction in one action.
 */
internal var pendingEmojiReactCallback: ((String) -> Unit)? = null

/** Bridge for removing emojis from the quick reaction list. Set by the hosting screen. */
internal var emojiRemoveCallback: ((String) -> Unit)? = null

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EmojiReactionPopup(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedEmojis: Set<String> = emptySet(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    onRemoveEmoji: ((String) -> Unit)? = null
) {
    val effectiveEmojis = unicodeEmojis.ifEmpty { DEFAULT_UNICODE_EMOJIS }
    val haptic = LocalHapticFeedback.current

    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            FlowRow(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .heightIn(max = 250.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Start,
                verticalArrangement = Arrangement.Center
            ) {
                effectiveEmojis.forEach { emoji ->
                    val isCustom = emoji.length > 2 && emoji.startsWith(':') && emoji.endsWith(':')
                    val shortcode = if (isCustom) emoji.substring(1, emoji.length - 1) else null
                    val customUrl = shortcode?.let { resolvedEmojis[it] }
                    // Skip custom emojis whose shortcode is no longer in resolvedEmojis
                    if (isCustom && customUrl == null) return@forEach
                    val isSelected = emoji in selectedEmojis
                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    onSelect(emoji)
                                    onDismiss()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemoveEmoji?.invoke(emoji)
                                }
                            )
                            .padding(8.dp)
                            .then(
                                if (isSelected) Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                    ) {
                        if (customUrl != null) {
                            AsyncImage(
                                model = customUrl,
                                contentDescription = shortcode,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Text(emoji, fontSize = 24.sp)
                        }
                    }
                }
                TextButton(onClick = {
                    pendingEmojiReactCallback = onSelect
                    onDismiss()
                    onOpenEmojiLibrary?.invoke()
                }) {
                    Text("+", fontSize = 24.sp)
                }
            }
        }
    }
}

