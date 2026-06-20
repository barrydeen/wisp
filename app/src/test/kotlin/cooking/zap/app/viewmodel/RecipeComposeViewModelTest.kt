package cooking.zap.app.viewmodel

import org.junit.Assert.assertEquals
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
