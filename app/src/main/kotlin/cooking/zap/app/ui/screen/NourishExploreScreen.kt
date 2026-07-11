package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.ui.component.NourishExploreRecipeCard
import cooking.zap.app.ui.component.NourishFilterChip
import cooking.zap.app.ui.component.NourishSortChip
import cooking.zap.app.viewmodel.NourishExploreViewModel

private val NourishGreen = Color(0xFF22C55E)

private val SORT_OPTIONS = listOf(
    NourishDiscovery.SortDimension.OVERALL to R.string.nourish_explore_sort_overall,
    NourishDiscovery.SortDimension.REAL_FOOD to R.string.nourish_explore_sort_real_food,
    NourishDiscovery.SortDimension.GUT to R.string.nourish_explore_sort_gut,
    NourishDiscovery.SortDimension.PROTEIN to R.string.nourish_explore_sort_protein,
)

/**
 * Nourish Explore — ranked/filtered discovery of pantry-analyzed recipes.
 * Models web `/nourish/explore`: filter chips (AND), client-side sort, degrade
 * note, honesty subtitle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NourishExploreScreen(
    viewModel: NourishExploreViewModel,
    onBack: () -> Unit,
    onRecipeClick: (author: String, dTag: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.nourish_explore_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.nourish_explore_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.nourish_explore_honesty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.nourish_explore_filters_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Flow-style wrap via LazyRow of chips is less ideal; use
                    // nested Columns of chip rows via a simple wrap Column.
                    FilterChipWrap(
                        activeIds = ui.activeChipIds,
                        onToggle = viewModel::toggleChip,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "sort") {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.nourish_explore_sort_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems(SORT_OPTIONS, key = { it.first.name }) { (dim, labelRes) ->
                            NourishSortChip(
                                label = stringResource(labelRes),
                                selected = ui.sortBy == dim,
                                onClick = { viewModel.setSort(dim) },
                                enabled = !ui.loading,
                            )
                        }
                    }
                }
            }

            when {
                ui.loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = NourishGreen)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.nourish_explore_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                ui.error -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.nourish_explore_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::retry) {
                            Text(stringResource(R.string.nourish_explore_retry))
                        }
                    }
                }

                ui.recipes.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.nourish_explore_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.nourish_explore_empty_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                else -> {
                    if (ui.degraded) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "degraded") {
                            Text(
                                text = stringResource(R.string.nourish_explore_degraded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (ui.refreshing) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "refreshing") {
                            Text(
                                text = stringResource(R.string.nourish_explore_updating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    gridItems(
                        items = ui.recipes,
                        key = { "${it.authorPubkey}:${it.recipeDTag}" },
                    ) { item ->
                        NourishExploreRecipeCard(
                            item = item,
                            onClick = { onRecipeClick(item.authorPubkey, item.recipeDTag) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                        val count = ui.recipes.size
                        val footer = if (ui.activeChipIds.isNotEmpty() && !ui.degraded) {
                            stringResource(R.string.nourish_explore_footer_filtered, count)
                        } else {
                            stringResource(R.string.nourish_explore_footer_all, count)
                        }
                        Text(
                            text = footer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipWrap(
    activeIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (chip in NourishDiscovery.FILTER_CHIPS) {
            NourishFilterChip(
                label = chip.label,
                selected = chip.id in activeIds,
                onClick = { onToggle(chip.id) },
            )
        }
    }
}
