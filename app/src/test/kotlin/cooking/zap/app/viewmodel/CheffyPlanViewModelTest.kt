package cooking.zap.app.viewmodel

import cooking.zap.app.api.MealPlanResult
import cooking.zap.app.mealplan.MealPlanCandidates
import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.SlotEligibility
import cooking.zap.app.nostr.FakeNip98Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheffyPlanViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val W29 = "2026-W29"
    private val pk = "ab".repeat(32)
    private lateinit var vm: CheffyPlanViewModel
    private lateinit var signer: FakeNip98Signer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = CheffyPlanViewModel()
        signer = FakeNip98Signer(pk)
        vm.open(W29, signedIn = true, readOnly = false)
    }

    @After
    fun tearDown() {
        vm.cancelInFlight()
        Dispatchers.resetMain()
    }

    @Test
    fun generate_formSigningWorkingPreview_authReadyFlipsOnce() {
        val phases = mutableListOf<CheffyPlanViewModel.Phase>()
        phases.add(vm.state.value.phase)
        var authFlips = 0
        val meals = listOf(
            meal("mon", "dinner", "Salmon").copy(a = "30023:$pk:pasta"),
        )

        runBlocking {
            vm.generateOnce(
            plan = Schema.createEmptyMealPlan(W29),
            pubkey = pk,
            signer = signer,
            discover = { _, _ -> listOf(discovered("pasta", "Weeknight Pasta", listOf("dinner"))) },
            request = { _, _, onAuth ->
                phases.add(vm.state.value.phase)
                assertEquals(CheffyPlanViewModel.Phase.SIGNING, vm.state.value.phase)
                onAuth?.invoke()
                authFlips++
                phases.add(vm.state.value.phase)
                onAuth?.invoke()
                assertEquals(CheffyPlanViewModel.Phase.WORKING, vm.state.value.phase)
                MealPlanResult.Ok(meals)
            },
            )
        }

        assertEquals(CheffyPlanViewModel.Phase.PREVIEW, vm.state.value.phase)
        assertEquals(1, authFlips)
        assertTrue(phases.contains(CheffyPlanViewModel.Phase.SIGNING))
        assertTrue(phases.contains(CheffyPlanViewModel.Phase.WORKING))
        assertEquals(meals[0].title, vm.state.value.meals[0].title)
        assertEquals("https://img.example/pasta.jpg", vm.state.value.meals[0].image)
    }

    @Test
    fun generate_zeroAfterFilter_slotAwareMessage_noNetworkNoSigner() {
        var network = 0
        var discoverCalls = 0
        vm.toggleSlot("breakfast") // dinner still on; add breakfast then drop dinner
        vm.toggleSlot("dinner")
        assertEquals(listOf("breakfast"), vm.state.value.mealSlots)

        runBlocking {
            vm.generateOnce(
            plan = Schema.createEmptyMealPlan(W29),
            pubkey = pk,
            signer = signer,
            discover = { _, _ ->
                discoverCalls++
                listOf(discovered("steak", "Steak Dinner", listOf("dinner", "beef")))
            },
            request = { _, _, _ ->
                network++
                error("must not request")
            },
            )
        }

        assertEquals(1, discoverCalls)
        assertEquals(0, network)
        assertEquals(0, signer.signCount)
        assertEquals(CheffyPlanViewModel.Phase.ERROR, vm.state.value.phase)
        assertEquals(
            SlotEligibility.noEligibleRecipesMessage(listOf("breakfast")),
            vm.state.value.message,
        )
    }

    @Test
    fun applyResult_mapsEachBranch() {
        vm.applyResult(MealPlanResult.MembersOnly("Cheffy is available to Cook+ members."))
        assertEquals(CheffyPlanViewModel.Phase.UPSELL, vm.state.value.phase)
        assertEquals("Cheffy is available to Cook+ members.", vm.state.value.message)

        vm.applyResult(MealPlanResult.RateLimited(60))
        assertEquals(CheffyPlanViewModel.Phase.ERROR, vm.state.value.phase)
        assertEquals(CheffyPlanViewModel.RATE_LIMITED_LINE, vm.state.value.message)

        vm.applyResult(MealPlanResult.SignInRequired)
        assertEquals(CheffyPlanViewModel.Phase.SIGN_IN, vm.state.value.phase)

        vm.applyResult(MealPlanResult.InvalidRequest("invalid-week", "That week looks off."))
        assertEquals(CheffyPlanViewModel.Phase.ERROR, vm.state.value.phase)
        assertEquals("That week looks off.", vm.state.value.message)

        vm.applyResult(MealPlanResult.Rejected("unknown-recipe", "Unknown recipe."))
        assertEquals("Unknown recipe.", vm.state.value.message)

        vm.applyResult(MealPlanResult.SignFailed)
        assertEquals(CheffyPlanViewModel.SIGN_FAILED_LINE, vm.state.value.message)

        vm.applyResult(MealPlanResult.Failed("Network error — check your connection and try again."))
        assertEquals("Network error — check your connection and try again.", vm.state.value.message)

        vm.applyResult(MealPlanResult.NoCandidates("no breakfasts"))
        assertEquals("no breakfasts", vm.state.value.message)
    }

    @Test
    fun generate_membersOnly_upsell_doesNotApply() {
        runBlocking {
            vm.generateOnce(
            plan = Schema.createEmptyMealPlan(W29),
            pubkey = pk,
            signer = signer,
            discover = { _, _ -> listOf(discovered("pasta", "Weeknight Pasta", listOf("dinner"))) },
            request = { _, _, onAuth ->
                onAuth?.invoke()
                MealPlanResult.MembersOnly("Cheffy is available to Cook+ members.")
            },
            )
        }
        assertEquals(CheffyPlanViewModel.Phase.UPSELL, vm.state.value.phase)
        assertEquals("Cheffy is available to Cook+ members.", vm.state.value.message)
        assertTrue(vm.state.value.meals.isEmpty())
    }

    @Test
    fun preview_partialCoverage_stillAllowsApply() {
        val one = meal("mon", "dinner", "Salmon")
        vm.applyResult(
            MealPlanResult.Ok(listOf(one)),
            mealSlots = listOf("dinner"),
            requested = 7,
        )
        assertEquals(CheffyPlanViewModel.Phase.PREVIEW, vm.state.value.phase)
        assertEquals(
            SlotEligibility.insufficientSlotCoverageMessage(listOf("dinner"), 1, 7),
            vm.state.value.coverageNote,
        )

        val planner = PlannerViewModel()
        planner.seedWeekForTest(W29, PlannerWeekState.Empty(Schema.createEmptyMealPlan(W29)))
        assertTrue(vm.apply(planner))
        assertEquals(1, planner.scheduledSaveCount)
        planner.clear()
    }

    @Test
    fun apply_callsApplyGeneratedPlanOnce_andFalseDoesNotCountAsDismiss() {
        val meals = listOf(meal("mon", "dinner", "Salmon"), meal("tue", "dinner", "Pasta"))
        vm.applyResult(MealPlanResult.Ok(meals), mealSlots = listOf("dinner"), requested = 2)

        val planner = PlannerViewModel()
        // Unknown week → applyGeneratedPlan returns false.
        assertFalse(vm.apply(planner))
        assertEquals(0, planner.scheduledSaveCount)
        assertEquals(CheffyPlanViewModel.APPLY_FAILED_LINE, vm.state.value.message)
        assertEquals(CheffyPlanViewModel.Phase.PREVIEW, vm.state.value.phase)

        planner.seedWeekForTest(W29, PlannerWeekState.Empty(Schema.createEmptyMealPlan(W29)))
        assertTrue(vm.apply(planner))
        assertEquals(1, planner.scheduledSaveCount)
        planner.clear()
    }

    @Test
    fun form_lastSlotAndLastDayCannotBeDeselected() {
        assertEquals(listOf("dinner"), vm.state.value.mealSlots)
        vm.toggleSlot("dinner")
        assertEquals(listOf("dinner"), vm.state.value.mealSlots)

        vm.toggleSlot("lunch")
        vm.toggleSlot("dinner")
        assertEquals(listOf("lunch"), vm.state.value.mealSlots)

        val allDays = Schema.DAY_KEYS.toList()
        assertEquals(allDays, vm.state.value.days)
        for (day in allDays.drop(1)) vm.toggleDay(day)
        assertEquals(listOf("mon"), vm.state.value.days)
        vm.toggleDay("mon")
        assertEquals(listOf("mon"), vm.state.value.days)
    }

    @Test
    fun applyResult_sortsMealsByDayThenSlot() {
        vm.applyResult(
            MealPlanResult.Ok(
                listOf(
                    meal("wed", "dinner", "C"),
                    meal("mon", "snack", "B"),
                    meal("mon", "breakfast", "A"),
                ),
            ),
        )
        assertEquals(
            listOf("mon:breakfast", "mon:snack", "wed:dinner"),
            vm.state.value.meals.map { MealPlanGeneration.slotKey(it.day, it.slot) },
        )
    }

    @Test
    fun open_readOnlyAndSignedOut() {
        vm.open(W29, signedIn = true, readOnly = true)
        assertEquals(CheffyPlanViewModel.Phase.READ_ONLY, vm.state.value.phase)
        vm.open(W29, signedIn = false, readOnly = false)
        assertEquals(CheffyPlanViewModel.Phase.SIGN_IN, vm.state.value.phase)
    }

    private fun meal(day: String, slot: String, title: String) =
        MealPlanGeneration.GeneratedMeal(
            day = day,
            slot = slot,
            a = "30023:$pk:${title.lowercase()}",
            title = title,
            reason = "because",
            image = null,
        )

    private fun discovered(
        dTag: String,
        title: String,
        tags: List<String>,
    ) = MealPlanCandidates.Candidate(
        recipe = MealPlanGeneration.RecipeCandidate(
            a = "30023:$pk:$dTag",
            title = title,
            tags = tags,
            ingredients = listOf("salt"),
        ),
        image = "https://img.example/$dTag.jpg",
    )
}
