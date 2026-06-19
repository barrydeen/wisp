package cooking.zap.app.api

import cooking.zap.app.nostr.RecipeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test for [NormalizedRecipe.toRecipePreview] — the Sous Chef import
 * preview mapping (concern 2.1) — against the **real** `/api/extract-recipe/
 * public` response for "Easy Meatloaf", frozen at
 * `resources/recipes/extract_meatloaf.json` exactly as the live endpoint
 * returned it.
 */
class NormalizedRecipeMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResponse(): ExtractRecipeResponse {
        val text = javaClass.getResource("/recipes/extract_meatloaf.json")!!.readText()
        return json.decodeFromString(ExtractRecipeResponse.serializer(), text)
    }

    @Test
    fun realResponse_parsesSuccess() {
        val resp = loadResponse()
        assertTrue(resp.success)
        assertEquals("Easy Meatloaf", resp.recipe?.title)
    }

    @Test
    fun mapsToRecipePreview_withDetailsAndLists() {
        val recipe = loadResponse().recipe!!.toRecipePreview()

        assertEquals("Easy Meatloaf", recipe.title)
        assertEquals("15 mins", recipe.content.details.prepTime)
        assertEquals("1 hr", recipe.content.details.cookTime)
        assertEquals("8", recipe.content.details.servings)
        assertEquals(9, recipe.content.ingredients.size)
        assertEquals(9, recipe.content.directions.size)
        assertEquals(listOf("Meatloaf", "Comfort Food", "Main Dish"), recipe.hashtags)
        assertTrue(recipe.content.chefNotes?.isNotBlank() == true)
        // First imageUrl becomes the hero.
        assertEquals(loadResponse().recipe!!.imageUrls.first(), recipe.image)
        // Not a published event → no addressable identity, no additional section.
        assertEquals("", recipe.id)
        assertEquals("", recipe.author)
        assertNull(recipe.content.additionalMarkdown)
    }

    @Test
    fun emptyImageUrls_yieldsNullHero_noCrash() {
        val r = NormalizedRecipe(title = "X", imageUrls = emptyList()).toRecipePreview()
        assertNull(r.image) // guarded — never imageUrls.first() on empty
    }

    @Test
    fun blankFields_becomeNull_notEmptyStrings() {
        val r = NormalizedRecipe(title = "X", summary = "", chefsnotes = "", servings = "").toRecipePreview()
        assertNull(r.summary)
        assertNull(r.content.chefNotes)
        assertNull(r.content.details.servings)
    }

    @Test
    fun previewIsParseableAsARecipe() {
        // The mapped preview is a real RecipeParser.Recipe the shared body renders.
        val recipe: RecipeParser.Recipe = loadResponse().recipe!!.toRecipePreview()
        assertTrue(recipe.content.ingredients.isNotEmpty())
        assertTrue(recipe.content.directions.isNotEmpty())
    }
}
