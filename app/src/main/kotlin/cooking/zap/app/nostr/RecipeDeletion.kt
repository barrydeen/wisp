package cooking.zap.app.nostr

/**
 * Tag builders for deleting a published recipe — a byte-faithful port of the
 * web `Recipe.svelte` `handleDelete`, which publishes **two** events:
 *
 *  1. a **blanked replacement** at the same address (same kind, same `d`, empty
 *     content, `deleted`/`title` tags) — the half that actually removes the
 *     recipe, because an addressable event is superseded by a newer one at the
 *     same coordinate on every NIP-01 relay whether or not it honors NIP-09;
 *  2. a **kind-5 deletion request** (`e` + `a` + `k`) for clients and relays
 *     that do honor NIP-09.
 *
 * Both are needed: relay support for kind 5 is uneven, and the replacement is
 * what makes the recipe stop resolving.
 *
 * **Where the replacement is invisible for free, and where it is not.** Every
 * *recipe* surface gates on [RecipeParser.isRecipe] / [RecipeFormats.forEvent]
 * (`RecipeRepository`'s collectors, the profile Recipes tab), and the blanked
 * event fails that gate on **both** of its independent halves: no recipe `#t`
 * tag, and blank content that can't satisfy the template check. So no recipe
 * list can show a "[Deleted]" card, and that survives a change to either half.
 *
 * The *article* surfaces do not ask. `FeedScreen` and `UserProfileScreen` route
 * on `kind == 30023` alone, so without a guard the blanked replacement would
 * arrive from a relay and render as an article card titled "[Deleted]" in the
 * home feed of everyone who follows the author — a ghost the delete itself
 * creates. [isBlankedReplacement] is that guard, applied where the feed is
 * built (`EventRepository.addEvent`).
 *
 * **Kind is read off the event, never hardcoded.** Deriving it (or the `d` tag)
 * from anything but the event being deleted is how a tombstone lands at a
 * different address than the recipe it was meant to remove.
 *
 * Tag *order* follows this repo's house style ([Nip09.buildAddressableDeletionTags]
 * then `e`, as in `GroceryEvents`/`MealPlanEvents`) rather than the web's `e`,
 * `a`, `k` — the tag set is identical and order carries no meaning in NIP-09.
 */
object RecipeDeletion {

    /** Title tag value on the blanked replacement (web parity). */
    const val TOMBSTONE_TITLE = "[Deleted]"

    /** Content of the kind-5 deletion request (web parity). */
    const val DELETION_REQUEST_CONTENT = "Recipe deleted by author"

    /** Content of the blanked replacement — empty, one of the two halves it fails
     *  [RecipeParser.isRecipe] on (the other is the dropped recipe `#t` tag). */
    const val TOMBSTONE_CONTENT = ""

    /**
     * Tags for the blanked replacement of [event]. The `d` tag is carried over
     * verbatim so the replacement lands at the *same* coordinate; it is omitted
     * when the original has no (or a blank) `d`, in which case both events
     * already address `kind:pubkey:` — web parity.
     */
    fun blankedReplacementTags(event: NostrEvent): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        dTagOf(event)?.let { tags.add(listOf("d", it)) }
        tags.add(listOf("deleted", "true"))
        tags.add(listOf("title", TOMBSTONE_TITLE))
        return tags
    }

    /**
     * Tags for the kind-5 deletion request targeting [event] — the addressable
     * coordinate (`a`) plus its kind (`k`) and the concrete event id (`e`), so
     * a relay that only indexes one of the two still resolves the target. The
     * `a` tag is omitted when the original has no `d` (nothing well-formed to
     * point at); `e` still identifies it.
     */
    fun deletionRequestTags(event: NostrEvent): List<List<String>> {
        val dTag = dTagOf(event)
        val tags = if (dTag != null) {
            Nip09.buildAddressableDeletionTags(event.kind, event.pubkey, dTag).toMutableList()
        } else {
            mutableListOf(listOf("k", event.kind.toString()))
        }
        tags.add(listOf("e", event.id))
        return tags
    }

    /**
     * The created_at both deletion events must carry: **strictly newer** than
     * the recipe being deleted. NIP-09 only voids events with
     * `created_at <= deletion.created_at`, and a replacement only supersedes an
     * older one — so a device clock behind the recipe's own stamp would
     * otherwise publish a tombstone that does nothing.
     *
     * Note the ceiling this cannot reach around: an event dated more than 30s
     * ahead of this device is dropped by `EventRepository.addEvent`'s
     * future-date guard (and by most relays), so a recipe stamped far in the
     * future stays undeletable from here.
     */
    fun deletionTimestamp(event: NostrEvent, now: Long = System.currentTimeMillis() / 1000): Long =
        maxOf(now, event.created_at + 1)

    /**
     * True when [event] is a blanked replacement — an addressable event the
     * author has emptied to delete it, marked `["deleted","true"]`. It is a
     * tombstone, not content: cache it (so an addressable lookup resolves to
     * the tombstone rather than a stale copy) but never render it.
     *
     * The marker is the web's (`Recipe.svelte` `handleDelete` writes it, and
     * `r/[naddr]` reads it), and this repo already treats the same tag the same
     * way for wallet backups ([Nip78.isDeletedBackup]).
     */
    fun isBlankedReplacement(event: NostrEvent): Boolean =
        event.tags.any { it.size >= 2 && it[0] == "deleted" && it[1] == "true" }

    /** [event]'s `d` tag value, or null when absent or blank. */
    fun dTagOf(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.takeIf { it.isNotBlank() }
}
