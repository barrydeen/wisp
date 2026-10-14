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
import java.security.MessageDigest

object Nip57 {
    const val KIND_PRIVATE_ZAP_EVENT = 9733
    const val KIND_ZAP_REQUEST = 9734
    const val KIND_ZAP_RECEIPT = 9735
    private const val ANON_TAG = "anon"
    private const val PZAP_HRP = "pzap"
    private const val IV_HRP = "iv"

    private val json = Json { ignoreUnknownKeys = true }

    data class DecryptedPrivateZap(val senderPubkey: String, val message: String)

    private fun tagValue(event: NostrEvent, name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** SHA-256 of the raw description string, hex-encoded — the invoice's `h` field commits to this. */
    private fun descriptionHashHex(description: String): String =
        MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8)).toHex()

    /**
     * Parse the receipt's embedded kind-9734 zap request with NIP-57 client-side
     * validation. A receipt is an ordinary relay event anyone can author; without
     * these checks the embedded request's pubkey would present as the zap sender —
     * the same spoof class as unauthenticated NIP-17 rumor authors.
     *
     * Requires: signature-valid request, request addressing the same recipient
     * (and, when both carry one, the same event) as the receipt, and an invoice
     * whose description hash commits to the exact request string in the receipt's
     * description tag. The hash binding is what stops a genuine request from being
     * replayed into a receipt for different tags.
     */
    fun parseValidatedZapRequest(receipt: NostrEvent): NostrEvent? {
        if (receipt.kind != KIND_ZAP_RECEIPT) return null
        val description = tagValue(receipt, "description") ?: return null
        val request = parseEmbeddedRequest(receipt) ?: return null
        if (request.kind != KIND_ZAP_REQUEST) return null
        if (!request.verifySignature()) return null
        if (receiptInconsistency(receipt, request, description) != null) return null
        return request
    }

    /**
     * Signature-independent consistency checks between a receipt and its embedded
     * request. Split from [parseValidatedZapRequest] so the tag/invoice logic is
     * testable on the JVM classpath, where secp256k1 JNI is unavailable.
     * Returns null when consistent, or the reason for rejection.
     */
    internal fun receiptInconsistency(receipt: NostrEvent, request: NostrEvent, description: String): String? {
        val requestP = tagValue(request, "p") ?: return "request has no p tag"
        val receiptP = tagValue(receipt, "p") ?: return "receipt has no p tag"
        if (!requestP.equals(receiptP, ignoreCase = true)) return "p tag mismatch"
        val requestE = tagValue(request, "e")
        val receiptE = tagValue(receipt, "e")
        if (requestE != null && receiptE != null && !requestE.equals(receiptE, ignoreCase = true)) {
            return "e tag mismatch"
        }
        val bolt11 = tagValue(receipt, "bolt11") ?: return "receipt has no bolt11"
        val invoice = Bolt11.decode(bolt11) ?: return "bolt11 undecodable"
        val invoiceHash = invoice.descriptionHash ?: return "invoice has no description hash"
        if (!invoiceHash.equals(descriptionHashHex(description), ignoreCase = true)) {
            return "description hash mismatch"
        }
        return null
    }

    fun getZappedEventId(event: NostrEvent): String? {
        return event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
    }

    /**
     * Extract the zapper's pubkey from a kind 9735 zap receipt.
     * Returns null unless the receipt passes [parseValidatedZapRequest].
     */
    fun getZapperPubkey(event: NostrEvent): String? {
        val zapRequest = parseValidatedZapRequest(event) ?: return null
        return zapRequest.pubkey.takeIf { it.isNotBlank() }
    }

    fun getZapMessage(event: NostrEvent): String {
        return parseValidatedZapRequest(event)?.content ?: ""
    }

    /** Extract the relay URLs from the embedded 9734 zap request's "relays" tag. */
    fun getZapRequestRelays(event: NostrEvent): List<String> {
        return parseValidatedZapRequest(event)
            ?.tags?.firstOrNull { it.size >= 2 && it[0] == "relays" }?.drop(1)
            ?: emptyList()
    }

    /**
     * Zap amount in sats from the receipt's bolt11, counting only validated
     * receipts. When the signed request carries an amount tag (msat), the
     * invoice must match it — otherwise a receipt author could re-mint the
     * invoice with an inflated amount while keeping the genuine request.
     */
    fun getZapAmountSats(event: NostrEvent): Long {
        val bolt11 = tagValue(event, "bolt11") ?: return 0
        val amountMsat = Bolt11.decodeAmountMsat(bolt11) ?: return 0
        val request = parseValidatedZapRequest(event) ?: return 0
        val requestedMsat = tagValue(request, "amount")?.toLongOrNull()
        if (requestedMsat != null && requestedMsat != amountMsat) return 0
        return amountMsat / 1000
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

    /** True if the receipt's validated embedded zap request carries a DIP-03 `anon` tag. */
    fun isPrivateZap(receipt: NostrEvent): Boolean {
        val request = parseValidatedZapRequest(receipt) ?: return false
        return request.tags.any { it.size >= 2 && it[0] == ANON_TAG }
    }

    /**
     * Decrypt a DIP-03 private zap addressed to us (we are the recipient).
     * The embedded request must pass NIP-57 validation first; the `anon` envelope
     * is otherwise unauthenticated, so the inner kind-9733 signature verification
     * in [decryptAnonTag] is mandatory.
     */
    fun decryptPrivateZap(receipt: NostrEvent, myPrivkey: ByteArray): DecryptedPrivateZap? {
        val request = parseValidatedZapRequest(receipt) ?: return null
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
        val request = parseValidatedZapRequest(receipt) ?: return null
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
