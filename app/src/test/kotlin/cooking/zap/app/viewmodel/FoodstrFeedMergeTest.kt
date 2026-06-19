package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.viewmodel.FoodstrFeedViewModel.FoodstrItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the home-feed merge: recipes and #foodstr notes interleave strictly by
 * timestamp, newest first. Recipes key off `publishedAt`, notes off
 * `created_at`.
 */
class FoodstrFeedMergeTest {

    private fun recipe(id: String, publishedAt: Long) = RecipeParser.Recipe(
        id = id, author = "a".repeat(64), dTag = "d-$id", title = "R$id",
        image = null, summary = null, publishedAt = publishedAt,
        hashtags = listOf("zapcooking"), categories = emptyList(),
        content = RecipeParser.RecipeContent(),
    )

    private fun note(id: String, createdAt: Long) = NostrEvent(
        id = id, pubkey = "b".repeat(64), created_at = createdAt, kind = 1,
        tags = listOf(listOf("t", "foodstr")), content = "n$id", sig = "0".repeat(128),
    )

    @Test
    fun interleavesByTimestampNewestFirst() {
        val recipes = listOf(recipe("r1", 300), recipe("r2", 100))
        val notes = listOf(note("n1", 200), note("n2", 50))

        val merged = mergeFoodstrItems(recipes, notes)

        assertEquals(4, merged.size)
        assertEquals(listOf(300L, 200L, 100L, 50L), merged.map { it.timestamp })
        // Recipe-then-note-then-recipe-then-note interleave.
        assertTrue(merged[0] is FoodstrItem.Recipe) // r1 @300
        assertTrue(merged[1] is FoodstrItem.Note)   // n1 @200
        assertTrue(merged[2] is FoodstrItem.Recipe) // r2 @100
        assertTrue(merged[3] is FoodstrItem.Note)   // n2 @50
    }

    @Test
    fun keysAreStableAndTyped() {
        val merged = mergeFoodstrItems(listOf(recipe("r1", 10)), listOf(note("n1", 20)))
        assertEquals("note-n1", merged[0].key)   // note is newer
        assertEquals("recipe-r1", merged[1].key)
    }

    @Test
    fun emptyInputs_yieldEmpty() {
        assertTrue(mergeFoodstrItems(emptyList(), emptyList()).isEmpty())
    }
}
