package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.ml.NSpamClassifier
import com.wisp.app.ml.NoteInput
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.SafetyPreferences
import com.wisp.app.repo.SpamAuthorCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadViewModel : ViewModel() {
    // The event this screen is focused on
    private val _focal = MutableStateFlow<NostrEvent?>(null)
    val focal: StateFlow<NostrEvent?> = _focal

    // Ancestor chain from root → focal-1 (in order, not including focal)
    private val _ancestors = MutableStateFlow<List<NostrEvent>>(emptyList())
    val ancestors: StateFlow<List<NostrEvent>> = _ancestors

    // Direct children of focal, sorted oldest first (own posts first)
    private val _replies = MutableStateFlow<List<NostrEvent>>(emptyList())
    val replies: StateFlow<List<NostrEvent>> = _replies

    // Direct child count per event id (for "View N replies" hints)
    private val _childCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val childCounts: StateFlow<Map<String, Int>> = _childCounts

    // IDs of direct replies whose author is blocked (shown as placeholders, not full cards)
    private val _blockedReplyIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedReplyIds: StateFlow<Set<String>> = _blockedReplyIds

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _spamThread = MutableStateFlow<List<NostrEvent>>(emptyList())
    val spamThread: StateFlow<List<NostrEvent>> = _spamThread

    private val _spamExpanded = MutableStateFlow(false)
    val spamExpanded: StateFlow<Boolean> = _spamExpanded

    // The root event of the entire thread (used for relay routing)
    private val _rootEvent = MutableStateFlow<NostrEvent?>(null)

    private val threadEvents = mutableMapOf<String, NostrEvent>()
    private var rootId: String = ""
    private var seedEventId: String = ""
    // Re-anchored to the inner kind-1 id when the seed turns out to be a kind-6 repost wrapper,
    // so reply / ancestor / engagement queries hit the id real replies actually `e`-tag.
    private var focalEventId: String = ""
    private var muteRepo: MuteRepository? = null
    private val activeMetadataSubs = mutableListOf<String>()
    private var relayPoolRef: RelayPool? = null
    private var topRelayUrls: List<String> = emptyList()
    private var relayListRepoRef: RelayListRepository? = null
    private var relayHintStoreRef: RelayHintStore? = null
    private var currentUserPubkey: String? = null
    private var metadataFetcherRef: MetadataFetcher? = null

    // Spam filter
    private var spamClassifier: NSpamClassifier? = null
    private var spamAuthorCache: SpamAuthorCache? = null
    private var safetyPrefs: SafetyPreferences? = null
    private var contactRepo: ContactRepository? = null
    private var eventRepoRef: EventRepository? = null
    private val spamScoringPubkeys = mutableSetOf<String>()

    // Jobs for cleanup
    private var collectorJob: Job? = null
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private var metadataBatchJob: Job? = null
    private var muteObserverJob: Job? = null
    private var deletionObserverJob: Job? = null
    private var ancestorFetchJob: Job? = null

    // Incremental metadata tracking
    private val metadataSubscribedIds = mutableSetOf<String>()
    private val pendingMetadataIds = mutableSetOf<String>()
    private var metadataBatchIndex = 0

    fun toggleSpamExpanded() {
        _spamExpanded.value = !_spamExpanded.value
    }

    fun markNotSpam(pubkey: String) {
        safetyPrefs?.addToSpamSafelist(pubkey)
        scheduleRebuild()
    }

    fun loadThread(
        eventId: String,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        outboxRouter: OutboxRouter,
        subManager: SubscriptionManager,
        metadataFetcher: MetadataFetcher,
        muteRepo: MuteRepository? = null,
        topRelayUrls: List<String> = emptyList(),
        relayListRepo: RelayListRepository? = null,
        relayHintStore: RelayHintStore? = null,
        spamClassifier: NSpamClassifier? = null,
        spamAuthorCache: SpamAuthorCache? = null,
        safetyPrefs: SafetyPreferences? = null,
        contactRepo: ContactRepository? = null
    ) {
        this.muteRepo = muteRepo
        this.spamClassifier = spamClassifier
        this.spamAuthorCache = spamAuthorCache
        this.safetyPrefs = safetyPrefs
        this.contactRepo = contactRepo
        this.eventRepoRef = eventRepo
        this.metadataFetcherRef = metadataFetcher
        this.seedEventId = eventId
        this.focalEventId = eventId

        // Reactively rebuild thread when blocked users change (e.g. blocking mid-thread)
        muteObserverJob?.cancel()
        muteObserverJob = muteRepo?.let { repo ->
            viewModelScope.launch {
                repo.blockedPubkeys.collect { scheduleRebuild() }
            }
        }
        this.relayPoolRef = relayPool
        this.topRelayUrls = topRelayUrls
        this.relayListRepoRef = relayListRepo
        this.relayHintStoreRef = relayHintStore
        this.currentUserPubkey = eventRepo.currentUserPubkey

        // Resolve root from cached event (we clicked on it, so it's in cache).
        // If the seed turns out to be a kind-6 repost, substitute the inner kind-1 in for
        // the focal slot — otherwise the focal would render the wrapper and Nip10's
        // ancestor walk would pull the inner in on top, duplicating the same content.
        // Re-anchor focalEventId to the inner id so reply / engagement filters match the
        // id real replies actually e-tag.
        val cached = eventRepo.getEvent(eventId)
        if (cached != null) {
            val focalEvent = Nip18.unwrapRepostForFocal(cached)
            focalEventId = focalEvent.id
            val resolvedRoot = Nip10.getRootId(focalEvent) ?: Nip10.getReplyTarget(focalEvent) ?: focalEventId
            rootId = resolvedRoot
            threadEvents[focalEvent.id] = focalEvent
            _focal.value = focalEvent

            if (resolvedRoot != focalEventId) {
                val cachedRoot = eventRepo.getEvent(resolvedRoot)
                if (cachedRoot != null) {
                    _rootEvent.value = cachedRoot
                    threadEvents[cachedRoot.id] = cachedRoot
                }
            } else {
                _rootEvent.value = focalEvent
            }
        } else {
            rootId = eventId
        }

        // Seed from cache: BFS walks nested replies (getCachedThreadEvents already filters deletions)
        val cachedEvents = eventRepo.getCachedThreadEvents(rootId)
        for (event in cachedEvents) {
            threadEvents[event.id] = event
            if (event.id == rootId) _rootEvent.value = event
        }
        rebuildTree()
        if (cachedEvents.size > 1) {
            _isLoading.value = false
        }

        // Prune thread state when any event is removed (e.g. NIP-09 deletion)
        deletionObserverJob?.cancel()
        deletionObserverJob = viewModelScope.launch {
            eventRepo.removedEvents.collect { removedId ->
                if (threadEvents.remove(removedId) != null) {
                    synchronized(pendingMetadataIds) { pendingMetadataIds.remove(removedId) }
                    metadataSubscribedIds.remove(removedId)
                    if (removedId == rootId) _rootEvent.value = null
                    scheduleRebuild()
                }
            }
        }

        // Direct RelayPool collection — no dependency on FeedViewModel
        collectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                if (subscriptionId != "thread-root" && subscriptionId != "thread-replies"
                    && subscriptionId != "thread-ancestors") return@collect

                // Route kind 5 deletions through EventRepository
                if (event.kind == 5) {
                    eventRepo.addEvent(event)
                    return@collect
                }

                if (event.kind != 1) return@collect

                // Silently drop events the user has already deleted
                if (eventRepo.deletedEventsRepo?.isDeleted(event.id) == true) return@collect

                eventRepo.cacheEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)

                if (Nip10.isStandaloneQuote(event)) return@collect

                // Validate: event must reference the thread root (some relays ignore eTags filter)
                // Allow ancestor fetches through (they may not reference root directly)
                if (subscriptionId != "thread-ancestors" &&
                    event.id != rootId &&
                    event.tags.none { it.size >= 2 && it[0] == "e" && it[1] == rootId }) {
                    return@collect
                }

                val isNew = event.id !in threadEvents
                threadEvents[event.id] = event
                if (event.id == rootId) {
                    _rootEvent.value = event
                }
                if (isNew) {
                    // Queue profile fetch for new authors
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                    // Track for incremental metadata subscriptions
                    synchronized(pendingMetadataIds) {
                        pendingMetadataIds.add(event.id)
                    }
                    scheduleRebuild()
                }
            }
        }

        // Also collect thread reactions/engagement + deletions for replies
        viewModelScope.launch {
            relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                if (!subscriptionId.startsWith("thread-reactions")) return@collect
                when (event.kind) {
                    5, 7, 6, 1018 -> eventRepo.addEvent(event)
                    9735 -> {
                        eventRepo.addEvent(event)
                        val zapperPubkey = com.wisp.app.nostr.Nip57.getZapperPubkey(event)
                        if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                            metadataFetcher.addToPendingProfiles(zapperPubkey)
                        }
                    }
                }
            }
        }

        // Two-phase loading with outbox routing
        loadJob = viewModelScope.launch {
            // Phase 1: Fetch root if not cached
            val needsFetchRoot = _rootEvent.value == null || _rootEvent.value?.id != rootId
            if (needsFetchRoot) {
                relayPool.sendToAll(ClientMessage.req("thread-root", Filter(ids = listOf(rootId))))
                subManager.awaitEoseWithTimeout("thread-root", 5_000)
            }

            // Phase 2: Now we (hopefully) have the root — use outbox routing for replies
            val rootEvent = _rootEvent.value
            // Include kind 5 so deletions of the root (or any event tagging the root) come through.
            val repliesFilter = Filter(kinds = listOf(1, 5), eTags = listOf(rootId))
            if (rootEvent != null) {
                outboxRouter.subscribeToUserReadRelays(
                    "thread-replies", rootEvent.pubkey, repliesFilter
                )
            } else {
                // Root still not found — query all relays as fallback
                relayPool.sendToAll(
                    ClientMessage.req("thread-replies", repliesFilter)
                )
            }
            // Also query top scored relays as safety net
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url,
                    ClientMessage.req("thread-replies", repliesFilter))
            }

            // Wait for replies EOSE, then hide spinner
            subManager.awaitEoseWithTimeout("thread-replies", 5_000)
            _isLoading.value = false

            // Fetch ancestor chain for focal events opened deep in a thread
            fetchAncestorChain(relayPool, subManager)

            // Baseline metadata subscription for all events known at this point
            subscribeThreadMetadata(relayPool, eventRepo, subManager)

            // Start incremental metadata batching for late arrivals
            startMetadataBatching(relayPool)
        }
    }

    /**
     * Walk from focal upward via Nip10.getReplyTarget to build the ancestor chain.
     * For each missing ancestor, fire a one-shot fetch from indexer relays + author relays.
     * Bounded at 30 hops as a safety stop.
     */
    private suspend fun fetchAncestorChain(relayPool: RelayPool, subManager: SubscriptionManager) {
        val focal = threadEvents[focalEventId] ?: return
        var current = focal
        var hops = 0

        while (hops < 30) {
            val parentInfo = Nip10.getReplyTargetWithHint(current) ?: break
            val parentId = parentInfo.first
            val relayHint = parentInfo.second

            // Already have this parent
            if (parentId in threadEvents) {
                current = threadEvents[parentId]!!
                hops++
                continue
            }

            // Fetch the missing parent
            val fetchIds = listOf(parentId)
            val filter = Filter(ids = fetchIds)
            val subId = "thread-ancestors"

            // Try relay hint first, then top relays
            if (relayHint != null) {
                relayPool.sendToRelayOrEphemeral(relayHint, ClientMessage.req(subId, filter))
            }
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filter))
            }
            relayPool.sendToAll(ClientMessage.req(subId, filter))

            subManager.awaitEoseWithTimeout(subId, 3_000)

            // Check if we got it
            val parent = threadEvents[parentId] ?: break
            current = parent
            hops++
        }

        // Rebuild after ancestor chain is complete
        scheduleRebuild()
    }

    /**
     * Debounced tree rebuild — cancels any pending rebuild and waits 100ms.
     * 50 rapid events = 1 rebuild instead of 50.
     */
    private fun scheduleRebuild() {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(100)
            rebuildTree()
        }
    }

    /**
     * Get the best relay URLs for a pubkey: NIP-65 read relays, falling back to relay hints.
     */
    private fun getAuthorRelays(pubkey: String): List<String> {
        val nip65 = relayListRepoRef?.getReadRelays(pubkey)
        if (!nip65.isNullOrEmpty()) return nip65
        val hints = relayHintStoreRef?.getHints(pubkey)
        if (!hints.isNullOrEmpty()) return hints.toList()
        return emptyList()
    }

    /**
     * Send a subscription to author relays + top scored relays.
     */
    private fun sendToEngagementRelays(
        relayPool: RelayPool, subId: String, filter: Filter, authorPubkey: String?
    ) {
        val msg = ClientMessage.req(subId, filter)
        val sent = mutableSetOf<String>()
        if (authorPubkey != null) {
            for (url in getAuthorRelays(authorPubkey)) {
                if (relayPool.sendToRelayOrEphemeral(url, msg)) sent.add(url)
            }
        }
        for (url in topRelayUrls) {
            if (url !in sent) relayPool.sendToRelayOrEphemeral(url, msg)
        }
    }

    private suspend fun subscribeThreadMetadata(
        relayPool: RelayPool,
        eventRepo: EventRepository,
        subManager: SubscriptionManager
    ) {
        for (subId in activeMetadataSubs) relayPool.closeOnAllRelays(subId)
        activeMetadataSubs.clear()

        val eventIds = threadEvents.keys.toList()
        if (eventIds.isEmpty()) return

        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            val parentId = Nip10.getReplyTarget(event) ?: rootId
            eventRepo.addReplyCount(parentId, event.id)
        }

        // Track these as already subscribed
        metadataSubscribedIds.addAll(eventIds)

        val rootAuthorPubkey = _rootEvent.value?.pubkey

        // Phase 1: Root note engagement (high priority) — await EOSE for reliable counts
        val rootSubId = "thread-reactions"
        activeMetadataSubs.add(rootSubId)
        val rootFilter = Filter(kinds = listOf(5, 7, 6, 1018, 9735), eTags = listOf(rootId))
        sendToEngagementRelays(relayPool, rootSubId, rootFilter, rootAuthorPubkey)
        subManager.awaitEoseWithTimeout(rootSubId, 3_500)

        // Phase 2: Reply engagement (lower priority) — fire-and-forget
        val replyIds = eventIds.filter { it != rootId }
        if (replyIds.isNotEmpty()) {
            replyIds.chunked(50).forEachIndexed { index, batch ->
                val subId = "thread-reactions-${index + 1}"
                activeMetadataSubs.add(subId)
                val filter = Filter(kinds = listOf(5, 7, 6, 1018, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, rootAuthorPubkey)
            }
        }
    }

    /**
     * Batch pending metadata IDs every 500ms into new subscriptions.
     */
    private fun startMetadataBatching(relayPool: RelayPool) {
        metadataBatchJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val batch = synchronized(pendingMetadataIds) {
                    val newIds = pendingMetadataIds.filter { it !in metadataSubscribedIds }
                    pendingMetadataIds.clear()
                    if (newIds.isEmpty()) null
                    else {
                        metadataSubscribedIds.addAll(newIds)
                        newIds.toList()
                    }
                } ?: continue
                metadataBatchIndex++
                val subId = "thread-reactions-b$metadataBatchIndex"
                activeMetadataSubs.add(subId)
                val filter = Filter(kinds = listOf(5, 7, 6, 1018, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, _rootEvent.value?.pubkey)
            }
        }
    }

    private fun scoreAuthorsAsync(pubkeys: Set<String>) {
        val classifier = spamClassifier ?: return
        val cache = spamAuthorCache ?: return
        val repo = eventRepoRef ?: return
        viewModelScope.launch(Dispatchers.Default) {
            var changed = false
            for (pubkey in pubkeys) {
                val notes = repo.getCachedEventsByAuthor(pubkey, 1, 10)
                if (notes.isEmpty()) continue
                val inputs = notes.map { e -> NoteInput(e.content, e.tags, e.created_at) }
                val score = classifier.score(inputs) ?: continue
                cache.put(pubkey, score, inputs.size)
                if (score >= 0.7f) changed = true
            }
            if (changed) withContext(Dispatchers.Main) { scheduleRebuild() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJob?.cancel()
        loadJob?.cancel()
        rebuildJob?.cancel()
        metadataBatchJob?.cancel()
        muteObserverJob?.cancel()
        deletionObserverJob?.cancel()
        ancestorFetchJob?.cancel()
        relayPoolRef?.let { pool ->
            pool.closeOnAllRelays("thread-root")
            pool.closeOnAllRelays("thread-replies")
            pool.closeOnAllRelays("thread-ancestors")
            for (subId in activeMetadataSubs) pool.closeOnAllRelays(subId)
        }
        activeMetadataSubs.clear()
    }

    private fun rebuildTree() {
        val deletedRepo = eventRepoRef?.deletedEventsRepo
        val spamEnabled = safetyPrefs?.spamFilterEnabled?.value == true
        val spamEvents = mutableListOf<NostrEvent>()
        val pubkeysToScore = mutableSetOf<String>()

        // Build parent→children map for all thread events
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()
        val hiddenSpamPubkeys = mutableSetOf<String>()

        val blockedIds = mutableSetOf<String>()

        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            if (deletedRepo?.isDeleted(event.id) == true) continue
            if (Nip10.isStandaloneQuote(event)) continue
            if (muteRepo?.isBlocked(event.pubkey) == true) {
                // Keep in tree as a placeholder stub; track id so UI can render accordingly
                blockedIds.add(event.id)
                var parentId = Nip10.getReplyTarget(event) ?: rootId
                if (parentId != rootId && parentId !in threadEvents) parentId = rootId
                parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
                continue
            }

            if (spamEnabled && spamClassifier != null &&
                event.pubkey != currentUserPubkey &&
                contactRepo?.isFollowing(event.pubkey) != true &&
                safetyPrefs?.isSpamSafelisted(event.pubkey) != true
            ) {
                val noteCount = eventRepoRef?.getCachedEventsByAuthor(event.pubkey, 1, 10)?.size ?: 0
                val cached = spamAuthorCache?.get(event.pubkey, noteCount)
                if (cached != null && cached >= 0.7f) {
                    spamEvents.add(event)
                    hiddenSpamPubkeys.add(event.pubkey)
                    continue
                }
                if (cached == null && event.pubkey !in spamScoringPubkeys) {
                    pubkeysToScore.add(event.pubkey)
                }
            }

            var parentId = Nip10.getReplyTarget(event) ?: rootId
            if (parentId != rootId && parentId !in threadEvents) {
                parentId = rootId
            }
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
        }

        if (pubkeysToScore.isNotEmpty()) {
            spamScoringPubkeys.addAll(pubkeysToScore)
            scoreAuthorsAsync(pubkeysToScore)
        }

        // Sort children: own posts first, then by timestamp
        val myPubkey = currentUserPubkey
        for (children in parentToChildren.values) {
            children.sortWith(Comparator { a, b ->
                val aIsOwn = myPubkey != null && a.pubkey == myPubkey
                val bIsOwn = myPubkey != null && b.pubkey == myPubkey
                if (aIsOwn != bIsOwn) {
                    if (aIsOwn) -1 else 1
                } else {
                    a.created_at.compareTo(b.created_at)
                }
            })
        }

        // --- Compute the three slices ---

        // 1. Focal
        val focalEvent = threadEvents[focalEventId]
        _focal.value = focalEvent

        // 2. Ancestors: walk from focal upward to root
        val ancestorChain = mutableListOf<NostrEvent>()
        if (focalEvent != null) {
            var current: NostrEvent = focalEvent
            val visited = mutableSetOf(focalEvent.id)
            while (true) {
                val parentId = Nip10.getReplyTarget(current) ?: break
                val parent = threadEvents[parentId] ?: break
                if (parent.id in visited) break
                visited.add(parent.id)
                ancestorChain.add(0, parent) // prepend to get root-first order
                current = parent
            }
        }
        _ancestors.value = ancestorChain

        // 3. Replies: direct children of focal (includes blocked stubs)
        val directReplies = if (focalEvent != null) {
            parentToChildren[focalEvent.id] ?: emptyList()
        } else {
            emptyList()
        }
        _replies.value = directReplies
        _blockedReplyIds.value = directReplies.filter { it.id in blockedIds }.map { it.id }.toSet()

        // 4. Child counts: count direct children for every event in the thread
        val counts = mutableMapOf<String, Int>()
        for ((parentId, children) in parentToChildren) {
            counts[parentId] = children.size
        }
        _childCounts.value = counts

        // 5. Spam thread (flat, for the spam toggle section)
        spamEvents.sortBy { it.created_at }
        _spamThread.value = spamEvents

        // Register reply counts with EventRepository for engagement display
        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            val parentId = Nip10.getReplyTarget(event) ?: rootId
            eventRepoRef?.addReplyCount(parentId, event.id)
        }
    }
}
