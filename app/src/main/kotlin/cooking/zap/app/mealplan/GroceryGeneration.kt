package cooking.zap.app.mealplan

import java.util.Locale
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Grocery generation from a week's meal plan — port of frontend
 * `src/lib/mealplan/groceryGeneration.ts` (Phase 3.3). **Web wins**: the
 * dedupe and title behavior is the cross-platform contract pinned by
 * `grocery-generation.vectors.json`; change the fixtures upstream and move
 * both platforms together.
 *
 * Pure helpers only — coordinate resolution (cache/relay I/O) and the
 * grocery-store writes stay with the caller, exactly like web keeps them in
 * the planner page. Approved v1 behavior: dedupe-without-merge (collapse
 * EXACT (name, quantity) duplicates, first occurrence's recipeId wins; no
 * quantity summing — that's v1.1), text slots skipped and reported.
 */
object GroceryGeneration {

    data class WeekRecipeSlots(
        /** Unique recipe coordinates in day/slot order. */
        val aTags: List<String>,
        /** Number of text entries (skipped by generation). */
        val textCount: Int,
        /** Total recipe slots including repeats of the same coordinate. */
        val recipeSlotCount: Int,
    )

    /** Walk the plan's days/slots and collect what generation operates on. */
    fun collectWeekRecipeSlots(plan: Schema.MealPlan): WeekRecipeSlots {
        val seen = LinkedHashSet<String>()
        var textCount = 0
        var recipeSlotCount = 0

        for (day in Schema.DAY_KEYS) {
            for (slotKey in Schema.SLOT_KEYS) {
                val entry = plan.slot(day, slotKey) ?: continue
                when (entry["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> textCount++
                    "recipe" -> {
                        recipeSlotCount++
                        // Schema validation guarantees a non-empty `a`; the
                        // guard mirrors web's shape rather than re-validating.
                        entry["a"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { seen.add(it) }
                    }
                }
            }
        }

        return WeekRecipeSlots(seen.toList(), textCount, recipeSlotCount)
    }

    data class GenerationRow(
        val ingredient: IngredientParser.ParsedIngredient,
        /** Source recipe coordinate (becomes GroceryItem.recipeId). */
        val recipeId: String,
    )

    /**
     * Approved v1 dedupe: collapse rows whose (name, quantity) match EXACTLY
     * (case-insensitive name, verbatim quantity). No unit normalization, no
     * quantity math. First occurrence wins, keeping its source recipeId.
     */
    fun dedupeIngredients(rows: List<GenerationRow>): List<GenerationRow> {
        val seen = HashSet<String>()
        val out = ArrayList<GenerationRow>(rows.size)
        for (row in rows) {
            // Locale.ROOT: JVM default locale can change case-folding (e.g. Turkish
            // İ/i) and break cross-device / web-parity dedupe keys.
            val key = "${row.ingredient.name.trim().lowercase(Locale.ROOT)}|${row.ingredient.quantity.trim()}"
            if (!seen.add(key)) continue
            out.add(row)
        }
        return out
    }

    /** "Groceries — Week 29 (Jul 13–19)" — reuses the PR 7 display helper. */
    fun groceryListTitle(weekId: String): String =
        "Groceries — ${Week.weekDisplayRange(weekId)}"

    /**
     * Web's parse loop (planner page): one row per resolved ingredient line,
     * in resolved-coordinate order, each carrying its source coordinate.
     */
    fun buildRows(
        resolvedATags: List<String>,
        linesByATag: Map<String, List<String>>,
    ): List<GenerationRow> = resolvedATags.flatMap { aTag ->
        (linesByATag[aTag] ?: emptyList()).map { line ->
            GenerationRow(IngredientParser.parseIngredient(line), aTag)
        }
    }
}
