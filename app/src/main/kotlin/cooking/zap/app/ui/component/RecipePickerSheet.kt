package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import cooking.zap.app.R
import cooking.zap.app.mealplan.RecipePickerLogic
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeRepository
import cooking.zap.app.viewmodel.CookbookViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Planner recipe picker (arc PR 8.5) — ModalBottomSheet scaffolding mirrors
 * [RecipeListChooserSheet] (that sheet picks lists; this one picks a recipe).
 * Two tabs: **Saved** (unique coords across 30001 collections) and **Published**
 * (authored-recipes query, lazy on first open). Selection returns `{a, title}`
 * so the caller can write the contract denormalized title snapshot.
 *
 * Unresolvable coordinates stay in the list with the d-tag as fallback title
 * (web drops them — Android keeps them so the user can still plan).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipePickerSheet(
    cookbookViewModel: CookbookViewModel,
    recipeRepo: RecipeRepository,
    userPubkey: String?,
    onSelect: (a: String, title: String) -> Unit,
    onDismiss: () -> Unit,
    onBrowseRecipes: () -> Unit,
    onCreateRecipe: (() -> Unit)?,
) {
    // Mid-session logout / store-clear while open → close gracefully.
    LaunchedEffect(userPubkey) {
        if (userPubkey.isNullOrBlank()) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        var tabIndex by rememberSaveable { mutableIntStateOf(0) }
        var search by rememberSaveable { mutableStateOf("") }
        var savedRows by remember { mutableStateOf<List<RecipePickerLogic.PickerRow>>(emptyList()) }
        var savedLoading by remember { mutableStateOf(true) }
        var lastSavedKey by remember { mutableStateOf<String?>(null) }

        val lists by cookbookViewModel.lists.collectAsState()
        val authored by cookbookViewModel.authoredRecipes.collectAsState()
        val authoredLoading by cookbookViewModel.isAuthoredLoading.collectAsState()

        // Saved: flatten unique coords + resolve (keep unresolvable with d-tag).
        // Keyed only on lists so tab switches never cancel an in-flight resolution.
        // Cache-only first paint, then concurrent network fill for misses — avoids
        // blocking the sheet open on a large Saved set's relay round-trips.
        LaunchedEffect(lists) {
            val key = lists.joinToString("|") { "${it.dTag}:${it.event.id}" }
            if (key == lastSavedKey) return@LaunchedEffect
            val coords = RecipePickerLogic.uniqueCoordinates(lists)
            savedLoading = true
            savedRows = withContext(Dispatchers.IO) {
                resolveSavedRows(coords, recipeRepo, allowNetwork = false)
            }
            savedLoading = false
            savedRows = withContext(Dispatchers.IO) {
                resolveSavedRows(coords, recipeRepo, allowNetwork = true)
            }
            // Mark complete only after network fill so a cancel mid-fill retries.
            lastSavedKey = key
        }

        // Published: same author-feed filter as My Recipes — lazy on first open.
        // Keyed on userPubkey so an account switch while the sheet is open
        // re-triggers (requestMyRecipes is already idempotent per-pubkey).
        LaunchedEffect(tabIndex, userPubkey) {
            if (tabIndex != 1 || userPubkey.isNullOrBlank()) return@LaunchedEffect
            cookbookViewModel.requestMyRecipes()
        }

        val publishedRows = remember(authored) {
            authored.map { recipe ->
                val a = "${RecipeParser.RECIPE_KIND}:${recipe.author}:${recipe.dTag}"
                RecipePickerLogic.PickerRow(
                    a = a,
                    title = recipe.title?.takeIf { it.isNotBlank() } ?: recipe.dTag,
                    image = recipe.image,
                )
            }
        }

        val rows = if (tabIndex == 0) savedRows else publishedRows
        val loading = if (tabIndex == 0) savedLoading else (authoredLoading && publishedRows.isEmpty())
        val filtered = remember(rows, search) { RecipePickerLogic.filterRows(rows, search) }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.planner_recipe_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text(stringResource(R.string.tab_saved)) },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text(stringResource(R.string.planner_recipe_picker_tab_published)) },
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.planner_recipe_picker_search)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
            )
            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                filtered.isEmpty() -> {
                    PickerEmptyState(
                        tabIndex = tabIndex,
                        hasRowsButFiltered = rows.isNotEmpty(),
                        search = search,
                        onBrowseRecipes = {
                            onDismiss()
                            onBrowseRecipes()
                        },
                        onCreateRecipe = onCreateRecipe?.let { create ->
                            {
                                onDismiss()
                                create()
                            }
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.a }) { row ->
                            PickerRowItem(
                                row = row,
                                onClick = {
                                    onSelect(row.a, row.title)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun PickerEmptyState(
    tabIndex: Int,
    hasRowsButFiltered: Boolean,
    search: String,
    onBrowseRecipes: () -> Unit,
    onCreateRecipe: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasRowsButFiltered) {
            Text(
                text = stringResource(R.string.planner_recipe_picker_no_match, search.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else if (tabIndex == 0) {
            Text(
                text = stringResource(R.string.planner_recipe_picker_saved_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBrowseRecipes) {
                Text(stringResource(R.string.planner_recipe_picker_browse_cta))
            }
        } else {
            Text(
                text = stringResource(R.string.planner_recipe_picker_published_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            if (onCreateRecipe != null) {
                TextButton(onClick = onCreateRecipe) {
                    Text(stringResource(R.string.planner_recipe_picker_create_cta))
                }
            }
        }
    }
}

@Composable
private fun PickerRowItem(
    row: RecipePickerLogic.PickerRow,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = row.image
            if (!image.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp),
                    loading = { /* keep placeholder */ },
                    error = { /* keep placeholder */ },
                )
            } else {
                Text("🍽", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Resolve Saved coordinates concurrently. When [allowNetwork] is false, only
 * the local cache/ObjectBox is consulted (instant sheet paint with d-tag
 * fallbacks). When true, cache misses hit relays in parallel. Unresolvable
 * rows stay with the d-tag as title (spec: do not drop).
 */
private suspend fun resolveSavedRows(
    coordinates: List<String>,
    recipeRepo: RecipeRepository,
    allowNetwork: Boolean,
): List<RecipePickerLogic.PickerRow> = coroutineScope {
    coordinates.map { raw ->
        async {
            val parsed = CookbookCovers.parseCoordinate(raw)
            if (parsed == null) {
                return@async RecipePickerLogic.PickerRow(
                    a = raw,
                    title = RecipePickerLogic.titleFallback(raw),
                    image = null,
                )
            }
            val cached = recipeRepo.findRecipeEventByCoordinate(parsed.kind, parsed.author, parsed.dTag)
            val event = cached ?: if (allowNetwork) {
                recipeRepo.requestRecipeEventByCoordinate(parsed.kind, parsed.author, parsed.dTag)
            } else {
                null
            }
            val recipe = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
            RecipePickerLogic.PickerRow(
                a = raw,
                title = recipe?.title?.takeIf { it.isNotBlank() } ?: parsed.dTag,
                image = recipe?.image,
            )
        }
    }.awaitAll()
}
