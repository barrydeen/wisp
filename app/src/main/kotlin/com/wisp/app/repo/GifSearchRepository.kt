package com.wisp.app.repo

import com.wisp.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Search/suggest failed — [kind] picks the message the picker shows. */
class GifSearchException(val kind: GifErrorKind, cause: Throwable? = null) : Exception(kind.name, cause)

/**
 * gifs.nostr.build search client — the native-client counterpart of the web
 * app's /api/gif-search proxy (frontend PR #827). The integration guide
 * reserves Authorization headers for server and native clients, so the key
 * ships here in BuildConfig and calls go direct; the proxy exists on the web
 * only because a browser bundle can't hold a secret.
 *
 * baseUrl and client are constructor-injected so MockWebServer tests can pin
 * the wire contract, like ZapCookingApi.
 */
class GifSearchRepository(
    private val baseUrl: String = API_BASE,
    private val apiKey: String = BuildConfig.GIFS_NOSTR_BUILD_API_KEY,
    private val client: OkHttpClient = sharedClient
) {

    companion object {
        const val API_BASE = "https://gifs.nostr.build/api/v1"

        private val sharedClient by lazy {
            com.wisp.app.relay.HttpClientFactory.createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 15
            )
        }

        private val json = Json { ignoreUnknownKeys = true }
    }

    /** One page of results for [query] at [offset]; blank queries answer empty. */
    suspend fun search(query: String, offset: Int = 0): GifPage {
        if (clipQuery(query).isEmpty()) return GifPage(emptyList(), null)
        val body = request(gifSearchUrl(baseUrl, query, offset)) ?: return GifPage(emptyList(), null)
        return parseGifPage(body)
    }

    /** Search-term suggestions for [query]; blank queries answer empty. */
    suspend fun suggest(query: String): List<String> {
        if (clipQuery(query).isEmpty()) return emptyList()
        val body = request(gifSuggestUrl(baseUrl, query)) ?: return emptyList()
        return parseGifSuggestions(body)
    }

    /**
     * GET [url] and parse the JSON body. Non-2xx answers map to the picker's
     * own messages by upstream status — a refused key (401/403), a rate limit
     * (429) and an unconfigured service (503) must not be flattened into a
     * generic failure. A cancelled coroutine aborts the call in flight.
     */
    private suspend fun request(url: String): JsonElement? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GifSearchException(GifErrorKind.UNAVAILABLE)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw GifSearchException(GifErrorKind.CONNECTION, e)
        }
        response.use {
            if (!it.isSuccessful) throw GifSearchException(gifErrorKind(it.code))
            // A body cut off mid-read or a 2xx that isn't JSON fails the same
            // way a dropped connection does — never as a raw exception.
            try {
                val text = it.body?.string() ?: return@withContext null
                json.parseToJsonElement(text)
            } catch (e: IOException) {
                throw GifSearchException(GifErrorKind.CONNECTION, e)
            } catch (e: SerializationException) {
                throw GifSearchException(GifErrorKind.CONNECTION, e)
            }
        }
    }

    /** OkHttp async call as a cancellable suspend — aborts the wire request on coroutine cancel. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                    return
                }
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}

/** The production instance; sheet-level state is per-open, the client is not. */
val gifSearchRepository: GifSearchRepository by lazy { GifSearchRepository() }
