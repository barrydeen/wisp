package cooking.zap.app.mealplan

import java.util.Locale

/**
 * Meal-slot eligibility for Cheffy planning — port of frontend
 * `src/lib/mealplan/slotEligibility.ts` (commit 8caef33, PR #644).
 *
 * Internal to meal-plan generation — not part of the shared Android
 * meal-plan schema. Breakfast (and snack) are hard filters; lunch and
 * dinner stay permissive.
 *
 * **Web wins.** The matching rules (contains-not-startsWith, the redundant
 * title-phrase clause, substring tag matches) are the cross-platform
 * contract pinned by `mealplan-eligibility.vectors.json`. Do not "improve"
 * them; change the fixtures upstream and move both platforms together.
 */
object SlotEligibility {

    /** Minimal candidate shape — kept local to avoid a cycle with [MealPlanGeneration]. */
    interface Candidate {
        val title: String
        val tags: List<String>
    }

    data class SlotCandidate(
        override val title: String,
        override val tags: List<String>,
    ) : Candidate

    // Web lists — order is part of the contract; do not sort or "correct".
    private val BREAKFAST_TAG_NEEDLES = listOf("breakfast", "brunch")

    private val BREAKFAST_TITLE_PHRASES = listOf(
        "breakfast",
        "brunch",
        "omelet",
        "omelette",
        "frittata",
        "pancake",
        "pancakes",
        "waffle",
        "waffles",
        "french toast",
        "oatmeal",
        "overnight oats",
        "porridge",
        "granola",
        "yogurt",
        "parfait",
        "smoothie",
        "breakfast sandwich",
        "breakfast burrito",
        "hash brown",
        "hashbrowns",
        "hash browns",
        "muffin",
        "muffins",
        "bagel",
        "bagels",
        "cereal",
        "muesli",
        "shakshuka",
        "eggs benedict",
        "scrambled eggs",
        "fried eggs",
        "poached eggs",
        "avocado toast",
        "crepe",
        "crepes",
        "scone",
        "scones",
        "acai",
    )

    private val BREAKFAST_TITLE_WORDS = listOf("eggs", "oats", "hash")

    private val SNACK_TAG_NEEDLES = listOf("snack", "snacks")

    private val SNACK_TITLE_PHRASES = listOf(
        "snack",
        "snacks",
        "bites",
        "energy ball",
        "energy balls",
        "granola bar",
        "protein bar",
        "trail mix",
        "popcorn",
        "hummus",
    )

    private val HARD_SLOTS = listOf("breakfast", "snack")

    /**
     * Lowercase, `[_/]+` → space, `-` → space, collapse whitespace, trim.
     * That order is load-bearing (`zapcooking-breakfast` → `zapcooking breakfast`).
     */
    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[_/]+"), " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tagMatches(tags: List<String>, needles: List<String>): Boolean {
        for (tag in tags) {
            val n = normalize(tag)
            if (n.isEmpty()) continue
            for (needle in needles) {
                // Contains, not startsWith — `zapcooking breakfast` still matches `breakfast`.
                if (n == needle || n.contains(needle)) return true
            }
        }
        return false
    }

    private fun titleHasPhrase(title: String, phrases: List<String>): Boolean {
        val n = " ${normalize(title)} "
        // Second clause subsumes the first (so "Muffins" matches "muffin");
        // both are ported verbatim.
        return phrases.any { phrase ->
            n.contains(" $phrase ") || n.contains(" $phrase")
        }
    }

    private fun titleHasWord(title: String, words: List<String>): Boolean {
        val n = normalize(title)
        if (n.isEmpty()) return false
        val tokens = n.split(" ").toSet()
        return words.any { it in tokens }
    }

    private fun hasBreakfastSignal(candidate: Candidate): Boolean {
        if (tagMatches(candidate.tags, BREAKFAST_TAG_NEEDLES)) return true
        val title = candidate.title
        if (titleHasPhrase(title, BREAKFAST_TITLE_PHRASES)) return true
        if (titleHasWord(title, BREAKFAST_TITLE_WORDS)) return true
        return false
    }

    private fun hasSnackSignal(candidate: Candidate): Boolean {
        if (tagMatches(candidate.tags, SNACK_TAG_NEEDLES)) return true
        return titleHasPhrase(candidate.title, SNACK_TITLE_PHRASES)
    }

    /**
     * Whether a recipe may be assigned to a planner slot.
     *
     * Breakfast and snack require a positive signal (tags first, then title).
     * Lunch and dinner are always eligible — many recipes work for either.
     * Unknown slot → false.
     */
    fun isRecipeEligibleForSlot(candidate: Candidate, slot: String): Boolean {
        if (slot == "lunch" || slot == "dinner") return true
        if (slot == "breakfast") return hasBreakfastSignal(candidate)
        if (slot == "snack") return hasSnackSignal(candidate)
        return false
    }

    fun eligibleSlotsForRecipe(candidate: Candidate): List<String> {
        val slots = mutableListOf<String>()
        if (isRecipeEligibleForSlot(candidate, "breakfast")) slots.add("breakfast")
        slots.add("lunch")
        slots.add("dinner")
        if (isRecipeEligibleForSlot(candidate, "snack")) slots.add("snack")
        return slots
    }

    /**
     * Drop recipes that cannot fill any requested slot. Breakfast-only
     * (or snack-only) requests keep only matching recipes — dinner entrees
     * never reach Cheffy as breakfast options. Lunch/dinner requests are
     * unrestricted.
     *
     * - empty [mealSlots] → passthrough
     * - no hard slot (breakfast/snack) → passthrough
     * - hard + soft → filter by any requested slot
     * - hard only → filter by the hard slots
     */
    fun <T : Candidate> restrictCandidatesToRequestedSlots(
        candidates: List<T>,
        mealSlots: List<String>,
    ): List<T> {
        if (mealSlots.isEmpty()) return candidates
        val hard = mealSlots.filter { it in HARD_SLOTS }
        val hasSoft = mealSlots.any { it == "lunch" || it == "dinner" }
        if (hard.isEmpty()) return candidates
        if (hasSoft) {
            return candidates.filter { c -> mealSlots.any { slot -> isRecipeEligibleForSlot(c, slot) } }
        }
        return candidates.filter { c -> hard.any { slot -> isRecipeEligibleForSlot(c, slot) } }
    }

    fun insufficientSlotCoverageMessage(
        mealSlots: List<String>,
        found: Int,
        requested: Int,
    ): String? {
        if (found >= requested || found <= 0) return null
        if (mealSlots.size == 1 && mealSlots[0] == "breakfast") {
            val n = found
            return "Cheffy found $n breakfast ${if (n == 1) "recipe" else "recipes"} that match your preferences. Try broadening your preferences to fill the rest of the week."
        }
        if (mealSlots.size == 1 && mealSlots[0] == "snack") {
            val n = found
            return "Cheffy found $n snack ${if (n == 1) "recipe" else "recipes"} that match your preferences. Try broadening your preferences to fill the rest of the week."
        }
        return "Cheffy found $found matching ${if (found == 1) "recipe" else "recipes"} — some slots were left empty rather than filled with a poor match."
    }

    fun noEligibleRecipesMessage(mealSlots: List<String>): String {
        if (mealSlots.size == 1 && mealSlots[0] == "breakfast") {
            return "Cheffy could not find breakfast or brunch recipes that match your preferences. Try another source, or add breakfast recipes."
        }
        if (mealSlots.size == 1 && mealSlots[0] == "snack") {
            return "Cheffy could not find snack recipes that match your preferences. Try another source, or loosen the filters."
        }
        return "Could not find enough matching recipes. Try another source, or loosen the time and ingredient filters."
    }
}
