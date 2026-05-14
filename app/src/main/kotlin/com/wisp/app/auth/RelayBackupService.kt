package com.wisp.app.auth

import android.util.Log
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Stores and retrieves encrypted nsec backups as NIP-78 kind 30078 events
 * (parameterized replaceable, application-specific data) on a handful of
 * widely-used public relays.
 *
 * The author of every backup event is a key derived from the user's Google
 * `sub` claim, so on restore we just query relays by that one author. The
 * `d` tag carries `wisp-backup:<target_npub>` so a single Google account can
 * back up multiple Nostr identities — each one is a separate replaceable
 * event under the same author.
 */
class RelayBackupService(
    private val relayUrls: List<String> = DEFAULT_RELAYS,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    data class StoredBackup(
        val eventId: String,
        val createdAt: Long,
        val targetNpub: String,
        val payload: String
    )

    /**
     * Opens parallel REQ subscriptions to every relay, collects all kind 30078
     * events authored by [backupPubkeyHex], dedupes by target npub (keeping the
     * newest created_at), then returns. Times out after [LIST_TIMEOUT_MS] or
     * when EOSE has arrived from every relay.
     */
    suspend fun listBackups(backupPubkeyHex: String): List<StoredBackup> = withContext(Dispatchers.IO) {
        // null = EOSE; non-null = a parsed event candidate
        val channel = Channel<Pair<String, StoredBackup>?>(capacity = Channel.UNLIMITED)
        val subId = "wisp-backup-list"

        val sockets = relayUrls.map { url ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val req = """["REQ","$subId",{"kinds":[30078],"authors":["$backupPubkeyHex"]}]"""
                    webSocket.send(req)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val arr = try { json.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null }
                        ?: return
                    if (arr.size < 2) return
                    when (arr[0].jsonPrimitive.content) {
                        "EVENT" -> {
                            if (arr.size < 3 || arr[1].jsonPrimitive.content != subId) return
                            val eventObj = arr[2] as? JsonObject ?: return
                            val backup = parseBackup(eventObj, backupPubkeyHex) ?: return
                            channel.trySend(url to backup)
                        }
                        "EOSE" -> {
                            if (arr[1].jsonPrimitive.content == subId) {
                                channel.trySend(null)
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "relay $url failed during list", t)
                    channel.trySend(null) // count as EOSE so we don't wait forever
                }
            }
            try {
                httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open $url", e)
                channel.trySend(null)
                null
            }
        }

        val byTarget = LinkedHashMap<String, StoredBackup>()
        val deadline = System.currentTimeMillis() + LIST_TIMEOUT_MS
        var eoseCount = 0

        while (eoseCount < relayUrls.size && System.currentTimeMillis() < deadline) {
            var got = channel.tryReceive()
            while (got.isSuccess) {
                val item = got.getOrNull()
                if (item == null) {
                    eoseCount++
                } else {
                    val (_, backup) = item
                    val existing = byTarget[backup.targetNpub]
                    if (existing == null || backup.createdAt > existing.createdAt) {
                        byTarget[backup.targetNpub] = backup
                    }
                }
                got = channel.tryReceive()
            }
            if (eoseCount >= relayUrls.size) break
            delay(100)
        }

        channel.close()
        for (socket in sockets.filterNotNull()) {
            try {
                socket.send("""["CLOSE","$subId"]""")
                socket.close(1000, null)
            } catch (_: Exception) {}
        }

        byTarget.values.toList()
    }

    /**
     * Builds a kind 30078 event, signs it with [signingPrivkey], and races it
     * to every relay. Returns true if at least one relay returned `OK true`
     * within [PUBLISH_TIMEOUT_MS]; logs and returns false otherwise.
     */
    suspend fun publishBackup(
        signingPrivkey: ByteArray,
        targetNpub: String,
        payload: String
    ): Boolean = withContext(Dispatchers.IO) {
        require(targetNpub.startsWith("npub1")) { "targetNpub must be bech32-encoded" }
        val keypair = Keys.fromPrivkey(signingPrivkey)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = 30078,
            content = payload,
            tags = listOf(listOf("d", "$D_TAG_PREFIX$targetNpub"))
        )
        val eventJson = event.toJson()
        val eventMessage = """["EVENT",$eventJson]"""
        val eventId = event.id

        val ackChannel = Channel<Boolean>(capacity = Channel.UNLIMITED)

        val sockets = relayUrls.map { url ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(eventMessage)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val arr = try { json.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null }
                        ?: return
                    if (arr.size < 3) return
                    if (arr[0].jsonPrimitive.content != "OK") return
                    if (arr[1].jsonPrimitive.content != eventId) return
                    val accepted = arr[2].jsonPrimitive.content == "true"
                    val msg = if (arr.size >= 4) arr[3].jsonPrimitive.content else ""
                    Log.d(TAG, "publish OK from $url accepted=$accepted msg=$msg")
                    ackChannel.trySend(accepted)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "relay $url failed during publish", t)
                    ackChannel.trySend(false)
                }
            }
            try {
                httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open $url for publish", e)
                ackChannel.trySend(false)
                null
            }
        }

        var anyAccepted = false
        val deadline = System.currentTimeMillis() + PUBLISH_TIMEOUT_MS
        var responsesSeen = 0
        while (System.currentTimeMillis() < deadline && responsesSeen < relayUrls.size) {
            var got = ackChannel.tryReceive()
            while (got.isSuccess) {
                responsesSeen++
                if (got.getOrNull() == true) anyAccepted = true
                got = ackChannel.tryReceive()
            }
            if (anyAccepted && responsesSeen >= 1) {
                // We have at least one accept — but keep going briefly to let other
                // relays catch up. Bail early only after a short grace period.
                if (System.currentTimeMillis() > deadline - PUBLISH_GRACE_MS) break
            }
            delay(50)
        }

        ackChannel.close()
        for (socket in sockets.filterNotNull()) {
            try { socket.close(1000, null) } catch (_: Exception) {}
        }

        Log.d(TAG, "publishBackup: $responsesSeen relay(s) responded, anyAccepted=$anyAccepted")
        anyAccepted
    }

    private fun parseBackup(eventObj: JsonObject, expectedAuthor: String): StoredBackup? {
        return try {
            val event = NostrEvent.fromJson(eventObj.toString())
            if (event.kind != 30078) return null
            if (event.pubkey != expectedAuthor) return null
            if (!event.verifySignature()) {
                Log.w(TAG, "event ${event.id} failed signature check")
                return null
            }
            val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1)
                ?: return null
            if (!dTag.startsWith(D_TAG_PREFIX)) return null
            val npub = dTag.removePrefix(D_TAG_PREFIX)
            if (!npub.startsWith("npub1")) return null
            StoredBackup(
                eventId = event.id,
                createdAt = event.created_at,
                targetNpub = npub,
                payload = event.content
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse backup event", e)
            null
        }
    }

    companion object {
        private const val TAG = "GoogleAuth"
        private const val D_TAG_PREFIX = "wisp-backup:"
        private const val LIST_TIMEOUT_MS = 6_000L
        private const val PUBLISH_TIMEOUT_MS = 8_000L
        private const val PUBLISH_GRACE_MS = 5_500L

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://nostr.wine"
        )

        private val json = Json { ignoreUnknownKeys = true }
    }
}
