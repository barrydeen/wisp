package cooking.zap.app.mealplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of frontend `src/lib/mealplan/schema.test.ts`.
 *
 * Vectors are inline (matching web's current style). PR 9's web-side half
 * will extract these to shared JSON fixtures — do not invent a parallel
 * fixture format here ahead of that extraction.
 */
class SchemaTest {

    private val WEEK = "2026-W29"
    private val json = Json { prettyPrint = false }

    private fun validPayload(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("week", WEEK)
        putJsonObject("days") {
            putJsonObject("mon") {
                putJsonObject("slots") {
                    putJsonObject("breakfast") {
                        put("type", "recipe")
                        put("a", "30023:abc:shakshuka")
                        put("title", "Shakshuka")
                    }
                    putJsonObject("lunch") {
                        put("type", "text")
                        put("text", "Leftovers")
                    }
                }
                put("notes", "prep the night before")
            }
        }
        put("notes", "light week")
        put("createdAt", 1_789_000_000L)
        put("updatedAt", 1_789_000_123L)
    }

    private fun ok(raw: kotlinx.serialization.json.JsonElement?): Schema.MealPlanPayloadResult.Ok {
        val result = Schema.validateMealPlanPayload(raw, WEEK)
        return result as? Schema.MealPlanPayloadResult.Ok
            ?: fail("expected Ok, got $result").let { error("unreachable") }
    }

    @Test
    fun `accepts both tagged-union branches`() {
        val result = ok(validPayload())
        assertFalse(result.readOnly)
        val breakfast = result.plan.slot("mon", "breakfast")
        assertEquals("recipe", breakfast?.get("type")?.let { (it as JsonPrimitive).content })
        assertEquals("30023:abc:shakshuka", breakfast?.get("a")?.let { (it as JsonPrimitive).content })
        assertEquals("Shakshuka", breakfast?.get("title")?.let { (it as JsonPrimitive).content })
        val lunch = result.plan.slot("mon", "lunch")
        assertEquals("text", lunch?.get("type")?.let { (it as JsonPrimitive).content })
        assertEquals("Leftovers", lunch?.get("text")?.let { (it as JsonPrimitive).content })
        assertEquals("prep the night before", result.plan.day("mon")?.get("notes")?.let { (it as JsonPrimitive).content })
        assertEquals("light week", result.plan.notes)
        assertEquals(1_789_000_000L, result.plan.createdAt)
    }

    @Test
    fun `round-trips - encode validate serialize preserves the payload`() {
        val original = validPayload()
        val validated = ok(json.parseToJsonElement(json.encodeToString(JsonObject.serializer(), original)))
        val roundTripped = json.parseToJsonElement(Schema.serializeMealPlan(validated.plan)).jsonObject
        assertEquals(original, roundTripped)
    }

    @Test
    fun `preserves unknown fields at top, day, and slot level`() {
        // Rebuild with extras at all three levels (contract rule 8).
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("week", WEEK)
            putJsonObject("days") {
                putJsonObject("mon") {
                    putJsonObject("slots") {
                        putJsonObject("breakfast") {
                            put("type", "recipe")
                            put("a", "30023:abc:shakshuka")
                            put("title", "Shakshuka")
                            put("servings", 3)
                        }
                        putJsonObject("lunch") {
                            put("type", "text")
                            put("text", "Leftovers")
                        }
                    }
                    put("notes", "prep the night before")
                    put("dayLevelExtra", 42)
                }
            }
            put("notes", "light week")
            put("createdAt", 1_789_000_000L)
            put("updatedAt", 1_789_000_123L)
            putJsonObject("futureFeature") {
                put("some", "data")
            }
        }

        val plan = ok(payload).plan
        val serialized = json.parseToJsonElement(Schema.serializeMealPlan(plan)).jsonObject
        assertEquals(
            buildJsonObject { put("some", "data") },
            serialized["futureFeature"],
        )
        assertEquals(
            JsonPrimitive(42),
            serialized["days"]!!.jsonObject["mon"]!!.jsonObject["dayLevelExtra"],
        )
        assertEquals(
            JsonPrimitive(3),
            serialized["days"]!!.jsonObject["mon"]!!.jsonObject["slots"]!!
                .jsonObject["breakfast"]!!.jsonObject["servings"],
        )
    }

    @Test
    fun `flags schemaVersion greater than 1 as read-only without dropping content`() {
        val payload = buildJsonObject {
            for ((k, v) in validPayload()) {
                if (k == "schemaVersion") put("schemaVersion", 2) else put(k, v)
            }
        }
        val result = ok(payload)
        assertTrue(result.readOnly)
        assertEquals(2, result.plan.schemaVersion)
        assertNotNull(result.plan.slot("mon", "breakfast"))
    }

    @Test
    fun `the d-tag week wins over a mismatched payload week`() {
        val payload = buildJsonObject {
            for ((k, v) in validPayload()) {
                if (k == "week") put("week", "2020-W01") else put(k, v)
            }
        }
        assertEquals(WEEK, ok(payload).plan.week)
    }

    @Test
    fun `drops invalid slot entries but keeps the rest of the day`() {
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("week", WEEK)
            putJsonObject("days") {
                putJsonObject("mon") {
                    putJsonObject("slots") {
                        putJsonObject("breakfast") {
                            put("type", "recipe")
                            put("a", "30023:abc:shakshuka")
                            put("title", "Shakshuka")
                        }
                        putJsonObject("lunch") {
                            put("type", "text")
                            put("text", "Leftovers")
                        }
                        putJsonObject("dinner") {
                            put("type", "recipe") // missing a
                        }
                        putJsonObject("snack") {
                            put("type", "mystery")
                            put("x", 1)
                        }
                    }
                    put("notes", "prep the night before")
                }
            }
            put("notes", "light week")
            put("createdAt", 1_789_000_000L)
            put("updatedAt", 1_789_000_123L)
        }
        val plan = ok(payload).plan
        assertEquals(null, plan.slot("mon", "dinner"))
        assertEquals(null, plan.slot("mon", "snack"))
        assertNotNull(plan.slot("mon", "breakfast"))
        assertEquals(
            "prep the night before",
            plan.day("mon")?.get("notes")?.let { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `ignores unknown day keys and non-object days`() {
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("week", WEEK)
            putJsonObject("days") {
                putJsonObject("mon") {
                    putJsonObject("slots") {
                        putJsonObject("breakfast") {
                            put("type", "recipe")
                            put("a", "30023:abc:shakshuka")
                        }
                    }
                    put("notes", "prep the night before")
                }
                putJsonObject("funday") {
                    putJsonObject("slots") {}
                }
                put("tue", JsonPrimitive("not a day"))
            }
            put("notes", "light week")
            put("createdAt", 1_789_000_000L)
            put("updatedAt", 1_789_000_123L)
        }
        val plan = ok(payload).plan
        assertEquals(null, plan.days["funday"])
        assertEquals(null, plan.day("tue"))
    }

    @Test
    fun `defensively defaults missing fields`() {
        val result = ok(buildJsonObject { })
        assertFalse(result.readOnly)
        assertEquals(1, result.plan.schemaVersion)
        assertEquals(WEEK, result.plan.week)
        assertEquals(JsonObject(emptyMap()), result.plan.days)
        assertTrue(result.plan.createdAt > 0)
    }

    @Test
    fun `rejects structurally unusable payloads as DecryptFailed`() {
        val nullResult = Schema.validateMealPlanPayload(null, WEEK)
        assertTrue(nullResult is Schema.MealPlanPayloadResult.DecryptFailed)

        val stringResult = Schema.validateMealPlanPayload(JsonPrimitive("a string"), WEEK)
        assertTrue(stringResult is Schema.MealPlanPayloadResult.DecryptFailed)

        val arrayResult = Schema.validateMealPlanPayload(
            json.parseToJsonElement("[1,2]"),
            WEEK,
        )
        assertTrue(arrayResult is Schema.MealPlanPayloadResult.DecryptFailed)
    }

    @Test
    fun `malformed JSON plaintext is DecryptFailed`() {
        val result = Schema.parseMealPlanPayload("{not-json", WEEK)
        assertTrue(result is Schema.MealPlanPayloadResult.DecryptFailed)
        assertEquals(
            "Malformed meal plan payload",
            (result as Schema.MealPlanPayloadResult.DecryptFailed).error,
        )
    }

    @Test
    fun `createEmptyMealPlan creates a valid v1 skeleton`() {
        val plan = Schema.createEmptyMealPlan(WEEK)
        assertEquals(Schema.MEALPLAN_SCHEMA_VERSION, plan.schemaVersion)
        assertEquals(WEEK, plan.week)
        assertEquals(JsonObject(emptyMap()), plan.days)
        // Field order matches web createEmptyMealPlan object literal.
        assertEquals(
            listOf("schemaVersion", "week", "days", "createdAt", "updatedAt"),
            plan.json.keys.toList(),
        )
        val validated = Schema.parseMealPlanPayload(Schema.serializeMealPlan(plan), WEEK)
        assertTrue(validated is Schema.MealPlanPayloadResult.Ok)
        assertFalse((validated as Schema.MealPlanPayloadResult.Ok).readOnly)
    }
}
