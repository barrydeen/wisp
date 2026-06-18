package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cooking.zap.app.nostr.Bolt11
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip57
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.RelayPool
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
        isPrivate: Boolean = false,
        extraTags: List<List<String>> = emptyList(),
        extraRelayHints: List<String> = emptyList(),
        eventCreatedAt: Long? = null
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

        // 2. Build zap request (kind 9734). For DIP-03 private zaps we route
        // the receipt only to both parties' NIP-51 DM relays — assumed to be
        // AUTH-gated for reads — so the LNURL-published kind 9735 (which
        // carries the recipient pubkey, amount, and target note id) never lands
        // on a publicly readable relay. The anon-tag envelope hides the sender
        // identity as a second layer in case the LNURL ignores `relays` or one
        // of the DM relays turns out to serve reads unauthed.
        val relayUrls = if (isPrivate) {
            val recipientDmRelays = relayListRepo.getDmRelays(recipientPubkey) ?: emptyList()
            val ourDmRelays = relayPool.getDmRelayUrls()
            val combined = (ourDmRelays + recipientDmRelays).distinct().take(5)
            if (combined.isEmpty()) {
                return Result.failure(Exception("Private zaps require DM relays on both sides"))
            }
            combined
        } else {
            val recipientRelays = relayListRepo.getReadRelays(recipientPubkey) ?: emptyList()
            val ourRelays = relayPool.getReadRelayUrls()
            (extraRelayHints + recipientRelays + ourRelays).distinct().take(5)
                .ifEmpty { relayPool.getRelayUrls().take(3) }
        }

        val allExtraTags = buildList {
            if (interfacePrefs.isClientTagEnabled()) add(listOf("client", "Wisp"))
            addAll(extraTags)
        }

        val zapRequest = when {
            isAnonymous -> {
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
                    extraTags = allExtraTags
                )
            }
            isPrivate -> {
                // DIP-03 requires a concrete note target (id + created_at) to
                // derive the deterministic ephemeral key. Profile / addressable
                // zaps fall back to non-private at the UI gate; this is a
                // defensive guard.
                if (eventId == null || eventCreatedAt == null) {
                    return Result.failure(Exception("Private zaps require a note target"))
                }
                val keypair = keyRepo.getKeypair()
                    ?: return Result.failure(Exception("Private zaps require a local private key"))
                Nip57.buildPrivateZapRequest(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = recipientPubkey,
                    eventId = eventId,
                    eventCreatedAt = eventCreatedAt,
                    amountMsats = amountMsats,
                    relayUrls = relayUrls,
                    lnurl = recipientLud16,
                    message = message,
                    extraTags = allExtraTags
                )
            }
            else -> {
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
                        extraTags = allExtraTags
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
                        extraTags = allExtraTags
                    )
                    else -> return Result.failure(Exception("No signer or keypair available"))
                }
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
