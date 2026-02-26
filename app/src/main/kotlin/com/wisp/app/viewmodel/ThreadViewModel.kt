package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ThreadViewModel : ViewModel() {
    private val _rootEvent = MutableStateFlow<NostrEvent?>(null)
    val rootEvent: StateFlow<NostrEvent?> = _rootEvent

    private val _flatThread = MutableStateFlow<List<Pair<NostrEvent, Int>>>(emptyList())
    val flatThread: StateFlow<List<Pair<NostrEvent, Int>>> = _flatThread

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _scrollToIndex = MutableStateFlow(-1)
    val scrollToIndex: StateFlow<Int> = _scrollToIndex

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

    // Jobs for cleanup
    private var collectorJob: Job? = null
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private var metadataBatchJob: Job? = null

    // Incremental metadata tracking
    private val metadataSubscribedIds = mutableSetOf<String>()
    private val pendingMetadataIds = mutableSetOf<String>()
    private var metadataBatchIndex = 0

    fun clearScrollTarget() {
        _scrollToIndex.value = -1
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
        relayHintStore: RelayHintStore? = null
    ) {
        this.muteRepo = muteRepo
        this.relayPoolRef = relayPool
        this.topRelayUrls = topRelayUrls
        this.relayListRepoRef = relayListRepo
        this.relayHintStoreRef = relayHintStore
        this.currentUserPubkey = eventRepo.currentUserPubkey

        // Resolve root from cached event (we clicked on it, so it's in cache)
        val cached = eventRepo.getEvent(eventId)
        if (cached != null) {
            val resolvedRoot = Nip10.getRootId(cached) ?: eventId
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

        // Seed from cache: BFS walks nested replies
        val cachedEvents = eventRepo.getCachedThreadEvents(rootId)
        for (event in cachedEvents) {
            threadEvents[event.id] = event
            if (event.id == rootId) _rootEvent.value = event
        }
        rebuildTree()
        if (cachedEvents.size > 1) {
            _isLoading.value = false
        }

        // Direct RelayPool collection — no dependency on FeedViewModel
        collectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                if (subscriptionId != "thread-root" && subscriptionId != "thread-replies") return@collect
                if (event.kind != 1) return@collect

                eventRepo.cacheEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)

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

        // Also collect thread reactions/engagement
        viewModelScope.launch {
            relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                if (!subscriptionId.startsWith("thread-reactions")) return@collect
                when (event.kind) {
                    7, 6 -> eventRepo.addEvent(event)
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
            val repliesFilter = Filter(kinds = listOf(1), eTags = listOf(rootId))
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
        val rootFilter = Filter(kinds = listOf(7, 6, 9735), eTags = listOf(rootId))
        sendToEngagementRelays(relayPool, rootSubId, rootFilter, rootAuthorPubkey)
        subManager.awaitEoseWithTimeout(rootSubId, 3_500)

        // Phase 2: Reply engagement (lower priority) — fire-and-forget
        val replyIds = eventIds.filter { it != rootId }
        if (replyIds.isNotEmpty()) {
            replyIds.chunked(50).forEachIndexed { index, batch ->
                val subId = "thread-reactions-${index + 1}"
                activeMetadataSubs.add(subId)
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
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
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, _rootEvent.value?.pubkey)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJob?.cancel()
        loadJob?.cancel()
        rebuildJob?.cancel()
        metadataBatchJob?.cancel()
        relayPoolRef?.let { pool ->
            pool.closeOnAllRelays("thread-root")
            pool.closeOnAllRelays("thread-replies")
            for (subId in activeMetadataSubs) pool.closeOnAllRelays(subId)
        }
        activeMetadataSubs.clear()
    }

    private fun rebuildTree() {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()

        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            if (muteRepo?.isBlocked(event.pubkey) == true) continue
            var parentId = Nip10.getReplyTarget(event) ?: rootId
            if (parentId != rootId && parentId !in threadEvents) {
                parentId = rootId
            }
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
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
