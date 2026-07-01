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

    /**
     * True when [event] is a long-form recipe. A NIP-23 `kind 30023` is a
     * recipe ONLY IF it carries a recipe root `#t` tag AND its content has the
     * recipe-template shape ([isRecipeContent]).
     *
     * The tag alone is NOT sufficient: recipes and plain long-form articles
     * share `kind 30023`, and an article that merely carries `#t zapcooking`
     * would otherwise leak into the recipe feed. The web drops these by running
     * the editor's save-time guard `validateMarkdownTemplate` on every event;
     * mirroring that content gate here is what keeps articles out — see
     * [validateMarkdownTemplate].
     */
    fun isRecipe(event: NostrEvent): Boolean =
        event.kind == RECIPE_KIND &&
            tagValues(event, "t").any { it in RECIPE_HASHTAGS } &&
            isRecipeContent(event.content)

    /**
     * True when [markdown] has the recipe-template structure — i.e.
     * [validateMarkdownTemplate] accepts it. This is the content-shape half of
     * [isRecipe] and the single shared gate every recipe-feed path funnels
     * through (via `RecipeFormats.forEvent` -> `Nip23RecipeFormat.matches`).
     */
    fun isRecipeContent(markdown: String): Boolean =
        validateMarkdownTemplate(markdown) is TemplateValidation.Valid

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

    // ---- Strict recipe-template validation (port of validateMarkdownTemplate) ----

    /**
     * Result of [validateMarkdownTemplate]: either the parsed [template] when the
     * markdown has the recipe-template shape, or a human-readable [reason] string
     * when it does not. Mirrors the web's `MarkdownTemplate | string` return.
     */
    sealed interface TemplateValidation {
        data class Valid(val template: RecipeContent) : TemplateValidation
        data class Invalid(val reason: String) : TemplateValidation
    }

    /**
     * Section splitter for the STRICT validator: `## <letters/space/apostrophe>`,
     * a newline, then one-or-more non-`#` characters, scanned globally. This is
     * the web's `/## [A-Za-z\s']+\n[^#]+/g` verbatim — and is **case-sensitive**
     * (no `i` flag, unlike the lenient [section] reader). Java's `\s` is ASCII
     * whitespace, matching the JS engine for these plain-ASCII headings.
     */
    private val TEMPLATE_SECTION = Regex("## [A-Za-z\\s']+\\n[^#]+")
    private val LEADING_STEP_NUMBER = Regex("^\\d+\\.")
    private val LEADING_DIGITS = Regex("^\\d+")

    // Exact Details field labels the web compares with `===`. The live bytes are
    // irregular: ⏲️ Prep carries U+FE0F and 🍽️ Servings carries U+FE0F, but
    // 🍳 Cook is the bare glyph (no selector). Matched verbatim — these gate only
    // the optional Details block, not the recipe/article decision.
    private const val PREP_KEY = "- ⏲️ Prep time"
    private const val COOK_KEY = "- 🍳 Cook time"
    private const val SERVINGS_KEY = "- 🍽️ Servings"

    /**
     * Strict recipe-template validator — a byte-faithful port of the web editor's
     * save-time guard `validateMarkdownTemplate` (`src/lib/parser.ts`). The web
     * runs this BEFORE publishing, so every real zap.cooking recipe already
     * satisfies it; porting it (rather than inventing a stricter rule) is what
     * keeps Android from over-rejecting genuine recipes — if the web shipped it,
     * this accepts it.
     *
     * Acceptance rule, identical to the web:
     *  - the body must contain at least one `## ` section ([TEMPLATE_SECTION]);
     *  - any `## Directions` must be a 1-based, strictly +1 incrementing ordered
     *    list (a prose "Directions" section is rejected);
     *  - after parsing there must be **at least one ingredient AND at least one
     *    direction**.
     *
     * Unlike the lenient [parseContent], ingredient/direction lines are matched
     * against the **raw** (un-trimmed) line exactly as the web does, and the
     * `slice(1, -1)` / `slice(1)` line trimming is reproduced precisely.
     */
    fun validateMarkdownTemplate(markdown: String): TemplateValidation {
        var chefNotes: String? = null
        var prepTime = ""
        var cookTime = ""
        var servings = ""
        val ingredients = mutableListOf<String>()
        val directions = mutableListOf<String>()
        var additionalMarkdown: String? = null

        val sections = TEMPLATE_SECTION.findAll(markdown).map { it.value }.toList()
        if (sections.isEmpty()) return TemplateValidation.Invalid("Sections are missing.")

        for (section in sections) {
            when {
                section.startsWith("## Chef's notes") -> {
                    val notes = section.substringAfter("## Chef's notes").trim()
                    if (notes.length > 99999) {
                        return TemplateValidation.Invalid("Chef's notes exceed character limit.")
                    }
                    chefNotes = notes
                }

                section.startsWith("## Details") -> {
                    // JS: section.split('\n').slice(1, -1) — drop header + last line.
                    for (line in section.split("\n").sliceMiddle()) {
                        // JS destructures `const [key, value] = line.split(': ')` —
                        // value is the SECOND segment (null when no ": " present).
                        val parts = line.split(": ")
                        val key = parts[0]
                        val value = parts.getOrNull(1) ?: continue
                        when (key) {
                            PREP_KEY -> {
                                if (value.length > 999) return TemplateValidation.Invalid("Prep time exceeds character limit.")
                                prepTime = value
                            }
                            COOK_KEY -> {
                                if (value.length > 999) return TemplateValidation.Invalid("Cook time exceeds character limit.")
                                cookTime = value
                            }
                            SERVINGS_KEY -> {
                                if (value.length > 999) return TemplateValidation.Invalid("Servings exceed character limit.")
                                servings = value
                            }
                        }
                    }
                }

                section.startsWith("## Ingredients") -> {
                    // JS: section.split('\n').slice(1, -1). Raw line, not trimmed.
                    for (line in section.split("\n").sliceMiddle()) {
                        if (line.startsWith("- ")) {
                            val ingredient = line.substring(2).trim()
                            if (ingredient.length > 9999) return TemplateValidation.Invalid("An ingredient exceeds the character limit.")
                            ingredients.add(ingredient)
                        }
                    }
                }

                section.startsWith("## Directions") -> {
                    // JS: section.split('\n').slice(1) — drop only the header line.
                    var prevStepNumber = 0L
                    for (line in section.split("\n").drop(1)) {
                        val step = LEADING_STEP_NUMBER.find(line)
                        if (step != null) {
                            // parseInt of the leading digits; overflow can't equal
                            // prev+1, so it falls through to the order-error like JS.
                            val stepNumber = LEADING_DIGITS.find(line)!!.value.toLongOrNull() ?: Long.MAX_VALUE
                            if (stepNumber != prevStepNumber + 1) {
                                return TemplateValidation.Invalid("Directions are not in the correct ordered list format.")
                            }
                            val stepDescription = line.substring(step.range.last + 1).trim()
                            if (stepDescription.length > 9999) return TemplateValidation.Invalid("A step in the directions exceeds the character limit.")
                            directions.add(stepDescription)
                            prevStepNumber = stepNumber
                        } else if (line.trim().isNotEmpty()) {
                            return TemplateValidation.Invalid("Directions are not in the correct ordered list format.")
                        }
                    }
                }

                section.startsWith("## Additional Resources") -> {
                    additionalMarkdown = section.substringAfter("## Additional Resources").trim()
                }
            }
        }

        if (directions.size < 1 || ingredients.size < 1) {
            return TemplateValidation.Invalid("Directions and/or ingredients list too short.")
        }

        return TemplateValidation.Valid(
            RecipeContent(
                chefNotes = chefNotes?.takeIf { it.isNotEmpty() },
                details = RecipeDetails(
                    prepTime = prepTime.takeIf { it.isNotEmpty() },
                    cookTime = cookTime.takeIf { it.isNotEmpty() },
                    servings = servings.takeIf { it.isNotEmpty() },
                ),
                ingredients = ingredients,
                directions = directions,
                additionalMarkdown = additionalMarkdown?.takeIf { it.isNotEmpty() },
            )
        )
    }

    /** JS `Array.prototype.slice(1, -1)`: drop the first and last elements. */
    private fun <T> List<T>.sliceMiddle(): List<T> = if (size <= 2) emptyList() else subList(1, size - 1)
}
