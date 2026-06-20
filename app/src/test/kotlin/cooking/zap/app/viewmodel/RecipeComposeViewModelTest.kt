package cooking.zap.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of [RecipeComposeViewModel]'s synchronous form logic:
 * the `canPublish`-mirroring [RecipeComposeViewModel.blockReason] gate and
 * category de-duplication. These touch only StateFlow setters (no
 * viewModelScope/coroutines, no Android deps), so they run on the JVM.
 *
 * Image uploads and the publish round-trip are network/coroutine paths,
 * verified on-device — not here.
 */
class RecipeComposeViewModelTest {

    private fun vm() = RecipeComposeViewModel()

    @Test
    fun blockReason_readOnly_alwaysBlocks() {
        assertEquals("Sign in to publish recipes.", vm().blockReason(canSign = false))
    }

    @Test
    fun blockReason_walksTheRequiredFieldsInOrder() {
        val vm = vm()
        // Title first.
        assertEquals("Add a title.", vm.blockReason(canSign = true))
        vm.setTitle("Tuscan Peposo")
        // Then a category.
        assertEquals("Add at least one category.", vm.blockReason(canSign = true))
        vm.addCategory("italian")
        // Then a photo (none added yet).
        assertEquals("Add at least one photo.", vm.blockReason(canSign = true))
    }

    @Test
    fun addCategory_deDupesCaseInsensitively_andTrims() {
        val vm = vm()
        vm.addCategory("Italian")
        vm.addCategory("  italian  ")
        vm.addCategory("ITALIAN")
        assertEquals(listOf("Italian"), vm.categories.value)
        vm.addCategory("Dessert")
        assertEquals(listOf("Italian", "Dessert"), vm.categories.value)
        vm.removeCategory("Italian")
        assertEquals(listOf("Dessert"), vm.categories.value)
    }

    @Test
    fun addCategory_ignoresBlank() {
        val vm = vm()
        vm.addCategory("   ")
        vm.addCategory("")
        assertEquals(emptyList<String>(), vm.categories.value)
    }

    @Test
    fun blockReason_valueOverload_clearsTitleGateWhenTitlePresent() {
        // The UI computes the gate from COLLECTED state via this overload, so a
        // present title must clear the title gate (a Cheffy pre-fill fills the
        // title → the button must stop saying "Add a title").
        val img = RecipeComposeViewModel.ImageItem(1, RecipeComposeViewModel.ImageItem.Status.Done("u"))
        val ing = listOf(RecipeComposeViewModel.Row(1, "2 eggs"))
        val dir = listOf(RecipeComposeViewModel.Row(2, "Whisk"))

        // Title present + everything else → not gated on the title.
        assertEquals(
            "Add at least one category.",
            RecipeComposeViewModel.blockReason(true, "Tuscan Peposo", emptyList(), listOf(img), ing, dir),
        )
        // Fully filled → publishable.
        assertNull(
            RecipeComposeViewModel.blockReason(true, "Tuscan Peposo", listOf("italian"), listOf(img), ing, dir),
        )
        // Blank title → the title gate.
        assertEquals(
            "Add a title.",
            RecipeComposeViewModel.blockReason(true, "  ", listOf("italian"), listOf(img), ing, dir),
        )
    }

    // ---- prefillFromMarkdown (Cheffy "Save" — concern 2.3c) ----------------

    private val cheffyRecipe = """
        # Garlic Butter Shrimp

        A quick weeknight shrimp dish.

        ## Details
        ⏲️ Prep time: 10 min
        🍳 Cook time: 8 min
        🍽️ Servings: 2

        ## Ingredients
        - 1 lb shrimp
        - 3 cloves garlic

        ## Directions
        1. Melt the butter.
        2. Add shrimp and garlic; cook 8 min.
    """.trimIndent()

    @Test
    fun prefill_cleanParse_seedsBody_leavesImagesCategoriesSummaryEmpty() {
        val vm = vm()
        vm.prefillFromMarkdown(cheffyRecipe)

        assertEquals("Garlic Butter Shrimp", vm.title.value)
        assertEquals(listOf("1 lb shrimp", "3 cloves garlic"), vm.ingredients.value.map { it.text })
        assertEquals(
            listOf("Melt the butter.", "Add shrimp and garlic; cook 8 min."),
            vm.directions.value.map { it.text },
        )
        assertEquals("10 min", vm.prepTime.value)
        assertEquals("8 min", vm.cookTime.value)
        assertEquals("2", vm.servings.value)
        // Web parity: image, category, summary are NOT seeded — the user adds them.
        assertTrue(vm.images.value.isEmpty())
        assertTrue(vm.categories.value.isEmpty())
        assertEquals("", vm.summary.value)
        assertNull(vm.prefillNotice.value)
        // Still gated until the user adds a category + photo.
        assertEquals("Add at least one category.", vm.blockReason(canSign = true))
    }

    @Test
    fun prefill_lossyParse_salvagesRawText_andStaysGated() {
        val vm = vm()
        vm.prefillFromMarkdown("Just sauté some veggies with olive oil and garlic, no real recipe here.")

        assertEquals("Untitled", vm.title.value)
        // Raw text preserved in Additional Resources; rows stay empty.
        assertTrue(vm.additionalResources.value.contains("sauté some veggies"))
        assertTrue(vm.ingredients.value.all { it.text.isBlank() })
        assertTrue(vm.directions.value.all { it.text.isBlank() })
        assertNotNull(vm.prefillNotice.value)
        // Publish stays blocked (defense: a bad parse can't be published blindly).
        assertNotNull(vm.blockReason(canSign = true))
    }

    @Test
    fun prefill_isIdempotent() {
        val vm = vm()
        vm.prefillFromMarkdown(cheffyRecipe)
        // A second call (e.g. a re-entrant route) must not re-seed/duplicate.
        vm.prefillFromMarkdown("# Other\n\n## Ingredients\n- x\n\n## Directions\n1. y")
        assertEquals("Garlic Butter Shrimp", vm.title.value)
        assertEquals(2, vm.ingredients.value.size)
    }

    @Test
    fun rowOps_keepAtLeastOneRow_andAssignStableIds() {
        val vm = vm()
        assertEquals(1, vm.ingredients.value.size)
        val firstId = vm.ingredients.value.first().id
        vm.updateIngredient(firstId, "2 eggs")
        assertEquals("2 eggs", vm.ingredients.value.first().text)
        // Removing the only row leaves a fresh empty row (the field never vanishes).
        vm.removeIngredient(firstId)
        assertEquals(1, vm.ingredients.value.size)
        assertEquals("", vm.ingredients.value.first().text)
        // Add then remove keeps ids distinct.
        vm.addIngredient()
        assertEquals(2, vm.ingredients.value.size)
        assertEquals(2, vm.ingredients.value.map { it.id }.distinct().size)
    }
}
