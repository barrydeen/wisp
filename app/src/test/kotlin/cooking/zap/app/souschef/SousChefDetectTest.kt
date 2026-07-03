package cooking.zap.app.souschef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors the web `$lib/souschefDetect.test.ts` case-for-case so the Kotlin
 * port cannot drift silently from [detectMode] in zapcooking/frontend.
 */
class SousChefDetectTest {

    private companion object {
        const val SAMPLE_URL = "https://example.com/recipes/sheet-pan-chicken"
        const val SAMPLE_RECIPE_TEXT =
            "Heat 2 tbsp olive oil in a large skillet over medium heat until shimmering."
    }

    // empty / whitespace input

    @Test
    fun returns_null_for_an_empty_string() {
        assertNull(detectMode("", hasImage = false))
    }

    @Test
    fun returns_null_for_whitespace_only_input() {
        assertNull(detectMode("   ", hasImage = false))
        assertNull(detectMode("\n\n\t  ", hasImage = false))
    }

    // URL detection

    @Test
    fun returns_url_for_a_single_trimmed_url() {
        assertEquals(SousChefMode.URL, detectMode(SAMPLE_URL, hasImage = false))
    }

    @Test
    fun returns_url_for_a_url_with_leading_trailing_whitespace() {
        assertEquals(SousChefMode.URL, detectMode("  $SAMPLE_URL  ", hasImage = false))
        assertEquals(SousChefMode.URL, detectMode("\n$SAMPLE_URL\n", hasImage = false))
    }

    @Test
    fun accepts_http_in_addition_to_https() {
        assertEquals(SousChefMode.URL, detectMode("http://example.com/recipe", hasImage = false))
    }

    @Test
    fun is_case_insensitive_on_the_scheme() {
        assertEquals(SousChefMode.URL, detectMode("HTTPS://Example.com/Recipe", hasImage = false))
    }

    // text-mode false-positive guards

    @Test
    fun routes_a_url_on_line_1_with_recipe_text_below_to_text() {
        val input = "$SAMPLE_URL\n\nIngredients:\n- 2 tbsp olive oil\n- 1 chicken breast"
        assertEquals(SousChefMode.TEXT, detectMode(input, hasImage = false))
    }

    @Test
    fun routes_a_url_with_trailing_text_on_the_same_line_to_text() {
        val input = "$SAMPLE_URL this looks great"
        assertEquals(SousChefMode.TEXT, detectMode(input, hasImage = false))
    }

    @Test
    fun routes_see_url_style_citations_to_text() {
        val input = "Sheet pan chicken — see $SAMPLE_URL for the original"
        assertEquals(SousChefMode.TEXT, detectMode(input, hasImage = false))
    }

    // text detection

    @Test
    fun returns_text_for_multi_line_recipe_text_with_no_url() {
        val input =
            "Ingredients:\n- 2 cups flour\n- 1 tsp salt\n- 1 egg\n\nDirections:\n1. Mix dry ingredients."
        assertEquals(SousChefMode.TEXT, detectMode(input, hasImage = false))
    }

    @Test
    fun returns_text_for_the_sample_recipe_sentence() {
        assertEquals(SousChefMode.TEXT, detectMode(SAMPLE_RECIPE_TEXT, hasImage = false))
    }

    // short-input threshold

    @Test
    fun returns_null_for_text_under_30_chars_with_no_url() {
        assertNull(detectMode("chicken with rice", hasImage = false))
        assertNull(detectMode("quick recipe", hasImage = false))
    }

    @Test
    fun returns_null_for_text_exactly_at_29_chars() {
        val twentyNine = "a".repeat(29)
        assertEquals(29, twentyNine.length)
        assertNull(detectMode(twentyNine, hasImage = false))
    }

    @Test
    fun returns_text_for_text_exactly_at_the_30_char_threshold() {
        val thirty = "a".repeat(30)
        assertEquals(30, thirty.length)
        assertEquals(SousChefMode.TEXT, detectMode(thirty, hasImage = false))
    }

    // image precedence

    @Test
    fun returns_image_when_hasImage_is_true_regardless_of_text() {
        assertEquals(SousChefMode.IMAGE, detectMode("", hasImage = true))
        assertEquals(SousChefMode.IMAGE, detectMode("   ", hasImage = true))
        assertEquals(SousChefMode.IMAGE, detectMode(SAMPLE_URL, hasImage = true))
        assertEquals(SousChefMode.IMAGE, detectMode(SAMPLE_RECIPE_TEXT, hasImage = true))
    }

    @Test
    fun returns_image_even_when_text_would_otherwise_be_a_clean_url() {
        assertEquals(SousChefMode.IMAGE, detectMode(SAMPLE_URL, hasImage = true))
    }
}
