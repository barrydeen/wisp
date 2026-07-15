package cooking.zap.app.mealplan

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure MealPlan transforms — optimistic immutable updates that preserve
 * unknown fields at every level (contract rule 8). [updatedAt] is NOT bumped
 * on each mutation; [stampForPublish] stamps it once per publish (grocery
 * [cooking.zap.app.repo.GroceryMutations.stampForPublish] precedent), and
 * [MealPlanEvents.createPlanEvent] pins the Nostr event `created_at` to that
 * stamp so replaceable supersession follows the monotonic clock.
 */
object PlannerMutations {

    fun recipeSlot(a: String, title: String? = null): JsonObject = buildJsonObject {
        put("type", "recipe")
        put("a", a)
        if (title != null) put("title", title)
    }

    fun textSlot(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun setSlot(
        plan: Schema.MealPlan,
        dayKey: String,
        slotKey: String,
        entry: JsonObject,
    ): Schema.MealPlan = withDay(plan, dayKey) { day ->
        val slots = linkedMapOf<String, JsonElement>()
        (day["slots"] as? JsonObject)?.forEach { (k, v) -> slots[k] = v }
        slots[slotKey] = entry
        day["slots"] = JsonObject(slots)
    }

    fun clearSlot(
        plan: Schema.MealPlan,
        dayKey: String,
        slotKey: String,
    ): Schema.MealPlan = withDay(plan, dayKey) { day ->
        val slots = linkedMapOf<String, JsonElement>()
        (day["slots"] as? JsonObject)?.forEach { (k, v) -> slots[k] = v }
        slots.remove(slotKey)
        if (slots.isEmpty()) day.remove("slots") else day["slots"] = JsonObject(slots)
    }

    fun setDayNotes(plan: Schema.MealPlan, dayKey: String, notes: String): Schema.MealPlan =
        withDay(plan, dayKey) { day ->
            if (notes.isNotEmpty()) day["notes"] = JsonPrimitive(notes)
            else day.remove("notes")
        }

    fun setWeekNotes(plan: Schema.MealPlan, notes: String): Schema.MealPlan {
        val root = linkedMapOf<String, JsonElement>()
        plan.json.forEach { (k, v) -> root[k] = v }
        if (notes.isNotEmpty()) root["notes"] = JsonPrimitive(notes)
        else root.remove("notes")
        return Schema.MealPlan(JsonObject(root))
    }

    /**
     * Monotonic `updatedAt` stamp at publish time (preserves `createdAt`).
     * Same-second republishes (debounce flush + immediate save) would otherwise
     * share a wall-clock `created_at` on the Nostr event; NIP-01's lowest-id
     * tie-break can then keep the OLDER version. Deterministic weekly d-tags
     * make this MORE likely than grocery — treat as release-blocking.
     */
    fun stampForPublish(plan: Schema.MealPlan, nowSeconds: Long): Schema.MealPlan {
        val root = linkedMapOf<String, JsonElement>()
        plan.json.forEach { (k, v) -> root[k] = v }
        val created = plan.createdAt.takeIf { it > 0 } ?: nowSeconds
        val updated = maxOf(nowSeconds, plan.updatedAt + 1)
        root["createdAt"] = JsonPrimitive(created)
        root["updatedAt"] = JsonPrimitive(updated)
        return Schema.MealPlan(JsonObject(root))
    }

    private fun withDay(
        plan: Schema.MealPlan,
        dayKey: String,
        mutate: (MutableMap<String, JsonElement>) -> Unit,
    ): Schema.MealPlan {
        val day = linkedMapOf<String, JsonElement>()
        plan.day(dayKey)?.forEach { (k, v) -> day[k] = v }
        mutate(day)
        val days = linkedMapOf<String, JsonElement>()
        plan.days.forEach { (k, v) -> days[k] = v }
        days[dayKey] = JsonObject(day)
        val root = linkedMapOf<String, JsonElement>()
        plan.json.forEach { (k, v) -> root[k] = v }
        root["days"] = JsonObject(days)
        return Schema.MealPlan(JsonObject(root))
    }
}
