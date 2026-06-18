package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayEvent
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetch a peer's kind 10002 relay list (NIP-65) from indexer relays + the connected pool,
 * then populate [RelayListRepository] with the freshest result. Caller can then ask the
 * repository for the peer's read/inbox or write/outbox relays.
 */
object PeerRelayListLookup {
    suspend fun fetch(
        pubkey: String,
        relayPool: RelayPool,
        relayListRepo: RelayListRepository
    ) {
        val subId = "rl_${pubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(10002),
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
            relayListRepo.updateFromEvent(best.event)
        }
    }
}
