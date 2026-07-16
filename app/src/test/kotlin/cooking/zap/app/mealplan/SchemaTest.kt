package cooking.zap.app.mealplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of frontend `schema.test.ts`, driven by `/fixtures/mealplan-schema.vectors.json`.
 */
class SchemaTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val fixtures = json.parseToJsonElement(
        javaClass.getResource("/fixtures/mealplan-schema.vectors.json")!!.readText(),
    ).jsonObject
    private val WEEK = fixtures["week"]!!.jsonPrimitive.content
    private val validPayload: JsonObject = fixtures["validPayload"]!!.jsonObject

    private fun resolvePayload(ref: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        if (ref is JsonPrimitive && ref.contentOrNull == "\$validPayload") return validPayload
        return ref
    }

    private fun ok(raw: kotlinx.serialization.json.JsonElement?): Schema.MealPlanPayloadResult.Ok {
        val result = Schema.validateMealPlanPayload(raw, WEEK)
        return result as? Schema.MealPlanPayloadResult.Ok
            ?: fail("expected Ok, got $result").let { error("unreachable") }
    }

    @Test
    fun fixtureCases() {
        for (el in fixtures["cases"]!!.jsonArray) {
            val c = el.jsonObject
            val id = c["id"]!!.jsonPrimitive.content
            when (c["kind"]!!.jsonPrimitive.content) {
                "validate" -> {
                    var payload = resolvePayload(c["payload"]!!).jsonObject
                    c["overrides"]?.jsonObject?.let { overrides ->
                        payload = JsonObject(payload.toMutableMap().apply { putAll(overrides) })
                    }
                    val result = ok(payload)
                    val exp = c["expected"]!!.jsonObject
                    exp["readOnly"]?.jsonPrimitive?.boolean?.let { assertEquals(id, it, result.readOnly) }
                    exp["schemaVersion"]?.jsonPrimitive?.int?.let {
                        assertEquals(id, it, result.plan.schemaVersion)
                    }
                    exp["week"]?.jsonPrimitive?.content?.let { assertEquals(id, it, result.plan.week) }
                    exp["createdAt"]?.jsonPrimitive?.long?.let { assertEquals(id, it, result.plan.createdAt) }
                    exp["dayNotes"]?.jsonPrimitive?.content?.let {
                        assertEquals(id, it, result.plan.day("mon")?.get("notes")?.jsonPrimitive?.content)
                    }
                    exp["weekNotes"]?.jsonPrimitive?.content?.let {
                        assertEquals(id, it, result.plan.notes)
                    }
                    exp["breakfast"]?.jsonObject?.let { expected ->
                        val breakfast = result.plan.slot("mon", "breakfast")!!
                        assertEquals(expected, breakfast)
                    }
                    exp["lunch"]?.jsonObject?.let { expected ->
                        assertEquals(expected, result.plan.slot("mon", "lunch"))
                    }
                    if (exp["breakfastPresent"]?.jsonPrimitive?.boolean == true) {
                        assertNotNull(id, result.plan.slot("mon", "breakfast"))
                    }
                }
                "roundTrip" -> {
                    val original = resolvePayload(c["payload"]!!).jsonObject
                    val validated = ok(json.parseToJsonElement(json.encodeToString(JsonObject.serializer(), original)))
                    val roundTripped = json.parseToJsonElement(Schema.serializeMealPlan(validated.plan)).jsonObject
                    assertEquals(id, original, roundTripped)
                }
                "unknownFields" -> {
                    val inject = c["inject"]!!.jsonObject
                    val base = resolvePayload(c["payload"]!!).jsonObject.toMutableMap()
                    inject["top"]!!.jsonObject.forEach { (k, v) -> base[k] = v }
                    val days = (base["days"] as JsonObject).toMutableMap()
                    val mon = (days["mon"] as JsonObject).toMutableMap()
                    inject["day"]!!.jsonObject.forEach { (k, v) -> mon[k] = v }
                    val slots = (mon["slots"] as JsonObject).toMutableMap()
                    val breakfast = (slots["breakfast"] as JsonObject).toMutableMap()
                    inject["slot"]!!.jsonObject.forEach { (k, v) -> breakfast[k] = v }
                    slots["breakfast"] = JsonObject(breakfast)
                    mon["slots"] = JsonObject(slots)
                    days["mon"] = JsonObject(mon)
                    base["days"] = JsonObject(days)
                    val result = ok(JsonObject(base))
                    val serialized = json.parseToJsonElement(Schema.serializeMealPlan(result.plan)).jsonObject
                    val exp = c["expected"]!!.jsonObject
                    assertEquals(exp["futureFeature"], serialized["futureFeature"])
                    assertEquals(
                        exp["dayLevelExtra"]!!.jsonPrimitive.int,
                        serialized["days"]!!.jsonObject["mon"]!!.jsonObject["dayLevelExtra"]!!.jsonPrimitive.int,
                    )
                    assertEquals(
                        exp["servings"]!!.jsonPrimitive.int,
                        serialized["days"]!!.jsonObject["mon"]!!.jsonObject["slots"]!!
                            .jsonObject["breakfast"]!!.jsonObject["servings"]!!.jsonPrimitive.int,
                    )
                }
                "dropInvalidSlots" -> {
                    val base = resolvePayload(c["payload"]!!).jsonObject.toMutableMap()
                    val days = (base["days"] as JsonObject).toMutableMap()
                    val mon = (days["mon"] as JsonObject).toMutableMap()
                    val slots = (mon["slots"] as JsonObject).toMutableMap()
                    c["injectSlots"]!!.jsonObject.forEach { (k, v) -> slots[k] = v }
                    mon["slots"] = JsonObject(slots)
                    days["mon"] = JsonObject(mon)
                    base["days"] = JsonObject(days)
                    val result = ok(JsonObject(base))
                    val exp = c["expected"]!!.jsonObject
                    if (exp["dinnerAbsent"]?.jsonPrimitive?.boolean == true) {
                        assertNull(id, result.plan.slot("mon", "dinner"))
                    }
                    if (exp["snackAbsent"]?.jsonPrimitive?.boolean == true) {
                        assertNull(id, result.plan.slot("mon", "snack"))
                    }
                    if (exp["breakfastPresent"]?.jsonPrimitive?.boolean == true) {
                        assertNotNull(id, result.plan.slot("mon", "breakfast"))
                    }
                    exp["dayNotes"]?.jsonPrimitive?.content?.let {
                        assertEquals(id, it, result.plan.day("mon")?.get("notes")?.jsonPrimitive?.content)
                    }
                }
                "ignoreUnknownDays" -> {
                    val base = resolvePayload(c["payload"]!!).jsonObject.toMutableMap()
                    val days = (base["days"] as JsonObject).toMutableMap()
                    c["injectDays"]!!.jsonObject.forEach { (k, v) -> days[k] = v }
                    base["days"] = JsonObject(days)
                    val result = ok(JsonObject(base))
                    assertNull(result.plan.day("funday"))
                    assertNull(result.plan.day("tue"))
                }
                "defaults" -> {
                    val result = ok(c["payload"]!!)
                    val exp = c["expected"]!!.jsonObject
                    assertEquals(exp["readOnly"]!!.jsonPrimitive.boolean, result.readOnly)
                    assertEquals(exp["schemaVersion"]!!.jsonPrimitive.int, result.plan.schemaVersion)
                    assertEquals(exp["week"]!!.jsonPrimitive.content, result.plan.week)
                    assertEquals(exp["days"]!!.jsonObject, result.plan.days)
                    if (exp["createdAtPositive"]?.jsonPrimitive?.boolean == true) {
                        assertTrue(result.plan.createdAt > 0)
                    }
                }
                "reject" -> {
                    // Web returns null; Android surfaces DecryptFailed (same
                    // semantic — structurally unusable, never silent empty).
                    for (p in c["payloads"]!!.jsonArray) {
                        val raw: kotlinx.serialization.json.JsonElement? =
                            if (p is JsonNull) null else p
                        val result = Schema.validateMealPlanPayload(raw, WEEK)
                        assertTrue(
                            "$id: expected DecryptFailed, got $result",
                            result is Schema.MealPlanPayloadResult.DecryptFailed,
                        )
                    }
                }
                "createEmpty" -> {
                    val plan = Schema.createEmptyMealPlan(WEEK)
                    val exp = c["expected"]!!.jsonObject
                    assertEquals(exp["schemaVersion"]!!.jsonPrimitive.int, plan.schemaVersion)
                    assertEquals(exp["week"]!!.jsonPrimitive.content, plan.week)
                    assertEquals(exp["days"]!!.jsonObject, plan.days)
                    val validated = ok(json.parseToJsonElement(Schema.serializeMealPlan(plan)))
                    assertFalse(validated.readOnly)
                }
                else -> fail("unknown kind in $id")
            }
        }
    }

    @Test
    fun parseMealPlanPayload_rejectsMalformedPlaintext() {
        val result = Schema.parseMealPlanPayload("{not valid json", WEEK)
        assertTrue(result is Schema.MealPlanPayloadResult.DecryptFailed)
    }
}
