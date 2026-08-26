package cooking.zap.app.viewmodel

import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.mealplan.PlannerLogic
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cheffy apply-generated-plan write path. The load-bearing assertion is
 * scheduled-save count (not coalesced publish-after-2s): looping [PlannerViewModel.setSlot]
 * would also collapse to one [cooking.zap.app.repo.PlannerRepository.saveMealPlan]
 * after debounce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelApplyTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val W29 = "2026-W29"
    private lateinit var vm: PlannerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = PlannerViewModel()
    }

    @After
    fun tearDown() {
        vm.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun applyGeneratedPlan_sevenMeals_schedulesExactlyOneSave() {
        seedEditable()
        val meals = Schema.DAY_KEYS.map { day ->
            meal(day, "dinner", "30023:pk:$day", "Dinner $day")
        }

        assertTrue(vm.applyGeneratedPlan(W29, meals))
        assertEquals(
            "7 meals must be one mutatePlan / one scheduleSave",
            1,
            vm.scheduledSaveCount,
        )
        assertTrue(vm.isDirty(W29))

        val plan = PlannerLogic.planOf(vm.weeks.value[W29])!!
        for (meal in meals) {
            assertEquals(
                PlannerMutations.recipeSlot(meal.a, meal.title),
                plan.slot(meal.day, meal.slot),
            )
        }
    }

    @Test
    fun applyGeneratedPlan_skipsOccupancyThatChangedSincePreview() {
        seedEditable()
        assertTrue(
            vm.setSlot(W29, "mon", "dinner", PlannerMutations.textSlot("Leftovers")),
        )
        val savesAfterManual = vm.scheduledSaveCount

        val preview = listOf(
            meal("mon", "dinner", "30023:pk:salmon", "Salmon"),
            meal("tue", "dinner", "30023:pk:pasta", "Pasta"),
        )
        assertTrue(
            vm.applyGeneratedPlan(
                W29,
                preview,
                MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
            ),
        )
        assertEquals(savesAfterManual + 1, vm.scheduledSaveCount)

        val plan = PlannerLogic.planOf(vm.weeks.value[W29])!!
        assertEquals(PlannerMutations.textSlot("Leftovers"), plan.slot("mon", "dinner"))
        assertEquals(PlannerMutations.recipeSlot("30023:pk:pasta", "Pasta"), plan.slot("tue", "dinner"))
    }

    @Test
    fun applyGeneratedPlan_rejectsReadOnlyDecryptFailedUnknownWeek() {
        val meals = listOf(meal("mon", "dinner", "30023:pk:salmon", "Salmon"))

        assertFalse("unknown week", vm.applyGeneratedPlan(W29, meals))
        assertEquals(0, vm.scheduledSaveCount)

        vm.seedWeekForTest(W29, PlannerWeekState.DecryptFailed("denied"))
        assertFalse(vm.applyGeneratedPlan(W29, meals))
        assertEquals(0, vm.scheduledSaveCount)
        assertEquals(PlannerWeekState.DecryptFailed("denied"), vm.weeks.value[W29])

        val readOnly = PlannerWeekState.Loaded(Schema.createEmptyMealPlan(W29), readOnly = true)
        vm.seedWeekForTest(W29, readOnly)
        assertFalse(vm.applyGeneratedPlan(W29, meals))
        assertEquals(0, vm.scheduledSaveCount)
        val after = vm.weeks.value[W29] as PlannerWeekState.Loaded
        assertTrue(after.readOnly)
        assertEquals(null, after.plan.slot("mon", "dinner"))
    }

    @Test
    fun applyGeneratedPlan_emptyOrFullyOccupied_doesNotScheduleSave() {
        seedEditable()

        assertFalse(vm.applyGeneratedPlan(W29, emptyList()))
        assertEquals(0, vm.scheduledSaveCount)

        assertTrue(vm.setSlot(W29, "mon", "dinner", PlannerMutations.textSlot("Tacos")))
        val afterFill = vm.scheduledSaveCount
        assertFalse(
            vm.applyGeneratedPlan(
                W29,
                listOf(meal("mon", "dinner", "30023:pk:salmon", "Salmon")),
                MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
            ),
        )
        assertEquals(afterFill, vm.scheduledSaveCount)
        assertEquals(
            PlannerMutations.textSlot("Tacos"),
            PlannerLogic.planOf(vm.weeks.value[W29])!!.slot("mon", "dinner"),
        )
    }

    private fun seedEditable() {
        vm.seedWeekForTest(W29, PlannerWeekState.Empty(Schema.createEmptyMealPlan(W29)))
    }

    private fun meal(
        day: String,
        slot: String,
        a: String,
        title: String,
    ) = MealPlanGeneration.GeneratedMeal(
        day = day,
        slot = slot,
        a = a,
        title = title,
        reason = "preview-only",
        image = "https://img.example/x.jpg",
    )
}
