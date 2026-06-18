package com.wisp.app.api

import com.wisp.app.nostr.Nip98
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    suspend fun getPublicMembership(pubkeyHex: String): MembershipStatus = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/membership")
            .addQueryParameter("pubkey", pubkeyHex)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ZapCookingApiException(resp.code, body)
            json.decodeFromString(MembershipStatus.serializer(), body)
        }
    }

    /**
     * `POST /api/membership/check-status` — NIP-98 verified owner lookup.
     * Signs the request with [signer]; the backend returns the full
     * owner record only when the signature is valid AND the signing pubkey
     * equals the queried pubkey. An absent/invalid/mismatched signature
     * silently degrades to the public shape (it does NOT error), so the
     * proof the server accepted our NIP-98 is [MembershipStatus.owner].
     *
     * The body bytes hashed into the NIP-98 `payload` tag are the exact
     * bytes sent — [bodyString] is the single source of truth.
     */
    suspend fun checkMembershipStatus(signer: NostrSigner): MembershipStatus = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/membership/check-status"
        val bodyString = json.encodeToString(
            CheckStatusRequest.serializer(),
            CheckStatusRequest(pubkey = signer.pubkeyHex)
        )
        val authHeader = Nip98.authHeader(signer, method = "POST", url = url, bodyString = bodyString)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(bodyString.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ZapCookingApiException(resp.code, body)
            json.decodeFromString(MembershipStatus.serializer(), body)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://zap.cooking"
    }
}

@Serializable
private data class CheckStatusRequest(val pubkey: String)

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
