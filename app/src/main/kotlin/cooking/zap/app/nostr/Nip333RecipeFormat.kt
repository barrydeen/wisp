package cooking.zap.app.nostr

/**
 * **STUB — not implemented, not registered.** A placeholder for a future
 * dedicated recipe NIP (e.g. a `kind 333xx`), kept as real code so the
 * [RecipeFormat] contract stays honest: if the interface changes, this file
 * breaks the build and must be updated in lockstep — a doc comment would
 * silently rot instead.
 *
 * **Guard:** this object is referenced **only as a type**. It is NOT in
 * [RecipeFormats.active], NOT `primary`, and must never be added to any runtime
 * list or test that iterates formats — so none of the `TODO()` bodies below can
 * ever execute. Activating it is a future concern: implement these members,
 * give it a [formatRank] above [Nip23RecipeFormat], and register it in
 * [RecipeFormats.active]. See `ZAPCOOKING_ANDROID_BUILD.md` for the dual-write
 * edit-sync caveat before turning it on.
 */
@Suppress("unused")
object Nip333RecipeFormat : RecipeFormat {

    override val kind: Int
        get() = TODO("Assign the dedicated recipe kind when this NIP is adopted.")

    override val formatRank: Int
        get() = TODO("Rank above Nip23RecipeFormat (0) so it wins the cross-format pick.")

    override fun matches(event: NostrEvent): Boolean =
        TODO("Recognize this format's events (kind + any tag qualifier).")

    override fun parse(event: NostrEvent): RecipeParser.Recipe =
        TODO("Decode this format into the shared RecipeParser.Recipe model.")

    override fun serialize(
        recipe: RecipeParser.Recipe,
        title: String,
        imageUrls: List<String>,
        categories: List<String>,
    ): UnsignedRecipeEvent =
        TODO("Encode RecipeParser.Recipe into this format's unsigned event.")

    override fun slug(title: String): String =
        TODO("Derive this format's addressable identifier from the title.")

    override fun feedFilter(limit: Int): Filter =
        TODO("Relay filter for this format's recipe feed.")

    override fun coordinateFilter(author: String, dTag: String): Filter =
        TODO("Relay filter to resolve a single recipe by coordinate.")
}
