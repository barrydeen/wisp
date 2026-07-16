package cooking.zap.app.mealplan

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipePickerLogicTest {

    private fun list(dTag: String, vararg coords: String) = CookbookList(
        dTag = dTag,
        title = dTag,
        summary = null,
        image = null,
        coverCoord = null,
        coordinates = coords.toSet(),
        isDefault = dTag == "saved",
        event = NostrEvent(
            id = "id-$dTag",
            pubkey = "pk",
            created_at = 1L,
            kind = 30001,
            tags = emptyList(),
            content = "",
            sig = "sig",
        ),
    )

    @Test
    fun uniqueCoordinates_flattensAndDedupes_firstSeenOrder() {
        val a = "30023:aa:soup"
        val b = "30023:bb:bread"
        val c = "30023:cc:salad"
        val lists = listOf(
            list("saved", a, b),
            list("favorites", b, c), // b duplicate
            list("empty"),
        )
        assertEquals(listOf(a, b, c), RecipePickerLogic.uniqueCoordinates(lists))
    }

    @Test
    fun uniqueCoordinates_trimsAndDropsBlank() {
        val a = "30023:aa:soup"
        assertEquals(
            listOf(a),
            RecipePickerLogic.uniqueCoordinates(listOf(list("saved", "  $a  ", "", "   "))),
        )
    }

    @Test
    fun filterRows_matchesTitleCaseInsensitive() {
        val rows = listOf(
            RecipePickerLogic.PickerRow("30023:a:one", "Tomato Soup", null),
            RecipePickerLogic.PickerRow("30023:a:two", "Garlic Bread", null),
        )
        assertEquals(
            listOf(rows[0]),
            RecipePickerLogic.filterRows(rows, "soup"),
        )
        assertEquals(rows, RecipePickerLogic.filterRows(rows, "  "))
        assertTrue(RecipePickerLogic.filterRows(rows, "pizza").isEmpty())
    }

    @Test
    fun titleFallback_usesDTag_notFullCoordinate() {
        assertEquals(
            "weeknight-chili",
            RecipePickerLogic.titleFallback("30023:abcdef:weeknight-chili"),
        )
        assertEquals(
            "not-a-coord",
            RecipePickerLogic.titleFallback("not-a-coord"),
        )
    }

    @Test
    fun recipeEntry_alwaysWritesTitleSnapshot() {
        val withTitle = RecipePickerLogic.recipeEntry("30023:aa:soup", "Tomato Soup")
        assertEquals("recipe", withTitle["type"]!!.jsonPrimitive.content)
        assertEquals("30023:aa:soup", withTitle["a"]!!.jsonPrimitive.content)
        assertEquals("Tomato Soup", withTitle["title"]!!.jsonPrimitive.content)

        val blankTitle = RecipePickerLogic.recipeEntry("30023:aa:soup", "  ")
        assertEquals("soup", blankTitle["title"]!!.jsonPrimitive.content)
        assertTrue(blankTitle.containsKey("title"))
    }
}
