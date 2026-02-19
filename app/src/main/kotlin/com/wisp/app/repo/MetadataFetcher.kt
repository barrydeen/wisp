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

    companion object {
        private const val MAX_PROFILE_ATTEMPTS = 3
        private const val QUOTE_RETRY_MS = 30_000L // retry failed quotes after 30s
    }

    // Batched quote fetching (unified queue for inline + on-demand)
    private val pendingOnDemandQuotes = mutableSetOf<String>()
    private val pendingRelayHints = mutableMapOf<String, List<String>>()
    private var onDemandQuoteBatchJob: Job? = null
    private var onDemandQuoteBatchCounter = 0

    fun clear() {
        profileBatchJob?.cancel()
        replyCountBatchJob?.cancel()
        zapCountBatchJob?.cancel()
        onDemandQuoteBatchJob?.cancel()
        synchronized(pendingProfilePubkeys) { pendingProfilePubkeys.clear() }
        synchronized(pendingReplyCountIds) { pendingReplyCountIds.clear() }
        synchronized(pendingZapCountIds) { pendingZapCountIds.clear() }
        synchronized(pendingOnDemandQuotes) {
            pendingOnDemandQuotes.clear()
            pendingRelayHints.clear()
        }
        profileAttempts.clear()
        scannedQuoteEvents.clear()
        failedQuoteFetches.clear()
        metaBatchCounter = 0
        replyCountBatchCounter = 0
        zapCountBatchCounter = 0
        onDemandQuoteBatchCounter = 0
    }

    fun queueProfileFetch(pubkey: String) = addToPendingProfiles(pubkey)

    fun addToPendingProfiles(pubkey: String) {
        synchronized(pendingProfilePubkeys) {
            if (profileRepo.has(pubkey)) return
            val attempts = profileAttempts[pubkey] ?: 0
            if (attempts >= MAX_PROFILE_ATTEMPTS) return
            if (pubkey in pendingProfilePubkeys) return
            pendingProfilePubkeys.add(pubkey)
            val shouldFlushNow = pendingProfilePubkeys.size >= 20
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
            val shouldFlushNow = pendingReplyCountIds.size >= 20
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
            val shouldFlushNow = pendingZapCountIds.size >= 20
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
            val failedAt = failedQuoteFetches[eventId]
            if (failedAt != null && System.currentTimeMillis() - failedAt < QUOTE_RETRY_MS) return
            if (eventId in pendingOnDemandQuotes) return
            // Clear old failure so this attempt is fresh
            if (failedAt != null) failedQuoteFetches.remove(eventId)
            pendingOnDemandQuotes.add(eventId)
            if (relayHints.isNotEmpty()) {
                pendingRelayHints[eventId] = relayHints
            }
            val shouldFlushNow = pendingOnDemandQuotes.size >= 20
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

    fun fetchProfilesForFollows(follows: List<String>) {
        if (follows.isEmpty()) return
        val missing = follows.filter { !profileRepo.has(it) }
        if (missing.isEmpty()) return
        missing.forEach { profileAttempts[it] = (profileAttempts[it] ?: 0) + 1 }
        missing.chunked(20).forEachIndexed { index, batch ->
            val subId = "follow-profiles-$index"
            outboxRouter.requestProfiles(subId, batch)
            scope.launch {
                subManager.awaitEoseWithTimeout(subId)
                subManager.closeSubscription(subId)
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

        pubkeys.forEach { profileAttempts[it] = (profileAttempts[it] ?: 0) + 1 }

        val maxAttempt = pubkeys.maxOf { profileAttempts[it] ?: 1 }
        if (maxAttempt <= 1) {
            outboxRouter.requestProfiles(subId, pubkeys)
        } else {
            val filter = Filter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size)
            relayPool.sendToAll(ClientMessage.req(subId, filter))
        }

        scope.launch(processingContext) {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            delay(15_000)
            for (pk in pubkeys) {
                if (!profileRepo.has(pk)) {
                    addToPendingProfiles(pk)
                }
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

        val subId = "quote-${onDemandQuoteBatchCounter++}"
        relayPool.sendToAll(ClientMessage.req(subId, Filter(ids = batch)))

        // Also query hinted relays for IDs that have hints
        for (id in batch) {
            val relays = hints[id] ?: continue
            for (url in relays) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, Filter(ids = listOf(id))))
            }
        }

        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
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
