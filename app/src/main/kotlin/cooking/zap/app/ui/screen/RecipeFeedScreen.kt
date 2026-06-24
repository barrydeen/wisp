package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.RecipeTag
import cooking.zap.app.nostr.RecipeTagCatalog
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeFeedScreen(
    viewModel: RecipeFeedViewModel,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    onTagClick: (tag: String) -> Unit = {},
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
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val gridState = rememberLazyGridState()
    var showMoreTagsSheet by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems(RecipeTagCatalog.popularRecipeTags, key = { it.tag }) { tag ->
                    RecipeTagChip(tag = tag, onClick = { onTagClick(tag.tag) })
                }
                item("more-tags") {
                    FilterChip(
                        selected = false,
                        onClick = { showMoreTagsSheet = true },
                        label = { Text("More ⌄") },
                    )
                }
            }
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
                        repeat(12) {
                            item {
                                RecipePosterSkeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            }
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
                        gridItems(recipes, key = { it.id }) { recipe ->
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
        if (showMoreTagsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMoreTagsSheet = false },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "All categories",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecipeTagCatalog.recipeTags.forEach { tag ->
                            RecipeTagChip(
                                tag = tag,
                                onClick = {
                                    showMoreTagsSheet = false
                                    onTagClick(tag.tag)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun RecipeTagChip(
    tag: RecipeTag,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text("${tag.emoji} ${tag.label}") },
    )
}
