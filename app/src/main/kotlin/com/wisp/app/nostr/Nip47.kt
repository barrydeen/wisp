package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object Nip47 {
    private val json = Json { ignoreUnknownKeys = true }

    enum class NwcEncryption { NIP04, NIP44 }

    data class NwcConnection(
        val walletServicePubkey: ByteArray,
        val relayUrl: String,
        val clientSecret: ByteArray,
        val encryption: NwcEncryption = NwcEncryption.NIP04
    ) {
        val clientPubkey: ByteArray get() = Keys.xOnlyPubkey(clientSecret)

        /** Precomputed NIP-04 shared secret */
        val sharedSecret: ByteArray by lazy {
            Nip04.computeSharedSecret(clientSecret, walletServicePubkey)
        }

        /** Precomputed NIP-44 conversation key */
        val conversationKey: ByteArray by lazy {
            Nip44.getConversationKey(clientSecret, walletServicePubkey)
        }

        fun withEncryption(enc: NwcEncryption) = copy(encryption = enc)

        override fun equals(other: Any?) =
            other is NwcConnection && walletServicePubkey.contentEquals(other.walletServicePubkey)

        override fun hashCode() = walletServicePubkey.contentHashCode()
    }

    sealed class NwcRequest {
        object GetBalance : NwcRequest()
        data class PayInvoice(val invoice: String) : NwcRequest()
        data class MakeInvoice(val amountMsats: Long, val description: String) : NwcRequest()
        data class ListTransactions(val limit: Int = 50, val offset: Int = 0) : NwcRequest()
    }

    data class Transaction(
        val type: String,
        val description: String?,
        val paymentHash: String,
        val amount: Long,
        val createdAt: Long,
        val settledAt: Long?
    )

    sealed class NwcResponse {
        data class Balance(val balanceMsats: Long) : NwcResponse()
        data class PayInvoiceResult(val preimage: String) : NwcResponse()
        data class MakeInvoiceResult(val invoice: String, val paymentHash: String) : NwcResponse()
        data class ListTransactionsResult(val transactions: List<Transaction>) : NwcResponse()
        data class Error(val code: String, val message: String) : NwcResponse()
    }

    /**
     * Parse the wallet service's info event (kind 13194) to determine supported
     * encryption schemes. Returns the best encryption to use (NIP-44 preferred).
     */
    fun parseInfoEncryption(event: NostrEvent): NwcEncryption {
        val encTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "encryption" }
        if (encTag != null) {
            // Tag value is space-separated list of supported schemes
            val schemes = encTag[1].split(" ").map { it.trim().lowercase() }
            if ("nip44_v2" in schemes) return NwcEncryption.NIP44
        }
        // Absent tag = NIP-04 only (backwards compatibility per spec)
        return NwcEncryption.NIP04
    }

    fun parseConnectionString(uri: String): NwcConnection? {
        return try {
            val normalized = encodeRelayParam(
                uri.trim().replace("nostr+walletconnect://", "nwc://")
            )
            val parsed = URI(normalized)
            val pubkeyHex = parsed.host ?: return null
            val params = parseQueryParams(parsed.rawQuery ?: return null)
            val relayUrl = params["relay"] ?: return null
            val secretHex = params["secret"] ?: return null
            NwcConnection(
                walletServicePubkey = pubkeyHex.hexToByteArray(),
                relayUrl = relayUrl,
                clientSecret = secretHex.hexToByteArray()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detect unencoded relay URLs in the query string and URL-encode them.
     * Users sometimes paste URIs with relay=wss://... instead of relay=wss%3A%2F%2F...
     * Handles multiple relay= params and trims whitespace.
     */
    private fun encodeRelayParam(uri: String): String {
        val qIdx = uri.indexOf('?')
        if (qIdx < 0) return uri

        val base = uri.substring(0, qIdx + 1)
        val query = uri.substring(qIdx + 1)

        val newQuery = Regex("""relay=([^&]+)""").replace(query) { match ->
            val relayValue = match.groupValues[1].trim()
            if (!relayValue.contains("%3A", ignoreCase = true) &&
                !relayValue.contains("%2F", ignoreCase = true)) {
                val encoded = URLEncoder.encode(
                    URLDecoder.decode(relayValue, "UTF-8").trim(), "UTF-8"
                )
                "relay=$encoded"
            } else {
                match.value
            }
        }
        return base + newQuery
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8").trim() to URLDecoder.decode(parts[1], "UTF-8").trim()
            } else null
        }.toMap()
    }

    fun buildRequest(connection: NwcConnection, request: NwcRequest): NostrEvent {
        val content = when (request) {
            is NwcRequest.GetBalance -> buildJsonObject {
                put("method", "get_balance")
            }
            is NwcRequest.PayInvoice -> buildJsonObject {
                put("method", "pay_invoice")
                put("params", buildJsonObject {
                    put("invoice", request.invoice)
                })
            }
            is NwcRequest.MakeInvoice -> buildJsonObject {
                put("method", "make_invoice")
                put("params", buildJsonObject {
                    put("amount", request.amountMsats)
                    put("description", request.description)
                })
            }
            is NwcRequest.ListTransactions -> buildJsonObject {
                put("method", "list_transactions")
                put("params", buildJsonObject {
                    put("limit", request.limit)
                    put("offset", request.offset)
                })
            }
        }

        val plaintext = content.toString()
        val wsPubkeyHex = connection.walletServicePubkey.toHex()

        val encrypted: String
        val tags: List<List<String>>

        when (connection.encryption) {
            NwcEncryption.NIP44 -> {
                encrypted = Nip44.encrypt(plaintext, connection.conversationKey)
                tags = listOf(
                    listOf("p", wsPubkeyHex),
                    listOf("encryption", "nip44_v2")
                )
            }
            NwcEncryption.NIP04 -> {
                encrypted = Nip04.encrypt(plaintext, connection.sharedSecret)
                tags = listOf(listOf("p", wsPubkeyHex))
            }
        }

        return NostrEvent.create(
            privkey = connection.clientSecret,
            pubkey = connection.clientPubkey,
            kind = 23194,
            content = encrypted,
            tags = tags
        )
    }

    fun parseResponse(connection: NwcConnection, event: NostrEvent): NwcResponse {
        val decrypted = decryptContent(connection, event.content)

        val obj = json.parseToJsonElement(decrypted).jsonObject

        val resultType = obj["result_type"]?.jsonPrimitive?.content
        val errorElement = obj["error"]
        if (errorElement != null && errorElement !is JsonNull) {
            val error = errorElement.jsonObject
            return NwcResponse.Error(
                code = error["code"]?.jsonPrimitive?.content ?: "UNKNOWN",
                message = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
            )
        }

        val resultElement = obj["result"]
        val result = if (resultElement != null && resultElement !is JsonNull) resultElement.jsonObject
            else return NwcResponse.Error("PARSE_ERROR", "No result")

        return when (resultType) {
            "get_balance" -> NwcResponse.Balance(
                balanceMsats = result["balance"]?.jsonPrimitive?.long ?: 0
            )
            "pay_invoice" -> NwcResponse.PayInvoiceResult(
                preimage = result["preimage"]?.jsonPrimitive?.content ?: ""
            )
            "make_invoice" -> NwcResponse.MakeInvoiceResult(
                invoice = result["invoice"]?.jsonPrimitive?.content ?: "",
                paymentHash = result["payment_hash"]?.jsonPrimitive?.content ?: ""
            )
            "list_transactions" -> {
                val txArray = result["transactions"]?.jsonArray ?: return NwcResponse.ListTransactionsResult(emptyList())
                val transactions = txArray.map { elem ->
                    val tx = elem.jsonObject
                    Transaction(
                        type = tx["type"]?.jsonPrimitive?.content ?: "outgoing",
                        description = tx["description"]?.jsonPrimitive?.content,
                        paymentHash = tx["payment_hash"]?.jsonPrimitive?.content ?: "",
                        amount = tx["amount"]?.jsonPrimitive?.longOrNull ?: 0,
                        createdAt = tx["created_at"]?.jsonPrimitive?.longOrNull ?: 0,
                        settledAt = tx["settled_at"]?.jsonPrimitive?.longOrNull
                    )
                }
                NwcResponse.ListTransactionsResult(transactions)
            }
            else -> NwcResponse.Error("UNKNOWN_METHOD", "Unknown result_type: $resultType")
        }
    }

    /**
     * Auto-detect encryption format and decrypt.
     * NIP-04 content contains "?iv=", NIP-44 is a plain base64 blob.
     * Tries the connection's negotiated encryption first, falls back to the other.
     */
    private fun decryptContent(connection: NwcConnection, content: String): String {
        val looksLikeNip04 = content.contains("?iv=")

        return if (looksLikeNip04) {
            try {
                Nip04.decrypt(content, connection.sharedSecret)
            } catch (_: Exception) {
                // Fallback: maybe it's actually NIP-44 with a coincidental "?iv=" in base64
                Nip44.decrypt(content, connection.conversationKey)
            }
        } else {
            try {
                Nip44.decrypt(content, connection.conversationKey)
            } catch (_: Exception) {
                // Fallback: try NIP-04 in case format detection was wrong
                Nip04.decrypt(content, connection.sharedSecret)
            }
        }
    }
}
