package cooking.zap.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.mealplan.IngredientParser
import cooking.zap.app.nostr.GroceryEvents.GroceryList
import cooking.zap.app.ui.screen.groceryCategoryEmoji
import cooking.zap.app.ui.screen.groceryCategoryLabel
import kotlinx.coroutines.launch

/**
 * Add-to-grocery sheet (arc PR 10) — the Android counterpart of web's
 * AddToListModal (parse recipe → pick/create a list → add items + recipe link).
 * Scaffolding follows [RecipeListChooserSheet]; two deliberate deviations from
 * web, per the arc spec:
 *  - ingredients are individually deselectable (all ON by default — confirming
 *    untouched adds everything, matching web's add-all),
 *  - a zero-parse recipe explains itself instead of showing a disabled add
 *    button over an empty preview.
 *
 * List choice is single-select (radio — a recipe's ingredients go to ONE list,
 * unlike the multi-membership bookmark chooser). The inline "New list" create
 * publishes immediately and selects the new list (web parity: the modal's
 * Create calls groceryStore.addList before the confirm step), which is why
 * [onCreateList] is suspend — it awaits the repo id to select.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToGroceryListSheet(
    recipeTitle: String,
    ingredients: List<IngredientParser.ParsedIngredient>,
    lists: List<GroceryList>,
    listsLoading: Boolean,
    onCreateList: suspend (name: String) -> String?,
    onConfirm: (listId: String, selected: List<IngredientParser.ParsedIngredient>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val scope = rememberCoroutineScope()
        var selectedIndices by remember { mutableStateOf(ingredients.indices.toSet()) }
        var selectedListId by remember { mutableStateOf<String?>(null) }
        var creating by remember { mutableStateOf(false) }
        var createInFlight by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }

        fun submitCreate() {
            val name = newName.trim()
            if (name.isEmpty() || createInFlight) return
            createInFlight = true
            scope.launch {
                val id = onCreateList(name)
                createInFlight = false
                if (id != null) {
                    selectedListId = id
                    newName = ""
                    creating = false
                }
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.grocery_add_to_list_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.grocery_add_from_recipe, recipeTitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            if (ingredients.isEmpty()) {
                // Zero-parse path: explain, never silently no-op. Keep an
                // explicit dismissal (review: swipe/scrim alone is not the
                // sheet's footer idiom); list selection/confirm stay hidden.
                Text(
                    text = stringResource(R.string.grocery_add_no_ingredients),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                return@Column
            }

            Text(
                text = pluralStringResource(
                    R.plurals.grocery_add_ingredients_found, ingredients.size, ingredients.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                itemsIndexed(ingredients) { index, ingredient ->
                    val checked = index in selectedIndices
                    fun toggle() {
                        selectedIndices = if (checked) selectedIndices - index else selectedIndices + index
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle() }
                            .padding(vertical = 2.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ingredient.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = buildString {
                                if (ingredient.quantity.isNotBlank()) append(ingredient.quantity).append("  ·  ")
                                append(groceryCategoryEmoji(ingredient.category))
                                append(' ')
                                append(groceryCategoryLabel(ingredient.category))
                            }
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.grocery_add_choose_list),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (listsLoading && lists.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else if (lists.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(lists, key = { it.id }) { list ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedListId = list.id }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = list.id == selectedListId,
                                onClick = { selectedListId = list.id },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = list.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.grocery_item_count_label, list.items.size, list.items.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (creating) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    enabled = !createInFlight,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.grocery_add_new_list_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitCreate() }),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { creating = false; newName = "" },
                        enabled = !createInFlight,
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = { submitCreate() },
                        enabled = newName.isNotBlank() && !createInFlight,
                    ) {
                        Text(stringResource(R.string.btn_create))
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.grocery_new_list),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                val targetId = selectedListId
                Button(
                    onClick = {
                        if (targetId != null) {
                            onConfirm(
                                targetId,
                                ingredients.filterIndexed { index, _ -> index in selectedIndices },
                            )
                        }
                    },
                    enabled = targetId != null && selectedIndices.isNotEmpty(),
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.grocery_add_confirm, selectedIndices.size, selectedIndices.size,
                        )
                    )
                }
            }
        }
    }
}
