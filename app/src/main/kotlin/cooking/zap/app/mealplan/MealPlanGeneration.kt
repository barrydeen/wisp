package cooking.zap.app.mealplan

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/**
 * Cheffy weekly meal-plan generation — types, deterministic filtering,
 * and validation. Port of frontend `src/lib/mealplan/generation.ts`
 * (commit 8caef33, PR #644).
 *
 * Preferences stay here (temporary UI/API state). They are NOT written
 * into the meal-plan payload — that schema is a frozen Android contract.
 *
 * Pure helpers only. Nothing in this module is reachable from the UI yet.
 */
object MealPlanGeneration {

    const val MAX_CANDIDATES = 48
    const val MAX_NOTES_CHARS = 500
    const val MAX_EXCLUDE_INGREDIENTS = 20
    const val MAX_INGREDIENTS_PER_CANDIDATE = 12
    const val MAX_TAGS_PER_CANDIDATE = 8
    const val MAX_TITLE_CHARS = 120
    const val MAX_REASON_CHARS = 160

    private const val RECIPE_KIND_PREFIX = "30023:"
    private const val STYLE_MATCH_KEEP_THRESHOLD = 8

    enum class MealPlanStrategy(val id: String) {
        FILL_EMPTY("fill-empty"),
        REPLACE_SELECTED("replace-selected"),
        ;

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): MealPlanStrategy? = byId[id]
        }
    }

    enum class RecipeSource(val id: String) {
        MY_RECIPES("my-recipes"),
        SAVED("saved"),
        EXPLORE("explore"),
        ALL("all"),
        ;

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): RecipeSource? = byId[id]
        }
    }

    enum class PreferenceStyleId(val id: String) {
        EASY("easy"),
        MEDITERRANEAN("mediterranean"),
        KETO("keto"),
        HIGH_PROTEIN("high-protein"),
        VEGETARIAN("vegetarian"),
        FAMILY_FRIENDLY("family-friendly"),
        BUDGET("budget"),
        SURPRISE("surprise"),
        ;

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): PreferenceStyleId? = byId[id]
        }
    }

    data class PreferenceStyle(
        val id: PreferenceStyleId,
        val label: String,
        val tags: List<String>,
    )

    val PREFERENCE_STYLES = listOf(
        PreferenceStyle(
            PreferenceStyleId.EASY,
            "Easy",
            listOf("easy", "quick", "simple", "weeknight"),
        ),
        PreferenceStyle(
            PreferenceStyleId.MEDITERRANEAN,
            "Mediterranean",
            listOf("mediterranean", "greek", "italian", "levantine"),
        ),
        PreferenceStyle(
            PreferenceStyleId.KETO,
            "Keto",
            listOf("keto", "low-carb", "lowcarb", "low carb"),
        ),
        PreferenceStyle(
            PreferenceStyleId.HIGH_PROTEIN,
            "High Protein",
            listOf("high-protein", "highprotein", "protein", "high protein"),
        ),
        PreferenceStyle(
            PreferenceStyleId.VEGETARIAN,
            "Vegetarian",
            listOf("vegetarian", "vegan", "plant-based"),
        ),
        PreferenceStyle(
            PreferenceStyleId.FAMILY_FRIENDLY,
            "Family Friendly",
            listOf("family", "kid", "kids", "family-friendly", "kid-friendly"),
        ),
        PreferenceStyle(
            PreferenceStyleId.BUDGET,
            "Budget",
            listOf("budget", "cheap", "affordable", "inexpensive"),
        ),
        PreferenceStyle(
            PreferenceStyleId.SURPRISE,
            "Surprise Me",
            emptyList(),
        ),
    )

    val STYLE_IDS: List<PreferenceStyleId> = PREFERENCE_STYLES.map { it.id }

    private val DAY_SET = Schema.DAY_KEYS.toSet()
    private val SLOT_SET = Schema.SLOT_KEYS.toSet()
    private val STYLE_BY_ID = PREFERENCE_STYLES.associateBy { it.id }

    // Substring-matched against a lowercased title+ingredient blob. "graham
    // cracker" trips "ham" — pinned quirk, matches web, do not tighten.
    private val VEGETARIAN_BLOCKLIST = listOf(
        "chicken",
        "beef",
        "pork",
        "lamb",
        "turkey",
        "bacon",
        "sausage",
        "ham",
        "steak",
        "shrimp",
        "salmon",
        "tuna",
        "anchovy",
        "anchovies",
        "fish",
        "seafood",
        "shellfish",
        "crab",
        "lobster",
        "clam",
        "mussel",
        "oyster",
        "duck",
        "venison",
        "pepperoni",
        "salami",
        "prosciutto",
        "pancetta",
        "chorizo",
        "meatball",
        "ground meat",
        "ground beef",
    )

    private val HOUR_RE = Regex("""(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b""")
    private val MIN_RE = Regex("""(\d+(?:\.\d+)?)\s*(?:minutes?|mins?|m)\b""")
    private val FIRST_NUM_RE = Regex("""(\d+)""")
    private val EXCLUDE_SPLIT_RE = Regex("""[,;\n]+""")

    data class RecipeCandidate(
        val a: String,
        override val title: String,
        override val tags: List<String>,
        val ingredients: List<String>,
        val prepTime: String? = null,
        val cookTime: String? = null,
        val servings: String? = null,
    ) : SlotEligibility.Candidate

    data class MealPlanPreferences(
        val styles: List<PreferenceStyleId> = emptyList(),
        val maxMinutes: Int? = null,
        val servings: Int? = null,
        val excludeIngredients: List<String>? = null,
        val notes: String? = null,
    )

    data class MealSlotRef(
        val day: String,
        val slot: String,
    )

    data class MealPlanGenerationRequest(
        val weekId: String,
        val days: List<String>,
        val mealSlots: List<String>,
        val preferences: MealPlanPreferences,
        val strategy: MealPlanStrategy,
        val candidates: List<RecipeCandidate>,
        /** Slots that already have a meal. Required for fill-empty enforcement. */
        val occupiedSlots: List<MealSlotRef> = emptyList(),
        /**
         * Explicit slots Cheffy should fill. When omitted / empty, the
         * cartesian product of `days` × `mealSlots` is used (minus occupied
         * slots when strategy is fill-empty).
         */
        val fillSlots: List<MealSlotRef> = emptyList(),
        /** Coordinates already used in the preview — avoid repeating them on swap. */
        val excludeCoordinates: List<String> = emptyList(),
    )

    data class GeneratedMeal(
        val day: String,
        val slot: String,
        val a: String,
        val title: String,
        val reason: String? = null,
        /** Client-only preview thumbnail. Never written into the meal-plan schema. */
        val image: String? = null,
    )

    data class GeneratedMealPlan(
        val meals: List<GeneratedMeal>,
    )

    enum class GenerationValidationError(val id: String) {
        INVALID_WEEK("invalid-week"),
        INVALID_DAYS("invalid-days"),
        INVALID_SLOTS("invalid-slots"),
        INVALID_STRATEGY("invalid-strategy"),
        NO_CANDIDATES("no-candidates"),
        TOO_MANY_CANDIDATES("too-many-candidates"),
        NO_TARGET_SLOTS("no-target-slots"),
        UNKNOWN_RECIPE("unknown-recipe"),
        UNKNOWN_DAY("unknown-day"),
        UNKNOWN_SLOT("unknown-slot"),
        DUPLICATE_SLOT("duplicate-slot"),
        OVERWRITE_OCCUPIED("overwrite-occupied"),
        INELIGIBLE_SLOT("ineligible-slot"),
        EMPTY_PLAN("empty-plan"),
    }

    sealed class ValidationResult {
        data class Ok(val plan: GeneratedMealPlan) : ValidationResult()
        data class Err(
            val error: GenerationValidationError,
            val message: String,
        ) : ValidationResult()
    }

    data class FilterCandidatesOptions(
        val maxMinutes: Int? = null,
        val excludeIngredients: List<String> = emptyList(),
        val styles: List<PreferenceStyleId> = emptyList(),
        val excludeCoordinates: List<String> = emptyList(),
        /** Soft-prefer recipes whose tags mention these meal slots. */
        val mealSlots: List<String> = emptyList(),
        val maxCandidates: Int? = null,
    )

    fun isMealPlanDayKey(value: String): Boolean = value in DAY_SET

    fun isMealSlotKey(value: String): Boolean = value in SLOT_SET

    fun isRecipeCoordinate(a: String): Boolean {
        if (!a.startsWith(RECIPE_KIND_PREFIX)) return false
        val parts = a.split(":")
        return parts.size == 3 && parts[1].isNotEmpty() && parts[2].isNotEmpty()
    }

    fun slotKey(day: String, slot: String): String = "$day:$slot"

    fun cartesianSlots(days: List<String>, mealSlots: List<String>): List<MealSlotRef> {
        val out = mutableListOf<MealSlotRef>()
        for (day in days) {
            for (slot in mealSlots) {
                out.add(MealSlotRef(day, slot))
            }
        }
        return out
    }

    fun occupiedSlotSet(occupied: List<MealSlotRef>): Set<String> {
        val set = mutableSetOf<String>()
        for (o in occupied) {
            if (isMealPlanDayKey(o.day) && isMealSlotKey(o.slot)) {
                set.add(slotKey(o.day, o.slot))
            }
        }
        return set
    }

    /** Slots Cheffy is allowed to fill for this request. */
    fun resolveTargetSlots(req: MealPlanGenerationRequest): List<MealSlotRef> {
        val occupied = occupiedSlotSet(req.occupiedSlots)
        val base = if (req.fillSlots.isNotEmpty()) {
            req.fillSlots
        } else {
            cartesianSlots(req.days, req.mealSlots)
        }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<MealSlotRef>()
        for (ref in base) {
            if (!isMealPlanDayKey(ref.day) || !isMealSlotKey(ref.slot)) continue
            val key = slotKey(ref.day, ref.slot)
            if (key in seen) continue
            seen.add(key)
            if (req.strategy == MealPlanStrategy.FILL_EMPTY && key in occupied) continue
            out.add(MealSlotRef(ref.day, ref.slot))
        }
        return out
    }

    fun occupiedSlotsFromPlan(
        plan: Schema.MealPlan,
        days: List<String>,
        mealSlots: List<String>,
    ): List<MealSlotRef> {
        val out = mutableListOf<MealSlotRef>()
        for (day in days) {
            val slotsEl = plan.day(day)?.get("slots") ?: continue
            if (slotsEl is JsonNull) continue
            val slots = slotsEl.jsonObject
            for (slot in mealSlots) {
                val entry = slots[slot]
                if (entry != null && entry !is JsonNull) out.add(MealSlotRef(day, slot))
            }
        }
        return out
    }

    /**
     * Parse a human cook/prep time into minutes. Returns null when the
     * string cannot be interpreted — callers must not treat unknown as a
     * violation.
     */
    fun parseDurationMinutes(raw: String?): Int? {
        if (raw == null) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) {
            val n = s.toIntOrNull() ?: return null
            return if (n > 0) n else null
        }

        var total = 0.0
        val hourMatch = HOUR_RE.find(s)
        val minMatch = MIN_RE.find(s)
        if (hourMatch != null) total += hourMatch.groupValues[1].toDouble() * 60
        if (minMatch != null) total += minMatch.groupValues[1].toDouble()
        if (total > 0) return total.roundToInt()

        val firstNum = FIRST_NUM_RE.find(s) ?: return null
        val n = firstNum.groupValues[1].toIntOrNull() ?: return null
        return if (n > 0) n else null
    }

    /** Prep + cook when both parse; otherwise whichever is known. */
    fun totalActiveMinutes(prepTime: String? = null, cookTime: String? = null): Int? {
        val prep = parseDurationMinutes(prepTime)
        val cook = parseDurationMinutes(cookTime)
        if (prep == null && cook == null) return null
        return (prep ?: 0) + (cook ?: 0)
    }

    /**
     * Deterministic pre-filter. Drops recipes that *obviously* violate hard
     * constraints; keeps unknowns. Then ranks and caps so Cheffy sees a
     * useful, bounded set rather than the whole corpus.
     *
     * [random] is used only for the Surprise-Me-alone shuffle so tests can
     * inject a seed — never [kotlin.random.Random.Default] from production
     * tests.
     */
    fun filterRecipeCandidates(
        candidates: List<RecipeCandidate>,
        opts: FilterCandidatesOptions = FilterCandidatesOptions(),
        random: Random = Random.Default,
    ): List<RecipeCandidate> {
        val maxMinutes = opts.maxMinutes?.takeIf { it > 0 }
        val excluded = opts.excludeIngredients.map { normalizeNeedle(it) }.filter { it.isNotEmpty() }
        val excludeAs = opts.excludeCoordinates.toSet()
        val styles = opts.styles
        val vegetarian = PreferenceStyleId.VEGETARIAN in styles
        val (styleTags, surpriseOnly) = styleMatchers(styles)
        val mealSlots = opts.mealSlots
        val cap = opts.maxCandidates ?: MAX_CANDIDATES

        val hardPassed = mutableListOf<RecipeCandidate>()
        for (c in candidates) {
            if (!isRecipeCoordinate(c.a)) continue
            if (c.a in excludeAs) continue
            if (maxMinutes != null) {
                val minutes = totalActiveMinutes(c.prepTime, c.cookTime)
                if (minutes != null && minutes > maxMinutes) continue
            }
            if (obviouslyContainsExcluded(c, excluded)) continue
            if (vegetarian && obviouslyNotVegetarian(c)) continue
            hardPassed.add(c)
        }

        val slotEligible = if (mealSlots.isNotEmpty()) {
            SlotEligibility.restrictCandidatesToRequestedSlots(hardPassed, mealSlots)
        } else {
            hardPassed
        }

        var ranked: List<RecipeCandidate> = slotEligible
        if (!surpriseOnly && styleTags.isNotEmpty()) {
            val matched = slotEligible.filter { candidateMatchesStyle(it, styleTags) }
            val unmatched = slotEligible.filter { !candidateMatchesStyle(it, styleTags) }
            // If we have enough style matches to cover a week with extras, drop
            // the rest. Otherwise keep unmatched as fallback so Cheffy can still plan.
            ranked = if (matched.size >= STYLE_MATCH_KEEP_THRESHOLD) {
                matched
            } else {
                matched + unmatched
            }
        } else if (surpriseOnly) {
            ranked = shuffleInPlace(slotEligible.toMutableList(), random)
        }

        ranked = if (mealSlots.isNotEmpty() && !surpriseOnly) {
            val slotNeedles = mealSlots.map { it.lowercase(Locale.ROOT) }
            ranked.sortedWith { a, b ->
                scoreCandidate(b, styleTags, slotNeedles) - scoreCandidate(a, styleTags, slotNeedles)
            }
        } else if (styleTags.isNotEmpty() && !surpriseOnly) {
            ranked.sortedWith { a, b ->
                scoreCandidate(b, styleTags, emptyList()) - scoreCandidate(a, styleTags, emptyList())
            }
        } else {
            ranked
        }

        return ranked.take(cap)
    }

    /**
     * Validate a model-produced plan against the request. Unknown recipe
     * coordinates are a hard reject — they must never reach the planner.
     *
     * Hard rejects: unknown day, unknown slot, slot not requested, duplicate
     * slot, overwrite of an occupied slot under fill-empty, unknown
     * coordinate, ineligible slot, empty/missing plan. Malformed (null)
     * meal entries are skipped (tolerated), matching web.
     */
    fun validateGeneratedPlan(
        plan: GeneratedMealPlan?,
        request: MealPlanGenerationRequest,
    ): ValidationResult {
        if (plan == null) {
            return ValidationResult.Err(
                GenerationValidationError.EMPTY_PLAN,
                "Cheffy did not return a meal plan.",
            )
        }
        if (plan.meals.isEmpty()) {
            return ValidationResult.Err(
                GenerationValidationError.EMPTY_PLAN,
                "Cheffy did not return any meals.",
            )
        }

        val candidateByA = request.candidates.associateBy { it.a }
        val targets = resolveTargetSlots(request)
        val targetSet = targets.map { slotKey(it.day, it.slot) }.toSet()
        val occupied = occupiedSlotSet(request.occupiedSlots)
        val seenSlots = mutableSetOf<String>()
        val meals = mutableListOf<GeneratedMeal>()

        for (m in plan.meals) {
            if (!isMealPlanDayKey(m.day)) {
                return ValidationResult.Err(
                    GenerationValidationError.UNKNOWN_DAY,
                    "Cheffy returned a day that was not requested.",
                )
            }
            if (!isMealSlotKey(m.slot)) {
                return ValidationResult.Err(
                    GenerationValidationError.UNKNOWN_SLOT,
                    "Cheffy returned a meal that was not requested.",
                )
            }
            val key = slotKey(m.day, m.slot)
            if (request.strategy == MealPlanStrategy.FILL_EMPTY && key in occupied) {
                return ValidationResult.Err(
                    GenerationValidationError.OVERWRITE_OCCUPIED,
                    "Cheffy tried to overwrite a meal that is already planned.",
                )
            }
            if (key !in targetSet) {
                return ValidationResult.Err(
                    GenerationValidationError.UNKNOWN_SLOT,
                    "Cheffy returned a meal slot that was not requested.",
                )
            }
            if (key in seenSlots) {
                return ValidationResult.Err(
                    GenerationValidationError.DUPLICATE_SLOT,
                    "Cheffy assigned two recipes to the same slot.",
                )
            }
            seenSlots.add(key)
            if (!isRecipeCoordinate(m.a) || m.a !in candidateByA) {
                return ValidationResult.Err(
                    GenerationValidationError.UNKNOWN_RECIPE,
                    "Cheffy returned a recipe that is not in Zap Cooking.",
                )
            }
            val candidate = candidateByA.getValue(m.a)
            if (!SlotEligibility.isRecipeEligibleForSlot(candidate, m.slot)) {
                return ValidationResult.Err(
                    GenerationValidationError.INELIGIBLE_SLOT,
                    "Cheffy assigned a recipe that does not fit that meal.",
                )
            }
            val reason = m.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON_CHARS)
            meals.add(
                GeneratedMeal(
                    day = m.day,
                    slot = m.slot,
                    a = candidate.a,
                    title = candidate.title,
                    reason = reason,
                ),
            )
        }

        if (meals.isEmpty()) {
            return ValidationResult.Err(
                GenerationValidationError.EMPTY_PLAN,
                "Cheffy did not return any meals.",
            )
        }
        return ValidationResult.Ok(GeneratedMealPlan(meals))
    }

    fun parseExcludeIngredientsInput(raw: String): List<String> =
        raw.split(EXCLUDE_SPLIT_RE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_EXCLUDE_INGREDIENTS)

    private fun normalizeNeedle(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun textBlob(parts: List<String?>): String =
        parts.filter { !it.isNullOrEmpty() }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

    private fun containsAny(haystack: String, needles: List<String>): Boolean {
        if (haystack.isEmpty()) return false
        return needles.any { n -> n.isNotEmpty() && haystack.contains(n) }
    }

    private fun styleMatchers(styles: List<PreferenceStyleId>): Pair<List<String>, Boolean> {
        val tags = mutableListOf<String>()
        var skip = false
        for (id in styles) {
            val def = STYLE_BY_ID[id] ?: continue
            if (id == PreferenceStyleId.SURPRISE) {
                skip = styles.size == 1
                continue
            }
            for (t in def.tags) tags.add(t.lowercase(Locale.ROOT))
        }
        return tags to skip
    }

    private fun candidateMatchesStyle(c: RecipeCandidate, styleTags: List<String>): Boolean {
        if (styleTags.isEmpty()) return false
        val blob = textBlob(listOf(c.title) + c.tags)
        return styleTags.any { blob.contains(it) }
    }

    private fun obviouslyNotVegetarian(c: RecipeCandidate): Boolean {
        if (c.ingredients.isEmpty()) return false
        val blob = textBlob(listOf(c.title) + c.ingredients)
        return containsAny(blob, VEGETARIAN_BLOCKLIST)
    }

    private fun obviouslyContainsExcluded(c: RecipeCandidate, excluded: List<String>): Boolean {
        if (excluded.isEmpty()) return false
        if (c.ingredients.isEmpty() && c.title.isEmpty()) return false
        val blob = textBlob(listOf(c.title) + c.ingredients)
        return containsAny(blob, excluded)
    }

    private fun scoreCandidate(
        c: RecipeCandidate,
        styleTags: List<String>,
        slotNeedles: List<String>,
    ): Int {
        var score = 0
        val blob = textBlob(listOf(c.title) + c.tags)
        if (styleTags.any { blob.contains(it) }) score += 3
        if (slotNeedles.any { blob.contains(it) }) score += 2
        if (!c.prepTime.isNullOrEmpty() || !c.cookTime.isNullOrEmpty()) score += 1
        if (c.ingredients.isNotEmpty()) score += 1
        return score
    }

    private fun shuffleInPlace(
        arr: MutableList<RecipeCandidate>,
        random: Random,
    ): List<RecipeCandidate> {
        for (i in arr.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }
}
