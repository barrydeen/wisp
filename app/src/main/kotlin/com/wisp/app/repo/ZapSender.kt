package com.wisp.app.repo

import com.wisp.app.nostr.Nip57
import com.wisp.app.relay.RelayPool
import okhttp3.OkHttpClient

class ZapSender(
    private val keyRepo: KeyRepository,
    private val nwcRepo: NwcRepository,
    private val relayPool: RelayPool,
    private val httpClient: OkHttpClient
) {
    suspend fun sendZap(
        recipientLud16: String,
        recipientPubkey: String,
        eventId: String?,
        amountMsats: Long,
        message: String = ""
    ): Result<Unit> {
        val keypair = keyRepo.getKeypair()
            ?: return Result.failure(Exception("No keypair"))

        // 1. LNURL discovery
        val payInfo = Nip57.resolveLud16(recipientLud16, httpClient)
            ?: return Result.failure(Exception("Could not resolve lightning address"))

        if (!payInfo.allowsNostr) {
            return Result.failure(Exception("Recipient does not support Nostr zaps"))
        }

        if (amountMsats < payInfo.minSendable || amountMsats > payInfo.maxSendable) {
            return Result.failure(Exception("Amount out of range (${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sats)"))
        }

        // 2. Build zap request (kind 9734)
        val relayUrls = relayPool.getRelayUrls().take(3)
        val zapRequest = Nip57.buildZapRequest(
            senderPrivkey = keypair.privkey,
            senderPubkey = keypair.pubkey,
            recipientPubkey = recipientPubkey,
            eventId = eventId,
            amountMsats = amountMsats,
            relayUrls = relayUrls,
            lnurl = recipientLud16,
            message = message
        )

        // 3. Fetch invoice from LNURL callback
        val bolt11 = Nip57.fetchInvoice(payInfo.callback, amountMsats, zapRequest, httpClient)
            ?: return Result.failure(Exception("Could not get invoice from lightning provider"))

        // 4. Pay via NWC
        val payResult = nwcRepo.payInvoice(bolt11)
        return payResult.map { }
    }
}
