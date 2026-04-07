package com.antz.app.viewmodel

import android.app.Application
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class LiveMetrics(val online: Int, val notes: Int)

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _profilePictures = MutableStateFlow<List<String>>(emptyList())
    val profilePictures: StateFlow<List<String>> = _profilePictures

    private val _liveMetrics = MutableStateFlow<LiveMetrics?>(null)
    val liveMetrics: StateFlow<LiveMetrics?> = _liveMetrics

    private var fetchJob: Job? = null
    private var metricsJob: Job? = null
    private var okHttpClient: OkHttpClient? = null

    companion object {
        private val RELAY_URLS = listOf("wss://premium.primal.net")
        private const val TARGET_COUNT = 300
        private val json = Json { ignoreUnknownKeys = true }
    }

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        okHttpClient = client

        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            fetchProfilePictures(client)
        }
        metricsJob = viewModelScope.launch(Dispatchers.IO) {
            connectLiveMetrics(client)
        }
    }

    private suspend fun fetchProfilePictures(client: OkHttpClient) {
        // null = EOSE signal, non-null = picture URL
        val channel = Channel<String?>(capacity = Channel.UNLIMITED)

        val sockets = RELAY_URLS.map { url ->
            val req = Request.Builder().url(url).build()
            client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""["REQ","splash",{"kinds":[0],"limit":300}]""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = json.parseToJsonElement(text) as? JsonArray ?: return
                        when (arr[0].jsonPrimitive.content) {
                            "EVENT" -> {
                                val content = arr[2].jsonObject["content"]
                                    ?.jsonPrimitive?.content ?: return
                                val profile = try {
                                    json.parseToJsonElement(content).jsonObject
                                } catch (_: Exception) { return }
                                val pic = profile["picture"]?.jsonPrimitive?.content
                                    ?.takeIf { it.isNotEmpty() } ?: return
                                channel.trySend(pic)
                            }
                            "EOSE" -> {
                                if (arr[1].jsonPrimitive.content == "splash") {
                                    channel.trySend(null)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
            })
        }

        val pictures = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + 10_000
        var eoseCount = 0
        var eoseGraceDeadline = Long.MAX_VALUE

        while (pictures.size < TARGET_COUNT) {
            val now = System.currentTimeMillis()
            if (now >= deadline || now >= eoseGraceDeadline) break

            var anyNew = false
            var result = channel.tryReceive()
            while (result.isSuccess) {
                val item = result.getOrNull()
                if (item == null) {
                    eoseCount++
                    if (eoseCount >= RELAY_URLS.size && eoseGraceDeadline == Long.MAX_VALUE) {
                        eoseGraceDeadline = System.currentTimeMillis() + 2_000
                    }
                } else {
                    pictures.add(item)
                    anyNew = true
                    if (pictures.size >= TARGET_COUNT) break
                }
                result = channel.tryReceive()
            }

            if (anyNew) {
                _profilePictures.value = pictures.toList()
            }

            delay(100)
        }

        if (pictures.isNotEmpty() && _profilePictures.value.size != pictures.size) {
            _profilePictures.value = pictures.toList()
        }

        channel.close()
        for (socket in sockets) {
            try {
                socket.send("""["CLOSE","splash"]""")
                socket.close(1000, null)
            } catch (_: Exception) {}
        }
    }

    private fun connectLiveMetrics(client: OkHttpClient) {
        val req = Request.Builder()
            .url("wss://api.nostrarchives.com/v1/ws/live-metrics")
            .build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = json.parseToJsonElement(text) as? JsonObject ?: return
                    val online = obj["online"]?.jsonPrimitive?.intOrNull ?: return
                    val notes = obj["notes"]?.jsonPrimitive?.intOrNull ?: return
                    _liveMetrics.value = LiveMetrics(online = online, notes = notes)
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        })
    }

    override fun onCleared() {
        fetchJob?.cancel()
        metricsJob?.cancel()
        okHttpClient?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
        }
    }
}
