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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.ui.component.RecipeCard
import cooking.zap.app.ui.component.RecipePosterSkeleton
import cooking.zap.app.viewmodel.RecipeFeedViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Prefetch margin (in grid items) for scroll-end pagination — fire the next
 * page once the last visible tile is within this many items of the end.
 * Sized to ~one row at the widest column count so it triggers a bit early.
 */
private const val LOAD_MORE_PREFETCH = 6

/**
 * The Recipes feed — recipe cards only (concern 1.6 un-merge), rendered as a
 * cookbook-style poster grid (2:3 portrait tiles, title-only). Tapping a card
 * opens the recipe-detail route. Social `#foodstr` notes moved to the OnlyFood
 * feed, so a post never appears in two feeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeFeedScreen(
    viewModel: RecipeFeedViewModel,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    // Recipes is a root tab: no back arrow. The nav icon opens the shared
    // drawer (hoisted to WispNavHost) and the top bar carries a search icon,
    // mirroring the Feed tab. No content-type filter here.
    onOpenDrawer: () -> Unit = {},
    onSearch: () -> Unit = {},
    userAvatarUrl: String? = null,
    // Null for READ_ONLY accounts (no signing key) — the FAB is then hidden.
    onCreateRecipe: (() -> Unit)? = null,
) {
    val recipes by viewModel.recipes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val gridState = rememberLazyGridState()

    // Scroll-end pagination: when the last visible tile nears the end of the
    // grid, fetch the next (older) page. distinctUntilChanged debounces repeat
    // emissions; loadMore() is gated here on !loadingMore && !exhausted and the
    // repo is single-flight, so it's safe to fire freely.
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && lastVisible >= total - LOAD_MORE_PREFETCH
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
                title = { Text("Recipes") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        ProfilePicture(url = userAvatarUrl, size = 32)
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            onCreateRecipe?.let {
                ExtendedFloatingActionButton(
                    onClick = it,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Create recipe") },
                )
            }
        },
    ) { padding ->
        // 2:3 poster grid — ~2 columns on a phone, scaling on wider screens.
        val columns = GridCells.Adaptive(minSize = 160.dp)
        val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        val spacing = Arrangement.spacedBy(12.dp)
        when {
            recipes.isEmpty() && isLoading -> {
                LazyVerticalGrid(
                    columns = columns,
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                    // Loading-more footer: a full-width (all columns) row with a
                    // poster skeleton while the next page is in flight.
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
