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
import kotlinx.serialization.json.longOrNull
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
private const val MAX_NOTE_ROUNDS = 5                     // how many times to page further back in time for #foodstr notes
private const val NOTES_PER_ROUND = 300                   // notes requested per relay per round
private const val ROUND_TIMEOUT_MS = 4_000L               // time budget per pagination round
private const val AUTHOR_CHECK_TIMEOUT_MS = 10_000L
private const val MAX_PHOTOS_PER_AUTHOR = 3               // cap so one prolific poster can't flood the grid

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
        // Phase 1: collect image URLs + their authors from #foodstr notes.
        // Pages backwards in time across multiple rounds (re-sending REQ with
        // an older `until` cursor on the same subscription id) instead of a
        // single fixed-size request — a quiet recent window would otherwise
        // starve the splash grid down to a handful of repeating authors/images.
        data class Candidate(val imageUrl: String, val pubkey: String)

        val noteEvents = Channel<Pair<Candidate, Long>?>(capacity = Channel.UNLIMITED)
        val noteSubId = "foodstr_notes"

        val sockets = RELAY_URLS.map { url ->
            val req = Request.Builder().url(url).build()
            client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """["REQ","$noteSubId",{"kinds":[1],"#t":["foodstr"],"limit":$NOTES_PER_ROUND}]"""
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
                                val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: return
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
                                    noteEvents.trySend(Candidate(url, pubkey) to createdAt)
                                }
                            }
                            type == "EOSE" && arr[1].jsonPrimitive.content == noteSubId -> {
                                noteEvents.trySend(null)
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    noteEvents.trySend(null)
                }
            })
        }

        suspend fun drainRound(): List<Pair<Candidate, Long>> {
            val result = mutableListOf<Pair<Candidate, Long>>()
            val deadline = System.currentTimeMillis() + ROUND_TIMEOUT_MS
            var eoseCount = 0
            while (eoseCount < RELAY_URLS.size && System.currentTimeMillis() < deadline) {
                var r = noteEvents.tryReceive()
                while (r.isSuccess) {
                    val item = r.getOrNull()
                    if (item == null) eoseCount++ else result += item
                    r = noteEvents.tryReceive()
                }
                delay(80)
            }
            return result
        }

        val collected = mutableListOf<Candidate>()
        val seenImageUrls = mutableSetOf<String>()
        var untilCursor: Long? = null
        var round = 0

        // Round 0 rides the REQ sent from onOpen above; each subsequent round
        // re-sends REQ on the same (already-open) sockets with an `until`
        // cursor one second older than the oldest note seen so far. Stop once
        // we've amassed a healthy surplus (Phase 2's author check will reject
        // some), hit the round cap, or a round contributes nothing further.
        while (round < MAX_NOTE_ROUNDS && seenImageUrls.size < TARGET_PHOTOS * 3) {
            if (round > 0) {
                val filter = """{"kinds":[1],"#t":["foodstr"],"limit":$NOTES_PER_ROUND,"until":$untilCursor}"""
                for (socket in sockets) {
                    try { socket.send("""["REQ","$noteSubId",$filter]""") } catch (_: Exception) {}
                }
            }

            val roundResults = drainRound()
            if (roundResults.isEmpty()) break

            var oldestTimestamp = Long.MAX_VALUE
            for ((candidate, createdAt) in roundResults) {
                if (seenImageUrls.add(candidate.imageUrl)) collected += candidate
                if (createdAt < oldestTimestamp) oldestTimestamp = createdAt
            }
            val nextCursor = oldestTimestamp - 1
            if (oldestTimestamp == Long.MAX_VALUE || nextCursor == untilCursor) break
            untilCursor = nextCursor
            round++
        }

        noteEvents.close()

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

        val authorDeadline = System.currentTimeMillis() + AUTHOR_CHECK_TIMEOUT_MS
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

        // Collect approved image URLs, deduplicated and capped per author so a
        // single prolific poster can't dominate the grid with visually similar shots.
        val seen = LinkedHashSet<String>()
        for (pubkey in approvedPubkeys) {
            var takenForAuthor = 0
            for (candidate in (pubkeyToCandidates[pubkey] ?: emptyList())) {
                if (takenForAuthor >= MAX_PHOTOS_PER_AUTHOR) break
                seen += candidate.imageUrl
                takenForAuthor++
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
