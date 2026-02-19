package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

object Nip47 {
    private val json = Json { ignoreUnknownKeys = true }

    data class NwcConnection(
        val walletServicePubkey: ByteArray,
        val relayUrl: String,
        val clientSecret: ByteArray
    ) {
        val clientPubkey: ByteArray get() = Keys.xOnlyPubkey(clientSecret)

        /** Precomputed NIP-04 shared secret for encrypting/decrypting NWC messages */
        val sharedSecret: ByteArray by lazy {
            Nip04.computeSharedSecret(clientSecret, walletServicePubkey)
        }

        override fun equals(other: Any?) =
            other is NwcConnection && walletServicePubkey.contentEquals(other.walletServicePubkey)

        override fun hashCode() = walletServicePubkey.contentHashCode()
    }

    sealed class NwcRequest {
        object GetBalance : NwcRequest()
        data class PayInvoice(val invoice: String) : NwcRequest()
        data class MakeInvoice(val amountMsats: Long, val description: String) : NwcRequest()
    }

    sealed class NwcResponse {
        data class Balance(val balanceMsats: Long) : NwcResponse()
        data class PayInvoiceResult(val preimage: String) : NwcResponse()
        data class MakeInvoiceResult(val invoice: String, val paymentHash: String) : NwcResponse()
        data class Error(val code: String, val message: String) : NwcResponse()
    }

    fun parseConnectionString(uri: String): NwcConnection? {
        return try {
            val normalized = uri.trim().replace("nostr+walletconnect://", "nwc://")
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

    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
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
        }

        // NIP-47 uses NIP-04 encryption
        val encrypted = Nip04.encrypt(content.toString(), connection.sharedSecret)
        val wsPubkeyHex = connection.walletServicePubkey.toHex()

        return NostrEvent.create(
            privkey = connection.clientSecret,
            pubkey = connection.clientPubkey,
            kind = 23194,
            content = encrypted,
            tags = listOf(listOf("p", wsPubkeyHex))
        )
    }

    fun parseResponse(connection: NwcConnection, event: NostrEvent): NwcResponse {
        // NIP-47 uses NIP-04 encryption
        // The response may come from the wallet service pubkey, so we need to handle
        // both cases: shared secret with wallet pubkey or with actual event pubkey
        val decrypted = try {
            Nip04.decrypt(event.content, connection.sharedSecret)
        } catch (_: Exception) {
            // Fallback: compute shared secret with actual response event pubkey
            val responsePubkey = event.pubkey.hexToByteArray()
            val fallbackSecret = Nip04.computeSharedSecret(connection.clientSecret, responsePubkey)
            Nip04.decrypt(event.content, fallbackSecret)
        }

        val obj = json.parseToJsonElement(decrypted).jsonObject

        val resultType = obj["result_type"]?.jsonPrimitive?.content
        val error = obj["error"]?.jsonObject

        if (error != null) {
            return NwcResponse.Error(
                code = error["code"]?.jsonPrimitive?.content ?: "UNKNOWN",
                message = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
            )
        }

        val result = obj["result"]?.jsonObject ?: return NwcResponse.Error("PARSE_ERROR", "No result")

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
            else -> NwcResponse.Error("UNKNOWN_METHOD", "Unknown result_type: $resultType")
        }
    }
}
