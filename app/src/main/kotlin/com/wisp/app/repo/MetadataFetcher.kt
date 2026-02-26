package com.wisp.app.repo

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Handles batched fetching of profiles, reply counts, zap counts, and quoted events.
 * Extracted from FeedViewModel to reduce its size.
 */
class MetadataFetcher(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val subManager: SubscriptionManager,
    private val profileRepo: ProfileRepository,
    private val eventRepo: EventRepository,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext
) {
    // Batched profile fetching
    private val pendingProfilePubkeys = mutableSetOf<String>()
    private val inFlightProfiles = mutableSetOf<String>() // currently being fetched, prevents re-queueing
    private var profileBatchJob: Job? = null
    private var metaBatchCounter = 0
    private val profileAttempts = mutableMapOf<String, Int>()

    // Batched reply count fetching
    private val pendingReplyCountIds = mutableSetOf<String>()
    private var replyCountBatchJob: Job? = null
    private var replyCountBatchCounter = 0

    // Batched zap count fetching
    private val pendingZapCountIds = mutableSetOf<String>()
    private var zapCountBatchJob: Job? = null
    private var zapCountBatchCounter = 0

    // Quoted event fetching
    private val scannedQuoteEvents = mutableSetOf<String>()
    private val failedQuoteFetches = mutableMapOf<String, Long>() // eventId -> timestamp of failure
    private val nostrNoteUriRegex = Regex("""nostr:(note1|nevent1)[a-z0-9]+""")
    private val validHexId = Regex("""^[0-9a-f]{64}$""")

    // Pubkeys that have been attempted MAX times and never found — stop fetching from feeds.
    // Only cleared on account switch. Profile page bypasses this via forceProfileFetch().
    private val exhaustedProfiles = mutableSetOf<String>()

    companion object {
        private const val MAX_PROFILE_ATTEMPTS = 2
        private const val QUOTE_RETRY_MS = 30_000L // retry failed quotes after 30s
    }

    // Batched quote fetching (unified queue for inline + on-demand)
    private val pendingOnDemandQuotes = mutableSetOf<String>()
    private val inFlightQuotes = mutableSetOf<String>() // currently being fetched, prevents re-queueing
    private val pendingRelayHints = mutableMapOf<String, List<String>>()
    private var onDemandQuoteBatchJob: Job? = null
    private var onDemandQuoteBatchCounter = 0

    // Addressable event fetching
    private val inFlightAddressables = mutableSetOf<String>()
    private var addressableBatchCounter = 0

    /** Returns URLs of the top relays by author coverage for quote lookups. Set by FeedViewModel. */
    var quoteRelayProvider: (() -> List<String>)? = null

    fun clear() {
        profileBatchJob?.cancel()
        replyCountBatchJob?.cancel()
        zapCountBatchJob?.cancel()
        onDemandQuoteBatchJob?.cancel()
        synchronized(pendingProfilePubkeys) {
            pendingProfilePubkeys.clear()
            inFlightProfiles.clear()
        }
        synchronized(pendingReplyCountIds) { pendingReplyCountIds.clear() }
        synchronized(pendingZapCountIds) { pendingZapCountIds.clear() }
        synchronized(pendingOnDemandQuotes) {
            pendingOnDemandQuotes.clear()
            inFlightQuotes.clear()
            pendingRelayHints.clear()
        }
        profileAttempts.clear()
        exhaustedProfiles.clear()
        scannedQuoteEvents.clear()
        failedQuoteFetches.clear()
        metaBatchCounter = 0
        replyCountBatchCounter = 0
        zapCountBatchCounter = 0
        onDemandQuoteBatchCounter = 0
    }

    fun queueProfileFetch(pubkey: String) = addToPendingProfiles(pubkey)

    /**
     * Force-fetch a profile even if it was previously exhausted. Used by profile pages
     * where the user explicitly navigated to a profile and expects to see data.
     */
    fun forceProfileFetch(pubkey: String) {
        synchronized(pendingProfilePubkeys) {
            if (profileRepo.has(pubkey)) return
            exhaustedProfiles.remove(pubkey)
            profileAttempts.remove(pubkey)
        }
        addToPendingProfiles(pubkey)
    }

    fun addToPendingProfiles(pubkey: String) {
        synchronized(pendingProfilePubkeys) {
            if (profileRepo.has(pubkey)) return
            if (pubkey in exhaustedProfiles) return
            if (pubkey in inFlightProfiles) return
            val attempts = profileAttempts[pubkey] ?: 0
            if (attempts >= MAX_PROFILE_ATTEMPTS) {
                exhaustedProfiles.add(pubkey)
                return
            }
            if (pubkey in pendingProfilePubkeys) return
            pendingProfilePubkeys.add(pubkey)
            val shouldFlushNow = pendingProfilePubkeys.size >= 200
            if (shouldFlushNow) {
                profileBatchJob?.cancel()
                flushProfileBatch()
            } else if (profileBatchJob == null || profileBatchJob?.isActive != true) {
                profileBatchJob = scope.launch(processingContext) {
                    delay(100)
                    synchronized(pendingProfilePubkeys) { flushProfileBatch() }
                }
            }
        }
    }

    fun addToPendingReplyCounts(eventId: String) {
        synchronized(pendingReplyCountIds) {
            pendingReplyCountIds.add(eventId)
            val shouldFlushNow = pendingReplyCountIds.size >= 200
            if (shouldFlushNow) {
                replyCountBatchJob?.cancel()
                flushReplyCountBatch()
            } else if (replyCountBatchJob == null || replyCountBatchJob?.isActive != true) {
                replyCountBatchJob = scope.launch(processingContext) {
                    delay(500)
                    synchronized(pendingReplyCountIds) { flushReplyCountBatch() }
                }
            }
        }
    }

    fun addToPendingZapCounts(eventId: String) {
        synchronized(pendingZapCountIds) {
            pendingZapCountIds.add(eventId)
            val shouldFlushNow = pendingZapCountIds.size >= 200
            if (shouldFlushNow) {
                zapCountBatchJob?.cancel()
                flushZapCountBatch()
            } else if (zapCountBatchJob == null || zapCountBatchJob?.isActive != true) {
                zapCountBatchJob = scope.launch(processingContext) {
                    delay(500)
                    synchronized(pendingZapCountIds) { flushZapCountBatch() }
                }
            }
        }
    }

    fun requestQuotedEvent(eventId: String, relayHints: List<String> = emptyList()) {
        synchronized(pendingOnDemandQuotes) {
            if (eventRepo.getEvent(eventId) != null) return
            if (eventId in inFlightQuotes) return
            val failedAt = failedQuoteFetches[eventId]
            if (failedAt != null && System.currentTimeMillis() - failedAt < QUOTE_RETRY_MS) return
            if (eventId in pendingOnDemandQuotes) return
            // Clear old failure so this attempt is fresh
            if (failedAt != null) failedQuoteFetches.remove(eventId)
            pendingOnDemandQuotes.add(eventId)
            if (relayHints.isNotEmpty()) {
                pendingRelayHints[eventId] = relayHints
            }
            val shouldFlushNow = pendingOnDemandQuotes.size >= 200
            if (shouldFlushNow) {
                onDemandQuoteBatchJob?.cancel()
                flushOnDemandQuoteBatch()
            } else if (onDemandQuoteBatchJob == null || onDemandQuoteBatchJob?.isActive != true) {
                onDemandQuoteBatchJob = scope.launch(processingContext) {
                    delay(300)
                    synchronized(pendingOnDemandQuotes) { flushOnDemandQuoteBatch() }
                }
            }
        }
    }

    fun requestAddressableEvent(kind: Int, author: String, dTag: String, relayHints: List<String> = emptyList()) {
        val coordKey = "$kind:$author:$dTag"
        synchronized(pendingOnDemandQuotes) {
            if (coordKey in inFlightAddressables) return
            inFlightAddressables.add(coordKey)
        }
        val subId = "addr-${addressableBatchCounter++}"
        val filter = Filter(kinds = listOf(kind), authors = listOf(author), dTags = listOf(dTag), limit = 1)
        val msg = ClientMessage.req(subId, filter)

        val topRelays = quoteRelayProvider?.invoke() ?: emptyList()
        val targetUrls = (topRelays + relayHints).distinct()
        if (targetUrls.isNotEmpty()) {
            for (url in targetUrls) {
                relayPool.sendToRelayOrEphemeral(url, msg)
            }
        } else {
            relayPool.sendToTopRelays(msg, maxRelays = 10)
        }

        scope.launch(processingContext) {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            synchronized(pendingOnDemandQuotes) {
                inFlightAddressables.remove(coordKey)
            }
        }
    }

    fun fetchQuotedEvents(event: com.wisp.app.nostr.NostrEvent) {
        event.tags.filter { it.size >= 2 && it[0] == "q" }
            .forEach { tag ->
                val id = tag[1].lowercase()
                if (validHexId.matches(id)) {
                    val hints = mutableListOf<String>()
                    if (tag.size >= 3 && tag[2].startsWith("wss://")) hints.add(tag[2])
                    requestQuotedEvent(id, hints)
                }
            }
        for (match in nostrNoteUriRegex.findAll(event.content)) {
            val decoded = Nip19.decodeNostrUri(match.value)
            if (decoded is NostrUriData.NoteRef) {
                requestQuotedEvent(decoded.eventId, decoded.relays)
            }
        }
    }

    fun sweepMissingProfiles() {
        val currentFeed = eventRepo.feed.value
        for (event in currentFeed) {
            if (eventRepo.getProfileData(event.pubkey) == null) {
                addToPendingProfiles(event.pubkey)
            }
            val reposter = eventRepo.getRepostAuthor(event.id)
            if (reposter != null && eventRepo.getProfileData(reposter) == null) {
                addToPendingProfiles(reposter)
            }
            if (event.kind == 1 && event.id !in scannedQuoteEvents) {
                scannedQuoteEvents.add(event.id)
                fetchQuotedEvents(event)
            }
        }
        // Prevent unbounded growth
        if (scannedQuoteEvents.size > 5000) scannedQuoteEvents.clear()
    }

    /** Must be called while holding pendingProfilePubkeys lock */
    private fun flushProfileBatch() {
        if (pendingProfilePubkeys.isEmpty()) return
        val subId = "meta-batch-${metaBatchCounter++}"
        val pubkeys = pendingProfilePubkeys.toList()
        pendingProfilePubkeys.clear()
        inFlightProfiles.addAll(pubkeys)

        pubkeys.forEach { profileAttempts[it] = (profileAttempts[it] ?: 0) + 1 }

        val maxAttempt = pubkeys.maxOf { profileAttempts[it] ?: 1 }
        if (maxAttempt <= 1) {
            outboxRouter.requestProfiles(subId, pubkeys)
        } else {
            // Retry: send to top 10 relays instead of all — most profiles are on major relays
            val filter = Filter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size)
            relayPool.sendToTopRelays(ClientMessage.req(subId, filter), maxRelays = 10)
        }

        scope.launch(processingContext) {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            synchronized(pendingProfilePubkeys) {
                inFlightProfiles.removeAll(pubkeys.toSet())
            }
        }
    }

    private fun flushReplyCountBatch() {
        if (pendingReplyCountIds.isEmpty()) return
        val subId = "reply-count-${replyCountBatchCounter++}"
        val eventIds = pendingReplyCountIds.toList()
        pendingReplyCountIds.clear()
        val filter = Filter(kinds = listOf(1), eTags = eventIds)
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }

    /** Must be called while holding pendingOnDemandQuotes lock */
    private fun flushOnDemandQuoteBatch() {
        if (pendingOnDemandQuotes.isEmpty()) return
        val batch = pendingOnDemandQuotes.toList()
        val hints = pendingRelayHints.toMap()
        pendingOnDemandQuotes.clear()
        pendingRelayHints.clear()
        inFlightQuotes.addAll(batch)

        val subId = "quote-${onDemandQuoteBatchCounter++}"
        val msg = ClientMessage.req(subId, Filter(ids = batch))

        // Send to top relays by coverage (most likely to have the quoted events)
        // plus any relay hints. Falls back to sendToAll if no scored relays available.
        val topRelays = quoteRelayProvider?.invoke() ?: emptyList()
        val hintUrls = hints.values.flatten().toSet()
        val targetUrls = (topRelays + hintUrls).distinct()

        if (targetUrls.isNotEmpty()) {
            for (url in targetUrls) {
                relayPool.sendToRelayOrEphemeral(url, msg)
            }
        } else {
            relayPool.sendToTopRelays(msg, maxRelays = 10)
        }

        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            synchronized(pendingOnDemandQuotes) {
                inFlightQuotes.removeAll(batch.toSet())
            }
            // Mark unfound events as temporarily failed (will retry after QUOTE_RETRY_MS)
            val now = System.currentTimeMillis()
            for (id in batch) {
                if (eventRepo.getEvent(id) == null) {
                    failedQuoteFetches[id] = now
                }
            }
            // Prevent unbounded growth — evict oldest entries
            if (failedQuoteFetches.size > 2000) {
                val cutoff = now - QUOTE_RETRY_MS * 2
                failedQuoteFetches.entries.removeAll { it.value < cutoff }
            }
        }
    }

    private fun flushZapCountBatch() {
        if (pendingZapCountIds.isEmpty()) return
        val subId = "zap-count-${zapCountBatchCounter++}"
        val eventIds = pendingZapCountIds.toList()
        pendingZapCountIds.clear()
        val filter = Filter(kinds = listOf(9735), eTags = eventIds)
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }
}
