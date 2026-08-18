package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real published recipe, as a fixture for the ARTICLE/RECIPE badge.
 *
 * Event `8f1459f2…` ("Bear Market Sludge", kind 30023) fetched from relays —
 * a recipe published by the web client, which is what the badge has to
 * recognize. It carries no `## Chef's notes` section, so this also pins that
 * the template validator doesn't require one.
 *
 * The badge reads [RecipeParser.isRecipe]; if this regresses, real recipes
 * start rendering as ARTICLE again.
 */
class PublishedRecipeBadgeTest {

    private val content = """

## Details

- ⏲️ Prep time: 10 min
- 🍳 Cook time: 30 min
- 🍽️ Servings: 2 (depends how hungry the bears are)

## Ingredients

- 1 pound boneless chicken (thighs or breast)
- 1 can Campbells Cream of Mushroom Soup
- half a can of heavy cream
- 6 ounces white rice
- garlic powder
- cayenne pepper
- onion powder
- salt & pepper


## Directions

1. Start by getting the rice going.
2. generously season chicken with salt and pepper
3. cook chicken
4. prepare sauce: mix cream of mushroom soup with heavy cream (i use 1/2 of the empty soup can to measure)
5. add generous portions of garlic powder and cayenne pepper. Add in some onion powder and salt and pepper as well. stir all these together on low/medium heat
6. once chicken is cooked, chop it up into tiny pieces. The chicken needs to be chopped up to give the sludge the proper consistency
7. add chicken to sludge (sauce) and stir so everything is well mixed.
8. finally, pour the chicken/sludge over the rice and mix all that together. ENJOY!
"""

    /** Tags exactly as published — note there is no `## Chef's notes` section. */
    private val event = NostrEvent(
        id = "8f1459f2efa361ddef6b897558506cc55f447ae4d9fc8480752cfde65f025931",
        pubkey = "8a3a9236d0eae6bc92eb17782d57e828a01f03cd28d3c68297c7e19d374b9419",
        created_at = 1724080079L,
        kind = 30023,
        tags = listOf(
            listOf("d", "bear-market-sludge-"),
            listOf("title", "Bear Market Sludge "),
            listOf("t", "nostrcooking"),
            listOf("t", "nostrcooking-bear-market-sludge-"),
            listOf("summary", "Delicious, easy, and won't break the bank."),
            listOf("image", "https://image.nostr.build/b09edf85f3856d20258d4f14a716c8d7d3e9b2c4be1533393d5fbd82ce719a5e.png")
        ),
        content = content,
        sig = "2".repeat(128)
    )

    @Test
    fun `the two cheap conditions pass`() {
        assertEquals(RecipeParser.RECIPE_KIND, event.kind)
        assertTrue(
            "carries the nostrcooking hashtag",
            event.tags.any { it.size >= 2 && it[0] == "t" && it[1] in RecipeParser.RECIPE_HASHTAGS }
        )
    }

    @Test
    fun `this published recipe is recognized as a recipe`() {
        assertTrue(
            "should badge as RECIPE, not ARTICLE — " +
                "template validation says ${RecipeParser.validateMarkdownTemplate(content)}",
            RecipeParser.isRecipe(event)
        )
    }
}
