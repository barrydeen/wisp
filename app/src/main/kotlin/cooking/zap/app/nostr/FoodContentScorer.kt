package cooking.zap.app.nostr

import java.util.Locale

/**
 * Content-keyword food relevance scorer — a precision refinement over the flat
 * [FoodHashtags] tag match, ported verbatim from the web client's
 * `FoodstrFeedOptimized.svelte` `contentContainsFoodWords` (and its three curated
 * lists). Lets a note that is *clearly about food* but carries **no** food
 * hashtag surface in the OnlyFood feed.
 *
 * These lists are DELIBERATELY separate from [FoodHashtags.ALL] and carry their own
 * false-positive management (the web removed `dish`→Dish Network, `cook`→Cook
 * Islands, `ate`→too many non-food uses). Do NOT merge them into [FoodHashtags.ALL].
 *
 * The decision (mirrors the web exactly):
 * 1. blank → no.
 * 2. a standalone `root` (e.g. root-of-trust chatter) → no, unconditionally.
 * 3. a food hashtag appearing *in the text* → yes (strong signal).
 * 4. otherwise include iff **≥1 HARD** word OR **≥2 SOFT** words match.
 * 5. guard: when the economics phrase "excluding food and energy" is present, it is
 *    suppressed unless the note also clears the normal bar — ≥1 hard or ≥2 soft matches
 *    *counting the phrase's own `food`* (soft). This mirrors the web's guard verbatim; as
 *    there, it only ever fires in the no-signal case the fall-through would reject anyway,
 *    so it changes no outcome and is kept purely for parity with the source of truth.
 *
 * Matching is word-boundary (`\bword\b`), supports multi-word phrases (`olive oil`,
 * `slow cooked` → `\bolive\s+oil\b`), and is locale-invariant: content is lowercased
 * with [Locale.ROOT] (consistent with [FoodHashtags.hasFoodTag]) and matched against
 * the all-lowercase term lists, so e.g. the Turkish locale can't drop an `I`.
 *
 * **Pure and stateless** — no Android deps, no I/O, no caching. Callers that score
 * under the firehose should wrap it in [MemoizedFoodContentScorer]. Mirrors how
 * `mergeFeedOrder` / `shouldLatchLoaded` are structured (pure, unit-tested).
 */
object FoodContentScorer {

    /** Hashtag terms (strong signal) — matched as inline `#term` in the text. */
    private val FOOD_HASHTAG_TERMS = listOf(
        "foodstr", "cookstr", "zapcooking", "recipestr", "soupstr", "drinkstr",
        "snackstr", "steakstr", "mealprep", "foodies", "carnivor", "carnivorediet",
    )

    /** Hard words — very low false-positive risk; a single hit is enough. */
    private val HARD_FOOD_WORDS = listOf(
        // Recipes & cooking intent
        "recipe", "recipes", "recipestr", "cooking", "baking", "bake", "chef",
        "chefs", "kitchen", "ingredient", "ingredients", "seasoned", "seasoning",
        "marinated", "saute", "sauteed", "simmer", "braised", "fermented",
        "pickled", "smoked", "slow cooked", "air fried",
        // Meals (strong real-world food signal)
        "breakfast", "lunch", "dinner", "dessert", "mealprep", "meal prep",
        "homecooking", "home cooked", "fromscratch", "homemade",
        // Food items & dishes
        "pasta", "pizza", "sushi", "taco", "tacos", "burrito", "sandwich",
        "salad", "soup", "stew", "curry", "burger", "steak", "bbq", "coffee",
        // Ingredients & staples
        "garlic", "onion", "tomato", "cheese", "butter", "olive oil", "rice",
        "beans", "eggs", "flour",
        // Diets & preferences (safe as hard)
        "vegan", "vegetarian", "keto", "paleo", "glutenfree", "gluten free",
        "dairyfree", "dairy free",
        // Restaurants (strong enough on Nostr)
        "restaurant", "restaurants",
    )

    /** Soft words — common metaphor / news words; require 2 hits. */
    private val SOFT_FOOD_WORDS = listOf(
        // ambiguous/general
        "food", "meal", "supper",
        // slang/metaphor prone
        "spicy", "sweet", "flavor", "healthy", "organic",
        // journalism-metaphor prone
        "grill", "grilled", "roast", "roasted",
        // cuisines (can be ambiguous - e.g., "Italian politics")
        "italian", "mexican", "thai", "indian", "mediterranean", "japanese",
        "korean",
    )

    private val REGEX_META = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\')

    /** Escape regex metacharacters — mirrors the web's `escapeRegex`. */
    private fun escapeRegex(s: String): String = buildString {
        for (c in s) {
            if (c in REGEX_META) append('\\')
            append(c)
        }
    }

    /**
     * Convert a term to a word-boundary pattern, supporting multi-word phrases —
     * mirrors the web's `termToPattern`. `olive oil` → `\bolive\s+oil\b`.
     */
    private fun termToPattern(term: String): String {
        val parts = term.trim().split(Regex("\\s+")).map { escapeRegex(it) }
        return if (parts.size == 1) "\\b${parts[0]}\\b" else "\\b${parts.joinToString("\\s+")}\\b"
    }

    // Precompiled, all-lowercase patterns (content is lowercased before matching,
    // so no IGNORE_CASE — keeps folding locale-invariant, matching hasFoodTag).
    private val FOOD_HASHTAG_REGEX =
        Regex("(?:^|\\s)#(" + FOOD_HASHTAG_TERMS.joinToString("|") { escapeRegex(it) } + ")\\b")
    private val HARD_FOOD_REGEX = Regex(HARD_FOOD_WORDS.joinToString("|") { termToPattern(it) })
    private val SOFT_FOOD_REGEX = Regex(SOFT_FOOD_WORDS.joinToString("|") { termToPattern(it) })
    private val MACRO_EXCLUDING_FOOD_ENERGY_REGEX =
        Regex("\\b(excluding|exclude)\\s+food\\s+and\\s+energy\\b")
    private val ROOT_REGEX = Regex("\\broot\\b")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * True iff [content] reads as food by the keyword scorer. See the class KDoc for
     * the exact rule; behavior-identical to the web's `contentContainsFoodWords`.
     */
    fun matches(content: String): Boolean {
        // Blank (empty or whitespace-only) is non-food; short-circuit before the
        // lowercase/regex work so firehose whitespace never gets scanned.
        if (content.isBlank()) return false
        // Lowercase locale-invariantly, then collapse whitespace so multi-word phrases
        // and newlines match (mirrors the web's `replace(/\s+/g, ' ').trim()`).
        val normalized = content.lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ").trim()

        // Exclude posts with a standalone `root` (root-of-trust / filesystem chatter),
        // unconditionally — the web check runs before any food signal.
        if (ROOT_REGEX.containsMatchIn(normalized)) return false

        // A food hashtag in the text is a strong enough signal on its own.
        if (FOOD_HASHTAG_REGEX.containsMatchIn(normalized)) return true

        // The decision only needs "any hard match" and "≥2 soft matches", so stop
        // scanning at those thresholds instead of counting every match (findAll is a
        // lazy sequence, so take(2) short-circuits). Behavior-identical to the web.
        val hasHard = HARD_FOOD_REGEX.containsMatchIn(normalized)
        val softMatches = SOFT_FOOD_REGEX.findAll(normalized).take(2).count() // 0, 1, or 2
        val hasTwoSoft = softMatches >= 2

        // Macro guard: "excluding food and energy" is a common economics phrase. Mirrors
        // the web — it only rejects when there's no other signal (which the fall-through
        // rejects anyway), so it's a no-op kept for parity. See the class KDoc.
        if (MACRO_EXCLUDING_FOOD_ENERGY_REGEX.containsMatchIn(normalized)) {
            if (!hasHard && !hasTwoSoft) return false
        }

        if (hasHard) return true
        if (hasTwoSoft) return true
        return false
    }
}

/**
 * Bounded-memoized wrapper over [FoodContentScorer.matches] — equivalent to the web's
 * 1000-entry `foodWordResultCache`, so the same note content isn't re-scanned as it
 * streams past under the firehose. Evicts the oldest entries in a batch (down to
 * [targetEntries]) once [maxEntries] is exceeded, keeping headroom so evictions are
 * infrequent — mirrors the web's 1000→900 batch eviction.
 *
 * **NOT thread-safe by design.** The plain [LinkedHashMap] must be confined to a
 * single thread; the OnlyFood feed owns one instance and touches it only from its
 * serial `feedDispatcher` (the same confinement as `seen`), so it can't race.
 */
internal class MemoizedFoodContentScorer(
    private val maxEntries: Int = 1000,
    private val targetEntries: Int = 900,
    private val score: (String) -> Boolean = FoodContentScorer::matches,
) {
    // Insertion-ordered: the eldest key is the front of the iterator, so a
    // front-to-back removal evicts oldest-first (like the web's Map + keys().next()).
    private val cache = LinkedHashMap<String, Boolean>()

    fun matches(content: String): Boolean {
        // Blank content is non-food (matching [FoodContentScorer.matches]); short-circuit
        // before touching the cache so whitespace-only firehose notes don't churn it.
        if (content.isBlank()) return false
        // Evict oldest in a batch when over the cap (before the get/insert, as the web does).
        if (cache.size > maxEntries) {
            val it = cache.keys.iterator()
            while (cache.size > targetEntries && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        cache[content]?.let { return it }
        val result = score(content)
        cache[content] = result
        return result
    }
}
