package cooking.zap.app.mealplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture-driven port of frontend `ingredientParser.test.ts`.
 * Vectors: `/fixtures/ingredient-parser.vectors.json` (15 cases).
 */
class IngredientParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures: JsonObject = json.parseToJsonElement(
        javaClass.getResource("/fixtures/ingredient-parser.vectors.json")!!.readText(),
    ).jsonObject

    @Test
    fun parseIngredient_allVectors() {
        val vectors = fixtures["parseIngredient"]!!.jsonArray
        assertEquals(11, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val input = v["input"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonObject
            val p = IngredientParser.parseIngredient(input)
            assertEquals("$id.quantity", expected["quantity"]!!.jsonPrimitive.content, p.quantity)
            assertEquals("$id.name", expected["name"]!!.jsonPrimitive.content, p.name)
            assertEquals("$id.category", expected["category"]!!.jsonPrimitive.content, p.category)
            assertEquals(
                "$id.originalText",
                expected["originalText"]!!.jsonPrimitive.content,
                p.originalText,
            )
        }
    }

    @Test
    fun extractIngredientsFromRecipe_allVectors() {
        val vectors = fixtures["extractIngredientsFromRecipe"]!!.jsonArray
        assertEquals(2, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val input = v["input"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(id, expected, IngredientParser.extractIngredientsFromRecipe(input))
        }
    }

    @Test
    fun parseIngredientsFromRecipe_allVectors() {
        val vectors = fixtures["parseIngredientsFromRecipe"]!!.jsonArray
        assertEquals(1, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val input = v["input"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonArray
            val parsed = IngredientParser.parseIngredientsFromRecipe(input)
            assertEquals("$id.size", expected.size, parsed.size)
            expected.forEachIndexed { i, expEl ->
                val exp = expEl.jsonObject
                val p = parsed[i]
                assertEquals("$id[$i].quantity", exp["quantity"]!!.jsonPrimitive.content, p.quantity)
                assertEquals("$id[$i].name", exp["name"]!!.jsonPrimitive.content, p.name)
                assertEquals("$id[$i].category", exp["category"]!!.jsonPrimitive.content, p.category)
                assertEquals(
                    "$id[$i].originalText",
                    exp["originalText"]!!.jsonPrimitive.content,
                    p.originalText,
                )
            }
        }
    }

    @Test
    fun normalizeFraction_allVectors() {
        val vectors = fixtures["normalizeFraction"]!!.jsonArray
        assertEquals(1, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            assertEquals(
                id,
                v["expected"]!!.jsonPrimitive.content,
                IngredientParser.normalizeFraction(v["input"]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun categoryDisplayOrder_matchesGroceryEvents() {
        assertEquals(
            listOf("produce", "protein", "dairy", "pantry", "frozen", "other"),
            IngredientParser.CATEGORY_DISPLAY_ORDER,
        )
    }

    @Test
    fun vectorCount_matchesWebSuite() {
        // Web vitest: 11 + 2 + 1 + 1 = 15 cases.
        val total = listOf(
            "parseIngredient",
            "extractIngredientsFromRecipe",
            "parseIngredientsFromRecipe",
            "normalizeFraction",
        ).sumOf { (fixtures[it] as JsonArray).size }
        assertEquals(15, total)
    }
}
