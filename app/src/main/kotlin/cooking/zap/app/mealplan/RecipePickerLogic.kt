package cooking.zap.app.mealplan

import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList

/**
 * Pure helpers for the planner recipe picker (arc PR 8.5) — flatten/dedupe
 * Saved coordinates, client-side search, and d-tag fallback for unresolvable
 * rows. Mirrors web `RecipePickerModal` source selection; Android keeps
 * unresolvable coordinates (web drops them) so the user can still plan them.
 */
object RecipePickerLogic {

    data class PickerRow(
        val a: String,
        val title: String,
        val image: String?,
    )

    /** Unique a-coordinates across all cookbook lists, first-seen order. */
    fun uniqueCoordinates(lists: List<CookbookList>): List<String> {
        val seen = LinkedHashSet<String>()
        for (list in lists) {
            for (raw in list.coordinates) {
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) seen.add(trimmed)
            }
        }
        return seen.toList()
    }

    /** Client-side title filter (case-insensitive). Blank query → all rows. */
    fun filterRows(rows: List<PickerRow>, query: String): List<PickerRow> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        return rows.filter { it.title.contains(q, ignoreCase = true) }
    }

    /**
     * Display title when the recipe event cannot be resolved — the coordinate's
     * d-tag (never drop the row; the user may still want to plan it).
     */
    fun titleFallback(coordinate: String): String =
        CookbookCovers.parseCoordinate(coordinate)?.dTag ?: coordinate

    /** Build the contract recipe-slot entry — always attempt a title snapshot. */
    fun recipeEntry(a: String, title: String?): kotlinx.serialization.json.JsonObject {
        val snapshot = title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFallback(a)
        return PlannerMutations.recipeSlot(a, snapshot)
    }
}
