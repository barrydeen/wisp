package cooking.zap.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "splash_food_cache"
private const val PREF_URLS = "food_photo_urls_v2"
private const val PREF_TIMESTAMP = "food_photo_timestamp_v2"
private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours
private const val MIN_FOLLOWS = 20                        // minimum contacts to pass spam filter
private const val TARGET_PHOTOS = 80
private const val FETCH_TIMEOUT_MS = 20_000L

private val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png)(?:[?#]\S*)?""",
    RegexOption.IGNORE_CASE
)

private val RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
)

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _foodPhotos = MutableStateFlow<List<String>>(emptyList())
    val foodPhotos: StateFlow<List<String>> = _foodPhotos

    private var fetchJob: Job? = null
    private var okHttpClient: OkHttpClient? = null

    private val json = Json { ignoreUnknownKeys = true }

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Show cached photos immediately while refreshing in background
        val cached = loadCache(prefs)
        if (cached.isNotEmpty()) {
            _foodPhotos.value = cached
        }

        val needsRefresh = (System.currentTimeMillis() - prefs.getLong(PREF_TIMESTAMP, 0)) > CACHE_TTL_MS
        if (cached.isEmpty() || needsRefresh) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            okHttpClient = client

            fetchJob = viewModelScope.launch(Dispatchers.IO) {
                val fresh = fetchFoodPhotos(client)
                if (fresh.isNotEmpty()) {
                    saveCache(prefs, fresh)
                    _foodPhotos.value = fresh
                }
            }
        }
    }

    private suspend fun fetchFoodPhotos(client: OkHttpClient): List<String> {
        // Phase 1: collect image URLs + their authors from #foodstr notes
        data class Candidate(val imageUrl: String, val pubkey: String)

        val candidates = Channel<Candidate?>(capacity = Channel.UNLIMITED)
        val noteSubId = "foodstr_notes"

        val sockets = RELAY_URLS.map { url ->
            val req = Request.Builder().url(url).build()
            client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """["REQ","$noteSubId",{"kinds":[1],"#t":["foodstr"],"limit":300}]"""
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = json.parseToJsonElement(text) as? JsonArray ?: return
                        val type = arr[0].jsonPrimitive.content
                        when {
                            type == "EVENT" && arr[1].jsonPrimitive.content == noteSubId -> {
                                val event = arr[2] as? kotlinx.serialization.json.JsonObject ?: return
                                val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                                val content = event["content"]?.jsonPrimitive?.content ?: ""

                                // Also check imeta tags for image URLs
                                val tagUrls = (event["tags"] as? JsonArray)
                                    ?.mapNotNull { tag ->
                                        val t = tag as? JsonArray ?: return@mapNotNull null
                                        val key = t.getOrNull(0)?.jsonPrimitive?.content
                                        if (key == "imeta" || key == "image") {
                                            t.drop(1).mapNotNull { v ->
                                                val s = v.jsonPrimitive.content
                                                if (s.startsWith("url ")) s.removePrefix("url ")
                                                else if (IMAGE_URL_REGEX.containsMatchIn(s)) s
                                                else null
                                            }.firstOrNull()
                                        } else null
                                    } ?: emptyList()

                                val contentUrls = IMAGE_URL_REGEX.findAll(content)
                                    .map { it.value }
                                    .toList()

                                (tagUrls + contentUrls).forEach { url ->
                                    candidates.trySend(Candidate(url, pubkey))
                                }
                            }
                            type == "EOSE" && arr[1].jsonPrimitive.content == noteSubId -> {
                                candidates.trySend(null)
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    candidates.trySend(null)
                }
            })
        }

        // Drain note phase until all relays signal EOSE or timeout
        val collected = mutableListOf<Candidate>()
        val deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS / 2
        var eoseCount = 0

        while (eoseCount < RELAY_URLS.size && System.currentTimeMillis() < deadline) {
            var result = candidates.tryReceive()
            while (result.isSuccess) {
                val item = result.getOrNull()
                if (item == null) eoseCount++ else collected += item
                result = candidates.tryReceive()
            }
            delay(80)
        }

        candidates.close()

        // Close note subscriptions and reclaim sockets for Phase 2
        for (socket in sockets) {
            try { socket.send("""["CLOSE","$noteSubId"]""") } catch (_: Exception) {}
        }

        if (collected.isEmpty()) {
            for (socket in sockets) try { socket.close(1000, null) } catch (_: Exception) {}
            return emptyList()
        }

        // Phase 2: verify authors via kind:3 contact-list size
        val pubkeyToCandidates = collected.groupBy { it.pubkey }
        val allPubkeys = pubkeyToCandidates.keys.toList()
        val approvedPubkeys = mutableSetOf<String>()

        val authorSubId = "foodstr_authors"
        val authorChannel = Channel<Pair<String, Int>?>(capacity = Channel.UNLIMITED) // pubkey → contact count

        // Re-use the same sockets for author queries
        val authorSockets = RELAY_URLS.map { url ->
            val req = Request.Builder().url(url).build()
            client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Query kind:3 (contact list) for all candidate pubkeys
                    val pubkeysJson = allPubkeys.joinToString(",") { "\"$it\"" }
                    webSocket.send(
                        """["REQ","$authorSubId",{"kinds":[3],"authors":[$pubkeysJson]}]"""
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = json.parseToJsonElement(text) as? JsonArray ?: return
                        val type = arr[0].jsonPrimitive.content
                        when {
                            type == "EVENT" && arr[1].jsonPrimitive.content == authorSubId -> {
                                val event = arr[2] as? kotlinx.serialization.json.JsonObject ?: return
                                val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                                val tags = event["tags"] as? JsonArray ?: return
                                val followCount = tags.count { tag ->
                                    (tag as? JsonArray)?.getOrNull(0)
                                        ?.jsonPrimitive?.content == "p"
                                }
                                authorChannel.trySend(Pair(pubkey, followCount))
                            }
                            type == "EOSE" && arr[1].jsonPrimitive.content == authorSubId -> {
                                authorChannel.trySend(null)
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    authorChannel.trySend(null)
                }
            })
        }

        val authorDeadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS / 2
        var authorEoseCount = 0

        while (authorEoseCount < RELAY_URLS.size && System.currentTimeMillis() < authorDeadline) {
            var result = authorChannel.tryReceive()
            while (result.isSuccess) {
                val item = result.getOrNull()
                if (item == null) {
                    authorEoseCount++
                } else {
                    val (pubkey, count) = item
                    if (count >= MIN_FOLLOWS) approvedPubkeys += pubkey
                }
                result = authorChannel.tryReceive()
            }
            delay(80)
        }

        authorChannel.close()
        for (socket in authorSockets) {
            try {
                socket.send("""["CLOSE","$authorSubId"]""")
                socket.close(1000, null)
            } catch (_: Exception) {}
        }
        for (socket in sockets) try { socket.close(1000, null) } catch (_: Exception) {}

        // Collect approved image URLs, deduplicated
        val seen = LinkedHashSet<String>()
        for (pubkey in approvedPubkeys) {
            for (candidate in (pubkeyToCandidates[pubkey] ?: emptyList())) {
                seen += candidate.imageUrl
                if (seen.size >= TARGET_PHOTOS) break
            }
            if (seen.size >= TARGET_PHOTOS) break
        }

        return seen.toList()
    }

    private fun loadCache(prefs: android.content.SharedPreferences): List<String> {
        val raw = prefs.getString(PREF_URLS, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun saveCache(prefs: android.content.SharedPreferences, urls: List<String>) {
        prefs.edit()
            .putString(PREF_URLS, urls.joinToString("\n"))
            .putLong(PREF_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    override fun onCleared() {
        fetchJob?.cancel()
        okHttpClient?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
        }
    }
}
