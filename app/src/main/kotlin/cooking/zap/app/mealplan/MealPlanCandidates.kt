package cooking.zap.app.mealplan

import cooking.zap.app.nostr.HiddenRecipes
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Build Cheffy planning candidates from raw recipe events.
 *
 * Port of frontend `src/lib/services/recipeDiscoveryService.ts` (commit 8caef33)
 * for the **pure** half: event → candidate, tag/ingredient shaping, first-seen
 * merge, and the saved-coordinate fill (cache then chunked network). Relay I/O
 * lives in `repo/MealPlanCandidateSource` so this module stays unit-testable
 * and [mealplan] does not grow a second recipe feed.
 *
 * Tags are read off the raw `t` values. [RecipeParser.Recipe.categories] is
 * the wrong source: it prefix-strips `zapcooking-*`, which deletes the
 * breakfast signal [SlotEligibility] keys on (`zapcooking-breakfast` →
 * `zapcooking breakfast` after normalize).
 */
object MealPlanCandidates {

    const val EXPLORE_LIMIT = 150
    const val MY_RECIPES_LIMIT = 200
    const val SAVED_NETWORK_CAP = 64
    const val SAVED_CHUNK_SIZE = 8
    const val FETCH_TIMEOUT_MS = 8_000L

    private val META_TAGS = setOf("zapcooking", "nostrcooking")

    /** Web's SIMPLE leading-quantity strip — not [IngredientParser.parseIngredient]. */
    private val LEADING_QUANTITY = Regex(
        """^\d[\d\s/.\-¼½¾⅓⅔⅛⅜⅝⅞]*\s*(?:cups?|tbsp|tsp|oz|lbs?|g|kg|ml|l|cloves?|cans?|slices?)?\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_BULLET = Regex("""^[-*•]\s+""")

    /**
     * Android-only wrapper: wire-shaped [recipe] plus a preview [image].
     * Phase 2's DTO mapper never sees [image] — only [recipe] is serialized.
     */
    data class Candidate(
        val recipe: MealPlanGeneration.RecipeCandidate,
        val image: String? = null,
    ) : SlotEligibility.Candidate {
        override val title: String get() = recipe.title
        override val tags: List<String> get() = recipe.tags
        val a: String get() = recipe.a
    }

    /**
     * Build a candidate from the RAW EVENT. Returns null when the coordinate
     * is malformed, hidden, or otherwise unusable. Does not consult
     * [RecipeParser.parse] for tags or ingredients.
     */
    fun candidateFromEvent(event: NostrEvent): Candidate? {
        val a = coordinateFromEvent(event) ?: return null
        val dTag = a.substringAfterLast(':')
        val title = (
            firstTag(event, "title")
                ?: dTag.takeIf { it.isNotEmpty() }
                ?: "Recipe"
            ).take(MealPlanGeneration.MAX_TITLE_CHARS)
        val details = event.content.takeIf { it.isNotEmpty() }
            ?.let { RecipeParser.parseContent(it).details }
        val prep = firstTag(event, "prep_time") ?: details?.prepTime
        val cook = firstTag(event, "cook_time") ?: details?.cookTime
        val servings = firstTag(event, "servings") ?: details?.servings
        return Candidate(
            recipe = MealPlanGeneration.RecipeCandidate(
                a = a,
                title = title,
                tags = recipeTags(event),
                ingredients = ingredientsFromEvent(event),
                prepTime = prep?.takeIf { it.isNotEmpty() },
                cookTime = cook?.takeIf { it.isNotEmpty() },
                servings = servings?.takeIf { it.isNotEmpty() },
            ),
            image = firstTag(event, "image"),
        )
    }

    /** First-seen-by-coordinate merge. Later groups cannot replace an earlier hit. */
    fun mergeFirstSeen(groups: List<List<Candidate>>): List<Candidate> {
        val byA = LinkedHashMap<String, Candidate>()
        for (group in groups) {
            for (candidate in group) {
                if (candidate.a !in byA) byA[candidate.a] = candidate
            }
        }
        return byA.values.toList()
    }

    /**
     * Run [loaders] concurrently. A thrown loader degrades to empty rather
     * than failing the whole run (web `discoverRecipesForPlanning` `all`).
     */
    suspend fun mergeLoaders(loaders: List<suspend () -> List<Candidate>>): List<Candidate> =
        coroutineScope {
            val results = loaders.map { loader ->
                async {
                    try {
                        loader()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            mergeFirstSeen(results.awaitAll())
        }

    /**
     * Cache-first saved resolution with a **capped, chunked** network fill.
     *
     * [cacheLookup] / [networkFetch] are injected so tests can assert call
     * counts without relays. Unresolved coordinates are dropped (no d-tag
     * fallback row — Cheffy cannot plan a recipe it has not read).
     */
    suspend fun resolveSaved(
        lists: List<CookbookList>,
        cacheLookup: (kind: Int, author: String, dTag: String) -> NostrEvent?,
        networkFetch: suspend (kind: Int, author: String, dTag: String) -> NostrEvent?,
        isUsable: (NostrEvent) -> Boolean = { true },
        networkCap: Int = SAVED_NETWORK_CAP,
        chunkSize: Int = SAVED_CHUNK_SIZE,
        timeoutMs: Long = FETCH_TIMEOUT_MS,
        onDrop: (String) -> Unit = {},
    ): List<Candidate> {
        val coordinates = RecipePickerLogic.uniqueCoordinates(lists)
            .filter { MealPlanGeneration.isRecipeCoordinate(it) && !HiddenRecipes.isHidden(it) }
        if (coordinates.isEmpty()) return emptyList()

        val byA = LinkedHashMap<String, Candidate>()
        val missing = mutableListOf<String>()
        for (a in coordinates) {
            val parsed = CookbookCovers.parseCoordinate(a) ?: continue
            val cached = cacheLookup(parsed.kind, parsed.author, parsed.dTag)
            val candidate = cached?.takeIf(isUsable)?.let { candidateFromEvent(it) }
            if (candidate != null) {
                byA[candidate.a] = candidate
            } else {
                missing.add(a)
            }
        }

        val toFetch = missing.take(networkCap)
        if (toFetch.isNotEmpty()) {
            val fetched = fetchChunked(toFetch, chunkSize, timeoutMs) { a ->
                val parsed = CookbookCovers.parseCoordinate(a) ?: return@fetchChunked null
                val event = networkFetch(parsed.kind, parsed.author, parsed.dTag)
                    ?.takeIf(isUsable)
                    ?: return@fetchChunked null
                candidateFromEvent(event)
            }
            for (candidate in fetched) byA[candidate.a] = candidate
        }
        for (a in missing) {
            if (a !in byA) onDrop(a)
        }
        return coordinates.mapNotNull { byA[it] }
    }

    /**
     * At most [chunkSize] in-flight [fetch] calls. Results that come back
     * null are omitted (the caller logs the drop).
     */
    suspend fun <T> fetchChunked(
        items: List<T>,
        chunkSize: Int = SAVED_CHUNK_SIZE,
        timeoutMs: Long = FETCH_TIMEOUT_MS,
        fetch: suspend (T) -> Candidate?,
    ): List<Candidate> {
        if (items.isEmpty()) return emptyList()
        val cap = chunkSize.coerceAtLeast(1)
        val out = mutableListOf<Candidate>()
        for (chunk in items.chunked(cap)) {
            val batch = coroutineScope {
                chunk.map { item ->
                    async { withTimeoutOrNull(timeoutMs) { fetch(item) } }
                }.awaitAll()
            }
            for (candidate in batch) if (candidate != null) out.add(candidate)
        }
        return out
    }

    internal fun recipeTags(event: NostrEvent): List<String> {
        val raw = event.tags.mapNotNull { tag ->
            if (tag.size < 2 || tag[0] != "t") return@mapNotNull null
            val value = tag[1]
            if (value in META_TAGS) return@mapNotNull null
            value
        }
        return uniqueTrim(raw, MealPlanGeneration.MAX_TAGS_PER_CANDIDATE, lowercase = true)
    }

    internal fun ingredientsFromEvent(event: NostrEvent): List<String> {
        val tagged = mutableListOf<String>()
        for (tag in event.tags) {
            if (tag.isEmpty() || tag[0] != "ingredient") continue
            val value = when {
                tag.size >= 4 -> tag[3].ifEmpty { tag.getOrNull(1).orEmpty() }
                tag.size >= 2 -> tag[1]
                else -> ""
            }
            if (value.isNotEmpty()) tagged.add(value)
        }
        val lines = if (tagged.isNotEmpty()) {
            tagged
        } else if (event.content.isNotEmpty()) {
            IngredientParser.parseIngredientsFromRecipe(event.content).map { parsed ->
                parsed.name.ifBlank { parsed.originalText }
            }
        } else {
            emptyList()
        }
        return uniqueTrim(
            lines.map { stripQuantity(it) },
            MealPlanGeneration.MAX_INGREDIENTS_PER_CANDIDATE,
            lowercase = false,
        )
    }

    private fun coordinateFromEvent(event: NostrEvent): String? {
        val dTag = RecipeParser.dTag(event)
        if (dTag.isEmpty() || event.pubkey.isEmpty()) return null
        if (HiddenRecipes.isHidden(event.kind, event.pubkey, dTag)) return null
        val a = "${event.kind}:${event.pubkey}:$dTag"
        return a.takeIf { MealPlanGeneration.isRecipeCoordinate(it) }
    }

    private fun firstTag(event: NostrEvent, name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun stripQuantity(line: String): String {
        val cleaned = line.replace(LEADING_BULLET, "").trim()
        return cleaned.replace(LEADING_QUANTITY, "").trim().ifEmpty { cleaned }
    }

    /**
     * Unique by lowercase key. [lowercase] true stores the lowercased form
     * (tags — eligibility is case-insensitive anyway); false keeps first-seen
     * original case (ingredients, matching web `uniqueTrim`).
     */
    internal fun uniqueTrim(values: List<String>, cap: Int, lowercase: Boolean): List<String> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<String>()
        for (raw in values) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase(Locale.ROOT)
            if (key in seen) continue
            seen.add(key)
            val stored = (if (lowercase) key else trimmed).take(80)
            out.add(stored)
            if (out.size >= cap) break
        }
        return out
    }
}
