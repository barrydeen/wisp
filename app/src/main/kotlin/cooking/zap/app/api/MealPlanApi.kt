package cooking.zap.app.api

import cooking.zap.app.mealplan.MealPlanGeneration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire DTOs and result type for `POST /api/zappy/meal-plan`.
 *
 * Domain types live in [MealPlanGeneration] and stay serialization-free.
 * This file is the only place Android-only fields (preview `image`, etc.)
 * are stripped before they can reach the wire.
 *
 * Encoder: [explicitNulls] = false so unset optionals are omitted rather
 * than sent as `null` — the server treats missing and null the same, but
 * NIP-98 hashes the exact bytes, and we want the body to match the web
 * client's `JSON.stringify` (which drops `undefined`).
 */
internal val mealPlanWireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Outcome of [ZapCookingApi.requestMealPlan]. Code-first for
 * `no-candidates`; every other bucket is status-led. There is no
 * membership-unavailable variant — this endpoint fails open on a
 * membership-service outage.
 */
sealed interface MealPlanResult {
    data class Ok(val meals: List<MealPlanGeneration.GeneratedMeal>) : MealPlanResult

    /** 401 — NIP-98 rejected or missing. */
    data object SignInRequired : MealPlanResult

    /** 403 / `NOT_MEMBER`. [message] is the server's user-facing copy. */
    data class MembersOnly(val message: String) : MealPlanResult

    /** 429 / `RATE_LIMITED`. Per-IP (10/hr, 30/day), not per-pubkey. */
    data class RateLimited(val retryAfter: Int? = null) : MealPlanResult

    /**
     * Body `code` `no-candidates`, any status. Expected outcome when the
     * library cannot cover the requested slots (e.g. 7 breakfasts from a
     * dinner set) — not a caller bug.
     */
    data class NoCandidates(val message: String) : MealPlanResult

    /** Other 400 codes, plus local pre-flight (empty / too-many). */
    data class InvalidRequest(val code: String, val message: String) : MealPlanResult

    /** 422 codes, plus local re-validation failure (invariant I4). */
    data class Rejected(val code: String, val message: String) : MealPlanResult

    /** Signer declined or cancelled. */
    data object SignFailed : MealPlanResult

    /** 5xx, unparseable body, network. */
    data class Failed(val message: String) : MealPlanResult
}

@Serializable
internal data class MealPlanWireRequest(
    val weekId: String,
    val days: List<String>,
    val mealSlots: List<String>,
    val strategy: String,
    val candidates: List<MealPlanWireCandidate>,
    val preferences: MealPlanWirePreferences,
    val occupiedSlots: List<MealPlanWireSlotRef>? = null,
    val fillSlots: List<MealPlanWireSlotRef>? = null,
    val excludeCoordinates: List<String>? = null,
)

@Serializable
internal data class MealPlanWireCandidate(
    val a: String,
    val title: String,
    val tags: List<String>,
    val ingredients: List<String>,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val servings: String? = null,
)

@Serializable
internal data class MealPlanWirePreferences(
    val styles: List<String>,
    val maxMinutes: Int? = null,
    val servings: Int? = null,
    val excludeIngredients: List<String>? = null,
    val notes: String? = null,
)

@Serializable
internal data class MealPlanWireSlotRef(
    val day: String,
    val slot: String,
)

@Serializable
internal data class MealPlanApiResponse(
    val ok: Boolean = false,
    val plan: MealPlanApiPlan? = null,
    val error: String? = null,
    val code: String? = null,
    val retryAfter: Int? = null,
)

@Serializable
internal data class MealPlanApiPlan(
    val meals: List<MealPlanApiMeal> = emptyList(),
)

@Serializable
internal data class MealPlanApiMeal(
    val day: String = "",
    val slot: String = "",
    val a: String = "",
    val title: String = "",
    val reason: String? = null,
)

internal fun MealPlanApiMeal.toGeneratedMeal(): MealPlanGeneration.GeneratedMeal =
    MealPlanGeneration.GeneratedMeal(
        day = day,
        slot = slot,
        a = a,
        title = title,
        reason = reason,
    )

internal fun MealPlanGeneration.MealPlanGenerationRequest.toWire(): MealPlanWireRequest =
    MealPlanWireRequest(
        weekId = weekId,
        days = days,
        mealSlots = mealSlots,
        strategy = strategy.id,
        candidates = candidates.map { it.toWire() },
        preferences = preferences.toWire(),
        occupiedSlots = occupiedSlots.takeIf { it.isNotEmpty() }?.map {
            MealPlanWireSlotRef(it.day, it.slot)
        },
        fillSlots = fillSlots.takeIf { it.isNotEmpty() }?.map {
            MealPlanWireSlotRef(it.day, it.slot)
        },
        excludeCoordinates = excludeCoordinates.takeIf { it.isNotEmpty() },
    )

/**
 * Copy only the wire-legal fields. [MealPlanGeneration.RecipeCandidate]
 * currently has no Android-only properties; listing the seven fields
 * here is the drop-gate if one is added (image, pantry, local path, …).
 */
private fun MealPlanGeneration.RecipeCandidate.toWire(): MealPlanWireCandidate =
    MealPlanWireCandidate(
        a = a,
        title = title,
        tags = tags,
        ingredients = ingredients,
        prepTime = prepTime,
        cookTime = cookTime,
        servings = servings,
    )

private fun MealPlanGeneration.MealPlanPreferences.toWire(): MealPlanWirePreferences =
    MealPlanWirePreferences(
        styles = styles.map { it.id },
        maxMinutes = maxMinutes,
        servings = servings,
        excludeIngredients = excludeIngredients,
        notes = notes,
    )

/** Serialize the body exactly once. The returned String is what we sign and send. */
internal fun encodeMealPlanBody(request: MealPlanGeneration.MealPlanGenerationRequest): String =
    mealPlanWireJson.encodeToString(MealPlanWireRequest.serializer(), request.toWire())
