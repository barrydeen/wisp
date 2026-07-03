package cooking.zap.app.repo

import cooking.zap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

data class GiphyGif(
    val id: String,
    val title: String,
    val previewUrl: String,
    val downloadUrl: String
)

/** Giphy trending/search, mirroring the web client's GifPicker.svelte. */
object GiphyRepository {
    private const val API_BASE = "https://api.giphy.com/v1/gifs"
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        cooking.zap.app.relay.HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 15
        )
    }

    val isConfigured: Boolean get() = BuildConfig.GIPHY_API_KEY.isNotBlank()

    // Shown when no API key is configured, so the picker stays usable out of the box —
    // mirrors the web client's fallback tiles in GifPicker.svelte.
    private val fallback = listOf(
        GiphyGif(
            id = "test-gif",
            title = "Test GIF",
            previewUrl = "https://c.tenor.com/ozqCVlQw6M4AAAAd/tenor.gif",
            downloadUrl = "https://c.tenor.com/ozqCVlQw6M4AAAAd/tenor.gif"
        ),
        GiphyGif(
            id = "test-webp",
            title = "Test WebP",
            previewUrl = "https://i.giphy.com/xT5LMzIK1AdZJ4cYW4.webp",
            downloadUrl = "https://i.giphy.com/xT5LMzIK1AdZJ4cYW4.webp"
        )
    )

    suspend fun trending(limit: Int = 24): List<GiphyGif> {
        if (!isConfigured) return fallback
        return fetch("$API_BASE/trending?api_key=${BuildConfig.GIPHY_API_KEY}&limit=$limit&rating=g")
    }

    suspend fun search(query: String, limit: Int = 24): List<GiphyGif> {
        if (query.isBlank()) return trending(limit)
        if (!isConfigured) return fallback
        val q = URLEncoder.encode(query, "UTF-8")
        return fetch("$API_BASE/search?api_key=${BuildConfig.GIPHY_API_KEY}&q=$q&limit=$limit&rating=g")
    }

    private suspend fun fetch(url: String): List<GiphyGif> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?: return@withContext emptyList()
                data.mapNotNull { element ->
                    val obj = element.jsonObject
                    val images = obj["images"]?.jsonObject ?: return@mapNotNull null
                    val preview = images["fixed_width_small"]?.jsonObject
                        ?.get("url")?.jsonPrimitive?.content ?: return@mapNotNull null
                    val download = images["downsized"]?.jsonObject
                        ?.get("url")?.jsonPrimitive?.content ?: preview
                    GiphyGif(
                        id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        previewUrl = preview,
                        downloadUrl = download
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
