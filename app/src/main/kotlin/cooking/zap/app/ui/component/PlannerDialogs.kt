package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R

/**
 * Slot editor for the planner week view. Text entry + optional recipe picker
 * entry (arc PR 8.5): when [onPickRecipe] is non-null, empty slots show
 * "Add recipe" and recipe-filled slots show "Replace recipe" alongside the
 * text field / Clear. The picker writes via the same [onSaveText]-adjacent
 * [cooking.zap.app.viewmodel.PlannerViewModel.setSlot] path.
 *
 * [existingRecipeTitle] is set when the slot currently holds a recipe entry
 * (resolved or denormalized title); the dialog shows it read-only above the text
 * field so replacing a recipe with text is an explicit, visible choice.
 */
@Composable
fun SlotEditorDialog(
    dayLabel: String,
    slotLabel: String,
    existingText: String?,
    existingRecipeTitle: String?,
    onSaveText: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onPickRecipe: (() -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf(existingText.orEmpty()) }
    val hasEntry = !existingText.isNullOrBlank() || !existingRecipeTitle.isNullOrBlank()
    val hasRecipe = !existingRecipeTitle.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_slot_editor_title, dayLabel, slotLabel)) },
        text = {
            Column {
                if (hasRecipe) {
                    Text(
                        text = "🍽 $existingRecipeTitle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (onPickRecipe != null) {
                    TextButton(onClick = onPickRecipe, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (hasRecipe) R.string.planner_slot_replace_recipe
                                else R.string.planner_slot_add_recipe,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.planner_slot_entry_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSaveText(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (hasEntry) {
                    TextButton(onClick = onClear) {
                        Text(
                            stringResource(R.string.planner_slot_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * Notes editor — day notes or week notes. Multi-line; blank clears the note.
 * Text survives rotation / process death via rememberSaveable.
 */
@Composable
fun PlannerNotesDialog(
    title: String,
    hint: String,
    existing: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(existing.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
