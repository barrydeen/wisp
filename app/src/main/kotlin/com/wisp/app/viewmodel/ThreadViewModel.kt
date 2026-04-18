package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip10
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
    private val _rootEvent = MutableStateFlow<NostrEvent?>(null)
    val rootEvent: StateFlow<NostrEvent?> = _rootEvent

    private val _flatThread = MutableStateFlow<List<Pair<NostrEvent, Int>>>(emptyList())
    val flatThread: StateFlow<List<Pair<NostrEvent, Int>>> = _flatThread

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _scrollToIndex = MutableStateFlow(-1)
    val scrollToIndex: StateFlow<Int> = _scrollToIndex

    private val _spamThread = MutableStateFlow<List<Pair<NostrEvent, Int>>>(emptyList())
    val spamThread: StateFlow<List<Pair<NostrEvent, Int>>> = _spamThread

    private val _spamExpanded = MutableStateFlow(false)
    val spamExpanded: StateFlow<Boolean> = _spamExpanded

    private val threadEvents = mutableMapOf<String, NostrEvent>()
    private var rootId: String = ""
    private var scrollTargetId: String? = null
    private var muteRepo: MuteRepository? = null
    private val activeMetadataSubs = mutableListOf<String>()
    private var relayPoolRef: RelayPool? = null
    private var topRelayUrls: List<String> = emptyList()
    private var relayListRepoRef: RelayListRepository? = null
    private var relayHintStoreRef: RelayHintStore? = null
    private var currentUserPubkey: String? = null

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

    // Incremental metadata tracking
    private val metadataSubscribedIds = mutableSetOf<String>()
    private val pendingMetadataIds = mutableSetOf<String>()
    private var metadataBatchIndex = 0

    fun clearScrollTarget() {
        _scrollToIndex.value = -1
        scrollTargetId = null
    }

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

        // Resolve root from cached event (we clicked on it, so it's in cache)
        val cached = eventRepo.getEvent(eventId)
        if (cached != null) {
            val resolvedRoot = Nip10.getRootId(cached) ?: Nip10.getReplyTarget(cached) ?: eventId
            rootId = resolvedRoot
            scrollTargetId = if (resolvedRoot != eventId) eventId else null
            threadEvents[cached.id] = cached

            if (resolvedRoot != eventId) {
                val cachedRoot = eventRepo.getEvent(resolvedRoot)
                if (cachedRoot != null) {
                    _rootEvent.value = cachedRoot
                    threadEvents[cachedRoot.id] = cachedRoot
                }
            } else {
                _rootEvent.value = cached
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

        // Prune thread state when any event is removed (e.g. NIP-09 deletion processed by EventRepository
        // via the feed sub, thread-reactions sub, or anywhere else).
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
                if (subscriptionId != "thread-root" && subscriptionId != "thread-replies") return@collect

                // Route kind 5 deletions through EventRepository so they mark the target deleted
                // and fire removedEvents; the observer above will prune threadEvents.
                if (event.kind == 5) {
                    eventRepo.addEvent(event)
                    return@collect
                }

                if (event.kind != 1) return@collect

                // Silently drop events the user has already deleted on some other client/session.
                if (eventRepo.deletedEventsRepo?.isDeleted(event.id) == true) return@collect

                eventRepo.cacheEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)

                if (Nip10.isStandaloneQuote(event)) return@collect

                // Validate: event must reference the thread root (some relays ignore eTags filter)
                if (event.id != rootId &&
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

            // Baseline metadata subscription for all events known at this point
            subscribeThreadMetadata(relayPool, eventRepo, subManager)

            // Start incremental metadata batching for late arrivals
            startMetadataBatching(relayPool)
        }
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
        // Include kind 5 so reply deletions (which reference the reply id, not the root) also arrive here.
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
     * Late-arriving events get their engagement data fetched incrementally.
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
                if (score >= 0.5f) changed = true
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
        relayPoolRef?.let { pool ->
            pool.closeOnAllRelays("thread-root")
            pool.closeOnAllRelays("thread-replies")
            for (subId in activeMetadataSubs) pool.closeOnAllRelays(subId)
        }
        activeMetadataSubs.clear()
    }

    private fun rebuildTree() {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()
        val spamEnabled = safetyPrefs?.spamFilterEnabled?.value == true
        val spamEvents = mutableListOf<NostrEvent>()
        val pubkeysToScore = mutableSetOf<String>()

        val deletedRepo = eventRepoRef?.deletedEventsRepo
        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            if (deletedRepo?.isDeleted(event.id) == true) continue
            if (muteRepo?.isBlocked(event.pubkey) == true) continue
            if (Nip10.isStandaloneQuote(event)) continue

            if (spamEnabled && spamClassifier != null &&
                event.pubkey != currentUserPubkey &&
                contactRepo?.isFollowing(event.pubkey) != true &&
                safetyPrefs?.isSpamSafelisted(event.pubkey) != true
            ) {
                val noteCount = eventRepoRef?.getCachedEventsByAuthor(event.pubkey, 1, 10)?.size ?: 0
                val cached = spamAuthorCache?.get(event.pubkey, noteCount)
                if (cached != null && cached >= 0.5f) {
                    spamEvents.add(event)
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

        spamEvents.sortBy { it.created_at }
        _spamThread.value = spamEvents.map { it to 0 }

        if (pubkeysToScore.isNotEmpty()) {
            spamScoringPubkeys.addAll(pubkeysToScore)
            scoreAuthorsAsync(pubkeysToScore)
        }

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

        val result = mutableListOf<Pair<NostrEvent, Int>>()
        val visited = mutableSetOf<String>()
        val root = threadEvents[rootId]
        if (root != null) {
            result.add(root to 0)
            visited.add(root.id)
            dfs(rootId, 1, parentToChildren, result, visited)
        } else {
            // Root not yet loaded — render replies we have
            val rootChildren = parentToChildren[rootId] ?: emptyList()
            for (child in rootChildren) {
                if (child.id in visited) continue
                visited.add(child.id)
                result.add(child to 0)
                dfs(child.id, 1, parentToChildren, result, visited)
            }
        }

        _flatThread.value = result

        val targetId = scrollTargetId
        if (targetId != null) {
            val index = result.indexOfFirst { it.first.id == targetId }
            if (index >= 0) {
                _scrollToIndex.value = index
            }
        }
    }

    private fun dfs(
        parentId: String,
        depth: Int,
        parentToChildren: Map<String, List<NostrEvent>>,
        result: MutableList<Pair<NostrEvent, Int>>,
        visited: MutableSet<String>
    ) {
        val children = parentToChildren[parentId] ?: return
        for (child in children) {
            if (child.id in visited) continue
            visited.add(child.id)
            result.add(child to depth)
            dfs(child.id, depth + 1, parentToChildren, result, visited)
        }
    }
}
