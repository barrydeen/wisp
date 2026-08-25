package cooking.zap.app.mealplan

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-local tests for [MealPlanGeneration] — not fixture-backed.
 * The server does not run `filterRecipeCandidates`, so drift here costs
 * prompt quality, not correctness. Eligibility is locked separately by
 * `mealplan-eligibility.vectors.json`.
 *
 * Port of frontend `src/lib/mealplan/generation.ts` (commit 8caef33).
 */
class MealPlanGenerationTest {

    private val author = "ab".repeat(32)

    private fun coord(dTag: String) = "30023:$author:$dTag"

    private fun recipe(
        dTag: String,
        title: String,
        tags: List<String> = emptyList(),
        ingredients: List<String> = emptyList(),
        prepTime: String? = null,
        cookTime: String? = null,
    ) = MealPlanGeneration.RecipeCandidate(
        a = coord(dTag),
        title = title,
        tags = tags,
        ingredients = ingredients,
        prepTime = prepTime,
        cookTime = cookTime,
    )

    private fun request(
        days: List<String> = listOf("mon"),
        mealSlots: List<String> = listOf("dinner"),
        strategy: MealPlanGeneration.MealPlanStrategy = MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
        candidates: List<MealPlanGeneration.RecipeCandidate> = listOf(recipe("pasta", "Weeknight Pasta")),
        occupiedSlots: List<MealPlanGeneration.MealSlotRef> = emptyList(),
        fillSlots: List<MealPlanGeneration.MealSlotRef> = emptyList(),
    ) = MealPlanGeneration.MealPlanGenerationRequest(
        weekId = "2026-W29",
        days = days,
        mealSlots = mealSlots,
        preferences = MealPlanGeneration.MealPlanPreferences(),
        strategy = strategy,
        candidates = candidates,
        occupiedSlots = occupiedSlots,
        fillSlots = fillSlots,
    )

    // ---- isRecipeCoordinate -------------------------------------------------

    @Test
    fun isRecipeCoordinate_rejectsWrongKind() {
        assertFalse(MealPlanGeneration.isRecipeCoordinate("1:$author:pasta"))
        assertFalse(MealPlanGeneration.isRecipeCoordinate("30024:$author:pasta"))
    }

    @Test
    fun isRecipeCoordinate_rejectsTwoParts() {
        assertFalse(MealPlanGeneration.isRecipeCoordinate("30023:$author"))
    }

    @Test
    fun isRecipeCoordinate_rejectsEmptyAuthor() {
        assertFalse(MealPlanGeneration.isRecipeCoordinate("30023::pasta"))
    }

    @Test
    fun isRecipeCoordinate_rejectsEmptyDTag() {
        assertFalse(MealPlanGeneration.isRecipeCoordinate("30023:$author:"))
    }

    @Test
    fun isRecipeCoordinate_acceptsKindPubkeyDTag() {
        assertTrue(MealPlanGeneration.isRecipeCoordinate(coord("pasta")))
    }

    @Test
    fun filterRecipeCandidates_dropsInvalidCoordinates() {
        val valid = recipe("ok", "Ok")
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(
                valid.copy(a = "1:$author:wrong-kind"),
                valid.copy(a = "30023:$author"),
                valid.copy(a = "30023::empty-author"),
                valid.copy(a = "30023:$author:"),
                valid,
            ),
        )
        assertEquals(listOf(valid.a), kept.map { it.a })
    }

    // ---- cap-48 -------------------------------------------------------------

    @Test
    fun filterRecipeCandidates_slicesAtCap48() {
        val candidates = (1..50).map { i -> recipe("r$i", "Recipe $i") }
        val kept = MealPlanGeneration.filterRecipeCandidates(candidates)
        assertEquals(MealPlanGeneration.MAX_CANDIDATES, kept.size)
        assertEquals(candidates.take(48).map { it.a }, kept.map { it.a })
    }

    // ---- resolveTargetSlots -------------------------------------------------

    @Test
    fun resolveTargetSlots_fillEmptySkipsOccupied() {
        val req = request(
            days = listOf("mon", "tue"),
            mealSlots = listOf("dinner"),
            occupiedSlots = listOf(MealPlanGeneration.MealSlotRef("mon", "dinner")),
        )
        assertEquals(
            listOf(MealPlanGeneration.MealSlotRef("tue", "dinner")),
            MealPlanGeneration.resolveTargetSlots(req),
        )
    }

    @Test
    fun resolveTargetSlots_explicitFillSlots() {
        val req = request(
            days = listOf("mon", "tue", "wed"),
            mealSlots = listOf("lunch", "dinner"),
            fillSlots = listOf(
                MealPlanGeneration.MealSlotRef("wed", "lunch"),
                MealPlanGeneration.MealSlotRef("fri", "dinner"),
            ),
        )
        // fillSlots is the source of truth: valid day/slot keys are kept even
        // when they are not in req.days / req.mealSlots (web cartesian is only
        // the fallback when fillSlots is empty).
        assertEquals(
            listOf(
                MealPlanGeneration.MealSlotRef("wed", "lunch"),
                MealPlanGeneration.MealSlotRef("fri", "dinner"),
            ),
            MealPlanGeneration.resolveTargetSlots(req),
        )
    }

    @Test
    fun resolveTargetSlots_dedupesDuplicateRefs() {
        val req = request(
            fillSlots = listOf(
                MealPlanGeneration.MealSlotRef("mon", "dinner"),
                MealPlanGeneration.MealSlotRef("mon", "dinner"),
                MealPlanGeneration.MealSlotRef("tue", "dinner"),
            ),
        )
        assertEquals(
            listOf(
                MealPlanGeneration.MealSlotRef("mon", "dinner"),
                MealPlanGeneration.MealSlotRef("tue", "dinner"),
            ),
            MealPlanGeneration.resolveTargetSlots(req),
        )
    }

    @Test
    fun occupiedSlotsFromPlan_readsFilledSlots() {
        val plan = Schema.MealPlan(
            buildJsonObject {
                put(
                    "days",
                    buildJsonObject {
                        put(
                            "mon",
                            buildJsonObject {
                                put(
                                    "slots",
                                    buildJsonObject {
                                        put(
                                            "dinner",
                                            buildJsonObject {
                                                put("type", "recipe")
                                                put("a", coord("pasta"))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        assertEquals(
            listOf(MealPlanGeneration.MealSlotRef("mon", "dinner")),
            MealPlanGeneration.occupiedSlotsFromPlan(plan, listOf("mon", "tue"), listOf("lunch", "dinner")),
        )
    }

    // ---- parseDurationMinutes / totalActiveMinutes --------------------------

    @Test
    fun parseDurationMinutes_bareNumber() {
        assertEquals(45, MealPlanGeneration.parseDurationMinutes("45"))
    }

    @Test
    fun parseDurationMinutes_hourAndMin() {
        assertEquals(75, MealPlanGeneration.parseDurationMinutes("1 hr 15 min"))
    }

    @Test
    fun parseDurationMinutes_decimalHours() {
        assertEquals(90, MealPlanGeneration.parseDurationMinutes("1.5 hours"))
    }

    @Test
    fun parseDurationMinutes_aboutAnHour_isNull() {
        assertNull(MealPlanGeneration.parseDurationMinutes("about an hour"))
    }

    @Test
    fun parseDurationMinutes_empty_isNull() {
        assertNull(MealPlanGeneration.parseDurationMinutes(""))
    }

    @Test
    fun parseDurationMinutes_zero_isNull() {
        assertNull(MealPlanGeneration.parseDurationMinutes("0"))
    }

    @Test
    fun totalActiveMinutes_unknownSideCountsAsZero() {
        assertEquals(20, MealPlanGeneration.totalActiveMinutes(prepTime = "20", cookTime = null))
        assertEquals(45, MealPlanGeneration.totalActiveMinutes(prepTime = null, cookTime = "45 min"))
        assertNull(MealPlanGeneration.totalActiveMinutes(prepTime = "about an hour", cookTime = null))
    }

    // ---- exclude / vegetarian -----------------------------------------------

    @Test
    fun filterRecipeCandidates_dropsExcludedIngredient() {
        val peanut = recipe("thai", "Pad Thai", ingredients = listOf("rice noodles", "peanuts"))
        val pasta = recipe("pasta", "Pasta", ingredients = listOf("spaghetti", "tomato"))
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(peanut, pasta),
            MealPlanGeneration.FilterCandidatesOptions(excludeIngredients = listOf("peanut")),
        )
        assertEquals(listOf(pasta.a), kept.map { it.a })
    }

    @Test
    fun filterRecipeCandidates_vegetarianDropsMeat() {
        val steak = recipe("steak", "Steak Frites", ingredients = listOf("ribeye", "potatoes"))
        val dal = recipe("dal", "Dal", ingredients = listOf("lentils", "onion"))
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(steak, dal),
            MealPlanGeneration.FilterCandidatesOptions(
                styles = listOf(MealPlanGeneration.PreferenceStyleId.VEGETARIAN),
            ),
        )
        assertEquals(listOf(dal.a), kept.map { it.a })
    }

    @Test
    fun filterRecipeCandidates_vegetarianKeepsUnknownIngredients() {
        val mystery = recipe("mystery", "Mystery Bowl", ingredients = emptyList())
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(mystery),
            MealPlanGeneration.FilterCandidatesOptions(
                styles = listOf(MealPlanGeneration.PreferenceStyleId.VEGETARIAN),
            ),
        )
        assertEquals(listOf(mystery.a), kept.map { it.a })
    }

    @Test
    fun filterRecipeCandidates_keepsUnparseableDuration() {
        val unknown = recipe("slow", "Slow Thing", prepTime = "about an hour")
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(unknown),
            MealPlanGeneration.FilterCandidatesOptions(maxMinutes = 30),
        )
        assertEquals(listOf(unknown.a), kept.map { it.a })
    }

    @Test
    fun filterRecipeCandidates_grahamCrackerTripsHamBlocklist() {
        // Pinned quirk: VEGETARIAN_BLOCKLIST matches as substrings, so
        // "graham cracker" contains "ham". Matches web; do not tighten here.
        val smores = recipe("smores", "S'mores Bars", ingredients = listOf("graham cracker", "chocolate"))
        val salad = recipe("salad", "Green Salad", ingredients = listOf("lettuce", "cucumber"))
        val kept = MealPlanGeneration.filterRecipeCandidates(
            listOf(smores, salad),
            MealPlanGeneration.FilterCandidatesOptions(
                styles = listOf(MealPlanGeneration.PreferenceStyleId.VEGETARIAN),
            ),
        )
        assertEquals(listOf(salad.a), kept.map { it.a })
    }

    // ---- style ranking at the 8-match boundary ------------------------------

    @Test
    fun filterRecipeCandidates_styleRankingDropsUnmatchedAtEight() {
        val matched = (1..8).map { i -> recipe("k$i", "Keto Bowl $i", tags = listOf("keto")) }
        val unmatched = recipe("pasta", "Weeknight Pasta", tags = listOf("easy"))
        val kept = MealPlanGeneration.filterRecipeCandidates(
            matched + unmatched,
            MealPlanGeneration.FilterCandidatesOptions(
                styles = listOf(MealPlanGeneration.PreferenceStyleId.KETO),
            ),
        )
        assertEquals(8, kept.size)
        assertFalse(kept.any { it.a == unmatched.a })
        assertEquals(matched.map { it.a }.toSet(), kept.map { it.a }.toSet())
    }

    @Test
    fun filterRecipeCandidates_styleRankingKeepsUnmatchedBelowEight() {
        val matched = (1..7).map { i -> recipe("k$i", "Keto Bowl $i", tags = listOf("keto")) }
        val unmatched = recipe("pasta", "Weeknight Pasta", tags = listOf("easy"))
        val kept = MealPlanGeneration.filterRecipeCandidates(
            matched + unmatched,
            MealPlanGeneration.FilterCandidatesOptions(
                styles = listOf(MealPlanGeneration.PreferenceStyleId.KETO),
            ),
        )
        assertEquals(8, kept.size)
        assertTrue(kept.any { it.a == unmatched.a })
        assertEquals(matched.map { it.a }.toSet() + unmatched.a, kept.map { it.a }.toSet())
    }

    // ---- validateGeneratedPlan ----------------------------------------------

    private fun dinnerPastaRequest(
        occupiedSlots: List<MealPlanGeneration.MealSlotRef> = emptyList(),
        days: List<String> = listOf("mon"),
        mealSlots: List<String> = listOf("dinner"),
        candidates: List<MealPlanGeneration.RecipeCandidate> = listOf(recipe("pasta", "Weeknight Pasta")),
        strategy: MealPlanGeneration.MealPlanStrategy = MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
    ) = request(
        days = days,
        mealSlots = mealSlots,
        strategy = strategy,
        candidates = candidates,
        occupiedSlots = occupiedSlots,
    )

    @Test
    fun validateGeneratedPlan_happyPath() {
        val candidate = recipe("pasta", "Weeknight Pasta")
        val req = dinnerPastaRequest(candidates = listOf(candidate))
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "dinner",
                        a = candidate.a,
                        title = "ignored — candidate title wins",
                        reason = "  leftover night  ",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Ok
        assertEquals(1, result.plan.meals.size)
        assertEquals(candidate.title, result.plan.meals[0].title)
        assertEquals("leftover night", result.plan.meals[0].reason)
    }

    @Test
    fun validateGeneratedPlan_unknownDay() {
        val req = dinnerPastaRequest()
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "monday",
                        slot = "dinner",
                        a = req.candidates[0].a,
                        title = "Weeknight Pasta",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.UNKNOWN_DAY, result.error)
        assertEquals("Cheffy returned a day that was not requested.", result.message)
    }

    @Test
    fun validateGeneratedPlan_unknownSlot() {
        val req = dinnerPastaRequest()
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "dessert",
                        a = req.candidates[0].a,
                        title = "Weeknight Pasta",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.UNKNOWN_SLOT, result.error)
        assertEquals("Cheffy returned a meal that was not requested.", result.message)
    }

    @Test
    fun validateGeneratedPlan_slotNotRequested() {
        val req = dinnerPastaRequest(mealSlots = listOf("dinner"))
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "lunch",
                        a = req.candidates[0].a,
                        title = "Weeknight Pasta",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.UNKNOWN_SLOT, result.error)
        assertEquals("Cheffy returned a meal slot that was not requested.", result.message)
    }

    @Test
    fun validateGeneratedPlan_duplicateSlot() {
        val req = dinnerPastaRequest()
        val meal = MealPlanGeneration.GeneratedMeal(
            day = "mon",
            slot = "dinner",
            a = req.candidates[0].a,
            title = "Weeknight Pasta",
        )
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(listOf(meal, meal)),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.DUPLICATE_SLOT, result.error)
        assertEquals("Cheffy assigned two recipes to the same slot.", result.message)
    }

    @Test
    fun validateGeneratedPlan_overwriteOccupiedUnderFillEmpty() {
        val req = dinnerPastaRequest(
            occupiedSlots = listOf(MealPlanGeneration.MealSlotRef("mon", "dinner")),
            days = listOf("mon", "tue"),
        )
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "dinner",
                        a = req.candidates[0].a,
                        title = "Weeknight Pasta",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.OVERWRITE_OCCUPIED, result.error)
        assertEquals("Cheffy tried to overwrite a meal that is already planned.", result.message)
    }

    @Test
    fun validateGeneratedPlan_unknownCoordinate() {
        val req = dinnerPastaRequest()
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "dinner",
                        a = coord("not-in-candidates"),
                        title = "Ghost Recipe",
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.UNKNOWN_RECIPE, result.error)
        assertEquals("Cheffy returned a recipe that is not in Zap Cooking.", result.message)
    }

    @Test
    fun validateGeneratedPlan_ineligibleSlot() {
        val ribs = recipe("ribs", "Braised Short Ribs")
        val req = dinnerPastaRequest(
            mealSlots = listOf("breakfast"),
            candidates = listOf(ribs),
        )
        val result = MealPlanGeneration.validateGeneratedPlan(
            MealPlanGeneration.GeneratedMealPlan(
                listOf(
                    MealPlanGeneration.GeneratedMeal(
                        day = "mon",
                        slot = "breakfast",
                        a = ribs.a,
                        title = ribs.title,
                    ),
                ),
            ),
            req,
        ) as MealPlanGeneration.ValidationResult.Err
        assertEquals(MealPlanGeneration.GenerationValidationError.INELIGIBLE_SLOT, result.error)
        assertEquals("Cheffy assigned a recipe that does not fit that meal.", result.message)
    }
}
