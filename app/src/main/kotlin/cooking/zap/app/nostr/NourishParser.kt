package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One of the 8 Nourish health dimensions: display name + 0–10 score. */
data class NourishDimension(val name: String, val score: Int)

/** A recipe's Nourish health analysis (concern 2.4a). */
data class NourishScore(
    val overall: Int,
    val overallLabel: String,
    val dimensions: List<NourishDimension>,
    val improvements: List<String>,
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
 * Pure (JSON + maps) — unit-tested.
 */
object NourishParser {

    /** The Zap Cooking service account that publishes Nourish events. */
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

    fun parse(content: String): NourishScore? = try {
        val obj = json.parseToJsonElement(content).jsonObject
        val overallObj = obj["overall"]?.jsonObject ?: return null
        // Trust the stored overall; we can't render without it. Clamp to 0..10
        // so malformed/future-scale values can't break the score bars downstream.
        val overall = overallObj["score"]?.jsonPrimitive?.doubleOrNull
            ?.let { clampScore(it) } ?: return null
        val overallLabel = overallObj["label"]?.jsonPrimitive?.contentOrNull ?: labelFor(overall)

        val dimensions = DIMENSIONS.map { (key, name) ->
            val s = obj[key]?.jsonObject?.get("score")?.jsonPrimitive?.doubleOrNull ?: 0.0
            NourishDimension(name, clampScore(s))
        }
        val improvements = obj["improvements"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.take(5)
            ?: emptyList()

        NourishScore(overall, overallLabel, dimensions, improvements)
    } catch (e: Exception) {
        null
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
