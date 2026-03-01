package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.repo.RelayListRepository

class OutboxRouter(
    private val relayPool: RelayPool,
    private val relayListRepo: RelayListRepository,
    private val relayHintStore: com.wisp.app.repo.RelayHintStore? = null
) {
    companion object {
        /**
         * Maximum number of authors per filter in a single REQ.
         * Big relays (damus.io, nos.social) reject requests where total filter items exceed
         * their limit. Keeping author lists ≤200 per REQ avoids "total filter items too large".
         */
        private const val MAX_AUTHORS_PER_FILTER = 200
    }

    /**
     * Build a single REQ with multiple filters, each containing ≤[MAX_AUTHORS_PER_FILTER] authors.
     * Uses one subscription ID — no chunk subIds needed. Each template filter is expanded into
     * N copies (one per author chunk), all packed into a single REQ message.
     *
     * Example: 500 authors + 1 template → ["REQ","feed",{authors:[1..200]},{authors:[201..400]},{authors:[401..500]}]
     *
     * @param limitPerAuthor When true, each chunk's filter gets `limit = chunk.size`
     *                       (useful for kind-0 profile requests where you expect 1 event/author).
     */
    private fun sendChunkedToAll(
        subId: String,
        authors: List<String>,
        templateFilters: List<Filter>,
        limitPerAuthor: Boolean = false
    ) {
        val chunks = authors.chunked(MAX_AUTHORS_PER_FILTER)
        val allFilters = mutableListOf<Filter>()
        for (chunk in chunks) {
            for (f in templateFilters) {
                val withAuthors = f.copy(authors = chunk)
                allFilters.add(if (limitPerAuthor) withAuthors.copy(limit = chunk.size) else withAuthors)
            }
        }
        val msg = if (allFilters.size == 1) ClientMessage.req(subId, allFilters[0])
        else ClientMessage.req(subId, allFilters)
        relayPool.sendToAll(msg)
    }

    /**
     * Like [sendChunkedToAll] but sends only to top connected relays to avoid broadcast storms.
     * Used for profile metadata fetches where full-pool broadcast is wasteful.
     */
    private fun sendChunkedToTopRelays(
        subId: String,
        authors: List<String>,
        templateFilters: List<Filter>,
        limitPerAuthor: Boolean = false,
        maxRelays: Int = 10
    ) {
        val chunks = authors.chunked(MAX_AUTHORS_PER_FILTER)
        val allFilters = mutableListOf<Filter>()
        for (chunk in chunks) {
            for (f in templateFilters) {
                val withAuthors = f.copy(authors = chunk)
                allFilters.add(if (limitPerAuthor) withAuthors.copy(limit = chunk.size) else withAuthors)
            }
        }
        val msg = if (allFilters.size == 1) ClientMessage.req(subId, allFilters[0])
        else ClientMessage.req(subId, allFilters)
        relayPool.sendToTopRelays(msg, maxRelays)
    }

    /**
     * Subscribe to content from [authors] by routing to each author's write relays.
     * Accepts multiple template filters — each gets `.copy(authors=subset)` per relay group.
     *
     * Uses a unified per-relay author map: each relay gets exactly ONE REQ containing all
     * authors relevant to it (outbox-routed + fallback merged). This avoids sub ID collisions
     * where a second sendToAll would overwrite a targeted REQ on the same relay.
     *
     * Authors without known relay lists are merged into each persistent relay's author set
     * (not broadcast separately), so each relay only receives authors relevant to it.
     *
     * [indexerRelays] receive the FULL author list as a safety net — when an author's write
     * relay is down, the indexer catches their notes. Event dedup handles overlaps.
     *
     * [blockedUrls] filters write relays before placement so authors always land on reachable
     * relays within their MIN_REDUNDANCY budget.
     *
     * Returns the set of relay URLs that received subscriptions.
     */
    fun subscribeByAuthors(
        subId: String,
        authors: List<String>,
        vararg templateFilters: Filter,
        indexerRelays: List<String> = emptyList(),
        blockedUrls: Set<String> = emptySet()
    ): Set<String> {
        val targetedRelays = mutableSetOf<String>()
        val templateList = templateFilters.toList()

        // Build unified relay → authors map, only using relays already in the pool.
        // Creating ephemeral connections per write-relay URL is too expensive — thousands
        // of unique URLs from kind 10002 lists across a large follow graph.
        val poolUrls = relayPool.getRelayUrls().toSet()
        Log.d("RLC", "[OutboxRouter] poolUrls=${poolUrls.size}, connectedCount=${relayPool.connectedCount.value}")
        val relayToAuthors = mutableMapOf<String, MutableSet<String>>()
        val fallbackAuthors = mutableListOf<String>()

        for (author in authors) {
            val writeRelays = relayListRepo.getWriteRelays(author)
            if (writeRelays != null) {
                val eligible = writeRelays.filter { it !in blockedUrls && it in poolUrls }
                if (eligible.isNotEmpty()) {
                    for (url in eligible) {
                        relayToAuthors.getOrPut(url) { mutableSetOf() }.add(author)
                    }
                } else {
                    fallbackAuthors.add(author)
                }
            } else {
                fallbackAuthors.add(author)
            }
        }

        // Merge fallback authors into PINNED relays only (not all pool relays).
        // Broadcasting to the full pool (30+ relays) for authors without relay lists
        // causes massive duplication. Pinned relays + indexers provide sufficient coverage.
        if (fallbackAuthors.isNotEmpty()) {
            val pinnedUrls = relayPool.getPinnedRelayUrls()
            val fallbackTargets = if (pinnedUrls.isNotEmpty()) {
                poolUrls.filter { it in pinnedUrls }
            } else {
                // No pinned relays known — use first 5 pool relays as fallback
                poolUrls.take(5)
            }
            for (url in fallbackTargets) {
                relayToAuthors.getOrPut(url) { mutableSetOf() }.addAll(fallbackAuthors)
            }
        }

        // Send one REQ per relay with its complete author set (chunked)
        for ((relayUrl, relayAuthors) in relayToAuthors) {
            val authorList = relayAuthors.toList()
            val chunks = authorList.chunked(MAX_AUTHORS_PER_FILTER)
            val allFilters = mutableListOf<Filter>()
            for (chunk in chunks) {
                for (f in templateList) {
                    allFilters.add(f.copy(authors = chunk))
                }
            }
            val msg = if (allFilters.size == 1) ClientMessage.req(subId, allFilters[0])
            else ClientMessage.req(subId, allFilters)
            relayPool.sendToRelay(relayUrl, msg)
            targetedRelays.add(relayUrl)
        }

        // Indexer relay safety net: send FULL author list to each indexer relay
        if (indexerRelays.isNotEmpty() && authors.isNotEmpty()) {
            val chunks = authors.chunked(MAX_AUTHORS_PER_FILTER)
            val allFilters = mutableListOf<Filter>()
            for (chunk in chunks) {
                for (f in templateList) {
                    allFilters.add(f.copy(authors = chunk))
                }
            }
            val msg = if (allFilters.size == 1) ClientMessage.req(subId, allFilters[0])
            else ClientMessage.req(subId, allFilters)
            for (indexerUrl in indexerRelays) {
                if (indexerUrl in blockedUrls) continue
                relayPool.sendToRelay(indexerUrl, msg)
                targetedRelays.add(indexerUrl)
            }
        }

        Log.d("RLC", "[OutboxRouter] subscribeByAuthors($subId): ${authors.size} authors → " +
                "${relayToAuthors.size} pool relays targeted, ${fallbackAuthors.size} fallback authors, " +
                "${indexerRelays.size} indexers")

        return targetedRelays
    }

    /**
     * Request profiles for [pubkeys] from their known write relays + general relays.
     * Large author lists are chunked to stay within relay filter limits.
     */
    fun requestProfiles(subId: String, pubkeys: List<String>) {
        // Group by write relay and send targeted requests
        val knownPubkeys = pubkeys.filter { relayListRepo.hasRelayList(it) }
        val unknownPubkeys = pubkeys.filter { !relayListRepo.hasRelayList(it) }

        if (knownPubkeys.isNotEmpty()) {
            val relayToAuthors = groupAuthorsByWriteRelay(knownPubkeys)
            for ((relayUrl, relayAuthors) in relayToAuthors) {
                if (relayUrl.isEmpty()) {
                    sendChunkedToAll(subId, relayAuthors, listOf(
                        Filter(kinds = listOf(0))
                    ), limitPerAuthor = true)
                } else {
                    val f = Filter(kinds = listOf(0), authors = relayAuthors, limit = relayAuthors.size)
                    relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(subId, f))
                }
            }
        }

        // Fallback: send unknown pubkeys to top relays (not all — avoids 150-relay broadcast)
        if (unknownPubkeys.isNotEmpty()) {
            sendChunkedToTopRelays(subId, unknownPubkeys, listOf(
                Filter(kinds = listOf(0))
            ), limitPerAuthor = true)
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

        // Fallback: if still no targeted relays found, send to all general relays
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
    fun publishToInbox(eventMsg: String, targetPubkey: String): Int {
        var sentCount = relayPool.sendToWriteRelays(eventMsg)
        val readRelays = relayListRepo.getReadRelays(targetPubkey)
        if (readRelays != null) {
            for (url in readRelays) {
                if (relayPool.sendToRelayOrEphemeral(url, eventMsg)) sentCount++
            }
        }
        return sentCount
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

        // Hint tier: accumulated relay hints
        val hints = relayHintStore?.getHints(pubkey) ?: emptySet()
        if (hints.isNotEmpty()) return hints.first()

        // Fallback: our outbox
        if (ourOutbox.isNotEmpty()) return ourOutbox.first()

        return ""
    }

    /**
     * Request kind 10002 relay lists for pubkeys we don't have cached yet.
     * Returns the subscription ID if a request was sent, null otherwise.
     */
    fun requestMissingRelayLists(pubkeys: List<String>, subId: String = "relay-lists"): String? {
        val missing = relayListRepo.getMissingPubkeys(pubkeys)
        Log.d("OutboxRouter", "requestMissingRelayLists: ${pubkeys.size} total, ${pubkeys.size - missing.size} cached, ${missing.size} missing")
        if (missing.isEmpty()) return null

        sendChunkedToAll(subId, missing, listOf(Filter(kinds = listOf(10002))))
        return subId
    }

    /**
     * Bootstrap request: fetch both relay lists (kind 10002) and profiles (kind 0)
     * for all [pubkeys] in a single REQ. Profiles are fetched for all pubkeys regardless
     * of whether we already have their relay list — profile data may be missing or stale.
     *
     * Returns the subscription ID if a request was sent, null otherwise.
     */
    fun requestRelayListsAndProfiles(
        pubkeys: List<String>,
        profileRepo: com.wisp.app.repo.ProfileRepository,
        subId: String = "relay-lists"
    ): String? {
        val missingRelayLists = relayListRepo.getMissingPubkeys(pubkeys)
        val missingProfiles = pubkeys.filter { !profileRepo.has(it) }
        val allMissing = (missingRelayLists + missingProfiles).distinct()
        Log.d("OutboxRouter", "requestRelayListsAndProfiles: ${pubkeys.size} total, " +
                "${missingRelayLists.size} missing relay lists, ${missingProfiles.size} missing profiles, " +
                "${allMissing.size} unique to fetch")
        if (allMissing.isEmpty()) return null

        sendChunkedToAll(subId, allMissing, listOf(Filter(kinds = listOf(0, 10002))))
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
    /**
     * Returns the number of unique relays the subscription was sent to,
     * so callers can compute a meaningful EOSE count target.
     */
    fun subscribeEngagementByAuthors(
        prefix: String,
        eventsByAuthor: Map<String, List<String>>,
        activeSubIds: MutableList<String>,
        safetyNetRelays: List<String> = emptyList()
    ): Int {
        val targetedRelays = mutableSetOf<String>()

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

        // Single sub ID sent to each relevant inbox relay — each relay independently
        // tracks its own subscription, and closeOnAllRelays(prefix) cleans up all of them.
        activeSubIds.add(prefix)

        for ((relayUrl, eventIds) in relayToEventIds) {
            val uniqueIds = eventIds.distinct()
            val filter = Filter(kinds = listOf(1, 5, 6, 7, 9735), eTags = uniqueIds, limit = 500)
            if (relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(prefix, filter))) {
                targetedRelays.add(relayUrl)
            }
        }

        // Fallback: authors without known relay lists → send to our read relays
        if (fallbackEventIds.isNotEmpty()) {
            val uniqueIds = fallbackEventIds.distinct()
            val filter = Filter(kinds = listOf(1, 5, 6, 7, 9735), eTags = uniqueIds, limit = 500)
            relayPool.sendToReadRelays(ClientMessage.req(prefix, filter))
        }

        // Safety net: send engagement queries for ALL event IDs to high-coverage relays.
        // Catches engagement from authors whose inbox relays we don't know.
        if (safetyNetRelays.isNotEmpty()) {
            val allEventIds = eventsByAuthor.values.flatten().distinct()
            if (allEventIds.isNotEmpty()) {
                val filter = Filter(kinds = listOf(1, 5, 6, 7, 9735), eTags = allEventIds, limit = 500)
                val msg = ClientMessage.req(prefix, filter)
                for (url in safetyNetRelays) {
                    if (relayPool.sendToRelayOrEphemeral(url, msg)) {
                        targetedRelays.add(url)
                    }
                }
            }
        }

        return targetedRelays.size
    }

    private fun groupAuthorsByWriteRelay(authors: List<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (pubkey in authors) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey)
            if (writeRelays != null) {
                var placed = false
                for (url in writeRelays) {
                    val list = result.getOrPut(url) { mutableListOf() }
                    if (list.size < MAX_AUTHORS_PER_FILTER) {
                        list.add(pubkey)
                        placed = true
                    }
                }
                if (!placed) {
                    // All write relays at capacity — fall back to broadcast
                    result.getOrPut("") { mutableListOf() }.add(pubkey)
                }
            } else {
                result.getOrPut("") { mutableListOf() }.add(pubkey)
            }
        }
        return result
    }
}
