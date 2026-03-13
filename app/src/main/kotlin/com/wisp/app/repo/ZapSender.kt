package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import okhttp3.OkHttpClient

class ZapSender(
    private val keyRepo: KeyRepository,
    private val getWalletProvider: () -> WalletProvider,
    private val relayPool: RelayPool,
    private val relayListRepo: RelayListRepository,
    private val httpClient: OkHttpClient,
    private val interfacePrefs: InterfacePreferences
) {
    var signer: NostrSigner? = null

    companion object {
        private const val PREFS_NAME = "wisp_zap_recipients"
        private const val MAX_ENTRIES = 500

        /** In-memory map of payment hash → recipient pubkey for outgoing zaps. */
        private val _zapRecipients = LinkedHashMap<String, String>()
        private var prefs: SharedPreferences? = null

        /** Call once from Application.onCreate to enable persistence. */
        fun init(context: Context) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Load persisted entries into memory
            synchronized(_zapRecipients) {
                prefs?.all?.forEach { (hash, pubkey) ->
                    if (pubkey is String) _zapRecipients[hash] = pubkey
                }
                if (_zapRecipients.isNotEmpty()) {
                    Log.d("ZapSender", "Loaded ${_zapRecipients.size} persisted zap recipients")
                }
            }
        }

        fun getZapRecipient(paymentHash: String): String? = synchronized(_zapRecipients) {
            _zapRecipients[paymentHash]
        }

        /**
         * Persist a payment hash → recipient pubkey mapping.
         * Called both at zap-send time and when resolving historical zap receipts.
         */
        fun persistRecipient(paymentHash: String, recipientPubkey: String) {
            synchronized(_zapRecipients) {
                if (_zapRecipients[paymentHash] == recipientPubkey) return
                _zapRecipients[paymentHash] = recipientPubkey
                while (_zapRecipients.size > MAX_ENTRIES) {
                    val first = _zapRecipients.keys.first()
                    _zapRecipients.remove(first)
                    prefs?.edit()?.remove(first)?.apply()
                }
            }
            prefs?.edit()?.putString(paymentHash, recipientPubkey)?.apply()
        }

        private fun recordZap(bolt11: String, recipientPubkey: String) {
            val decoded = Bolt11.decode(bolt11)
            val hash = decoded?.paymentHash ?: return
            persistRecipient(hash, recipientPubkey)
        }
    }

    suspend fun sendZap(
        recipientLud16: String,
        recipientPubkey: String,
        eventId: String?,
        amountMsats: Long,
        message: String = "",
        isAnonymous: Boolean = false,
        isPrivate: Boolean = false
    ): Result<Unit> {
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
        val relayUrls = if (isPrivate) {
            // Private zap: route receipt through DM relays only
            val recipientDmRelays = relayListRepo.getDmRelays(recipientPubkey) ?: emptyList()
            val ourDmRelays = relayPool.getDmRelayUrls()
            val combined = (recipientDmRelays + ourDmRelays).distinct().take(5)
            if (combined.isEmpty()) {
                return Result.failure(Exception("No DM relays available for private zap"))
            }
            combined
        } else {
            // Recipient's read relays first (so they see the receipt), then our own
            // read relays (so we can verify it), deduped, capped at 5.
            val recipientRelays = relayListRepo.getReadRelays(recipientPubkey) ?: emptyList()
            val ourRelays = relayPool.getReadRelayUrls()
            (recipientRelays + ourRelays).distinct().take(5)
                .ifEmpty { relayPool.getRelayUrls().take(3) }
        }

        val extraTags = if (interfacePrefs.isClientTagEnabled()) {
            listOf(listOf("client", "Wisp"))
        } else {
            emptyList()
        }

        val zapRequest = if (isAnonymous) {
            val throwaway = Keys.generate()
            Nip57.buildZapRequest(
                senderPrivkey = throwaway.privkey,
                senderPubkey = throwaway.pubkey,
                recipientPubkey = recipientPubkey,
                eventId = eventId,
                amountMsats = amountMsats,
                relayUrls = relayUrls,
                lnurl = recipientLud16,
                message = message,
                extraTags = extraTags
            )
        } else {
            val s = signer
            val keypair = keyRepo.getKeypair()

            when {
                s != null -> Nip57.buildZapRequestWithSigner(
                    signer = s,
                    recipientPubkey = recipientPubkey,
                    eventId = eventId,
                    amountMsats = amountMsats,
                    relayUrls = relayUrls,
                    lnurl = recipientLud16,
                    message = message,
                    extraTags = extraTags
                )
                keypair != null -> Nip57.buildZapRequest(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = recipientPubkey,
                    eventId = eventId,
                    amountMsats = amountMsats,
                    relayUrls = relayUrls,
                    lnurl = recipientLud16,
                    message = message,
                    extraTags = extraTags
                )
                else -> return Result.failure(Exception("No signer or keypair available"))
            }
        }

        // 3. Fetch invoice from LNURL callback
        val bolt11 = Nip57.fetchInvoice(payInfo.callback, amountMsats, zapRequest, httpClient)
            ?: return Result.failure(Exception("Could not get invoice from lightning provider"))

        // 4. Record zap recipient for transaction history display
        recordZap(bolt11, recipientPubkey)

        // 5. Pay via wallet
        val payResult = getWalletProvider().payInvoice(bolt11)
        return payResult.map { }
    }
}
