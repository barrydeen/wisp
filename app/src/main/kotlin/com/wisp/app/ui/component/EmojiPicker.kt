package com.wisp.app.ui.component

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
private val DEFAULT_UNICODE_EMOJIS = listOf("\uD83E\uDDE1", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80", "\uD83E\uDD17", "\uD83D\uDE02", "\uD83D\uDE22", "\uD83D\uDC68\u200D\uD83D\uDCBB", "\uD83D\uDC40", "\u2705", "\uD83E\uDD21", "\uD83D\uDC38", "\uD83D\uDC80", "\u26A1", "\uD83D\uDE4F", "\uD83C\uDF46")

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
    val effectiveUnicode = unicodeEmojis.ifEmpty { DEFAULT_UNICODE_EMOJIS }
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
                // Unicode emoji shortcuts
                effectiveUnicode.forEach { emoji ->
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
                        Text(emoji, fontSize = 24.sp)
                    }
                }
                // Custom image emojis
                resolvedEmojis.forEach { (shortcode, url) ->
                    val emojiKey = ":$shortcode:"
                    val isSelected = emojiKey in selectedEmojis
                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    onSelect(emojiKey)
                                    onDismiss()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemoveEmoji?.invoke(emojiKey)
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
                        AsyncImage(
                            model = url,
                            contentDescription = shortcode,
                            modifier = Modifier.size(28.dp)
                        )
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

