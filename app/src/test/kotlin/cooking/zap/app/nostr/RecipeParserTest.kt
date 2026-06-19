package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden conformance for [RecipeParser] against a **real** zap.cooking
 * recipe — the "Tuscan Peposo" event (kind 30023, `#t zapcooking`) fetched
 * live from `relay.primal.net` during Step-0 investigation and frozen at
 * `resources/recipes/tuscan_peposo.json` exactly as the relay sent it.
 *
 * This pins the two drift cases Step 0 surfaced (build doc §Phase 1):
 *  - the event has **no `published_at` tag** -> [Recipe.publishedAt] must
 *    fall back to `created_at`;
 *  - the `## Details` block has prep + cook but **no servings** -> the
 *    servings field must be null, not an empty string or a crash.
 *
 * Pure JVM: [RecipeParser] is regex + strings only, no Android framework.
 */
class RecipeParserTest {

    private fun loadEvent(name: String): NostrEvent {
        val json = javaClass.getResource("/recipes/$name")
            ?.readText()
            ?: error("fixture /recipes/$name not found on test classpath")
        return NostrEvent.fromJson(json)
    }

    private val recipe by lazy { RecipeParser.parse(loadEvent("tuscan_peposo.json")) }

    @Test
    fun isRecipe_recognizesRealEvent() {
        assertTrue(RecipeParser.isRecipe(loadEvent("tuscan_peposo.json")))
    }

    @Test
    fun parsesAddressableCoordinatesAndTopTags() {
        assertEquals("19fec9967054f84cedf6c74c095f544f9630464913c7c9543b2e1cc6640ff2bb", recipe.id)
        assertEquals("1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207", recipe.author)
        assertEquals("tuscan-peposo-(black-pepper-beef-stew)", recipe.dTag)
        assertEquals("Tuscan Peposo (Black Pepper Beef Stew)", recipe.title)
        assertEquals(
            "https://image.nostr.build/95df427de745f56529810d928a4b6dd059f972fd5aee86efc618cd92023486ad.jpg",
            recipe.image
        )
        assertEquals(
            "A bold Tuscan beef stew cooked with red wine, garlic, black pepper, " +
                "and a touch of tomato. Simple ingredients, deep flavour.",
            recipe.summary
        )
    }

    @Test
    fun missingPublishedAt_fallsBackToCreatedAt() {
        // The live event carries no `published_at` tag; created_at is 1776632470.
        assertEquals(1776632470L, recipe.publishedAt)
    }

    @Test
    fun derivesCategories_excludingRootAndSlugTags() {
        // t-tags: zapcooking, zapcooking-<slug>, zapcooking-italian/-beef/-stew/-slowcooked
        assertEquals(listOf("italian", "beef", "stew", "slowcooked"), recipe.categories)
        // Raw hashtags are preserved verbatim.
        assertTrue("zapcooking" in recipe.hashtags)
        assertTrue("zapcooking-tuscan-peposo-(black-pepper-beef-stew)" in recipe.hashtags)
    }

    @Test
    fun parsesChefNotes() {
        assertEquals(
            "Peposo comes from Impruneta near Florence, traditionally cooked for hours " +
                "over low heat. The secret is time, black pepper, and patience. A little " +
                "tomato paste adds depth without changing its rustic identity.",
            recipe.content.chefNotes
        )
    }

    @Test
    fun parsesDetails_prepAndCookPresent_servingsAbsent() {
        assertEquals("10 min", recipe.content.details.prepTime)
        assertEquals("3 hours", recipe.content.details.cookTime)
        assertNull(recipe.content.details.servings)
    }

    /**
     * The one spot where a clean regex passes synthetic input but misses real
     * events: the `## Details` emoji labels carry U+FE0F variation selectors
     * in the live bytes. This proves the REAL event's prep AND cook extract to
     * non-null actual values — i.e. the emoji handling works on production
     * bytes, not just on hand-typed glyphs.
     */
    @Test
    fun realEmojiDetails_extractNonNull_despiteVariationSelector() {
        // Guard: the fixture must actually contain the hard case (⏲️ = U+23F2
        // U+FE0F). If someone "cleans" the fixture and drops the selector, this
        // fails loudly rather than silently weakening the golden.
        val content = loadEvent("tuscan_peposo.json").content
        assertTrue(
            "fixture lost its U+FE0F variation selector — golden no longer exercises the hard path",
            content.contains("⏲️")
        )

        val details = recipe.content.details
        assertNotNull("real ⏲️ Prep time (U+23F2 U+FE0F) must extract", details.prepTime)
        assertNotNull("real 🍳 Cook time (U+1F373) must extract", details.cookTime)
        assertEquals("10 min", details.prepTime)
        assertEquals("3 hours", details.cookTime)
    }

    @Test
    fun details_tolerateMissingVariationSelectorAndMultiSpace() {
        // Base emoji without U+FE0F + irregular spacing — a stricter regex
        // would miss these; the hardened one must not.
        val md = "## Details\n\n- ⏲  Prep time:  12 min\n- 🍽   Servings:   4\n"
        val d = RecipeParser.parseContent(md).details
        assertEquals("12 min", d.prepTime)   // ⏲ with NO selector, double space
        assertEquals("4", d.servings)        // 🍽 with NO selector, triple space
    }

    @Test
    fun parsesIngredients_asBulletList() {
        assertEquals(
            listOf(
                "1 kg beef for stewing (chuck or similar)",
                "750 ml red wine",
                "4 garlic cloves",
                "2 tbsp black pepper (coarsely ground)",
                "1 tbsp tomato paste",
                "Salt",
                "Extra virgin olive oil",
            ),
            recipe.content.ingredients
        )
    }

    @Test
    fun parsesDirections_strippingStepNumbers() {
        assertEquals(6, recipe.content.directions.size)
        assertEquals("Cut beef into large chunks.", recipe.content.directions.first())
        assertEquals("Rest 10 minutes, adjust salt, serve hot.", recipe.content.directions.last())
    }

    @Test
    fun noAdditionalResourcesSection_yieldsNull() {
        assertNull(recipe.content.additionalMarkdown)
    }

    // ---- Synthetic edge cases for drift the single fixture can't cover ----

    @Test
    fun details_servingsAndEmoji_parseLikeTheFrontend() {
        // Mirrors live "Spicy Hot Chocolate": prep with no unit, servings present.
        val md = """
            ## Details

            - ⏲️ Prep time: 10
            - 🍳 Cook time: 5 min
            - 🍽️ Servings: 2
        """.trimIndent()
        val d = RecipeParser.parseContent(md).details
        assertEquals("10", d.prepTime)       // free-text, no unit, never assumed numeric
        assertEquals("5 min", d.cookTime)
        assertEquals("2", d.servings)
    }

    @Test
    fun directions_keepSectionHeaderPseudoSteps_flat() {
        // Mirrors live "Japanese Milk Bread" where "1. Tangzhong:" is itself a step.
        val md = """
            ## Directions

            1. Tangzhong:
            2. Place the water in a small saucepan.
        """.trimIndent()
        val steps = RecipeParser.parseContent(md).directions
        assertEquals(listOf("Tangzhong:", "Place the water in a small saucepan."), steps)
    }

    @Test
    fun emptyBody_yieldsEmptyContent_noCrash() {
        val c = RecipeParser.parseContent("")
        assertNull(c.chefNotes)
        assertTrue(c.details.isEmpty)
        assertTrue(c.ingredients.isEmpty())
        assertTrue(c.directions.isEmpty())
        assertNull(c.additionalMarkdown)
    }
}
