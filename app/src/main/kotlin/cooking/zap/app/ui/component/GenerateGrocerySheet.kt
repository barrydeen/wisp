package cooking.zap.app.ui.component

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.mealplan.GroceryGeneration
import cooking.zap.app.mealplan.IngredientParser
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Generate-grocery confirm sheet (arc PR 11, the convergence step) — the
 * Android counterpart of web's planner-page generate confirm. One deliberate
 * ordering difference, per the arc spec: the RESOLUTION dry-run happens
 * BEFORE confirm (web resolves after), so the sheet can state exactly what
 * will be created — "N items from X recipes", unresolvable coordinates
 * listed by name — and a zero-resolution week can refuse to create anything.
 * The commit step (list create + links + items) only runs on Generate.
 *
 * Resolution mirrors the planner grid's cache-first pattern
 * (`findRecipeEventByCoordinate` then `requestRecipeEventByCoordinate`);
 * per web, a coordinate counts as resolved when its EVENT loads — a recipe
 * whose markdown yields zero parseable lines still resolves (and still gets
 * a recipe link), it just contributes no items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateGrocerySheet(
    weekId: String,
    slots: GroceryGeneration.WeekRecipeSlots,
    /** Denormalized slot titles by coordinate — fallback naming for unresolved rows. */
    denormTitles: Map<String, String>,
    recipeRepo: RecipeRepository,
    onConfirm: (
        title: String,
        resolvedATags: List<String>,
        rows: List<GroceryGeneration.GenerationRow>,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = remember(weekId) { GroceryGeneration.groceryListTitle(weekId) }

    var resolving by remember { mutableStateOf(true) }
    var resolvedATags by remember { mutableStateOf<List<String>>(emptyList()) }
    var unresolvedATags by remember { mutableStateOf<List<String>>(emptyList()) }
    var dedupedRows by remember { mutableStateOf<List<GroceryGeneration.GenerationRow>>(emptyList()) }
    var confirmed by remember { mutableStateOf(false) }

    LaunchedEffect(weekId, slots) {
        resolving = true
        // Off the main dispatcher: cache lookups are ObjectBox scans (blocking
        // I/O) and the fallback hits relays — use Dispatchers.IO per convention.
        // Resolve coordinates concurrently so cold weeks aren't N×timeout;
        // awaitAll preserves slots.aTags order for the LinkedHashMap insert.
        val linesByATag = withContext(Dispatchers.IO) {
            coroutineScope {
                val pairs = slots.aTags.map { aTag ->
                    async {
                        val parts = aTag.split(":", limit = 3)
                        if (parts.size < 3) return@async aTag to null
                        val kind = parts[0].toIntOrNull() ?: return@async aTag to null
                        val event = recipeRepo.findRecipeEventByCoordinate(kind, parts[1], parts[2])
                            ?: recipeRepo.requestRecipeEventByCoordinate(kind, parts[1], parts[2])
                        aTag to event?.let { IngredientParser.extractIngredientsFromRecipe(it.content) }
                    }
                }.awaitAll()
                LinkedHashMap<String, List<String>>(pairs.size).apply {
                    for ((aTag, lines) in pairs) {
                        if (lines != null) put(aTag, lines)
                    }
                }
            }
        }
        val resolved = slots.aTags.filter { it in linesByATag }
        resolvedATags = resolved
        unresolvedATags = slots.aTags.filterNot { it in linesByATag }
        dedupedRows = GroceryGeneration.dedupeIngredients(
            GroceryGeneration.buildRows(resolved, linesByATag),
        )
        resolving = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.planner_generate_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            when {
                resolving -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.planner_generate_resolving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                resolvedATags.isEmpty() -> {
                    // Zero-resolution: show the failure, create nothing.
                    Text(
                        text = stringResource(R.string.planner_generate_failed_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }

                else -> {
                    val counts = stringResource(
                        R.string.planner_generate_summary_counts,
                        pluralStringResource(
                            R.plurals.grocery_item_count_label, dedupedRows.size, dedupedRows.size,
                        ),
                        pluralStringResource(
                            R.plurals.planner_generate_recipe_count,
                            resolvedATags.size,
                            resolvedATags.size,
                        ),
                    ) + if (slots.textCount > 0) {
                        " · " + pluralStringResource(
                            R.plurals.planner_generate_text_skipped, slots.textCount, slots.textCount,
                        )
                    } else ""
                    Text(
                        text = stringResource(R.string.planner_generate_summary, title, counts),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.planner_generate_dedupe_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (unresolvedATags.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.planner_generate_unresolved,
                                unresolvedATags.size,
                                unresolvedATags.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                            items(unresolvedATags, key = { it }) { aTag ->
                                Text(
                                    // Denormalized slot title, else the coordinate's d-tag.
                                    text = "• " + (
                                        denormTitles[aTag]
                                            ?: aTag.substringAfterLast(':').ifEmpty { aTag }
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !confirmed) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!confirmed) {
                                    confirmed = true
                                    onConfirm(title, resolvedATags, dedupedRows)
                                }
                            },
                            enabled = !confirmed,
                        ) {
                            if (confirmed) {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                Text(stringResource(R.string.planner_generate_action))
                            }
                        }
                    }
                }
            }
        }
    }
}
