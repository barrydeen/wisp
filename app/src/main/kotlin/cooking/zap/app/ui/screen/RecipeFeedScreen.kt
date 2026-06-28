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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.RecipeTag
import cooking.zap.app.nostr.RecipeTagCatalog
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.RecipePackSummary
import cooking.zap.app.repo.CookbookMemberRecipe
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import cooking.zap.app.ui.component.ChooseCoverSheet
import cooking.zap.app.ui.component.CookbookCollectionCard
import cooking.zap.app.ui.component.DeleteCollectionDialog
import cooking.zap.app.ui.component.EditDescriptionDialog
import cooking.zap.app.ui.component.IntelligenceMenu
import cooking.zap.app.ui.component.RecipePackCard
import cooking.zap.app.ui.component.RenameCollectionDialog
import cooking.zap.app.ui.util.LocalCanSign
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
    onKitchenTools: () -> Unit = {},
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
                    IconButton(onClick = onKitchenTools) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cooking_pot),
                            contentDescription = stringResource(R.string.drawer_kitchen_tools),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                    onRecipeClick = onRecipeClick,
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
 * Cookbook tab. Two sub-tabs reusing the Packs `TabRow` pattern:
 *  - **Saved** (PR 3b-i) — the user's kind-30001 recipe collections (PR 3a):
 *    default Saved list first, named collections after, each a cover card that
 *    drills into the shared pack-detail grid.
 *  - **My Recipes** (PR 3b-ii) — the user's OWN published recipes via the live
 *    author query, rendered in the same `RecipeCard` poster grid. Distinct from
 *    Saved (authored, not bookmarked). Loaded lazily when first shown.
 *
 * Both sub-tabs are personal: signed-out shows a sign-in prompt. READ_ONLY still
 * renders (the account's own lists and authored recipes are fetchable); management
 * affordances are PR 3b-iii.
 */
@Composable
private fun CookbookSection(
    viewModel: CookbookViewModel,
    userPubkey: String?,
    onCollectionClick: (dTag: String) -> Unit,
    onRecipeClick: (author: String, dTag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lists by viewModel.lists.collectAsState()
    val covers by viewModel.covers.collectAsState()
    var subTab by remember { mutableStateOf(CookbookSubTab.SAVED) }
    // Management (PR 3b-iii) is owner-only — a signing key is required.
    val canManage = LocalCanSign.current
    var manage by remember { mutableStateOf<CookbookManageState?>(null) }

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
                                canManage = canManage,
                                onRename = { manage = CookbookManageState.Rename(list) },
                                onEditDescription = { manage = CookbookManageState.Description(list) },
                                onChooseCover = { manage = CookbookManageState.Cover(list) },
                                onDelete = { manage = CookbookManageState.Delete(list) },
                            )
                        }
                    }
                }
            }

            // PR 3b-ii — the user's OWN published recipes via the live author
            // query, in the same poster grid as Saved/Recipes.
            CookbookSubTab.MY_RECIPES -> {
                val authored by viewModel.authoredRecipes.collectAsState()
                val isAuthoredLoading by viewModel.isAuthoredLoading.collectAsState()
                // Lazy: kick off the author query when this sub-tab shows. Keyed on
                // userPubkey so a late sign-in / account switch re-runs it for the
                // new author (requestMyRecipes() no-ops if already loaded for it).
                LaunchedEffect(userPubkey) { viewModel.requestMyRecipes() }

                val columns = GridCells.Adaptive(minSize = 160.dp)
                val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                val spacing = Arrangement.spacedBy(12.dp)
                when {
                    authored.isEmpty() && isAuthoredLoading -> {
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
                    authored.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.cookbook_my_recipes_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = columns,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = contentPadding,
                            horizontalArrangement = spacing,
                            verticalArrangement = spacing,
                        ) {
                            gridItems(authored, key = { it.id }) { recipe ->
                                RecipeCard(
                                    recipe = recipe,
                                    onClick = { onRecipeClick(recipe.author, recipe.dTag) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Management dialogs (PR 3b-iii) — owner-only; dismissed on confirm.
    CookbookManageDialogs(
        state = manage,
        viewModel = viewModel,
        onDismiss = { manage = null },
    )
}

/** The active management dialog/sheet for a Saved collection (PR 3b-iii). */
private sealed interface CookbookManageState {
    val list: CookbookList
    data class Rename(override val list: CookbookList) : CookbookManageState
    data class Description(override val list: CookbookList) : CookbookManageState
    data class Cover(override val list: CookbookList) : CookbookManageState
    data class Delete(override val list: CookbookList) : CookbookManageState
}

/**
 * Hosts the rename / description / cover / delete dialogs for the Saved sub-tab.
 * Each confirm delegates to the [CookbookViewModel] write pass-through (which
 * republishes via the kind-30001 repo) and dismisses. Cover resolves the list's
 * member recipes lazily for the picker.
 */
@Composable
private fun CookbookManageDialogs(
    state: CookbookManageState?,
    viewModel: CookbookViewModel,
    onDismiss: () -> Unit,
) {
    when (state) {
        null -> Unit
        is CookbookManageState.Rename -> RenameCollectionDialog(
            initialTitle = state.list.title,
            onConfirm = { title -> viewModel.renameList(state.list.dTag, title); onDismiss() },
            onDismiss = onDismiss,
        )
        is CookbookManageState.Description -> EditDescriptionDialog(
            initialSummary = state.list.summary.orEmpty(),
            onConfirm = { summary -> viewModel.setDescription(state.list.dTag, summary); onDismiss() },
            onDismiss = onDismiss,
        )
        is CookbookManageState.Delete -> DeleteCollectionDialog(
            title = state.list.title,
            onConfirm = { viewModel.deleteList(state.list.dTag); onDismiss() },
            onDismiss = onDismiss,
        )
        is CookbookManageState.Cover -> {
            var members by remember(state.list.dTag) { mutableStateOf<List<CookbookMemberRecipe>>(emptyList()) }
            var loading by remember(state.list.dTag) { mutableStateOf(true) }
            LaunchedEffect(state.list.dTag) {
                members = viewModel.memberRecipes(state.list)
                loading = false
            }
            ChooseCoverSheet(
                members = members,
                currentCoord = state.list.coverCoord,
                loading = loading,
                onPick = { coord -> viewModel.setCover(state.list.dTag, coord); onDismiss() },
                onDismiss = onDismiss,
            )
        }
    }
}
