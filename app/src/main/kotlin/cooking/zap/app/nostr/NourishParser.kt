package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One of the 8 Nourish health dimensions: display name + 0–10 score. */
data class NourishDimension(val name: String, val score: Int)

/** Per-serving macro estimate (honest rounding applied server-side). */
data class NourishMacroPerServing(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
)

/**
 * Additive macro block (prompt v4). Sibling to scores — not a ninth
 * dimension. Absent on v3 responses/events and when the engine degraded
 * to omitted.
 */
data class NourishMacros(
    val perServing: NourishMacroPerServing,
    val servingsUsed: Int,
    val servingsParsed: Boolean,
    /** `estimate` (default) or `rough` (breaded+fried / bone-in honesty class). */
    val confidence: String,
    /** Swap point for a future USDA lookup (`llm-per100g-v1` today). */
    val method: String,
)

/**
 * Resolved macros-row view for [cooking.zap.app.ui.component.NourishCard].
 * `null` from [macrosRowView] means "do not render" (v3 / degraded / malformed).
 */
data class MacrosRowView(
    val label: String,
    /** `estimate` or `rough` — rough is muted/hedged in the card. */
    val tone: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
)

/** A recipe's Nourish health analysis (concern 2.4a). */
data class NourishScore(
    val overall: Int,
    val overallLabel: String,
    val dimensions: List<NourishDimension>,
    val improvements: List<String>,
    /** Estimated per-serving macros (prompt v4). Null on v3 / degraded. */
    val macros: NourishMacros? = null,
)

/**
 * Parses a kind-30078 Nourish analysis event's JSON content into a
 * [NourishScore] (concern 2.4a). Mirrors the frontend `parseNourishEvent`.
 *
 * **Trust the stored `overall`** — do NOT recompute it from the dimensions:
 * legacy events lack the newer dims (which default to 0), and recomputing
 * with current weights would deflate the overall by ~25%. Missing per-dimension
 * scores default to 0 (legacy), same as the web.
 *
 * Macros (prompt v4) are additive and lenient: malformed / absent → null,
 * never throw. v3 events parse exactly as before.
 *
 * Pure (JSON + maps) — unit-tested.
 */
object NourishParser {

    /**
     * The Zap Cooking service account that publishes Nourish events.
     * Source of truth: frontend `NOURISH_SERVICE_PUBKEY` in
     * `src/lib/nourish/types.ts` — keep these in lockstep.
     */
    const val SERVICE_PUBKEY = "fdd263f69f9e95a2a0a58ec3e7e8053011214fa66007d93b26d2f4717d31917b"

    /** NIP-78 app-data kind the Nourish service uses. */
    const val KIND = 30078

    // (content key, display name), in the web's display order.
    private val DIMENSIONS = listOf(
        "realFood" to "Real Food",
        "gut" to "Gut",
        "protein" to "Protein",
        "antiInflammatory" to "Anti-Inflammatory",
        "bloodSugar" to "Blood Sugar",
        "immuneSupportive" to "Immune",
        "brainHealth" to "Brain",
        "heartHealth" to "Heart",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The `#d` value addressing a recipe's Nourish event. */
    fun dTag(recipeAuthor: String, recipeDTag: String): String =
        "nourish:30023:$recipeAuthor:$recipeDTag"

    /**
     * Parse the pantry event content (2.4a). The scores object (8 dims +
     * `overall`) and `improvements` are both top-level in the event content.
     * Optional top-level `macros` is parsed additively (v4).
     */
    fun parse(content: String): NourishScore? = try {
        val obj = json.parseToJsonElement(content).jsonObject
        val score = parseScores(obj, extractImprovements(obj)) ?: return null
        score.copy(macros = parseMacros(obj["macros"]))
    } catch (e: Exception) {
        null
    }

    /**
     * Shared core used by BOTH the pantry read (2.4a) and the compute response
     * (2.4b). [scores] holds the 8 per-dimension `{score,label}` objects + the
     * stored `overall`; [improvements] is passed separately because the compute
     * response nests scores under `scores` but keeps `improvements` at the top
     * level. **Trusts the stored `overall`** — never recomputes from dims (a
     * legacy event's missing dims default to 0 and would deflate it). Returns
     * null if `overall` is absent — both paths must agree on the headline number.
     *
     * Does not parse macros — callers attach them via [parseMacros] on the
     * parent object (compute keeps macros next to `scores`, not inside it).
     */
    fun parseScores(scores: JsonObject, improvements: List<String>): NourishScore? {
        val overallObj = scores["overall"]?.jsonObject ?: return null
        val overall = overallObj["score"]?.jsonPrimitive?.doubleOrNull
            ?.let { clampScore(it) } ?: return null
        val overallLabel = overallObj["label"]?.jsonPrimitive?.contentOrNull ?: labelFor(overall)
        val dimensions = DIMENSIONS.map { (key, name) ->
            val s = scores[key]?.jsonObject?.get("score")?.jsonPrimitive?.doubleOrNull ?: 0.0
            NourishDimension(name, clampScore(s))
        }
        return NourishScore(overall, overallLabel, dimensions, improvements)
    }

    /** Extract & clean the (top-level) `improvements` list, ≤5 non-blank entries. */
    fun extractImprovements(obj: JsonObject): List<String> =
        obj["improvements"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.take(5)
            ?: emptyList()

    /**
     * Lenient parse of a pantry/API macros block (mirrors web
     * `parseMacrosBlock`). Returns null when the shape is unusable so the UI
     * degrades to "no row" rather than shipping garbage figures. Unknown
     * future fields are ignored.
     */
    fun parseMacros(raw: JsonElement?): NourishMacros? {
        if (raw == null) return null
        return try {
            val m = raw.jsonObject
            val ps = m["perServing"]?.jsonObject ?: return null
            val kcal = asNonNegInt(ps["kcal"]) ?: return null
            val proteinG = asNonNegInt(ps["protein_g"]) ?: return null
            val carbsG = asNonNegInt(ps["carbs_g"]) ?: return null
            val fatG = asNonNegInt(ps["fat_g"]) ?: return null
            val servingsUsed = asPositiveInt(m["servingsUsed"]) ?: return null
            val servingsParsed = m["servingsParsed"]?.jsonPrimitive?.booleanOrNull ?: return null
            val confidence = m["confidence"]?.jsonPrimitive?.contentOrNull
            if (confidence != "estimate" && confidence != "rough") return null
            val methodRaw = m["method"]?.jsonPrimitive?.contentOrNull
            val method = if (methodRaw == "llm-per100g-v1") methodRaw else "llm-per100g-v1"
            NourishMacros(
                perServing = NourishMacroPerServing(kcal, proteinG, carbsG, fatG),
                servingsUsed = servingsUsed,
                servingsParsed = servingsParsed,
                confidence = confidence,
                method = method,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the macros row view, or null when the block is absent/unusable.
     * Label discipline matches web Phase 3a (`macrosRowView`) verbatim:
     *   - estimate + parsed → "Estimated per serving"
     *   - rough + parsed → "Rough estimate"
     *   - servingsParsed false → append "(servings assumed: N)"
     */
    fun macrosRowView(macros: NourishMacros?): MacrosRowView? {
        if (macros == null) return null
        val per = macros.perServing
        val tone = if (macros.confidence == "rough") "rough" else "estimate"
        val base = if (tone == "rough") "Rough estimate" else "Estimated per serving"
        val label =
            if (!macros.servingsParsed) "$base (servings assumed: ${macros.servingsUsed})"
            else base
        // Whole numbers only — server already applied honest rounding; we
        // never reformat to look more precise (no decimals).
        return MacrosRowView(
            label = label,
            tone = tone,
            kcal = per.kcal,
            proteinG = per.proteinG,
            carbsG = per.carbsG,
            fatG = per.fatG,
        )
    }

    private fun asNonNegInt(el: JsonElement?): Int? {
        val n = el?.jsonPrimitive?.doubleOrNull ?: return null
        if (!n.isFinite() || n < 0) return null
        return Math.round(n).toInt()
    }

    private fun asPositiveInt(el: JsonElement?): Int? {
        val n = el?.jsonPrimitive?.doubleOrNull ?: return null
        if (!n.isFinite() || n <= 0) return null
        return Math.round(n).toInt()
    }

    private fun clampScore(raw: Double): Int = Math.round(raw).toInt().coerceIn(0, 10)

    /** Score band → label (only used when the event omits the stored label). */
    private fun labelFor(score: Int): String = when {
        score <= 2 -> "Low"
        score <= 4 -> "Fair"
        score <= 6 -> "Moderate"
        score <= 8 -> "Strong"
        else -> "Excellent"
    }
}
