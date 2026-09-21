package com.wisp.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.R

/**
 * The composer's alt-text editor: image preview, a one-line explainer, and a
 * capped multiline field with a remaining-count. Saving an empty field clears
 * the description, and a cleared image emits no `imeta` tag on publish.
 */
@Composable
fun AltTextEditorDialog(
    url: String,
    initialAlt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(initialAlt)) }

    fun applyInput(value: TextFieldValue): TextFieldValue {
        // Hard cap at 2000 chars, count shown below.
        val overflow = value.text.length - ALT_TEXT_MAX_CHARS
        return if (overflow > 0) {
            value.copy(text = value.text.dropLast(overflow)).let {
                TextFieldValue(it.text, TextRange(it.text.length))
            }
        } else value
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_add_alt_text)) },
        text = {
            Column {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.alt_editor_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = applyInput(it) },
                    placeholder = { Text(stringResource(R.string.alt_editor_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.alt_editor_chars_remaining,
                            ALT_TEXT_MAX_CHARS - field.text.length
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (initialAlt.isNotEmpty()) {
                        TextButton(onClick = { onSave("") }) {
                            Text(stringResource(R.string.alt_editor_clear))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }) {
                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
