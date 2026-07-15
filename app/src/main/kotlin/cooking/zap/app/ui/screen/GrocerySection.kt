package cooking.zap.app.ui.screen

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.GroceryEvents.GroceryList
import cooking.zap.app.ui.component.DeleteGroceryListDialog
import cooking.zap.app.viewmodel.GroceryListViewModel
import kotlinx.coroutines.launch

/**
 * The Grocery section of the My Kitchen hub (arc PR 5) — list-of-lists view,
 * behavioral parity with web `/my-kitchen/grocery`. Cards show title, relative
 * updated time, checked progress, and a linked-recipes count; tapping opens the
 * detail route via [onOpenList]. Create publishes immediately with the default
 * title and jumps straight into the new list (web parity — no title prompt).
 * Card-level delete-with-confirmation is an Android addition over web (which
 * only deletes from detail).
 */
@Composable
fun GrocerySection(
    viewModel: GroceryListViewModel,
    onOpenList: (listId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lists by viewModel.lists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    var isCreating by remember { mutableStateOf(false) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val defaultTitle = stringResource(R.string.grocery_default_list_title)

    fun createAndOpen() {
        if (isCreating) return
        isCreating = true
        scope.launch {
            val id = try {
                viewModel.createListReturningId(defaultTitle)
            } finally {
                // Always clear the guard — a throw/cancel must not wedge the button.
                isCreating = false
            }
            if (id != null) onOpenList(id)
        }
    }

    when {
        isLoading && lists.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }

        lists.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text(text = "🛒", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.grocery_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.grocery_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = ::createAndOpen, enabled = !isCreating) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grocery_new_list))
                }
            }
        }

        else -> Column(modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = ::createAndOpen, enabled = !isCreating) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grocery_new_list))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(lists, key = { it.id }) { list ->
                    GroceryListCard(
                        list = list,
                        onClick = { onOpenList(list.id) },
                        onDelete = { deleteTarget = list.id },
                    )
                }
            }
        }
    }

    deleteTarget?.let { targetId ->
        val target = lists.firstOrNull { it.id == targetId } ?: run { deleteTarget = null; return }
        DeleteGroceryListDialog(
            title = target.title,
            onConfirm = {
                deleteTarget = null
                viewModel.deleteList(targetId)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun GroceryListCard(
    list: GroceryList,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val total = list.items.size
    val checked = list.items.count { it.checked }
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = list.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(list.updatedAt * 1000).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.cookbook_action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (total == 0) {
                Text(
                    text = stringResource(R.string.grocery_no_items),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.grocery_progress, checked, total),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (checked < total) {
                            stringResource(R.string.grocery_remaining, total - checked)
                        } else {
                            stringResource(R.string.grocery_complete)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (checked < total) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { checked.toFloat() / total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp),
                )
            }
            if (list.recipeLinks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.grocery_linked_recipes, list.recipeLinks.size, list.recipeLinks.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
