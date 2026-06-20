package cooking.zap.app.api

import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import kotlinx.serialization.json.jsonObject
import cooking.zap.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Authenticated client for the zap.cooking backend (base
 * `https://zap.cooking`). AI and membership logic live server-side; this
 * client only calls HTTPS endpoints — it never holds OpenAI/Strike/Stripe
 * keys (see ZAPCOOKING_ANDROID_BUILD.md §"Backend-as-API rule").
 *
 * All network runs on `Dispatchers.IO`. Reuses the shared OkHttp pool
 * from [HttpClientFactory].
 */
class ZapCookingApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = HttpClientFactory.getGeneralClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * `GET /api/membership?pubkey=<hex>` — public, unauthenticated batch
     * read of membership status. Use for badge surfaces and for READ_ONLY
     * accounts (which cannot sign). Returns the public response shape.
     */
    suspend fun getPublicMembership(pubkeyHex: String): MembershipStatus {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/membership")
            .addQueryParameter("pubkey", pubkeyHex)
            .build()
        return getJson(url, MembershipStatus.serializer())
    }

    /**
     * `POST /api/membership/check-status` — NIP-98 verified owner lookup.
     * Signs the request with [signer]; the backend returns the full
     * owner record only when the signature is valid AND the signing pubkey
     * equals the queried pubkey. An absent/invalid/mismatched signature
     * silently degrades to the public shape (it does NOT error), so the
     * proof the server accepted our NIP-98 is [MembershipStatus.owner].
     */
    suspend fun checkMembershipStatus(signer: NostrSigner): MembershipStatus {
        val bodyString = json.encodeToString(
            CheckStatusRequest.serializer(),
            CheckStatusRequest(pubkey = signer.pubkeyHex)
        )
        return authedPost("/api/membership/check-status", bodyString, signer, MembershipStatus.serializer())
    }

    // --- Request spine (shared by membership today + the AI endpoints in Phase 2) ---

    /**
     * NIP-98-authenticated POST. Signs [bodyString] via [signer] (the exact
     * bytes hashed into the `payload` tag are the bytes sent — single source
     * of truth), then runs the shared execute/error/decode path on
     * `Dispatchers.IO`.
     */
    private suspend fun <T> authedPost(
        path: String,
        bodyString: String,
        signer: NostrSigner,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = "$baseUrl$path"
        val authHeader = Nip98.authHeader(signer, method = "POST", url = url, bodyString = bodyString)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(bodyString.toRequestBody(jsonMediaType))
            .build()
        execute(request, deserializer)
    }

    /** Unauthenticated GET on `Dispatchers.IO`, sharing the execute path. */
    private suspend fun <T> getJson(
        url: HttpUrl,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).get().build(), deserializer)
    }

    /**
     * Unauthenticated JSON POST on `Dispatchers.IO`. The Phase 2 AI endpoints
     * gate on a client-supplied `pubkey` in the body (not NIP-98 — see build
     * doc §Phase 2), so they use this rather than [authedPost]. Non-2xx throws
     * [ZapCookingApiException] carrying the HTTP code + body, so callers can
     * distinguish 400 (bad URL) / 429 (rate-limited) / 403 (membership).
     */
    private suspend fun <T> postJson(
        path: String,
        bodyString: String,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(bodyString.toRequestBody(jsonMediaType))
            .build()
        execute(request, deserializer)
    }

    /**
     * `POST /api/extract-recipe/public` — anon URL-only recipe import (Sous
     * Chef, concern 2.1). Free + per-IP rate-limited; no pubkey, no NIP-98.
     * Returns the structured [NormalizedRecipe], NOT markdown.
     */
    suspend fun extractRecipeFromUrl(url: String): ExtractRecipeResponse =
        postJson(
            "/api/extract-recipe/public",
            json.encodeToString(ExtractUrlRequest.serializer(), ExtractUrlRequest(url)),
            ExtractRecipeResponse.serializer(),
        )

    /** Best-effort extraction of the server's `{ "error": ... }` from a 4xx body. */
    fun parseError(body: String): String? = try {
        json.decodeFromString(ExtractRecipeResponse.serializer(), body).error
    } catch (_: Exception) {
        null
    }

    /**
     * `POST /api/nourish` — member-gated compute (concern 2.4b). pubkey-in-body
     * (not NIP-98, same as the other AI endpoints). The response carries the
     * score directly, so we parse it here (no pantry re-read); the server also
     * publishes to pantry for future viewers. Uses the long-timeout compute
     * client — LLM scoring + the awaited pantry publish routinely exceed 15s.
     * Lenient: ignores audience_scores/promptVersion/createdAt for v1.
     */
    suspend fun computeNourish(request: NourishComputeRequest): NourishComputeResult =
        withContext(Dispatchers.IO) {
            try {
                val bodyString = json.encodeToString(NourishComputeRequest.serializer(), request)
                val httpRequest = Request.Builder()
                    .url("$baseUrl/api/nourish")
                    .post(bodyString.toRequestBody(jsonMediaType))
                    .build()
                HttpClientFactory.getComputeClient().newCall(httpRequest).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 403) return@withContext NourishComputeResult.MembersOnly
                    if (!resp.isSuccessful) {
                        return@withContext NourishComputeResult.Error(
                            parseError(body) ?: "Couldn't compute the Nourish score (${resp.code})."
                        )
                    }
                    val obj = json.parseToJsonElement(body).jsonObject
                    val scores = obj["scores"]?.jsonObject
                        ?: return@withContext NourishComputeResult.Error("No score in the response.")
                    val score = NourishParser.parseScores(scores, NourishParser.extractImprovements(obj))
                        ?: return@withContext NourishComputeResult.Error("Couldn't read the Nourish score.")
                    NourishComputeResult.Success(score)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                NourishComputeResult.Error("Network error — please try again.")
            }
        }

    /** Single error/decode path. Call only from a `Dispatchers.IO` context. */
    private fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ZapCookingApiException(resp.code, body)
            return json.decodeFromString(deserializer, body)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://zap.cooking"
    }
}

@Serializable
private data class CheckStatusRequest(val pubkey: String)

@Serializable
private data class ExtractUrlRequest(val url: String)

/**
 * `POST /api/nourish` request (concern 2.4b). pubkey is the signed-in user's
 * (membership gate); recipePubkey/recipeDTag/contentHash let the server publish
 * the result to pantry for future viewers. contentHash = SHA-256 of the recipe
 * event's raw content (UTF-8), byte-exact with the server.
 */
@Serializable
data class NourishComputeRequest(
    val pubkey: String,
    val eventId: String,
    val title: String,
    val ingredients: List<String>,
    val tags: List<String>,
    val servings: String,
    val recipePubkey: String,
    val recipeDTag: String,
    val contentHash: String,
)

/** Outcome of [ZapCookingApi.computeNourish]. */
sealed interface NourishComputeResult {
    data class Success(val score: NourishScore) : NourishComputeResult
    /** 403 — the account isn't an active member. */
    object MembersOnly : NourishComputeResult
    data class Error(val message: String) : NourishComputeResult
}

/** `/api/extract-recipe(/public)` response. Lenient — unknown keys ignored. */
@Serializable
data class ExtractRecipeResponse(
    val success: Boolean = false,
    val recipe: NormalizedRecipe? = null,
    val error: String? = null,
)

/**
 * The structured recipe the import endpoint returns — NOT markdown. Field
 * names match the server's `NormalizedRecipe` exactly (validated live). All
 * defaulted so a partial response never throws.
 */
@Serializable
data class NormalizedRecipe(
    val title: String = "",
    val summary: String = "",
    val chefsnotes: String = "",
    val preptime: String = "",
    val cooktime: String = "",
    val servings: String = "",
    val ingredients: List<String> = emptyList(),
    val directions: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
) {
    /**
     * Map to a [cooking.zap.app.nostr.RecipeParser.Recipe] for the read-only
     * import preview, reusing the recipe-detail rendering. Pure (unit-tested).
     * `id`/`author`/`dTag` are empty (not a published event); the missing
     * author means the preview shows no byline/date. Empty `imageUrls` →
     * `image = null` → no hero (guarded — never `imageUrls.first` on empty).
     */
    fun toRecipePreview(): cooking.zap.app.nostr.RecipeParser.Recipe =
        cooking.zap.app.nostr.RecipeParser.Recipe(
            id = "",
            author = "",
            dTag = "",
            title = title.ifBlank { null },
            image = imageUrls.firstOrNull(),
            summary = summary.ifBlank { null },
            publishedAt = 0L,
            hashtags = tags,
            categories = emptyList(),
            content = cooking.zap.app.nostr.RecipeParser.RecipeContent(
                chefNotes = chefsnotes.ifBlank { null },
                details = cooking.zap.app.nostr.RecipeParser.RecipeDetails(
                    prepTime = preptime.ifBlank { null },
                    cookTime = cooktime.ifBlank { null },
                    servings = servings.ifBlank { null },
                ),
                ingredients = ingredients,
                directions = directions,
                additionalMarkdown = null,
            ),
        )
}

/**
 * Response for both `/api/membership` (public) and
 * `/api/membership/check-status`. Lenient by design — the public and
 * owner shapes differ, and unknown keys are ignored for forward-compat.
 * [owner] is true only when the server verified a NIP-98 signature from
 * the queried pubkey itself.
 */
@Serializable
data class MembershipStatus(
    val found: Boolean = false,
    val isActive: Boolean = false,
    val isExpired: Boolean? = null,
    val owner: Boolean = false,
    val member: Member? = null,
    val error: String? = null,
) {
    @Serializable
    data class Member(
        val pubkey: String? = null,
        val tier: String? = null,
        val status: String? = null,
        val subscription_end: String? = null,
        val subscription_start: String? = null,
        val payment_method: String? = null,
    )
}

/** Non-2xx response from the zap.cooking backend. */
class ZapCookingApiException(val code: Int, val body: String) :
    Exception("zap.cooking API error $code: $body")
