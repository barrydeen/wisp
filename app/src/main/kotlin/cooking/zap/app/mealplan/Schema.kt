package cooking.zap.app.mealplan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Meal-plan payload schema (schemaVersion 1) — the decrypted JSON
 * contract shared verbatim with web. See frontend `docs/mealplan-contract.md`;
 * that document is frozen and this module implements it.
 *
 * Manual [JsonObject]-level parse/rebuild (not kotlinx data-class decode):
 * invalid slot entries are dropped, unknown fields are PRESERVED on
 * round-trip at every level (plan / day / slot — contract rule 8; same
 * carry-forward spirit as [cooking.zap.app.repo.RecipeBookmarkRepository]
 * `buildListTags`'s `else` branch), and schemaVersion > 1 flags the plan
 * read-only rather than failing.
 *
 * Port of frontend `src/lib/mealplan/schema.ts`.
 */
object Schema {
    const val MEALPLAN_SCHEMA_VERSION = 1

    val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val SLOT_KEYS = listOf("breakfast", "lunch", "dinner", "snack")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    /**
     * A validated meal-plan payload. [json] is the rebuilt object with
     * unknowns preserved — [serializeMealPlan] emits this verbatim so field
     * order and extras survive round-trip (matching web `JSON.stringify`).
     */
    data class MealPlan(val json: JsonObject) {
        val schemaVersion: Int
            get() = json["schemaVersion"]?.jsonPrimitive?.intOrNull ?: MEALPLAN_SCHEMA_VERSION
        val week: String
            get() = json["week"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val days: JsonObject
            get() = json["days"]?.jsonObject ?: JsonObject(emptyMap())
        val notes: String?
            get() = json["notes"]?.jsonPrimitive?.contentOrNull
        val createdAt: Long
            get() = json["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val updatedAt: Long
            get() = json["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L

        fun day(key: String): JsonObject? = days[key]?.jsonObject

        fun slot(dayKey: String, slotKey: String): JsonObject? =
            day(dayKey)?.get("slots")?.jsonObject?.get(slotKey)?.jsonObject
    }

    /**
     * Outcome of parsing a decrypted meal-plan payload.
     *
     * PR 7's fetch/decrypt path consumes this and maps crypto failures onto
     * the same [DecryptFailed] branch — never silent "no plan" (contract:
     * decrypt failures are not empty weeks).
     */
    sealed class MealPlanPayloadResult {
        data class Ok(val plan: MealPlan, val readOnly: Boolean) : MealPlanPayloadResult()

        /**
         * Structurally unusable payload (or, once PR 7 wires crypto, a decrypt
         * denial). Distinct from an empty week.
         */
        data class DecryptFailed(val error: String) : MealPlanPayloadResult()
    }

    /**
     * Validate a decrypted payload against schemaVersion 1.
     *
     * [DecryptFailed] only when the payload is structurally unusable (not an
     * object). Unknown top-level fields are preserved; [weekId] (from the
     * d-tag, which wins per contract rule 2) backfills a missing/mismatched
     * `week`.
     */
    fun validateMealPlanPayload(raw: JsonElement?, weekId: String): MealPlanPayloadResult {
        val obj = raw as? JsonObject
            ?: return MealPlanPayloadResult.DecryptFailed("Malformed meal plan payload")

        val schemaVersion = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: MEALPLAN_SCHEMA_VERSION
        val readOnly = schemaVersion > MEALPLAN_SCHEMA_VERSION

        val nowSeconds = System.currentTimeMillis() / 1000
        val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: nowSeconds
        val updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: nowSeconds

        // Rebuild like web's `{ ...raw, schemaVersion, week, days: {}, createdAt, updatedAt }`:
        // copy raw keys in order, then overwrite known fields (existing keys keep
        // position; missing known keys append — mirrors JS object spread).
        val rebuilt = linkedMapOf<String, JsonElement>()
        for ((k, v) in obj) {
            rebuilt[k] = v
        }
        rebuilt["schemaVersion"] = JsonPrimitive(schemaVersion)
        rebuilt["week"] = JsonPrimitive(weekId)
        rebuilt["days"] = JsonObject(emptyMap())
        rebuilt["createdAt"] = JsonPrimitive(createdAt)
        rebuilt["updatedAt"] = JsonPrimitive(updatedAt)

        // Web: `typeof notes !== 'string'` → delete. Keep only JSON strings.
        if (rebuilt["notes"] != null && !isJsonString(rebuilt["notes"])) {
            rebuilt.remove("notes")
        }

        val daysOut = linkedMapOf<String, JsonElement>()
        val rawDays = obj["days"] as? JsonObject
        if (rawDays != null) {
            for (key in DAY_KEYS) {
                val day = validateDay(rawDays[key]) ?: continue
                daysOut[key] = day
            }
        }
        rebuilt["days"] = JsonObject(daysOut)

        return MealPlanPayloadResult.Ok(MealPlan(JsonObject(rebuilt)), readOnly)
    }

    /**
     * Parse plaintext JSON then validate. Malformed JSON → [MealPlanPayloadResult.DecryptFailed]
     * (defensive path for PR 7's decrypt→parse pipeline).
     */
    fun parseMealPlanPayload(plaintext: String, weekId: String): MealPlanPayloadResult {
        val element = try {
            json.parseToJsonElement(plaintext)
        } catch (_: Exception) {
            return MealPlanPayloadResult.DecryptFailed("Malformed meal plan payload")
        }
        return validateMealPlanPayload(element, weekId)
    }

    fun createEmptyMealPlan(weekId: String): MealPlan {
        val now = System.currentTimeMillis() / 1000
        // Field order matches web createEmptyMealPlan object literal.
        return MealPlan(
            buildJsonObject {
                put("schemaVersion", MEALPLAN_SCHEMA_VERSION)
                put("week", weekId)
                put("days", buildJsonObject { })
                put("createdAt", now)
                put("updatedAt", now)
            }
        )
    }

    /** Serialize for encryption — plain JSON keeps unknown fields intact. */
    fun serializeMealPlan(plan: MealPlan): String =
        json.encodeToString(JsonObject.serializer(), plan.json)

    // ---- internals ----------------------------------------------------------

    /** Validate one slot entry; null drops it (contract rule 9). */
    private fun validateSlot(raw: JsonElement?): JsonObject? {
        val obj = raw as? JsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        when (type) {
            "recipe" -> {
                val a = obj["a"]?.jsonPrimitive?.contentOrNull
                if (a.isNullOrEmpty()) return null
                // Preserve the whole object (unknown slot fields survive).
                return obj
            }
            "text" -> {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                if (text.isNullOrEmpty()) return null
                return obj
            }
            else -> return null
        }
    }

    private fun validateDay(raw: JsonElement?): JsonObject? {
        val obj = raw as? JsonObject ?: return null
        // Preserve unknown day-level fields; rebuild only `slots` with invalid
        // entries dropped — carry-forward idiom (contract rule 8).
        val day = linkedMapOf<String, JsonElement>()
        for ((k, v) in obj) {
            day[k] = v
        }
        val rawSlots = obj["slots"]
        if (rawSlots != null) {
            val slotsObj = rawSlots as? JsonObject
            if (slotsObj == null) {
                day.remove("slots")
            } else {
                val slots = linkedMapOf<String, JsonElement>()
                for (key in SLOT_KEYS) {
                    val slot = validateSlot(slotsObj[key]) ?: continue
                    slots[key] = slot
                }
                day["slots"] = JsonObject(slots)
            }
        }
        if (day["notes"] != null && !isJsonString(day["notes"])) {
            day.remove("notes")
        }
        return JsonObject(day)
    }

    private fun isJsonString(el: JsonElement?): Boolean =
        el is JsonPrimitive && el.isString
}
