package cooking.zap.app.repo

import cooking.zap.app.nostr.GroceryEvents.GroceryItem
import cooking.zap.app.nostr.GroceryEvents.GroceryList

/**
 * Pure list/item transforms for grocery lists — the testable core of
 * [GroceryRepository]'s mutation API (the planMutation precedent from #169:
 * decision logic extracted, hermetically tested, no Android/relay deps).
 *
 * Every function returns a NEW [GroceryList] with `updatedAt` unchanged — the
 * repository stamps `updatedAt = now` at save time, exactly once per publish,
 * so callers can batch several mutations before a single debounced save without
 * each one bumping the timestamp. Behavioral parity with web's groceryStore
 * mutators (optimistic, immutable updates).
 */
object GroceryMutations {

    fun createEmptyList(id: String, title: String, nowSeconds: Long): GroceryList = GroceryList(
        id = id,
        title = title.ifBlank { "New List" },
        items = emptyList(),
        recipeLinks = emptyList(),
        notes = null,
        createdAt = nowSeconds,
        updatedAt = nowSeconds,
    )

    fun rename(list: GroceryList, newTitle: String): GroceryList =
        list.copy(title = newTitle.ifBlank { list.title })

    /** Blank clears the note (web parity: notes is optional/undefined). */
    fun setNotes(list: GroceryList, notes: String): GroceryList =
        list.copy(notes = notes.ifBlank { null })

    fun addItem(list: GroceryList, item: GroceryItem): GroceryList =
        list.copy(items = list.items + item)

    /** Replace the item with [item.id]; no-op if absent. */
    fun editItem(list: GroceryList, item: GroceryItem): GroceryList =
        list.copy(items = list.items.map { if (it.id == item.id) item else it })

    fun setChecked(list: GroceryList, itemId: String, checked: Boolean): GroceryList =
        list.copy(items = list.items.map { if (it.id == itemId) it.copy(checked = checked) else it })

    fun removeItem(list: GroceryList, itemId: String): GroceryList =
        list.copy(items = list.items.filterNot { it.id == itemId })

    /** Web parity (groceryStore.addRecipeLink): append once, never duplicate. */
    fun addRecipeLink(list: GroceryList, recipeATag: String): GroceryList =
        if (recipeATag in list.recipeLinks) list else list.copy(recipeLinks = list.recipeLinks + recipeATag)

    /**
     * Publish-time `updatedAt` stamp: now, but STRICTLY greater than the list's
     * previous stamp. Two publishes of the same list within one second (create →
     * immediate flush) would otherwise carry identical `created_at`s, and
     * NIP-01's replaceable tie-break (lowest event id wins) lets relays keep the
     * OLDER version — observed losing a list's items in the PR 4 smoke test.
     * Monotonic stamps also keep [merge]'s newest-wins deterministic.
     */
    fun stampForPublish(list: GroceryList, nowSeconds: Long): GroceryList =
        list.copy(updatedAt = maxOf(nowSeconds, list.updatedAt + 1))

    /**
     * Merge a fetched/decrypted set into the current set, newest-`updatedAt`
     * wins per list id, sorted newest-first — web's `lists.sort((a,b) =>
     * b.updatedAt - a.updatedAt)`. A locally-dirty list (pending save, so newer
     * than any relay copy) survives because its `updatedAt` is larger.
     */
    fun merge(current: List<GroceryList>, incoming: List<GroceryList>): List<GroceryList> {
        val byId = LinkedHashMap<String, GroceryList>()
        (current + incoming).forEach { candidate ->
            val existing = byId[candidate.id]
            if (existing == null || candidate.updatedAt >= existing.updatedAt) {
                byId[candidate.id] = candidate
            }
        }
        return byId.values.sortedByDescending { it.updatedAt }
    }
}
