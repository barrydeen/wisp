package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.RecipeTag
import cooking.zap.app.ui.component.RecipeCard
import cooking.zap.app.ui.component.RecipePosterSkeleton
import cooking.zap.app.viewmodel.RecipeTagFeedViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private const val TAG_LOAD_MORE_PREFETCH = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeTagFeedScreen(
    viewModel: RecipeTagFeedViewModel,
    tagInfo: RecipeTag?,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    onBack: () -> Unit,
) {
    val recipes by viewModel.recipes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && lastVisible >= total - TAG_LOAD_MORE_PREFETCH
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (!viewModel.isLoadingMore.value && !viewModel.exhausted.value) {
                    viewModel.loadMore()
                }
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_recipes)) },
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
        val columns = GridCells.Adaptive(minSize = 160.dp)
        val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        val spacing = Arrangement.spacedBy(12.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val header = tagInfo ?: RecipeTag(tag = "", label = "Tag", emoji = "🏷️")
            RowHeader(
                emoji = header.emoji,
                label = header.label,
                slug = tagInfo?.tag.orEmpty(),
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                when {
                    recipes.isEmpty() && isLoading -> {
                        LazyVerticalGrid(
                            columns = columns,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = contentPadding,
                            horizontalArrangement = spacing,
                            verticalArrangement = spacing,
                        ) {
                            items(12) {
                                RecipePosterSkeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            }
                        }
                    }

                    recipes.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "🍳", style = MaterialTheme.typography.displaySmall)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "No recipes yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = columns,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = contentPadding,
                            horizontalArrangement = spacing,
                            verticalArrangement = spacing,
                        ) {
                            items(recipes, key = { it.id }) { recipe ->
                                RecipeCard(
                                    recipe = recipe,
                                    onClick = { onRecipeClick(recipe.author, recipe.dTag) },
                                )
                            }
                            if (isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        RecipePosterSkeleton(Modifier.width(150.dp).aspectRatio(2f / 3f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowHeader(
    emoji: String,
    label: String,
    slug: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$emoji $label",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (slug.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "#$slug",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
