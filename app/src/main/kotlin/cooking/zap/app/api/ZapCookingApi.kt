package cooking.zap.app.api

import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cooking.zap.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
     * `GET /api/membership?pubkeys=<hex>` — public, unauthenticated **batch**
     * read of membership status. Use for badge surfaces and for READ_ONLY
     * accounts (which cannot sign).
     *
     * The server (see the frontend `src/routes/api/membership/+server.ts`,
     * `parsePubkeys` + the `results` map) takes a comma-separated `pubkeys`
     * query param — NOT a singular `pubkey` — and answers with a JSON object
     * keyed by the **lowercased** pubkey, each value shaped
     * `{ active, tier, expiresAt? }` (NOT the `{ found, isActive, member }`
     * shape of `check-status`). A request with no valid pubkeys yields `{}`.
     *
     * We therefore lowercase the pubkey (our pubkeys are lowercase hex, but
     * the server normalizes with `.toLowerCase()`, so the response key echoes
     * the lowercased value), send it as `pubkeys`, deserialize the keyed map,
     * and pull out our entry. A missing key (e.g. the `{}` response) maps to
     * an inactive [MembershipStatus] — the caller ([SousChefViewModel]) treats
     * inactive the same as unknown for the banner, and the server's 403 at
     * extraction time stays authoritative.
     */
    suspend fun getPublicMembership(pubkeyHex: String): MembershipStatus {
        val lookupKey = pubkeyHex.lowercase()
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/membership")
            .addQueryParameter("pubkeys", lookupKey)
            .build()
        val byPubkey = getJson(url, BATCH_MEMBERSHIP_SERIALIZER)
        return mapBatchMembership(byPubkey, lookupKey)
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
     * `Dispatchers.IO`. Pass [httpClient] to override the general client
     * (e.g. the long-timeout compute client for LLM endpoints).
     */
    private suspend fun <T> authedPost(
        path: String,
        bodyString: String,
        signer: NostrSigner,
        deserializer: DeserializationStrategy<T>,
        httpClient: OkHttpClient = client,
    ): T = withContext(Dispatchers.IO) {
        val url = "$baseUrl$path"
        val authHeader = Nip98.authHeader(signer, method = "POST", url = url, bodyString = bodyString)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(bodyString.toRequestBody(jsonMediaType))
            .build()
        execute(request, deserializer, httpClient)
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
        httpClient: OkHttpClient = client,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(bodyString.toRequestBody(jsonMediaType))
            .build()
        execute(request, deserializer, httpClient)
    }

    /**
     * `POST /api/extract-recipe/public` — anon URL-only recipe import (Sous
     * Chef, concern 2.1). Free + per-IP rate-limited; no pubkey, no NIP-98.
     * Returns the structured [NormalizedRecipe], NOT markdown.
     *
     * Uses the long-timeout compute client: server-side URL extraction (page
     * fetch + LLM parse) routinely exceeds the general client's 15s read
     * timeout, same as the image/text extraction and Nourish/Cheffy paths.
     */
    suspend fun extractRecipeFromUrl(url: String): ExtractRecipeResponse =
        postJson(
            "/api/extract-recipe/public",
            json.encodeToString(ExtractUrlRequest.serializer(), ExtractUrlRequest(url)),
            ExtractRecipeResponse.serializer(),
            httpClient = HttpClientFactory.getComputeClient(),
        )

    /**
     * `POST /api/extract-recipe` — NIP-98-authenticated image extraction
     * (Sous Chef, Phase 3). [dataUrl] is the prepared
     * `data:image/jpeg;base64,…` string, consumed verbatim server-side.
     * Member-gated: identity comes from the verified header, NOT a body
     * pubkey. Uses the long-timeout compute client (vision extraction
     * routinely exceeds the general 15s read timeout).
     */
    suspend fun extractRecipeFromImage(dataUrl: String, signer: NostrSigner): ExtractAuthedResult =
        extractAuthed(
            json.encodeToString(
                ExtractImageRequest.serializer(),
                ExtractImageRequest(type = "image", imageData = dataUrl),
            ),
            signer,
        )

    /** `POST /api/extract-recipe` — NIP-98-authenticated text extraction. */
    suspend fun extractRecipeFromText(text: String, signer: NostrSigner): ExtractAuthedResult =
        extractAuthed(
            json.encodeToString(
                ExtractTextRequest.serializer(),
                ExtractTextRequest(type = "text", textData = text),
            ),
            signer,
        )

    /**
     * Shared image/text extraction call. Maps the member-gate statuses (401 →
     * [ExtractAuthedResult.SignInRequired], 403 →
     * [ExtractAuthedResult.MembersOnly]); signer failures
     * ([cooking.zap.app.nostr.SignerRejectedException] /
     * [cooking.zap.app.nostr.SignerCancelledException]) and cancellation
     * propagate to the caller — a declined Amber prompt is not a network
     * error.
     */
    private suspend fun extractAuthed(bodyString: String, signer: NostrSigner): ExtractAuthedResult =
        try {
            val resp = authedPost(
                "/api/extract-recipe",
                bodyString,
                signer,
                ExtractRecipeResponse.serializer(),
                httpClient = HttpClientFactory.getComputeClient(),
            )
            val recipe = resp.recipe
            if (resp.success && recipe != null) {
                ExtractAuthedResult.Success(recipe)
            } else {
                ExtractAuthedResult.Error(resp.error ?: "Couldn't extract a recipe.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SignerRejectedException) {
            throw e
        } catch (e: SignerCancelledException) {
            throw e
        } catch (e: ZapCookingApiException) {
            when (e.code) {
                401 -> ExtractAuthedResult.SignInRequired
                403 -> ExtractAuthedResult.MembersOnly
                else -> ExtractAuthedResult.Error(
                    parseError(e.body) ?: "Import failed (${e.code})."
                )
            }
        } catch (e: Exception) {
            ExtractAuthedResult.Error("Network error — check your connection and try again.")
        }

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

    /**
     * `POST /api/zappy` — Cheffy, the member-gated kitchen-companion chat
     * (concern 2.3; endpoint stays `/zappy` for back-compat, the feature is
     * "Cheffy"). pubkey-in-body (not NIP-98, same as the other AI endpoints).
     * **Stateless full-history**: the client passes the live thread
     * ([CheffyRequest.messages]) every request — the server keeps no session.
     * Whole-response (no streaming), so it uses the long-timeout compute client
     * — `gpt-4.1-mini` replies routinely exceed the general 15s read timeout.
     * 403 → [CheffyResult.MembersOnly], mirroring [computeNourish].
     */
    suspend fun sendCheffy(request: CheffyRequest): CheffyResult =
        withContext(Dispatchers.IO) {
            try {
                val bodyString = json.encodeToString(CheffyRequest.serializer(), request)
                val httpRequest = Request.Builder()
                    .url("$baseUrl/api/zappy")
                    .post(bodyString.toRequestBody(jsonMediaType))
                    .build()
                HttpClientFactory.getComputeClient().newCall(httpRequest).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 403) return@withContext CheffyResult.MembersOnly
                    // The server reports failures as { ok:false, error } even on
                    // a 200, so parse the body rather than trusting the status.
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@withContext CheffyResult.Error(parseError(body) ?: "Cheffy could not respond.")
                    val ok = obj["ok"]?.let { it.toString() == "true" } == true
                    if (!resp.isSuccessful || !ok) {
                        val msg = obj["error"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            ?: parseError(body)
                        return@withContext CheffyResult.Error(msg ?: "Cheffy could not respond.")
                    }
                    val output = obj["output"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.trim()
                    if (output.isNullOrEmpty()) {
                        return@withContext CheffyResult.Error("Cheffy went quiet. Please try again.")
                    }
                    CheffyResult.Reply(output)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CheffyResult.Error("Network error — please try again.")
            }
        }

    /** Single error/decode path. Call only from a `Dispatchers.IO` context. */
    private fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
        httpClient: OkHttpClient = client,
    ): T {
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ZapCookingApiException(resp.code, body)
            return json.decodeFromString(deserializer, body)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://zap.cooking"

        /** Deserializer for the `/api/membership` keyed-map batch response. */
        internal val BATCH_MEMBERSHIP_SERIALIZER =
            MapSerializer(String.serializer(), PublicMembershipEntry.serializer())

        /**
         * Project the batch endpoint's per-pubkey entry onto the shared
         * [MembershipStatus] (the badge/banner UI only reads `isActive`). The
         * entry's `active` maps to `isActive`, and its top-level `tier` moves
         * under `member`. A missing [lookupKey] (empty `{}` response, or the
         * pubkey simply absent) → an inactive status. Pure — unit-tested
         * against real-shape fixtures.
         */
        internal fun mapBatchMembership(
            byPubkey: Map<String, PublicMembershipEntry>,
            lookupKey: String,
        ): MembershipStatus {
            val entry = byPubkey[lookupKey]
                ?: return MembershipStatus(found = false, isActive = false)
            return MembershipStatus(
                found = true,
                isActive = entry.active,
                member = MembershipStatus.Member(
                    tier = entry.tier,
                    subscription_end = entry.expiresAt,
                ),
            )
        }
    }
}

/**
 * One entry in the `/api/membership` batch response
 * (`Record<pubkey, { active, tier, expiresAt? }>`). Distinct from
 * [MembershipStatus] because the batch endpoint uses `active`/top-level `tier`,
 * whereas `check-status` uses `isActive`/nested `member.tier`. Lenient defaults
 * so a partial entry never throws.
 */
@Serializable
internal data class PublicMembershipEntry(
    val active: Boolean = false,
    val tier: String? = null,
    val expiresAt: String? = null,
)

@Serializable
private data class CheckStatusRequest(val pubkey: String)

@Serializable
private data class ExtractUrlRequest(val url: String)

// No property defaults: the shared Json has encodeDefaults=false, so a
// defaulted `type` would be silently dropped from the wire body.
@Serializable
private data class ExtractImageRequest(val type: String, val imageData: String)

@Serializable
private data class ExtractTextRequest(val type: String, val textData: String)

/**
 * Outcome of the authenticated `/api/extract-recipe` call (image/text).
 * Mirrors [CheffyResult]/[NourishComputeResult], plus 401 → [SignInRequired]
 * (NIP-98 rejected — stale/invalid auth) distinct from 403 → [MembersOnly]
 * (valid auth, no active membership).
 */
sealed interface ExtractAuthedResult {
    data class Success(val recipe: NormalizedRecipe) : ExtractAuthedResult
    data object SignInRequired : ExtractAuthedResult
    data object MembersOnly : ExtractAuthedResult
    data class Error(val message: String) : ExtractAuthedResult
}

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

/** Cheffy chat mode (concern 2.3). `chat` = conversation; `hungry` = "surprise me". */
enum class CheffyMode(val wire: String) { CHAT("chat"), HUNGRY("hungry") }

/** One prior turn in the stateless history the client re-sends each request. */
@Serializable
data class CheffyMessage(val role: String, val content: String)

/**
 * `POST /api/zappy` request (concern 2.3). [pubkey] is the signed-in user's
 * (membership gate). [messages] is the live thread (stateless — re-sent every
 * request; server keeps no session). For [CheffyMode.HUNGRY] the server
 * supplies its own prompt, so [prompt] is empty.
 */
@Serializable
data class CheffyRequest(
    val prompt: String,
    val mode: String,
    val pubkey: String,
    val messages: List<CheffyMessage>,
)

/** Outcome of [ZapCookingApi.sendCheffy]. Mirrors [NourishComputeResult]. */
sealed interface CheffyResult {
    data class Reply(val output: String) : CheffyResult
    /** 403 — the account isn't an active member. */
    object MembersOnly : CheffyResult
    data class Error(val message: String) : CheffyResult
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
