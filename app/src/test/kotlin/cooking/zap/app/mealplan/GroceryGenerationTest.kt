package cooking.zap.app.mealplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GroceryGeneration] against the cross-platform contract vectors — the
 * dedupe and title cases ARE the acceptance tests for the generation port
 * (same discipline as [IngredientParserTest]).
 *
 * Vectors: `/fixtures/grocery-generation.vectors.json` (6 cases).
 */
class GroceryGenerationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixtures: JsonObject = json.parseToJsonElement(
        javaClass.getResource("/fixtures/grocery-generation.vectors.json")!!.readText(),
    ).jsonObject

    @Test
    fun collectWeekRecipeSlots_allVectors() {
        val vectors = fixtures["collectWeekRecipeSlots"]!!.jsonArray
        for (vector in vectors) {
            val v = vector.jsonObject
            val id = v["id"]!!.jsonPrimitive.contentOrNull
            // The fixture's `days` object is already schema-shaped; MealPlan is
            // a JsonObject wrapper, so no validation pass is needed.
            val plan = Schema.MealPlan(buildJsonObject { put("days", v["days"]!!) })
            val expected = v["expected"]!!.jsonObject

            val result = GroceryGeneration.collectWeekRecipeSlots(plan)

            assertEquals(
                "$id: aTags",
                expected["aTags"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
                result.aTags,
            )
            assertEquals("$id: textCount", expected["textCount"]!!.jsonPrimitive.int, result.textCount)
            assertEquals(
                "$id: recipeSlotCount",
                expected["recipeSlotCount"]!!.jsonPrimitive.int,
                result.recipeSlotCount,
            )
        }
    }

    @Test
    fun dedupeIngredients_allVectors() {
        val vectors = fixtures["dedupeIngredients"]!!.jsonArray
        for (vector in vectors) {
            val v = vector.jsonObject
            val id = v["id"]!!.jsonPrimitive.contentOrNull
            val rows = v["rows"]!!.jsonArray.map { raw ->
                val row = raw.jsonObject
                GroceryGeneration.GenerationRow(
                    ingredient = IngredientParser.ParsedIngredient(
                        name = row["name"]!!.jsonPrimitive.contentOrNull.orEmpty(),
                        quantity = row["quantity"]!!.jsonPrimitive.contentOrNull.orEmpty(),
                        category = "other",
                        originalText = "",
                    ),
                    recipeId = row["recipeId"]!!.jsonPrimitive.contentOrNull.orEmpty(),
                )
            }

            val deduped = GroceryGeneration.dedupeIngredients(rows)

            assertEquals("$id: length", v["expectedLength"]!!.jsonPrimitive.int, deduped.size)
            v["expectedFirstRecipeId"]?.jsonPrimitive?.contentOrNull?.let { expectedFirst ->
                assertEquals("$id: first recipeId", expectedFirst, deduped.first().recipeId)
            }
        }
    }

    @Test
    fun groceryListTitle_allVectors() {
        val vectors = fixtures["groceryListTitle"]!!.jsonArray
        for (vector in vectors) {
            val v = vector.jsonObject
            val id = v["id"]!!.jsonPrimitive.contentOrNull
            assertEquals(
                "$id: title",
                v["expected"]!!.jsonPrimitive.contentOrNull,
                GroceryGeneration.groceryListTitle(v["weekId"]!!.jsonPrimitive.contentOrNull!!),
            )
        }
    }

    @Test
    fun buildRows_ordersByResolvedCoordinateAndTagsSource() {
        val rows = GroceryGeneration.buildRows(
            resolvedATags = listOf("30023:pk:curry", "30023:pk:salad"),
            linesByATag = mapOf(
                "30023:pk:curry" to listOf("1 cup rice", "2 tbsp olive oil"),
                "30023:pk:salad" to listOf("2 tbsp olive oil"),
                "30023:pk:unlisted" to listOf("never appears"),
            ),
        )
        assertEquals(listOf("rice", "olive oil", "olive oil"), rows.map { it.ingredient.name })
        assertEquals(
            listOf("30023:pk:curry", "30023:pk:curry", "30023:pk:salad"),
            rows.map { it.recipeId },
        )
        // The fixture-pinned dedupe collapses the exact repeat, first source wins.
        val deduped = GroceryGeneration.dedupeIngredients(rows)
        assertEquals(2, deduped.size)
        assertEquals("30023:pk:curry", deduped.last().recipeId)
    }

    @Test
    fun vectorCount_matchesWebSuite() {
        // Web vitest groceryGeneration.test.ts: 2 + 3 + 1 = 6 cases.
        val total = listOf(
            "collectWeekRecipeSlots",
            "dedupeIngredients",
            "groceryListTitle",
        ).sumOf { (fixtures[it] as JsonArray).size }
        assertEquals(6, total)
    }
}
