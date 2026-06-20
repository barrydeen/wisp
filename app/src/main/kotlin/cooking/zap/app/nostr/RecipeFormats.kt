package cooking.zap.app.nostr

/**
 * Registry of the recipe formats the app understands. **The single extension
 * point**: adding a second format is a one-line edit to [active] (plus a new
 * [RecipeFormat] implementation) — no changes to the screens, feeds, compose
 * form, or domain model.
 *
 * Today only [Nip23RecipeFormat] is registered, so every read/write resolves to
 * NIP-23 and behavior is identical to before the seam existed.
 *
 * The future second format would be `Nip333RecipeFormat` — its skeleton exists
 * (compile-checked against [RecipeFormat]) but is deliberately **not** in
 * [active] and never iterated at runtime, so its `TODO()` bodies can't fire.
 * When it's ready: implement it and add it here with a higher
 * [RecipeFormat.formatRank].
 */
object RecipeFormats {

    /** Formats consulted on read, in priority order. Iterated at runtime. */
    val active: List<RecipeFormat> = listOf(Nip23RecipeFormat)

    /** The format used to author new recipes. */
    val primary: RecipeFormat = Nip23RecipeFormat

    /** The first active format that recognizes [event], or null if none does. */
    fun forEvent(event: NostrEvent): RecipeFormat? = active.firstOrNull { it.matches(event) }

    /** [event]'s active-format rank (for [dedupeAcrossFormats]), or null if no format owns it. */
    fun rankOf(event: NostrEvent): Int? = forEvent(event)?.formatRank
}
