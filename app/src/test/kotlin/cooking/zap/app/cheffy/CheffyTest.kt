package cooking.zap.app.cheffy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the Cheffy brand logic against the web `$lib/cheffy` (concern 2.3): the
 * structured-recipe gate (which flips a reply's rendering/expression), the
 * cosmetic recipe-request heuristic, and `pickLine`'s no-immediate-repeat rule.
 */
class CheffyTest {

    private val structuredRecipe = """
        # Tuscan Peposo

        A peppery Tuscan beef stew.

        ## Details
        ⏲️ Prep time: 20 min
        🍳 Cook time: 3 hours
        🍽️ Servings: 4

        ## Ingredients
        - 1kg beef
        - black pepper

        ## Directions
        1. Brown the beef.
        2. Simmer low and slow.
    """.trimIndent()

    @Test
    fun looksLikeStructuredRecipe_trueOnlyWithTitlePlusBothSections() {
        assertTrue(Cheffy.looksLikeStructuredRecipe(structuredRecipe))
    }

    @Test
    fun looksLikeStructuredRecipe_falseForConversation() {
        assertFalse(Cheffy.looksLikeStructuredRecipe("Sure! You can swap yogurt for sour cream 1:1."))
        // Missing Directions → not a structured recipe.
        assertFalse(Cheffy.looksLikeStructuredRecipe("# Soup\n\n## Ingredients\n- water"))
        // Missing the title heading.
        assertFalse(Cheffy.looksLikeStructuredRecipe("## Ingredients\n- x\n## Directions\n1. y"))
        assertFalse(Cheffy.looksLikeStructuredRecipe(""))
        assertFalse(Cheffy.looksLikeStructuredRecipe(null))
    }

    @Test
    fun looksLikeRecipeRequest_matchesKeywordsAndHavePrefix() {
        assertTrue(Cheffy.looksLikeRecipeRequest("make me a recipe for dinner"))
        assertTrue(Cheffy.looksLikeRecipeRequest("I have: eggs, spinach, cheese"))
        assertTrue(Cheffy.looksLikeRecipeRequest("what should I cook tonight"))
        assertFalse(Cheffy.looksLikeRecipeRequest("can I substitute butter for oil?"))
    }

    @Test
    fun pickLine_avoidsImmediateRepeat() {
        // A Random that always returns 0 would pick the same line; pickLine must
        // re-roll off the avoided value.
        val alwaysZero = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(until: Int) = 0
        }
        val pool = listOf("a", "b", "c")
        assertEquals("b", Cheffy.pickLine(pool, avoid = "a", random = alwaysZero))
        // No avoid → returns the picked line as-is.
        assertEquals("a", Cheffy.pickLine(pool, avoid = null, random = alwaysZero))
    }

    @Test
    fun pickLine_singleEntryPool_returnsIt() {
        assertEquals("only", Cheffy.pickLine(listOf("only"), avoid = "only"))
        assertEquals("", Cheffy.pickLine(emptyList()))
    }

    @Test
    fun voiceLinePools_matchTheWebVerbatim() {
        // Guards against accidental drift from the web source of truth.
        assertEquals(6, Cheffy.PROMPT_PLACEHOLDERS.size)
        assertEquals("What are we cooking?", Cheffy.PROMPT_PLACEHOLDERS.first())
        assertEquals(5, Cheffy.THINKING_LINES.size)
        assertEquals("Tasting the idea…", Cheffy.THINKING_LINES.first())
        assertEquals(5, Cheffy.COOKING_LINES.size)
        assertEquals("Firing up the burners…", Cheffy.COOKING_LINES.first())
        assertEquals(4, Cheffy.ERROR_LINES.size)
    }
}
