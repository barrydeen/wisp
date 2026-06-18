package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayEvent
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetch a peer's kind 10050 DM relays from indexer relays + the connected pool.
 *
 * Shared by [cooking.zap.app.viewmodel.DmConversationViewModel] (peer DM send) and
 * [cooking.zap.app.viewmodel.ComposeViewModel] (private reply send) so both code paths
 * use the same indexer set, 4s collection window, and LRU cache via [DmRepository].
 */
object DmRelayLookup {
    suspend fun fetch(
        pubkey: String,
        relayPool: RelayPool,
        dmRepo: DmRepository,
        forceRefresh: Boolean = false
    ): List<String> {
        if (!forceRefresh) {
            dmRepo.getCachedDmRelays(pubkey)?.let { return it }
        }

        val subId = "dm_relay_${pubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(pubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        val results = mutableListOf<RelayEvent>()
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId }
                .collect { results.add(it) }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        val best = results.maxByOrNull { it.event.created_at }
        if (best != null) {
            val urls = Nip51.parseRelaySet(best.event)
            if (urls.isNotEmpty()) {
                dmRepo.cacheDmRelays(pubkey, urls)
                return urls
            }
        }
        return emptyList()
    }
}
