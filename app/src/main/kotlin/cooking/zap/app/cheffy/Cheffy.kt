package cooking.zap.app.cheffy

import kotlin.random.Random

/**
 * Cheffy — shared voice lines and small helpers for Zap Cooking's kitchen
 * companion (concern 2.3). A **verbatim port of the web `$lib/cheffy`** so the
 * Android assistant speaks in exactly the same voice: the rotating line pools,
 * the input placeholders, and the structured-recipe gate that decides how a
 * reply renders.
 *
 * Pure (strings + regex), so the recipe gate is unit-tested against the web's
 * cases.
 */
object Cheffy {

    /** Cheffy's small, typed set of moods (mirrors the web `CheffyExpression`). */
    enum class Expression { NEUTRAL, HAPPY, THINKING, EXCITED, CONCERNED, COOKING }

    /** Rotating placeholders for the main Cheffy input. */
    val PROMPT_PLACEHOLDERS: List<String> = listOf(
        "What are we cooking?",
        "Tell me what is in your fridge.",
        "Can I substitute yogurt for sour cream?",
        "I burned the bottom. Can this be saved?",
        "I need dinner in 20 minutes.",
        "What goes well with salmon?",
    )

    /** Shown while Cheffy is preparing a conversational reply. */
    val THINKING_LINES: List<String> = listOf(
        "Tasting the idea…",
        "Rummaging through the pantry…",
        "Thinking with my whole spatula…",
        "Checking what plays well together…",
        "Giving it a quick stir…",
    )

    /** Shown while Cheffy is generating a full structured recipe. */
    val COOKING_LINES: List<String> = listOf(
        "Firing up the burners…",
        "Plating this up…",
        "Dinner has entered the chat…",
        "Three ingredients, zero panic…",
        "Crisping the edges…",
    )

    /**
     * Cooking-flavored error lines. Each is paired in the UI with a real
     * recovery action and the actual technical detail — these never hide a
     * validation, membership, payment, or network error.
     */
    val ERROR_LINES: List<String> = listOf(
        "Cheffy dropped a spoon. Try that again.",
        "The kitchen lost the signal for a second.",
        "That request did not finish cooking.",
        "Cheffy got distracted by something on the stove.",
    )

    /**
     * Pick a line from a pool, avoiding [avoid] (usually the previously shown
     * line) so consecutive states don't repeat. Falls back to the first line
     * for single-entry pools. Mirrors the web `pickLine`.
     */
    fun pickLine(pool: List<String>, avoid: String? = null, random: Random = Random.Default): String {
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool[0]
        var next = pool[random.nextInt(pool.size)]
        // One re-roll is enough to dodge an immediate repeat without risking a
        // pathological loop.
        if (next == avoid) {
            next = pool[(pool.indexOf(next) + 1) % pool.size]
        }
        return next
    }

    private val INGREDIENTS_HEADING = Regex("^##\\s*Ingredients\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val DIRECTIONS_HEADING = Regex("^##\\s*Directions\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val TITLE_HEADING = Regex("^#\\s+\\S", RegexOption.MULTILINE)

    /**
     * Does this assistant message contain a full, structured recipe (the format
     * the editor can parse), as opposed to a conversational answer? Requires a
     * title heading plus both an Ingredients and a Directions section. Verbatim
     * port of the web `looksLikeStructuredRecipe` — in v1 this only drives the
     * reply's expression/rendering (Save/Share/Zap actions land in 2.3c).
     */
    fun looksLikeStructuredRecipe(md: String?): Boolean {
        if (md.isNullOrEmpty()) return false
        return TITLE_HEADING.containsMatchIn(md) &&
            INGREDIENTS_HEADING.containsMatchIn(md) &&
            DIRECTIONS_HEADING.containsMatchIn(md)
    }

    private val RECIPE_REQUEST = Regex("\\b(recipe|cook|dinner|lunch|breakfast|dessert|make me)\\b", RegexOption.IGNORE_CASE)
    private val HAVE_PREFIX = Regex("\\bi have:?\\s", RegexOption.IGNORE_CASE)

    /**
     * Cosmetic only — does the user's text read like a recipe request, so the
     * pending bubble should show the "cooking" expression + [COOKING_LINES]
     * instead of [THINKING_LINES]? Mirrors the web `looksLikeRecipeRequest`
     * (the "I have:" prefix is matched separately — the trailing colon defeats
     * a `\b` word boundary).
     */
    fun looksLikeRecipeRequest(text: String): Boolean =
        RECIPE_REQUEST.containsMatchIn(text) || HAVE_PREFIX.containsMatchIn(text)

    /** Server-enforced caps, mirrored client-side for clean UX. */
    const val MAX_PROMPT_CHARS = 2000
    const val MAX_HISTORY_TURNS = 12

    /** The members-gate message (message-only — no purchase CTA in v1, per 2.4b). */
    const val MEMBERS_ONLY_MESSAGE = "Cheffy is a Pro Kitchen members feature."
}
