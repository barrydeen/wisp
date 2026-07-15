package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.GroceryEvents.GroceryItem
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.repo.GroceryRepository
import cooking.zap.app.repo.RecipeRepository
import cooking.zap.app.ui.component.DeleteGroceryListDialog
import cooking.zap.app.ui.component.EditGroceryItemDialog
import cooking.zap.app.ui.component.RenameGroceryListDialog
import cooking.zap.app.viewmodel.GroceryListViewModel
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/** Web groceryStore's fixed category iteration order — mirrored for parity. */
val GROCERY_CATEGORY_ORDER = listOf("produce", "protein", "dairy", "pantry", "frozen", "other")

internal fun groceryCategoryEmoji(category: String): String = when (category) {
    "produce" -> "🥬"; "protein" -> "🥩"; "dairy" -> "🧀"
    "pantry" -> "🥫"; "frozen" -> "🧊"; else -> "📦"
}

@Composable
internal fun groceryCategoryLabel(category: String): String = when (category) {
    "produce" -> stringResource(R.string.grocery_category_produce)
    "protein" -> stringResource(R.string.grocery_category_protein)
    "dairy" -> stringResource(R.string.grocery_category_dairy)
    "pantry" -> stringResource(R.string.grocery_category_pantry)
    "frozen" -> stringResource(R.string.grocery_category_frozen)
    "other" -> stringResource(R.string.grocery_category_other)
    else -> category.replaceFirstChar { it.uppercase() } // unknown category from another client
}

/**
 * Grocery list detail (arc PR 5) — own route per the RECIPE_COLLECTION
 * precedent. Behavioral parity with web `/my-kitchen/grocery/[id]`: items
 * grouped by category in fixed order, checked items stay visible in place
 * (dimmed + struck through), quick-add keeps focus for rapid entry, notes save
 * on collapse/blur. Android additions over web (arc spec): per-item edit
 * dialog, resolved recipe-name chips (web shows only a count on the card).
 *
 * The "Saving…" indicator derives from mutations + [writeResults]: any local
 * mutation arms it; the next write result (the debounced publish finishing,
 * however it went) clears it. Failures also surface globally as toasts from
 * Navigation's collector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryListDetailScreen(
    viewModel: GroceryListViewModel,
    recipeRepo: RecipeRepository,
    writeResults: SharedFlow<GroceryRepository.WriteResult>,
    onBack: () -> Unit,
    onRecipeClick: (author: String, dTag: String) -> Unit,
) {
    val list by viewModel.selectedList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val canWrite by viewModel.canWrite.collectAsState()

    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { writeResults.collect { saving = false } }

    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var editItemId by rememberSaveable { mutableStateOf<String?>(null) }

    val current = list
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = current?.title.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (canWrite && current != null) {
                            IconButton(onClick = { showRename = true }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(R.string.grocery_rename_title),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (saving) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.grocery_saving),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (canWrite && current != null) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cookbook_action_delete))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (canWrite && current != null) {
                QuickAddBar(
                    onAdd = { name, qty ->
                        saving = true
                        viewModel.addItem(
                            current.id,
                            GroceryItem(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                quantity = qty,
                                checked = false,
                                addedAt = System.currentTimeMillis() / 1000,
                            ),
                        )
                    },
                )
            }
        },
    ) { padding ->
        when {
            current == null && isLoading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp) }

            current == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.grocery_list_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> GroceryListBody(
                listId = current.id,
                items = current.items,
                notes = current.notes,
                recipeLinks = current.recipeLinks,
                canWrite = canWrite,
                recipeRepo = recipeRepo,
                onRecipeClick = onRecipeClick,
                onMutate = { saving = true },
                onEditItem = { editItemId = it },
                viewModel = viewModel,
                contentPadding = padding,
            )
        }
    }

    if (showRename && current != null) {
        RenameGroceryListDialog(
            currentTitle = current.title,
            onConfirm = { saving = true; viewModel.renameList(current.id, it); showRename = false },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete && current != null) {
        DeleteGroceryListDialog(
            title = current.title,
            onConfirm = {
                showDelete = false
                viewModel.deleteList(current.id)
                onBack()
            },
            onDismiss = { showDelete = false },
        )
    }
    editItemId?.let { id ->
        val item = current?.items?.firstOrNull { it.id == id } ?: run { editItemId = null; return }
        EditGroceryItemDialog(
            item = item,
            onConfirm = { saving = true; viewModel.editItem(current.id, it); editItemId = null },
            onDismiss = { editItemId = null },
        )
    }

}

@Composable
private fun GroceryListBody(
    listId: String,
    items: List<GroceryItem>,
    notes: String?,
    recipeLinks: List<String>,
    canWrite: Boolean,
    recipeRepo: RecipeRepository,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    onMutate: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    viewModel: GroceryListViewModel,
    contentPadding: PaddingValues,
) {
    val total = items.size
    val checkedCount = items.count { it.checked }
    val grouped = remember(items) {
        val byCategory = items.groupBy { it.category.ifBlank { "other" } }
        val known = GROCERY_CATEGORY_ORDER.mapNotNull { cat -> byCategory[cat]?.let { cat to it } }
        val unknown = (byCategory.keys - GROCERY_CATEGORY_ORDER.toSet()).sorted()
            .map { it to byCategory.getValue(it) }
        known + unknown
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (recipeLinks.isNotEmpty()) {
            item(key = "recipes") {
                RecipeLinkChips(recipeLinks, recipeRepo, onRecipeClick)
            }
        }

        item(key = "stats") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (total == 0) {
                            stringResource(R.string.grocery_no_items)
                        } else {
                            stringResource(R.string.grocery_items_checked, checkedCount, total)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (canWrite && checkedCount > 0) {
                        TextButton(onClick = {
                            onMutate()
                            items.filter { it.checked }.forEach { viewModel.removeItem(listId, it.id) }
                        }) { Text(stringResource(R.string.grocery_clear_checked)) }
                    }
                }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { checkedCount.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        grouped.forEach { (category, categoryItems) ->
            item(key = "header-$category") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    Text(groceryCategoryEmoji(category), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${groceryCategoryLabel(category)} (${categoryItems.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(categoryItems, key = { it.id }) { item ->
                GroceryItemRow(
                    item = item,
                    canWrite = canWrite,
                    onToggle = { checked ->
                        onMutate()
                        viewModel.setChecked(listId, item.id, checked)
                    },
                    onEdit = { onEditItem(item.id) },
                    onRemove = {
                        onMutate()
                        viewModel.removeItem(listId, item.id)
                    },
                )
            }
        }

        item(key = "notes") {
            NotesSection(
                listId = listId,
                notes = notes,
                canWrite = canWrite,
                onMutate = onMutate,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun GroceryItemRow(
    item: GroceryItem,
    canWrite: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp)
            .alpha(if (item.checked) 0.6f else 1f),
    ) {
        Checkbox(checked = item.checked, onCheckedChange = if (canWrite) onToggle else null)
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            // Web parity: an empty quantity renders as "1".
            text = item.quantity.ifBlank { "1" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canWrite) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.grocery_edit_item_title),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cookbook_action_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Resolved recipe-name chips for a list's a-tag links. Cache-first, then a
 * network fill; coordinates that stay unresolved are silently dropped (arc
 * spec: degrade to nothing, not errors).
 */
@Composable
private fun RecipeLinkChips(
    recipeLinks: List<String>,
    recipeRepo: RecipeRepository,
    onRecipeClick: (author: String, dTag: String) -> Unit,
) {
    var resolved by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    LaunchedEffect(recipeLinks) {
        resolved = recipeLinks.mapNotNull { coord ->
            val parts = coord.split(":", limit = 3)
            if (parts.size < 3 || parts[0] != "30023") return@mapNotNull null
            val (_, author, dTag) = parts
            val event = recipeRepo.findRecipeEventByCoordinate(30023, author, dTag)
                ?: recipeRepo.requestRecipeEventByCoordinate(30023, author, dTag)
            val title = event?.let { RecipeFormats.forEvent(it)?.parse(it)?.title }
            title?.let { Triple(it, author, dTag) }
        }
    }
    if (resolved.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items(resolved, key = { it.second + it.third }) { (title, author, dTag) ->
            AssistChip(
                onClick = { onRecipeClick(author, dTag) },
                label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun NotesSection(
    listId: String,
    notes: String?,
    canWrite: Boolean,
    onMutate: () -> Unit,
    viewModel: GroceryListViewModel,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var buffer by rememberSaveable(listId) { mutableStateOf(notes.orEmpty()) }

    fun commit() {
        if (buffer != notes.orEmpty()) {
            onMutate()
            viewModel.setNotes(listId, buffer)
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📝 " + stringResource(R.string.grocery_notes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (expanded) commit() else buffer = notes.orEmpty()
                expanded = !expanded
            }) {
                Text(if (expanded) stringResource(R.string.action_save) else stringResource(R.string.grocery_notes))
            }
        }
        if (!expanded && !notes.isNullOrBlank()) {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = buffer,
                onValueChange = { buffer = it },
                enabled = canWrite,
                placeholder = { Text(stringResource(R.string.grocery_notes_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Rapid-entry bar (web AddItemForm parity): submit adds the item, clears the
 * fields, and KEEPS focus in the name field for the next item. Text survives
 * config change / process death via rememberSaveable.
 */
@Composable
private fun QuickAddBar(onAdd: (name: String, quantity: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        onAdd(trimmed, quantity.trim())
        name = ""
        quantity = ""
        // Focus is deliberately NOT cleared — grocery entry is rapid-fire.
    }

    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.grocery_add_item_hint)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                placeholder = { Text(stringResource(R.string.grocery_qty_hint)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.width(84.dp),
            )
            IconButton(onClick = ::submit, enabled = name.isNotBlank()) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.grocery_action_add))
            }
        }
    }
}
