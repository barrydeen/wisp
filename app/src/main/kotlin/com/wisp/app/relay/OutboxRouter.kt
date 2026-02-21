package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.repo.RelayListRepository

class OutboxRouter(
    private val relayPool: RelayPool,
    private val relayListRepo: RelayListRepository,
    private var relayScoreBoard: RelayScoreBoard? = null
) {
    fun setScoreBoard(scoreBoard: RelayScoreBoard) {
        relayScoreBoard = scoreBoard
    }
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
        val targetedRelays = mutableSetOf<String>()

        // Group authors by relay (scoreboard-aware or fallback)
        val knownAuthors = authors.filter { relayListRepo.hasRelayList(it) }
        val unknownAuthors = authors.filter { !relayListRepo.hasRelayList(it) }

        if (knownAuthors.isNotEmpty()) {
            val relayToAuthors = groupAuthorsByWriteRelay(knownAuthors)
            for ((relayUrl, relayAuthors) in relayToAuthors) {
                // Empty key = scoreboard fallback authors (no scored relay covers them)
                if (relayUrl.isEmpty()) {
                    val filters = templateFilters.map { it.copy(authors = relayAuthors) }
                    val msg = if (filters.size == 1) ClientMessage.req(subId, filters[0])
                    else ClientMessage.req(subId, filters)
                    relayPool.sendToAll(msg)
                    targetedRelays.addAll(relayPool.getRelayUrls())
                    continue
                }
                val filters = templateFilters.map { it.copy(authors = relayAuthors) }
                val msg = if (filters.size == 1) ClientMessage.req(subId, filters[0])
                else ClientMessage.req(subId, filters)
                if (relayPool.sendToRelayOrEphemeral(relayUrl, msg)) {
                    targetedRelays.add(relayUrl)
                }
            }
        }

        // Authors without any relay list → sendToAll
        if (unknownAuthors.isNotEmpty()) {
            val filters = templateFilters.map { it.copy(authors = unknownAuthors) }
            val msg = if (filters.size == 1) ClientMessage.req(subId, filters[0])
            else ClientMessage.req(subId, filters)
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
                if (relayUrl.isEmpty()) {
                    // Scoreboard fallback — broadcast to all
                    relayPool.sendToAll(ClientMessage.req(subId, f))
                } else {
                    relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(subId, f))
                }
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
     * Publish an event to own write relays AND the target user's read (inbox) relays.
     * Used for replies, reactions, and reposts so they reach the intended recipient.
     */
    fun publishToInbox(eventMsg: String, targetPubkey: String) {
        relayPool.sendToWriteRelays(eventMsg)
        val readRelays = relayListRepo.getReadRelays(targetPubkey)
        if (readRelays != null) {
            for (url in readRelays) {
                relayPool.sendToRelayOrEphemeral(url, eventMsg)
            }
        }
    }

    /**
     * Pick the best relay hint URL for an event authored by [pubkey].
     * Prefers a relay in both the target's inbox and our outbox,
     * then falls back to their inbox, then our outbox, then empty.
     */
    fun getRelayHint(pubkey: String): String {
        val theirInbox = relayListRepo.getReadRelays(pubkey)?.toSet() ?: emptySet()
        val ourOutbox = relayPool.getWriteRelayUrls().toSet()

        // Best: a relay in both sets
        val overlap = theirInbox.intersect(ourOutbox)
        if (overlap.isNotEmpty()) return overlap.first()

        // Next: their inbox
        if (theirInbox.isNotEmpty()) return theirInbox.first()

        // Fallback: our outbox
        if (ourOutbox.isNotEmpty()) return ourOutbox.first()

        return ""
    }

    /**
     * Request kind 10002 relay lists for pubkeys we don't have cached yet.
     * Returns the subscription ID if a request was sent, null otherwise.
     */
    fun requestMissingRelayLists(pubkeys: List<String>): String? {
        val missing = relayListRepo.getMissingPubkeys(pubkeys)
        Log.d("OutboxRouter", "requestMissingRelayLists: ${pubkeys.size} total, ${pubkeys.size - missing.size} cached, ${missing.size} missing")
        if (missing.isEmpty()) return null

        val subId = "relay-lists"
        val filter = Filter(kinds = listOf(10002), authors = missing)
        val msg = ClientMessage.req(subId, filter)
        relayPool.sendToAll(msg)
        return subId
    }

    /**
     * Subscribe for engagement data (reactions, zaps, replies) on events, routed to each
     * author's read (inbox) relays per NIP-65. Reactors publish to the author's inbox,
     * so we must query there instead of our own read relays.
     *
     * @param prefix Subscription ID prefix (e.g. "engage", "user-engage")
     * @param eventsByAuthor Map of authorPubkey -> list of eventIds authored by them
     * @param activeSubIds Mutable list to track created subscription IDs for later cleanup
     */
    fun subscribeEngagementByAuthors(
        prefix: String,
        eventsByAuthor: Map<String, List<String>>,
        activeSubIds: MutableList<String>
    ) {
        // Group authors by their read (inbox) relays
        val relayToEventIds = mutableMapOf<String, MutableList<String>>()
        val fallbackEventIds = mutableListOf<String>()

        for ((authorPubkey, eventIds) in eventsByAuthor) {
            val readRelays = relayListRepo.getReadRelays(authorPubkey)
            if (readRelays != null && readRelays.isNotEmpty()) {
                for (url in readRelays) {
                    relayToEventIds.getOrPut(url) { mutableListOf() }.addAll(eventIds)
                }
            } else {
                fallbackEventIds.addAll(eventIds)
            }
        }

        var subIndex = 0

        // Send targeted engagement queries to each author's inbox relays
        for ((relayUrl, eventIds) in relayToEventIds) {
            val uniqueIds = eventIds.distinct()
            for (batch in uniqueIds.chunked(50)) {
                val subId = if (subIndex == 0) prefix else "$prefix-$subIndex"
                subIndex++
                activeSubIds.add(subId)
                val filters = listOf(
                    Filter(kinds = listOf(7), eTags = batch),
                    Filter(kinds = listOf(9735), eTags = batch),
                    Filter(kinds = listOf(1), eTags = batch)
                )
                relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(subId, filters))
            }
        }

        // Fallback: authors without known relay lists → send to our read relays
        if (fallbackEventIds.isNotEmpty()) {
            val uniqueIds = fallbackEventIds.distinct()
            for (batch in uniqueIds.chunked(50)) {
                val subId = if (subIndex == 0) prefix else "$prefix-$subIndex"
                subIndex++
                activeSubIds.add(subId)
                val filters = listOf(
                    Filter(kinds = listOf(7), eTags = batch),
                    Filter(kinds = listOf(9735), eTags = batch),
                    Filter(kinds = listOf(1), eTags = batch)
                )
                relayPool.sendToReadRelays(ClientMessage.req(subId, filters))
            }
        }
    }

    private fun groupAuthorsByWriteRelay(authors: List<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        // Use scoreboard for authors it covers; for uncovered authors, look up
        // their write relays directly so they get proper relay routing instead of
        // being dumped into a single sendToAll broadcast.
        val scoreBoard = relayScoreBoard
        if (scoreBoard != null && scoreBoard.hasScoredRelays()) {
            val grouped = scoreBoard.getRelaysForAuthors(authors)
            for ((relay, group) in grouped) {
                if (relay.isNotEmpty()) {
                    result.getOrPut(relay) { mutableListOf() }.addAll(group)
                } else {
                    // Authors not in scoreboard — look up their write relays directly
                    for (pubkey in group) {
                        val writeRelays = relayListRepo.getWriteRelays(pubkey)
                        if (writeRelays != null) {
                            for (url in writeRelays) {
                                result.getOrPut(url) { mutableListOf() }.add(pubkey)
                            }
                        } else {
                            result.getOrPut("") { mutableListOf() }.add(pubkey)
                        }
                    }
                }
            }
        } else {
            for (pubkey in authors) {
                val writeRelays = relayListRepo.getWriteRelays(pubkey) ?: continue
                for (url in writeRelays) {
                    result.getOrPut(url) { mutableListOf() }.add(pubkey)
                }
            }
        }

        return result
    }
}
