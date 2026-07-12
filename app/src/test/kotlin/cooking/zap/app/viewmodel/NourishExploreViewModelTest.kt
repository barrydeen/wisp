package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.nostr.NourishDimension
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.nostr.RecipeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the chip-toggle → repository contract that device testing found
 * broken on PR #161: toggles must invoke fetch with the **post-toggle**
 * filter label set (not the pre-toggle / empty set).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NourishExploreViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        NourishDiscovery.resetSessionCaches()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        NourishDiscovery.resetSessionCaches()
    }

    @Test
    fun toggleChip_invokesFetchWithNewFilterLabels() {
        val seenFilters = mutableListOf<List<String>>()
        val vm = NourishExploreViewModel()
        vm.initForTest(
            fetch = { _, _, filters ->
                seenFilters.add(filters)
                emptyResult()
            },
            autoLoad = false,
        )

        vm.toggleChip("high-protein")

        assertTrue("expected at least one fetch after chip toggle", seenFilters.isNotEmpty())
        assertEquals(
            listOf("protein:30plus"),
            seenFilters.last(),
        )
        assertEquals(setOf("high-protein"), vm.ui.value.activeChipIds)
    }

    @Test
    fun toggleChip_andComposition_passesAllLabelsInChipOrder() {
        val seenFilters = mutableListOf<List<String>>()
        val vm = NourishExploreViewModel()
        vm.initForTest(
            fetch = { _, _, filters ->
                seenFilters.add(filters)
                emptyResult()
            },
            autoLoad = false,
        )

        vm.toggleChip("no-seed-oils")
        vm.toggleChip("high-protein")

        assertEquals(
            listOf("protein:30plus", "seedoil:free"),
            seenFilters.last(),
        )
        assertEquals(setOf("no-seed-oils", "high-protein"), vm.ui.value.activeChipIds)
    }

    @Test
    fun toggleChip_off_refetchesUnfiltered() {
        val seenFilters = mutableListOf<List<String>>()
        val vm = NourishExploreViewModel()
        vm.initForTest(
            fetch = { _, _, filters ->
                seenFilters.add(filters)
                emptyResult()
            },
            autoLoad = false,
        )

        vm.toggleChip("under-600")
        assertEquals(listOf("kcal:under600"), seenFilters.last())

        vm.toggleChip("under-600")
        assertEquals(emptyList<String>(), seenFilters.last())
        assertTrue(vm.ui.value.activeChipIds.isEmpty())
    }

    @Test
    fun setSort_doesNotRefetch() {
        val seenFilters = mutableListOf<List<String>>()
        val seenSorts = mutableListOf<NourishDiscovery.SortDimension>()
        val vm = NourishExploreViewModel()
        vm.initForTest(
            fetch = { sortBy, _, filters ->
                seenSorts.add(sortBy)
                seenFilters.add(filters)
                resultWithScores()
            },
            autoLoad = true,
        )
        val afterLoad = seenFilters.size

        vm.setSort(NourishDiscovery.SortDimension.PROTEIN)

        assertEquals(
            "sort must be client-side only (no refetch)",
            afterLoad,
            seenFilters.size,
        )
        assertEquals(NourishDiscovery.SortDimension.PROTEIN, vm.ui.value.sortBy)
    }

    @Test
    fun loadRecipes_passesActiveSortDimension() {
        val seenSorts = mutableListOf<NourishDiscovery.SortDimension>()
        val vm = NourishExploreViewModel()
        vm.initForTest(
            fetch = { sortBy, _, _ ->
                seenSorts.add(sortBy)
                resultWithScores()
            },
            autoLoad = true,
        )
        assertEquals(
            listOf(NourishDiscovery.SortDimension.OVERALL),
            seenSorts,
        )

        vm.setSort(NourishDiscovery.SortDimension.PROTEIN)
        vm.retry()

        assertEquals(NourishDiscovery.SortDimension.PROTEIN, seenSorts.last())
    }

    private fun emptyResult() = NourishDiscovery.DiscoveryResult(
        recipes = emptyList(),
        degraded = false,
    )

    private fun resultWithScores(): NourishDiscovery.DiscoveryResult {
        val recipe = RecipeParser.Recipe(
            id = "r1",
            author = "pk",
            dTag = "pasta",
            title = "Pasta",
            image = null,
            summary = null,
            publishedAt = 1,
            hashtags = emptyList(),
            categories = emptyList(),
            content = RecipeParser.RecipeContent(),
        )
        val score = NourishScore(
            overall = 8,
            overallLabel = "Strong",
            dimensions = listOf(
                NourishDimension("Real Food", 7),
                NourishDimension("Gut", 6),
                NourishDimension("Protein", 9),
            ),
            improvements = emptyList(),
        )
        return NourishDiscovery.DiscoveryResult(
            recipes = listOf(
                NourishDiscovery.RankedRecipe(
                    recipe = recipe,
                    score = score,
                    createdAt = 1,
                    authorPubkey = "pk",
                    recipeDTag = "pasta",
                ),
            ),
            degraded = false,
        )
    }
}
