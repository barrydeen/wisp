package cooking.zap.app.nostr

/**
 * Serializes a [RecipeParser.Recipe] into a kind-30023 recipe body + tags —
 * the inverse of [RecipeParser], for publishing recipes (concern 2.2). Mirrors
 * the zap.cooking web create flow byte-for-byte (`createMarkdown` +
 * `create/+page.svelte` tag-building) so app-authored recipes round-trip
 * against web-authored ones.
 *
 * Pure (string ops only) — round-trip unit-tested (serialize → [RecipeParser]
 * → equals).
 */
object RecipeSerializer {

    /** New recipes always carry the current root tag (never legacy nostrcooking). */
    private val ROOT = RecipeParser.RECIPE_HASHTAGS.first() // "zapcooking"

    /**
     * The web's exact d-tag/slug: `title.toLowerCase().replaceAll(' ', '-')` —
     * **spaces only**. Parens/slashes are deliberately kept (e.g.
     * `tuscan-peposo-(black-pepper-beef-stew)`); the recipe route already
     * URL-encodes them. Do not "clean up" further or recipes won't round-trip
     * against web-authored ones. Same title → same d-tag (replaces) — web
     * behavior; mirrored, not de-duplicated.
     */
    fun slug(title: String): String =
        // Locale.ROOT, not the device locale: JS `toLowerCase()` is locale-
        // independent, and a Turkish-locale dotless-i would break web parity
        // and the "same title → same d-tag" guarantee.
        title.lowercase(java.util.Locale.ROOT).replace(" ", "-")

    /** Structured body → `##`-section markdown (mirrors web `createMarkdown`). */
    fun toContent(recipe: RecipeParser.Recipe): String {
        val c = recipe.content
        return buildString {
            c.chefNotes?.takeIf { it.isNotBlank() }?.let { append("\n## Chef's notes\n\n$it\n") }

            val d = c.details
            if (d.prepTime != null || d.cookTime != null || d.servings != null) {
                append("\n## Details\n\n")
                d.prepTime?.let { append("- ⏲️ Prep time: $it\n") }
                d.cookTime?.let { append("- 🍳 Cook time: $it\n") }
                d.servings?.let { append("- 🍽️ Servings: $it\n") }
            }

            if (c.ingredients.isNotEmpty()) {
                append("\n## Ingredients\n\n")
                c.ingredients.forEach { append("- $it\n") }
            }

            if (c.directions.isNotEmpty()) {
                append("\n\n## Directions\n\n")
                c.directions.forEachIndexed { i, step -> append("${i + 1}. $step\n") }
            }

            c.additionalMarkdown?.takeIf { it.isNotBlank() }
                ?.let { append("\n\n## Additional Resources\n\n$it\n\n") }
        }
    }

    /**
     * kind-30023 tags for a recipe (mirrors the web create flow):
     * `d`=identifier, `title`, `t:zapcooking`, `t:zapcooking-<identifier>`,
     * `summary` (if set), one `image` per URL, and
     * `t:zapcooking-<category-slug>` per category. The `client` tag is added at
     * publish, not here.
     *
     * [identifier] and [publishedAt] are both **null on create** — the emitted
     * tags are then byte-identical to the web's, `published_at` is omitted
     * exactly as the web omits it, and `d` is `slug(title)`. Both are set on
     * **edit**: `d` must keep the original event's identifier (a re-derived one
     * would publish a second recipe instead of replacing the first), and
     * `published_at` must be written back from the original or the parser's
     * `created_at` fallback re-dates the recipe to the edit time. The asymmetry
     * is the design, not a compromise — create's output does not change.
     *
     * The per-recipe self-tag is derived from the **identifier**, never from
     * `title`, and that is load-bearing: [RecipeParser.deriveCategories]
     * excludes exactly `"<root>-<dTag>"`. Derive it from a retitled recipe's new
     * title and the two stop matching, so the self-tag survives that filter and
     * renders as a category chip named after the recipe itself, on the recipe
     * screen and in every category feed.
     */
    fun toTags(
        title: String,
        summary: String?,
        imageUrls: List<String>,
        categories: List<String>,
        identifier: String? = null,
        publishedAt: Long? = null,
    ): List<List<String>> = buildList {
        // One source of truth for the address: `d` and the self-tag below both
        // read this, so they cannot drift apart at a call site.
        //
        // Note what the blank branch means: a BLANK [identifier] is create
        // semantics, silently. So [RecipeFormat.serializeEdit]'s "the address is
        // preserved, never re-derived from title" is a guarantee about its
        // CALLERS, not something enforced here. It holds today because the only
        // edit path resolves the original by a non-blank `d`
        // ([RecipeParser.dTag]), which is why there is no guard on a state that
        // cannot currently occur. It stops holding the day a format whose
        // identifier is not slugged from the title reaches `serializeEdit` —
        // that format must pass its own identifier, not rely on this fallback.
        val id = identifier?.takeIf { it.isNotBlank() } ?: slug(title)
        add(listOf("d", id))
        add(listOf("title", title))
        add(listOf("t", ROOT))
        add(listOf("t", "$ROOT-$id"))
        publishedAt?.let { add(listOf("published_at", it.toString())) }
        if (!summary.isNullOrBlank()) add(listOf("summary", summary))
        imageUrls.forEach { add(listOf("image", it)) }
        // Categories are raw display names → `zapcooking-<slug>`, same slug fn.
        categories.filter { it.isNotBlank() }.forEach { add(listOf("t", "$ROOT-${slug(it)}")) }
    }

    /**
     * Tag names [toTags] writes and therefore owns outright on an edit. `client`
     * is here but is **not** written by [toTags]: the publisher adds it per the
     * member's NIP-89 preference, so carrying the original's forward would both
     * duplicate it and override a preference since turned off.
     */
    private val OWNED_TAG_NAMES = setOf("d", "title", "summary", "image", "published_at", "client")

    /**
     * True when a `#t` value is one [toTags] generates — the root itself or any
     * `<root>-…` value (the per-recipe self-tag and the category tags).
     *
     * Scoped deliberately: `hashtags` on the model is *every* `t` value, but
     * [RecipeParser.deriveCategories] only reads the `<root>-` prefixed ones, so
     * a plain `t: vegan` is parsed and then not represented in `categories`.
     * Treating all of `t` as owned would delete that tag on the first edit —
     * the same lossy-model deletion one tag over.
     */
    private fun isGeneratedHashtag(value: String): Boolean =
        value in RecipeParser.RECIPE_HASHTAGS ||
            RecipeParser.RECIPE_HASHTAGS.any { value.startsWith("$it-") }

    /**
     * Edit tag set: [newTags] (from [toTags]) plus every tag on the original
     * event that this serializer does not own.
     *
     * The rule this encodes: **a re-serialize from a model deletes everything
     * the model does not know.** The compose form models title, summary, cover
     * photos, categories and the identifier; a real recipe event can carry
     * anything else — a `gated` marker, an `a`/`e` reference, a plain
     * non-category `#t`, a tag from a client that does not exist yet. Rebuilding
     * tags from scratch on an edit drops all of it silently, and "silently" is
     * the part that makes it a defect rather than a limitation.
     *
     * Preserved tags keep their original relative order and precede the
     * regenerated ones. Order is not semantic in NIP-01, and no reader here
     * depends on it (`firstTagValue` only ever sees one of each owned name).
     */
    fun mergeForEdit(
        originalTags: List<List<String>>,
        newTags: List<List<String>>,
    ): List<List<String>> {
        val preserved = originalTags.filter { tag ->
            val name = tag.firstOrNull()
            when {
                name == null -> false
                name in OWNED_TAG_NAMES -> false
                name == "t" -> !isGeneratedHashtag(tag.getOrNull(1) ?: "")
                else -> true
            }
        }
        return preserved + newTags
    }
}
