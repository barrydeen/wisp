package com.wisp.app.relay

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.repo.RelayListRepository

class OutboxRouter(
    private val relayPool: RelayPool,
    private val relayListRepo: RelayListRepository
) {
    /**
     * Subscribe to content from [authors] by routing to each author's write relays.
     * Accepts multiple template filters — each gets `.copy(authors=subset)` per relay group.
     * Authors without known relay lists fall back to sendToAll.
     * Returns the set of relay URLs that received subscriptions.
     */
    fun subscribeByAuthors(
        subId: String,
        authors: List<String>,
        vararg templateFilters: Filter
    ): Set<String> {
        val knownAuthors = mutableListOf<String>()
        val unknownAuthors = mutableListOf<String>()

        for (pubkey in authors) {
            if (relayListRepo.hasRelayList(pubkey)) {
                knownAuthors.add(pubkey)
            } else {
                unknownAuthors.add(pubkey)
            }
        }

        val targetedRelays = mutableSetOf<String>()

        // Group known authors by their write relays
        if (knownAuthors.isNotEmpty()) {
            val relayToAuthors = groupAuthorsByWriteRelay(knownAuthors)
            for ((relayUrl, relayAuthors) in relayToAuthors) {
                val filters = templateFilters.map { it.copy(authors = relayAuthors) }
                val msg = if (filters.size == 1) {
                    ClientMessage.req(subId, filters[0])
                } else {
                    ClientMessage.req(subId, filters)
                }
                if (relayPool.sendToRelayOrEphemeral(relayUrl, msg)) {
                    targetedRelays.add(relayUrl)
                }
            }
        }

        // Fallback: send unknown authors to all general relays
        if (unknownAuthors.isNotEmpty()) {
            val filters = templateFilters.map { it.copy(authors = unknownAuthors) }
            val msg = if (filters.size == 1) {
                ClientMessage.req(subId, filters[0])
            } else {
                ClientMessage.req(subId, filters)
            }
            relayPool.sendToAll(msg)
            targetedRelays.addAll(relayPool.getRelayUrls())
        }

        return targetedRelays
    }

    /**
     * Request profiles for [pubkeys] from their known write relays + general relays.
     */
    fun requestProfiles(subId: String, pubkeys: List<String>) {
        val filter = Filter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size)

        // Group by write relay and send targeted requests
        val knownPubkeys = pubkeys.filter { relayListRepo.hasRelayList(it) }
        val unknownPubkeys = pubkeys.filter { !relayListRepo.hasRelayList(it) }

        if (knownPubkeys.isNotEmpty()) {
            val relayToAuthors = groupAuthorsByWriteRelay(knownPubkeys)
            for ((relayUrl, relayAuthors) in relayToAuthors) {
                val f = Filter(kinds = listOf(0), authors = relayAuthors, limit = relayAuthors.size)
                relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(subId, f))
            }
        }

        // Fallback: send unknown pubkeys to all general relays
        if (unknownPubkeys.isNotEmpty()) {
            val f = Filter(kinds = listOf(0), authors = unknownPubkeys, limit = unknownPubkeys.size)
            relayPool.sendToAll(ClientMessage.req(subId, f))
        }
    }

    /**
     * Subscribe to a specific user's content via their write relays.
     * Falls back to general relays when no write relays are known.
     */
    fun subscribeToUserWriteRelays(subId: String, pubkey: String, filter: Filter): Set<String> {
        val targetedRelays = mutableSetOf<String>()
        val writeRelays = relayListRepo.getWriteRelays(pubkey)

        if (writeRelays != null) {
            val msg = ClientMessage.req(subId, filter)
            for (url in writeRelays) {
                if (relayPool.sendToRelayOrEphemeral(url, msg)) {
                    targetedRelays.add(url)
                }
            }
        }

        // Fallback: if no targeted relays found, send to all general relays
        if (targetedRelays.isEmpty()) {
            val msg = ClientMessage.req(subId, filter)
            relayPool.sendToAll(msg)
            targetedRelays.addAll(relayPool.getRelayUrls())
        }

        return targetedRelays
    }

    /**
     * Subscribe to replies on a user's read relays (where commenters should publish).
     * Falls back to general relays when no read relays are known.
     */
    fun subscribeToUserReadRelays(subId: String, pubkey: String, filter: Filter): Set<String> {
        val targetedRelays = mutableSetOf<String>()
        val readRelays = relayListRepo.getReadRelays(pubkey)

        if (readRelays != null) {
            val msg = ClientMessage.req(subId, filter)
            for (url in readRelays) {
                if (relayPool.sendToRelayOrEphemeral(url, msg)) {
                    targetedRelays.add(url)
                }
            }
        }

        // Fallback: if no targeted relays found, send to all general relays
        if (targetedRelays.isEmpty()) {
            val msg = ClientMessage.req(subId, filter)
            relayPool.sendToAll(msg)
            targetedRelays.addAll(relayPool.getRelayUrls())
        }

        return targetedRelays
    }

    /**
     * Request kind 10002 relay lists for pubkeys we don't have cached yet.
     * Returns the subscription ID if a request was sent, null otherwise.
     */
    fun requestMissingRelayLists(pubkeys: List<String>): String? {
        val missing = relayListRepo.getMissingPubkeys(pubkeys)
        if (missing.isEmpty()) return null

        val subId = "relay-lists"
        val filter = Filter(kinds = listOf(10002), authors = missing)
        val msg = ClientMessage.req(subId, filter)
        relayPool.sendToAll(msg)
        return subId
    }

    private fun groupAuthorsByWriteRelay(authors: List<String>): Map<String, List<String>> {
        val relayToAuthors = mutableMapOf<String, MutableList<String>>()
        for (pubkey in authors) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey) ?: continue
            for (url in writeRelays) {
                relayToAuthors.getOrPut(url) { mutableListOf() }.add(pubkey)
            }
        }
        return relayToAuthors
    }
}
