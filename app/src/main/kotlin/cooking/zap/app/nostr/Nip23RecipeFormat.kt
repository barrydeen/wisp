package cooking.zap.app.nostr

/**
 * The NIP-23 (`kind 30023`) recipe format — the only active format today.
 *
 * A **thin adapter** that delegates to the existing [RecipeParser] (read) and
 * [RecipeSerializer] (write) objects verbatim — it carries no parsing or
 * serialization logic of its own. Keeping those objects as the implementation
 * is what guarantees byte-identical output and unchanged parity tests: the
 * adapter only re-shapes their surface behind [RecipeFormat].
 */
object Nip23RecipeFormat : RecipeFormat {

    override val kind: Int = RecipeParser.RECIPE_KIND

    /** Baseline rank — a superseding format ranks above this. */
    override val formatRank: Int = 0

    override fun matches(event: NostrEvent): Boolean = RecipeParser.isRecipe(event)

    override fun parse(event: NostrEvent): RecipeParser.Recipe = RecipeParser.parse(event)

    override fun serialize(
        recipe: RecipeParser.Recipe,
        title: String,
        imageUrls: List<String>,
        categories: List<String>,
    ): UnsignedRecipeEvent = UnsignedRecipeEvent(
        kind = RecipeParser.RECIPE_KIND,
        content = RecipeSerializer.toContent(recipe),
        tags = RecipeSerializer.toTags(title, recipe.summary, imageUrls, categories),
    )

    override fun slug(title: String): String = RecipeSerializer.slug(title)

    override fun feedFilter(limit: Int): Filter = Filter(
        kinds = listOf(RecipeParser.RECIPE_KIND),
        tTags = RecipeParser.RECIPE_HASHTAGS,
        limit = limit,
    )

    override fun coordinateFilter(author: String, dTag: String): Filter = Filter(
        kinds = listOf(RecipeParser.RECIPE_KIND),
        authors = listOf(author),
        dTags = listOf(dTag),
        limit = 1,
    )
}
