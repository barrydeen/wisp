package cooking.zap.app.nostr

/**
 * Parses a zap.cooking recipe — a NIP-23 long-form event (`kind 30023`)
 * tagged `#t zapcooking` (or legacy `nostrcooking`) — into the structured
 * fields the recipe UI needs.
 *
 * The content body is a fixed Markdown shape authored by the zap.cooking
 * web editor. This is a **byte-faithful port of the frontend's lenient
 * `parseMarkdownForEditing`** (`zapcooking/frontend` `src/lib/parser.ts`):
 * the same section regexes, the same emoji field labels, the same
 * leniency. The strict `validateMarkdownTemplate` is the editor's
 * save-time guard and is intentionally NOT ported — readers must render
 * whatever real authors actually published.
 *
 * Drift confirmed against live events (Step 0, ZAPCOOKING_ANDROID_BUILD.md
 * §Phase 1) and handled here:
 *  - `published_at` tag is **optional** — absent on every live `zapcooking`
 *    event, present on legacy `nostrcooking`. [publishedAt] falls back to
 *    the event's `created_at`.
 *  - `## Details` prep/cook/servings are **all optional and free-text**
 *    (no normalized units, e.g. "10", "30min", "45 minutes + proofing").
 *    Each is a nullable raw string; absent fields stay null.
 *  - `## Directions` may contain section-header pseudo-steps
 *    (e.g. "1. Tangzhong:") — the frontend keeps these as flat steps, so
 *    do we.
 *
 * Pure JVM (regex + string ops only), so it unit-tests hermetically
 * against a real fetched event — see `RecipeParserTest`.
 */
object RecipeParser {

    /** NIP-23 long-form kind used for recipes. */
    const val RECIPE_KIND = 30023

    /**
     * Root `#t` tags that mark a long-form event as a recipe.
     * `zapcooking` is current; `nostrcooking` is the legacy tag — support
     * both (build doc §1).
     */
    val RECIPE_HASHTAGS = listOf("zapcooking", "nostrcooking")

    /** Free-text recipe timings — each optional, never assumed numeric. */
    data class RecipeDetails(
        val prepTime: String? = null,
        val cookTime: String? = null,
        val servings: String? = null,
    ) {
        val isEmpty: Boolean get() = prepTime == null && cookTime == null && servings == null
    }

    /** The structured Markdown body, mirroring the frontend `MarkdownTemplate`. */
    data class RecipeContent(
        val chefNotes: String? = null,
        val details: RecipeDetails = RecipeDetails(),
        val ingredients: List<String> = emptyList(),
        val directions: List<String> = emptyList(),
        val additionalMarkdown: String? = null,
    )

    /** A fully-resolved recipe: addressable coordinates, tags, parsed body. */
    data class Recipe(
        val id: String,
        /** Author pubkey (hex) — the `author` half of the `naddr` coordinate. */
        val author: String,
        /** Addressable `d` identifier — the `dTag` half of the coordinate. */
        val dTag: String,
        val title: String?,
        val image: String?,
        val summary: String?,
        /** `published_at` if present, else the event `created_at` (epoch seconds). */
        val publishedAt: Long,
        /** Every `#t` value on the event, verbatim. */
        val hashtags: List<String>,
        /**
         * Display categories derived from the `<root>-<category>` tag
         * convention (e.g. `zapcooking-italian` -> `italian`), excluding the
         * root tag itself and the per-recipe `<root>-<dTag>` slug tag.
         */
        val categories: List<String>,
        val content: RecipeContent,
    )

    /** True when [event] is a long-form recipe (right kind + a recipe root tag). */
    fun isRecipe(event: NostrEvent): Boolean =
        event.kind == RECIPE_KIND && tagValues(event, "t").any { it in RECIPE_HASHTAGS }

    /** Resolve a recipe event into [Recipe]. Does not validate it is a recipe. */
    fun parse(event: NostrEvent): Recipe {
        val hashtags = tagValues(event, "t")
        val dTag = firstTagValue(event, "d") ?: ""
        val publishedAt = firstTagValue(event, "published_at")?.toLongOrNull() ?: event.created_at

        return Recipe(
            id = event.id,
            author = event.pubkey,
            dTag = dTag,
            title = firstTagValue(event, "title"),
            image = firstTagValue(event, "image"),
            summary = firstTagValue(event, "summary"),
            publishedAt = publishedAt,
            hashtags = hashtags,
            categories = deriveCategories(hashtags, dTag),
            content = parseContent(event.content),
        )
    }

    // ---- Markdown body parsing (port of parseMarkdownForEditing) ----------

    private val CHEF_NOTES = section("Chef's notes")
    private val DETAILS = section("Details")
    private val INGREDIENTS = section("Ingredients")
    private val DIRECTIONS = section("Directions")
    private val ADDITIONAL = section("Additional Resources")

    // Emoji-prefixed Details fields. The live bytes are irregular: ⏲️ Prep is
    // U+23F2 + U+FE0F and 🍽️ Servings is U+1F37D + U+FE0F (variation selector
    // present), but 🍳 Cook is bare U+1F373 (no selector). Each pattern is the
    // base emoji followed by `️?` — a trailing U+FE0F made OPTIONAL — and `\s*`
    // for the gap, so this is a strict SUPERSET of the frontend's single
    // authored-glyph + single-space regex: it matches everything the editor
    // emits, and also survives a client that strips the selector or pads the
    // space. (Cook keeps an optional selector too; harmless, and symmetric.)
    private val PREP_TIME = Regex("⏲️?\\s*Prep time[:\\s]+([^\\n]+)", RegexOption.IGNORE_CASE)
    private val COOK_TIME = Regex("🍳️?\\s*Cook time[:\\s]+([^\\n]+)", RegexOption.IGNORE_CASE)
    private val SERVINGS = Regex("🍽️?\\s*Servings[:\\s]+([^\\n]+)", RegexOption.IGNORE_CASE)

    private val NUMBERED_STEP = Regex("^(\\d+)\\.\\s*(.+)$")

    fun parseContent(markdown: String): RecipeContent = RecipeContent(
        chefNotes = CHEF_NOTES.find(markdown)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        details = parseDetails(DETAILS.find(markdown)?.groupValues?.get(1)),
        ingredients = parseIngredients(INGREDIENTS.find(markdown)?.groupValues?.get(1)),
        directions = parseDirections(DIRECTIONS.find(markdown)?.groupValues?.get(1)),
        additionalMarkdown = ADDITIONAL.find(markdown)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun parseDetails(body: String?): RecipeDetails {
        if (body == null) return RecipeDetails()
        return RecipeDetails(
            prepTime = PREP_TIME.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            cookTime = COOK_TIME.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            servings = SERVINGS.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseIngredients(body: String?): List<String> {
        if (body == null) return emptyList()
        val out = mutableListOf<String>()
        for (line in body.split('\n')) {
            val t = line.trim()
            when {
                t.startsWith("- ") -> out.add(t.substring(2).trim())
                t.startsWith("* ") -> out.add(t.substring(2).trim())
                // Lenient: keep any non-empty, non-heading line (the frontend
                // pushes the whole trimmed line here, unstripped).
                t.isNotEmpty() && !t.startsWith("#") -> out.add(t)
            }
        }
        return out
    }

    private fun parseDirections(body: String?): List<String> {
        if (body == null) return emptyList()
        val out = mutableListOf<String>()
        for (line in body.split('\n')) {
            val t = line.trim()
            val numbered = NUMBERED_STEP.find(t)
            when {
                numbered != null -> out.add(numbered.groupValues[2].trim())
                t.startsWith("- ") -> out.add(t.substring(2).trim())
                // Lenient fallback for substantial unmarked lines.
                t.isNotEmpty() && !t.startsWith("#") && t.length > 10 -> out.add(t)
            }
        }
        return out
    }

    // ---- Tag helpers ------------------------------------------------------

    /**
     * `<root>-<category>` tags minus the root and the per-recipe
     * `<root>-<dTag>` slug, with the root prefix stripped for display.
     */
    private fun deriveCategories(hashtags: List<String>, dTag: String): List<String> {
        val root = hashtags.firstOrNull { it in RECIPE_HASHTAGS } ?: return emptyList()
        val slugTag = "$root-$dTag"
        val prefix = "$root-"
        return hashtags
            .filter { it != root && it != slugTag && it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }

    private fun firstTagValue(event: NostrEvent, name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    private fun tagValues(event: NostrEvent, name: String): List<String> =
        event.tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    /**
     * Section extractor matching the frontend regex
     * `/## <name>\s*\n([\s\S]*?)(?=\n## |$)/i` — captures everything from a
     * heading up to the next `## ` heading or end of input.
     */
    private fun section(name: String): Regex =
        Regex("## ${Regex.escape(name)}\\s*\\n([\\s\\S]*?)(?=\\n## |$)", RegexOption.IGNORE_CASE)
}
