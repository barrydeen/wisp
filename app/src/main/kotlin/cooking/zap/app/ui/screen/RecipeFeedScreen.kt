package cooking.zap.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.RecipeTag
import cooking.zap.app.nostr.RecipeTagCatalog
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.RecipePackSummary
import cooking.zap.app.ui.component.CookbookCollectionCard
import cooking.zap.app.ui.component.IntelligenceMenu
import cooking.zap.app.ui.component.RecipePackCard
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.ui.component.RecipeCard
import cooking.zap.app.ui.component.RecipePosterSkeleton
import cooking.zap.app.viewmodel.CookbookViewModel
import cooking.zap.app.viewmodel.RecipeFeedViewModel
import cooking.zap.app.viewmodel.RecipePacksTab
import cooking.zap.app.viewmodel.RecipePacksViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Prefetch margin (in grid items) for scroll-end pagination — fire the next
 * page once the last visible tile is within this many items of the end.
 * Sized to ~one row at the widest column count so it triggers a bit early.
 */
private const val LOAD_MORE_PREFETCH = 6

private enum class RecipesMainTab { RECIPES, PACKS, COOKBOOK }

private enum class CookbookSubTab { SAVED, MY_RECIPES }

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
    packsViewModel: RecipePacksViewModel,
    cookbookViewModel: CookbookViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    onPackClick: (author: String, dTag: String) -> Unit,
    onCollectionClick: (dTag: String) -> Unit = {},
    onTagClick: (tag: String) -> Unit = {},
    // Recipes is a root tab: no back arrow. The nav icon opens the shared
    // drawer (hoisted to WispNavHost) and the top bar carries a search icon,
    // mirroring the Feed tab. No content-type filter here.
    onOpenDrawer: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSousChef: () -> Unit = {},
    onCheffy: () -> Unit = {},
    onNourish: () -> Unit = {},
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
    var mainTab by remember { mutableStateOf(RecipesMainTab.RECIPES) }

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

    // Repaint packs from cache whenever the main Packs tab is activated/re-entered.
    LaunchedEffect(mainTab) {
        if (mainTab == RecipesMainTab.PACKS) {
            packsViewModel.onPacksActivated()
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
                            contentDescription = stringResource(R.string.title_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IntelligenceMenu(
                        onSousChef = onSousChef,
                        onCheffy = onCheffy,
                        onNourish = onNourish,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            onCreateRecipe?.let {
                cooking.zap.app.ui.component.ZapGradientFab(
                    onClick = it,
                    contentDescription = stringResource(R.string.cd_create_recipe)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(4.dp)
            ) {
                RecipesMainTabButton(
                    label = stringResource(R.string.tab_recipes),
                    selected = mainTab == RecipesMainTab.RECIPES,
                    onClick = { mainTab = RecipesMainTab.RECIPES },
                    modifier = Modifier.weight(1f),
                )
                RecipesMainTabButton(
                    label = stringResource(R.string.tab_packs),
                    selected = mainTab == RecipesMainTab.PACKS,
                    onClick = {
                        if (mainTab == RecipesMainTab.PACKS) {
                            packsViewModel.onPacksActivated()
                        } else {
                            mainTab = RecipesMainTab.PACKS
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                RecipesMainTabButton(
                    label = stringResource(R.string.tab_cookbook),
                    selected = mainTab == RecipesMainTab.COOKBOOK,
                    onClick = { mainTab = RecipesMainTab.COOKBOOK },
                    modifier = Modifier.weight(1f),
                )
            }

            if (mainTab == RecipesMainTab.PACKS) {
                RecipePacksSection(
                    viewModel = packsViewModel,
                    eventRepo = eventRepo,
                    userPubkey = userPubkey,
                    onPackClick = onPackClick,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                return@Column
            }

            if (mainTab == RecipesMainTab.COOKBOOK) {
                CookbookSection(
                    viewModel = cookbookViewModel,
                    userPubkey = userPubkey,
                    onCollectionClick = onCollectionClick,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                return@Column
            }

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

@Composable
private fun RecipesMainTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                androidx.compose.foundation.shape.RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RecipePacksSection(
    viewModel: RecipePacksViewModel,
    eventRepo: EventRepository,
    userPubkey: String?,
    onPackClick: (author: String, dTag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val discoverPacks by viewModel.discoverPacks.collectAsState()
    val minePacks by viewModel.minePacks.collectAsState()
    val savedPacks by viewModel.savedPacks.collectAsState()
    val isDiscoverLoading by viewModel.isDiscoverLoading.collectAsState()
    val isMineLoading by viewModel.isMineLoading.collectAsState()
    val isSavedLoading by viewModel.isSavedLoading.collectAsState()

    val packs = when (selectedTab) {
        RecipePacksTab.DISCOVER -> discoverPacks
        RecipePacksTab.MINE -> minePacks
        RecipePacksTab.SAVED -> savedPacks
    }
    val isLoading = when (selectedTab) {
        RecipePacksTab.DISCOVER -> isDiscoverLoading
        RecipePacksTab.MINE -> isMineLoading
        RecipePacksTab.SAVED -> isSavedLoading
    }

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == RecipePacksTab.DISCOVER,
                onClick = { viewModel.selectTab(RecipePacksTab.DISCOVER) },
                text = { Text(stringResource(R.string.tab_discover)) }
            )
            Tab(
                selected = selectedTab == RecipePacksTab.MINE,
                onClick = { viewModel.selectTab(RecipePacksTab.MINE) },
                text = { Text(stringResource(R.string.tab_mine)) }
            )
            Tab(
                selected = selectedTab == RecipePacksTab.SAVED,
                onClick = { viewModel.selectTab(RecipePacksTab.SAVED) },
                text = { Text(stringResource(R.string.tab_saved)) }
            )
        }

        if ((selectedTab == RecipePacksTab.MINE || selectedTab == RecipePacksTab.SAVED) && userPubkey.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.error_sign_in_packs_tab),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        when {
            isLoading && packs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            packs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (selectedTab) {
                            RecipePacksTab.DISCOVER -> stringResource(R.string.error_no_recipe_packs_found)
                            RecipePacksTab.MINE -> stringResource(R.string.error_no_recipe_packs_mine)
                            RecipePacksTab.SAVED -> stringResource(R.string.error_no_recipe_packs_saved)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(
                        items = packs,
                        key = { "${it.author}:${it.dTag}:${it.event.id}" }
                    ) { pack ->
                        val profile = eventRepo.getProfileData(pack.author)
                        RecipePackCard(
                            pack = pack,
                            creatorName = profile?.displayString,
                            creatorPicture = profile?.picture,
                            onClick = { onPackClick(pack.author, pack.dTag) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cookbook tab (A14 PR 3b-i). Two sub-tabs reusing the Packs `TabRow` pattern:
 *  - **Saved** — the user's kind-30001 recipe collections (PR 3a): default Saved
 *    list first, named collections after, each a cover card that drills into the
 *    shared pack-detail grid.
 *  - **My Recipes** — the user's own published recipes; the shell lands here, the
 *    live author query is filled in PR 3b-ii.
 *
 * Both sub-tabs are personal: signed-out shows a sign-in prompt. READ_ONLY still
 * renders (the account's own lists are fetchable); management affordances are PR 3b-iii.
 */
@Composable
private fun CookbookSection(
    viewModel: CookbookViewModel,
    userPubkey: String?,
    onCollectionClick: (dTag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lists by viewModel.lists.collectAsState()
    val covers by viewModel.covers.collectAsState()
    var subTab by remember { mutableStateOf(CookbookSubTab.SAVED) }

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = subTab.ordinal) {
            Tab(
                selected = subTab == CookbookSubTab.SAVED,
                onClick = { subTab = CookbookSubTab.SAVED },
                text = { Text(stringResource(R.string.tab_saved)) }
            )
            Tab(
                selected = subTab == CookbookSubTab.MY_RECIPES,
                onClick = { subTab = CookbookSubTab.MY_RECIPES },
                text = { Text(stringResource(R.string.cookbook_tab_my_recipes)) }
            )
        }

        // Both sub-tabs are personal — gate on having an account at all.
        if (userPubkey.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.cookbook_sign_in),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        when (subTab) {
            CookbookSubTab.SAVED -> {
                if (lists.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Text(text = "📖", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.cookbook_saved_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.cookbook_saved_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 280.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(lists, key = { it.dTag }) { list ->
                            CookbookCollectionCard(
                                title = list.title,
                                coverUrl = covers[list.dTag],
                                recipeCount = list.coordinates.size,
                                isDefault = list.isDefault,
                                onClick = { onCollectionClick(list.dTag) },
                            )
                        }
                    }
                }
            }

            // Shell for PR 3b-ii — the live author query (kinds=recipe, authors=[me]) lands here.
            CookbookSubTab.MY_RECIPES -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.cookbook_my_recipes_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
