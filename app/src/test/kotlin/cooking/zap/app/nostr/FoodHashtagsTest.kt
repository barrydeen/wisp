package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the OnlyFood hashtag set: no duplicates (the frontend array had
 * `soup`/`mealprep` twice — those become a wasted relay filter term), and the
 * core tags the feed depends on are present.
 */
class FoodHashtagsTest {

    @Test
    fun isDeduplicated() {
        val all = FoodHashtags.ALL
        assertEquals("FOOD_HASHTAGS must be deduped", all.toSet().size, all.size)
    }

    @Test
    fun allLowercaseNonBlank() {
        FoodHashtags.ALL.forEach { tag ->
            assertTrue("blank tag", tag.isNotBlank())
            assertEquals("tags must be lowercase (relay #t is case-sensitive)", tag.lowercase(), tag)
        }
    }

    @Test
    fun containsCoreFoodTags() {
        val all = FoodHashtags.ALL.toSet()
        listOf("foodstr", "zapcooking", "cooking", "recipe", "food", "chef", "bbq", "dessert", "baking")
            .forEach { assertTrue("missing core tag #$it", it in all) }
    }

    @Test
    fun isBroadEnoughToNotLookEmpty() {
        // The whole point of the expanded set — sanity floor.
        assertTrue("expected a broad set", FoodHashtags.ALL.size >= 60)
    }
}
