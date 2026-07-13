package cooking.zap.app.souschef

import cooking.zap.app.viewmodel.RecipeComposeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the Sous Chef → composer hand-off markdown and the
 * publish-confirm enablement gate. Navigation/`pendingComposeMarkdown` wiring
 * is exercised on-device; the pure payload + compose prefill round-trip is
 * pinned here.
 */
class SousChefComposeHandoffTest {

    private fun sampleRecipe(
        title: String = "Garlic Pasta",
        servings: String? = "2",
        ingredients: List<String> = listOf("2 cups flour", "1 egg"),
        directions: List<String> = listOf("Boil water", "Cook pasta"),
    ) = cooking.zap.app.nostr.RecipeParser.Recipe(
        id = "",
        author = "",
        dTag = "",
        title = title,
        image = "https://example.com/pasta.jpg",
        summary = null,
        publishedAt = 0L,
        hashtags = listOf("italian"),
        categories = emptyList(),
        content = cooking.zap.app.nostr.RecipeParser.RecipeContent(
            chefNotes = "Al dente",
            details = cooking.zap.app.nostr.RecipeParser.RecipeDetails(
                prepTime = "10 min",
                cookTime = "15 min",
                servings = servings,
            ),
            ingredients = ingredients,
            directions = directions,
            additionalMarkdown = null,
        ),
    )

    @Test
    fun handoffMarkdown_includesTitleHeadingAndBodySections() {
        val md = sampleRecipe().toComposeHandoffMarkdown()
        assertTrue(md.startsWith("# Garlic Pasta\n"))
        assertTrue(md.contains("## Chef's notes"))
        assertTrue(md.contains("Al dente"))
        assertTrue(md.contains("## Details"))
        assertTrue(md.contains("🍽️ Servings: 2"))
        assertTrue(md.contains("## Ingredients"))
        assertTrue(md.contains("- 2 cups flour"))
        assertTrue(md.contains("## Directions"))
        assertTrue(md.contains("1. Boil water"))
    }

    @Test
    fun handoffMarkdown_appliesServingsMultiplier() {
        val md = sampleRecipe().toComposeHandoffMarkdown(multiplier = 2.0)
        assertTrue(md.contains("🍽️ Servings: 4"))
        assertTrue(md.contains("- 4 cups flour"))
        assertTrue(md.contains("- 2 egg"))
        // Directions are not scaled.
        assertTrue(md.contains("1. Boil water"))
    }

    @Test
    fun handoffMarkdown_blankTitleBecomesUntitled() {
        val md = sampleRecipe(title = "  ").toComposeHandoffMarkdown()
        assertTrue(md.startsWith("# Untitled\n"))
    }

    @Test
    fun handoffMarkdown_survivesComposePrefill() {
        val md = sampleRecipe().toComposeHandoffMarkdown(multiplier = 2.0)
        val vm = RecipeComposeViewModel()
        vm.prefillFromMarkdown(md)
        assertEquals("Garlic Pasta", vm.title.value)
        assertEquals("4", vm.servings.value)
        assertEquals(listOf("4 cups flour", "2 egg"), vm.ingredients.value.map { it.text })
        assertEquals(listOf("Boil water", "Cook pasta"), vm.directions.value.map { it.text })
        assertEquals("Al dente", vm.chefNotes.value)
        assertEquals("10 min", vm.prepTime.value)
        assertEquals("15 min", vm.cookTime.value)
        // Cheffy/Sous Chef hand-off contract: images & categories stay empty.
        assertTrue(vm.images.value.isEmpty())
        assertTrue(vm.categories.value.isEmpty())
    }

    @Test
    fun publishConfirm_opensOnlyWhenPublishable() {
        assertTrue(SousChefPublishConfirm.shouldOpenConfirm(canSign = true, hasImage = true, publishing = false))
        assertFalse(SousChefPublishConfirm.shouldOpenConfirm(canSign = false, hasImage = true, publishing = false))
        assertFalse(SousChefPublishConfirm.shouldOpenConfirm(canSign = true, hasImage = false, publishing = false))
        assertFalse(SousChefPublishConfirm.shouldOpenConfirm(canSign = true, hasImage = true, publishing = true))
    }

    @Test
    fun publishConfirm_copyIsHonestAboutPublicPost() {
        assertEquals(
            "Publish to your followers? This posts the recipe publicly under your account.",
            SousChefPublishConfirm.MESSAGE,
        )
    }

    @Test
    fun cookbookSaveConfirm_mentionsPublishAndMyKitchen() {
        assertEquals(
            "Save to My Kitchen? This publishes the recipe publicly under your account and adds it to your Saved list.",
            SousChefPublishConfirm.COOKBOOK_SAVE_MESSAGE,
        )
        assertEquals("Saved to My Kitchen", SousChefPublishConfirm.SAVED_TOAST)
        assertEquals(
            "30023:abcd:garlic-pasta",
            SousChefPublishConfirm.cookbookCoordinate(30023, "abcd", "garlic-pasta"),
        )
    }
}
