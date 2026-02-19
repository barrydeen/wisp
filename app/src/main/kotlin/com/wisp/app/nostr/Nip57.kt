package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

object Nip57 {
    private val bolt11AmountRegex = Regex("""lnbc(\d+)([munp]?)1""")
    private val json = Json { ignoreUnknownKeys = true }

    fun getZappedEventId(event: NostrEvent): String? {
        return event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
    }

    /**
     * Extract the zapper's pubkey from a kind 9735 zap receipt.
     * The description tag contains the serialized kind 9734 zap request whose pubkey is the sender.
     */
    fun getZapperPubkey(event: NostrEvent): String? {
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return null
        return try {
            val zapRequest = NostrEvent.fromJson(description)
            zapRequest.pubkey.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun getZapMessage(event: NostrEvent): String {
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return ""
        return try {
            val zapRequest = NostrEvent.fromJson(description)
            zapRequest.content
        } catch (_: Exception) {
            ""
        }
    }

    fun getZapAmountSats(event: NostrEvent): Long {
        val bolt11 = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }?.get(1)
            ?: return 0
        val match = bolt11AmountRegex.find(bolt11.lowercase()) ?: return 0
        val amount = match.groupValues[1].toLongOrNull() ?: return 0
        val multiplier = match.groupValues[2]
        // Base unit is BTC, convert to sats (1 BTC = 100,000,000 sats)
        return when (multiplier) {
            "m" -> amount * 100_000        // milli-BTC
            "u" -> amount * 100            // micro-BTC
            "n" -> amount / 10             // nano-BTC (0.1 sat per nano)
            "p" -> amount / 10_000         // pico-BTC
            "" -> amount * 100_000_000     // whole BTC
            else -> 0
        }
    }

    data class LnurlPayInfo(
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String?
    )

    suspend fun resolveLud16(lud16: String, httpClient: OkHttpClient): LnurlPayInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val parts = lud16.split("@", limit = 2)
                if (parts.size != 2) return@withContext null
                val (user, domain) = parts
                val url = "https://$domain/.well-known/lnurlp/$user"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                val allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.boolean ?: false
                LnurlPayInfo(
                    callback = obj["callback"]?.jsonPrimitive?.content ?: return@withContext null,
                    minSendable = obj["minSendable"]?.jsonPrimitive?.long ?: 1000,
                    maxSendable = obj["maxSendable"]?.jsonPrimitive?.long ?: 100_000_000_000,
                    allowsNostr = allowsNostr,
                    nostrPubkey = obj["nostrPubkey"]?.jsonPrimitive?.content
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun buildZapRequest(
        senderPrivkey: ByteArray,
        senderPubkey: ByteArray,
        recipientPubkey: String,
        eventId: String?,
        amountMsats: Long,
        relayUrls: List<String>,
        lnurl: String,
        message: String = ""
    ): NostrEvent {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", recipientPubkey))
        if (eventId != null) tags.add(listOf("e", eventId))
        tags.add(listOf("relays") + relayUrls)
        tags.add(listOf("amount", amountMsats.toString()))
        tags.add(listOf("lnurl", lnurl))

        return NostrEvent.create(
            privkey = senderPrivkey,
            pubkey = senderPubkey,
            kind = 9734,
            content = message,
            tags = tags
        )
    }

    suspend fun fetchInvoice(
        callbackUrl: String,
        amountMsats: Long,
        zapRequest: NostrEvent,
        httpClient: OkHttpClient
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val zapRequestJson = zapRequest.toJson()
                val encoded = URLEncoder.encode(zapRequestJson, "UTF-8")
                val separator = if (callbackUrl.contains("?")) "&" else "?"
                val url = "${callbackUrl}${separator}amount=$amountMsats&nostr=$encoded"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                obj["pr"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }
    }
}
