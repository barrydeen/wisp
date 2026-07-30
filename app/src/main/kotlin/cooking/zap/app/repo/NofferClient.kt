package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NofferData
import cooking.zap.app.nostr.NofferException
import cooking.zap.app.nostr.Noffer
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RelayMessage
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.relay.Relay
import cooking.zap.app.relay.RelayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * CLINK noffer kind-21001 RPC client (payer side).
 *
 * Spec: https://github.com/shocknet/CLINK/blob/main/specs/clink-offers.md
 *
 * Flow:
 *   1. Build a JSON request payload with the offer id and (when needed) the
 *      amount in sats.
 *   2. NIP-44 encrypt the payload to the offer's service pubkey via the
 *      caller's [NostrSigner] — this works for both local keys and external
 *      signers (e.g. Amber).
 *   3. Sign + publish a kind-21001 event tagged `["p", servicePubkey]` and
 *      `["clink_version","1"]` on the relay carried in the noffer's TLV 1.
 *   4. Subscribe to kind-21001 on the same relay, filtered to events from the
 *      service tagged for us. Decrypt the first response and parse it as either
 *      `{bolt11}` (success) or `{error,code,…}` (typed failure → [NofferException]).
 *
 * The caller pays the returned bolt11 via the active wallet provider.
 */
object NofferClient {

    private const val KIND_NOFFER_RPC = 21001
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Request a bolt11 invoice for [noffer], following a `code: 3`
     * "expired/moved" response to its `latest` offer once (per spec).
     * Throws [NofferException] on failure or timeout.
     */
    suspend fun requestInvoice(
        noffer: NofferData,
        signer: NostrSigner,
        amountSats: Long?,
        description: String? = null,
        zapRequest: String? = null,
        timeoutMs: Long = 30_000,
        allowRetry: Boolean = true
    ): String {
        return try {
            requestInvoiceOnce(noffer, signer, amountSats, description, zapRequest, timeoutMs)
        } catch (err: NofferException) {
            // Expired or moved — if the service handed us a replacement, decode
            // it and retry exactly once against the new offer.
            if (allowRetry && err.code == 3) {
                val updated = err.latest?.let { Noffer.decodeOrNull(it) } ?: throw err
                requestInvoice(
                    noffer = updated, signer = signer, amountSats = amountSats,
                    description = description, zapRequest = zapRequest,
                    timeoutMs = timeoutMs, allowRetry = false
                )
            } else {
                throw err
            }
        }
    }

    private suspend fun requestInvoiceOnce(
        noffer: NofferData,
        signer: NostrSigner,
        amountSats: Long?,
        description: String?,
        zapRequest: String?,
        timeoutMs: Long
    ): String {
        val servicePubkey = noffer.pubkey
        try {
            servicePubkey.hexToByteArray()
        } catch (_: Exception) {
            throw NofferException(0, "Invalid offer service pubkey.")
        }

        // Build the encrypted request payload. amount_sats is required for
        // Spontaneous/Variable offers; harmless to include for Fixed.
        val payload = buildJsonObject {
            put("offer", JsonPrimitive(noffer.offerId))
            if (amountSats != null && amountSats > 0) put("amount_sats", JsonPrimitive(amountSats))
            if (!description.isNullOrEmpty()) put("description", JsonPrimitive(description.take(100)))
            if (!zapRequest.isNullOrEmpty()) put("zap", JsonPrimitive(zapRequest))
        }.toString()
        // NIP-44 encryption and event signing go through the signer so this
        // works for both local keys and external signers (e.g. Amber).
        val ciphertext = try {
            signer.nip44Encrypt(payload, servicePubkey)
        } catch (_: Exception) {
            throw NofferException(0, "Could not encrypt the offer request.")
        }

        val event = signer.signEvent(
            kind = KIND_NOFFER_RPC,
            content = ciphertext,
            tags = listOf(listOf("p", servicePubkey), listOf("clink_version", "1"))
        )

        val subId = "noffer-${UUID.randomUUID().toString().take(8)}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relay = Relay(RelayConfig(noffer.relay), Relay.createClient(), scope = scope)
        try {
            relay.connect()
            return withTimeout(timeoutMs) {
                relay.connectionState.first { it }

                // Subscribe BEFORE publishing so a fast response isn't raced
                // away. 5s grace on `since` covers slight clock skew with the
                // service.
                val filter = Filter(
                    kinds = listOf(KIND_NOFFER_RPC),
                    authors = listOf(servicePubkey),
                    pTags = listOf(signer.pubkeyHex),
                    since = System.currentTimeMillis() / 1000 - 5
                )
                relay.send(ClientMessage.req(subId, filter))
                relay.send(ClientMessage.event(event))

                var result: String? = null
                relay.messages.first { msg ->
                    if (msg !is RelayMessage.EventMsg) return@first false
                    if (msg.subscriptionId != subId) return@first false
                    val response = msg.event
                    // `authors` already guards this server-side; defend against
                    // relays that ignore filters.
                    if (response.pubkey != servicePubkey) return@first false
                    val plaintext = try {
                        signer.nip44Decrypt(response.content, servicePubkey)
                    } catch (_: Exception) {
                        return@first false
                    }
                    val parsed = parseResponse(plaintext) ?: return@first false
                    when {
                        !parsed.bolt11.isNullOrEmpty() -> {
                            result = parsed.bolt11
                            true
                        }
                        parsed.code != null || parsed.error != null -> {
                            throw NofferException(
                                code = parsed.code ?: 0,
                                message = parsed.error ?: "The offer request failed.",
                                rangeMin = parsed.rangeMin,
                                rangeMax = parsed.rangeMax,
                                latest = parsed.latest
                            )
                        }
                        // Unrecognised payload — keep listening; maybe a stale event.
                        else -> false
                    }
                }
                result ?: throw NofferException(0, "The offer request failed.")
            }
        } catch (_: TimeoutCancellationException) {
            throw NofferException(0, "The offer request timed out. The recipient's service may be offline.")
        } finally {
            relay.send(ClientMessage.close(subId))
            relay.disconnect()
            scope.cancel()
        }
    }

    private data class Response(
        val bolt11: String?,
        val error: String?,
        val code: Int?,
        val rangeMin: Long?,
        val rangeMax: Long?,
        val latest: String?
    )

    private fun parseResponse(plaintext: String): Response? {
        return try {
            val obj = json.parseToJsonElement(plaintext).jsonObject
            val range = obj["range"]?.jsonObject
            Response(
                bolt11 = obj["bolt11"]?.jsonPrimitive?.content?.trim(),
                error = obj["error"]?.jsonPrimitive?.content,
                code = obj["code"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
                rangeMin = range?.get("min")?.jsonPrimitive?.content?.toLongOrNull(),
                rangeMax = range?.get("max")?.jsonPrimitive?.content?.toLongOrNull(),
                latest = obj["latest"]?.jsonPrimitive?.content
            )
        } catch (_: Exception) {
            null
        }
    }
}
