package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
private val DEFAULT_UNICODE_EMOJIS = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiReactionPopup(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedEmojis: Set<String> = emptySet(),
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onManageEmojis: (() -> Unit)? = null
) {
    val effectiveUnicode = unicodeEmojis.ifEmpty { DEFAULT_UNICODE_EMOJIS }
    var showCustomDialog by remember { mutableStateOf(false) }

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
                    TextButton(
                        onClick = {
                            onSelect(emoji)
                            onDismiss()
                        },
                        modifier = if (isSelected) Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
                // Custom image emojis
                resolvedEmojis.forEach { (shortcode, url) ->
                    val emojiKey = ":$shortcode:"
                    val isSelected = emojiKey in selectedEmojis
                    TextButton(
                        onClick = {
                            onSelect(emojiKey)
                            onDismiss()
                        },
                        modifier = if (isSelected) Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = shortcode,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                TextButton(onClick = {
                    if (onManageEmojis != null) {
                        onDismiss()
                        onManageEmojis()
                    } else {
                        showCustomDialog = true
                    }
                }) {
                    Text("+", fontSize = 24.sp)
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomEmojiDialog(
            onConfirm = { emoji ->
                onSelect(emoji)
                onDismiss()
            },
            onDismiss = { showCustomDialog = false }
        )
    }

}

@Composable
private fun CustomEmojiDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Reaction") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type an emoji") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text("React")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

