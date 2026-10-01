package com.wisp.app.relay

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.NotePublicationTransport
import kotlinx.coroutines.flow.map

class NotePublicationRelayTransport(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter
) : NotePublicationTransport {
    override val results = relayPool.publishResults
    override val relayCopies = relayPool.verifiedRelayEvents.map { it.event to it.relayUrl }

    override fun targetRelays(inboxPubkeys: Collection<String>): Set<String> =
        outboxRouter.getPublicationTargets(inboxPubkeys)

    override suspend fun send(relayUrl: String, event: NostrEvent): Boolean =
        relayPool.sendPublicationToRelay(relayUrl, event)
}
