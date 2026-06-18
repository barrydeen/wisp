package cooking.zap.app.nostr

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
import java.security.MessageDigest

object Nip57 {
    const val KIND_PRIVATE_ZAP_EVENT = 9733
    private const val ANON_TAG = "anon"
    private const val PZAP_HRP = "pzap"
    private const val IV_HRP = "iv"

    private val bolt11AmountRegex = Regex("""lnbc(\d+)([munp]?)1""")
    private val json = Json { ignoreUnknownKeys = true }

    data class DecryptedPrivateZap(val senderPubkey: String, val message: String)

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

    /** Extract the relay URLs from the embedded 9734 zap request's "relays" tag. */
    fun getZapRequestRelays(event: NostrEvent): List<String> {
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return emptyList()
        return try {
            val zapRequest = NostrEvent.fromJson(description)
            zapRequest.tags.firstOrNull { it.size >= 2 && it[0] == "relays" }?.drop(1) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
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
        message: String = "",
        extraTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", recipientPubkey))
        if (eventId != null) tags.add(listOf("e", eventId))
        tags.add(listOf("relays") + relayUrls)
        tags.add(listOf("amount", amountMsats.toString()))
        tags.add(listOf("lnurl", lnurl))
        tags.addAll(extraTags)

        return NostrEvent.create(
            privkey = senderPrivkey,
            pubkey = senderPubkey,
            kind = 9734,
            content = message,
            tags = tags
        )
    }

    suspend fun buildZapRequestWithSigner(
        signer: NostrSigner,
        recipientPubkey: String,
        eventId: String?,
        amountMsats: Long,
        relayUrls: List<String>,
        lnurl: String,
        message: String = "",
        extraTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", recipientPubkey))
        if (eventId != null) tags.add(listOf("e", eventId))
        tags.add(listOf("relays") + relayUrls)
        tags.add(listOf("amount", amountMsats.toString()))
        tags.add(listOf("lnurl", lnurl))
        tags.addAll(extraTags)

        return signer.signEvent(kind = 9734, content = message, tags = tags)
    }

    /**
     * DIP-03 deterministic ephemeral private key:
     * sha256(utf8(senderPrivkeyHex + targetEventIdHex + createdAtString)).
     * Re-derivable from public note data so the sender can identify their own
     * outgoing private zaps after the fact.
     */
    fun deriveEphemeralPrivkey(
        senderPrivkey: ByteArray,
        targetEventId: String,
        targetCreatedAt: Long
    ): ByteArray {
        val input = senderPrivkey.toHex() + targetEventId + targetCreatedAt.toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * DIP-03 private zap request. Inner kind 9733 (real sender, NIP-04 encrypted
     * to recipient via ephemeral ECDH) is packed into the outer kind 9734's
     * `anon` tag; the outer event is signed by the deterministic ephemeral key
     * so the LNURL provider and relays only see an unlinkable pubkey.
     */
    fun buildPrivateZapRequest(
        senderPrivkey: ByteArray,
        senderPubkey: ByteArray,
        recipientPubkey: String,
        eventId: String,
        eventCreatedAt: Long,
        amountMsats: Long,
        relayUrls: List<String>,
        lnurl: String,
        message: String,
        extraTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val ephPrivkey = deriveEphemeralPrivkey(senderPrivkey, eventId, eventCreatedAt)
        val ephPubkey = Keys.xOnlyPubkey(ephPrivkey)

        val inner = NostrEvent.create(
            privkey = senderPrivkey,
            pubkey = senderPubkey,
            kind = KIND_PRIVATE_ZAP_EVENT,
            content = message,
            tags = listOf(listOf("p", recipientPubkey))
        )

        val shared = Nip04.computeSharedSecret(ephPrivkey, recipientPubkey.hexToByteArray())
        val (ct, iv) = Nip04.encryptRaw(inner.toJson(), shared)
        val anonValue = Nip19.bech32Encode(PZAP_HRP, ct) + "_" + Nip19.bech32Encode(IV_HRP, iv)

        val outerTags = buildList {
            add(listOf("p", recipientPubkey))
            add(listOf("e", eventId))
            add(listOf("relays") + relayUrls)
            add(listOf("amount", amountMsats.toString()))
            add(listOf("lnurl", lnurl))
            add(listOf(ANON_TAG, anonValue))
            addAll(extraTags)
        }
        return NostrEvent.create(
            privkey = ephPrivkey,
            pubkey = ephPubkey,
            kind = 9734,
            content = "",
            tags = outerTags
        )
    }

    /** True if the receipt's embedded zap request carries a DIP-03 `anon` tag. */
    fun isPrivateZap(receipt: NostrEvent): Boolean {
        val request = parseEmbeddedRequest(receipt) ?: return false
        return request.tags.any { it.size >= 2 && it[0] == ANON_TAG }
    }

    /**
     * Decrypt a DIP-03 private zap addressed to us (we are the recipient).
     * Returns null unless the `anon` envelope decodes, AES-decrypts, and the
     * inner kind 9733's Schnorr signature checks out — the anon tag is
     * otherwise unauthenticated, so signature verification is mandatory.
     */
    fun decryptPrivateZap(receipt: NostrEvent, myPrivkey: ByteArray): DecryptedPrivateZap? {
        val request = parseEmbeddedRequest(receipt) ?: return null
        val anon = request.tags.firstOrNull { it.size >= 2 && it[0] == ANON_TAG }?.get(1)
            ?: return null
        val shared = runCatching {
            Nip04.computeSharedSecret(myPrivkey, request.pubkey.hexToByteArray())
        }.getOrNull() ?: return null
        return decryptAnonTag(anon, shared)
    }

    /**
     * Decrypt a DIP-03 private zap we sent ourselves. Re-derives the
     * deterministic ephemeral key from [target], confirms the outer pubkey
     * matches (i.e. this is ours), then runs ECDH(ephPriv, recipient) to
     * recover the same shared secret used at encryption. Returns null for any
     * receipt that isn't one of ours.
     */
    fun decryptOwnOutgoingPrivateZap(
        receipt: NostrEvent,
        myPrivkey: ByteArray,
        target: NostrEvent
    ): DecryptedPrivateZap? {
        val request = parseEmbeddedRequest(receipt) ?: return null
        val anon = request.tags.firstOrNull { it.size >= 2 && it[0] == ANON_TAG }?.get(1)
            ?: return null
        val ephPriv = deriveEphemeralPrivkey(myPrivkey, target.id, target.created_at)
        val ephPubHex = runCatching { Keys.xOnlyPubkey(ephPriv).toHex() }.getOrNull()
            ?: return null
        if (ephPubHex != request.pubkey) return null
        val recipientHex = request.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            ?: return null
        val shared = runCatching {
            Nip04.computeSharedSecret(ephPriv, recipientHex.hexToByteArray())
        }.getOrNull() ?: return null
        return decryptAnonTag(anon, shared)
    }

    private fun decryptAnonTag(anon: String, sharedSecret: ByteArray): DecryptedPrivateZap? {
        val parts = anon.split("_", limit = 2)
        if (parts.size != 2) return null
        val (hrpCt, ct) = runCatching { Nip19.bech32Decode(parts[0]) }.getOrNull() ?: return null
        val (hrpIv, iv) = runCatching { Nip19.bech32Decode(parts[1]) }.getOrNull() ?: return null
        if (hrpCt != PZAP_HRP || hrpIv != IV_HRP) return null
        val plaintext = runCatching { Nip04.decryptRaw(ct, iv, sharedSecret) }.getOrNull()
            ?: return null
        val inner = runCatching { NostrEvent.fromJson(plaintext) }.getOrNull() ?: return null
        if (inner.kind != KIND_PRIVATE_ZAP_EVENT) return null
        if (!inner.verifySignature()) return null
        return DecryptedPrivateZap(inner.pubkey, inner.content)
    }

    private fun parseEmbeddedRequest(receipt: NostrEvent): NostrEvent? {
        val description = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return null
        return runCatching { NostrEvent.fromJson(description) }.getOrNull()
    }

    suspend fun fetchSimpleInvoice(
        callbackUrl: String,
        amountMsats: Long,
        httpClient: OkHttpClient
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val separator = if (callbackUrl.contains("?")) "&" else "?"
                val url = "${callbackUrl}${separator}amount=$amountMsats"
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
