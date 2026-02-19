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
import com.wisp.app.repo.MuteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    fun clearScrollTarget() {
        _scrollToIndex.value = -1
    }

    fun loadThread(
        eventId: String,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        queueProfileFetch: (String) -> Unit,
        outboxRouter: OutboxRouter? = null,
        muteRepo: MuteRepository? = null
    ) {
        this.muteRepo = muteRepo

        // Resolve the true root ID from the clicked event
        val cached = eventRepo.getEvent(eventId)
        if (cached != null) {
            val resolvedRoot = Nip10.getRootId(cached) ?: eventId
            rootId = resolvedRoot
            scrollTargetId = if (resolvedRoot != eventId) eventId else null
            threadEvents[cached.id] = cached

            // Load root from cache if we resolved to a different root
            if (resolvedRoot != eventId) {
                val cachedRoot = eventRepo.getEvent(resolvedRoot)
                if (cachedRoot != null) {
                    _rootEvent.value = cachedRoot
                    threadEvents[cachedRoot.id] = cachedRoot
                }
            } else {
                _rootEvent.value = cached
            }
            rebuildTree()
        } else {
            rootId = eventId
        }

        viewModelScope.launch {
            // If not cached, fetch the clicked event first to resolve the root
            if (cached == null) {
                val resolvedRootId = resolveRootFromRelay(eventId, eventRepo, relayPool, queueProfileFetch)
                rootId = resolvedRootId
                scrollTargetId = if (resolvedRootId != eventId) eventId else null
            }

            // Start collecting BEFORE sending REQs to avoid race condition
            val collectJob = launch {
                relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                    if (subscriptionId == "thread-root" || subscriptionId == "thread-replies") {
                        eventRepo.cacheEvent(event)
                        if (event.kind == 1) {
                            val isNew = event.id !in threadEvents
                            threadEvents[event.id] = event
                            if (subscriptionId == "thread-root" && event.id == rootId) {
                                _rootEvent.value = event
                            }
                            if (isNew) {
                                if (eventRepo.getProfileData(event.pubkey) == null) {
                                    queueProfileFetch(event.pubkey)
                                }
                                rebuildTree()
                            }
                        }
                    }
                }
            }

            // Now send subscriptions — collector is already active
            val rootRelayCount = relayPool.getRelayUrls().size.coerceAtLeast(1)
            val needsFetchRoot = _rootEvent.value == null || _rootEvent.value?.id != rootId
            if (needsFetchRoot) {
                relayPool.sendToAll(ClientMessage.req("thread-root", Filter(ids = listOf(rootId))))
            }

            // Fetch ALL replies that reference the root event
            val repliesFilter = Filter(kinds = listOf(1), eTags = listOf(rootId))
            val rootAuthor = _rootEvent.value?.pubkey ?: cached?.pubkey
            val repliesRelays = if (rootAuthor != null && outboxRouter != null) {
                outboxRouter.subscribeToUserReadRelays("thread-replies", rootAuthor, repliesFilter)
            } else {
                relayPool.sendToAll(ClientMessage.req("thread-replies", repliesFilter))
                relayPool.getRelayUrls().toSet()
            }
            val repliesRelayCount = repliesRelays.size.coerceAtLeast(1)

            // Wait for EOSE from ALL relays before closing each subscription (with timeout)
            launch {
                withTimeoutOrNull(20_000) {
                    var rootEoseCount = if (!needsFetchRoot) rootRelayCount else 0
                    var repliesEoseCount = 0
                    relayPool.eoseSignals.collect { subId ->
                        when (subId) {
                            "thread-root" -> {
                                rootEoseCount++
                                if (rootEoseCount >= rootRelayCount) {
                                    relayPool.closeOnAllRelays("thread-root")
                                }
                            }
                            "thread-replies" -> {
                                repliesEoseCount++
                                if (repliesEoseCount >= repliesRelayCount) {
                                    relayPool.closeOnAllRelays("thread-replies")
                                }
                            }
                        }
                        if (rootEoseCount >= rootRelayCount && repliesEoseCount >= repliesRelayCount) {
                            return@collect
                        }
                    }
                }
                // Unconditionally close both subs and finish loading
                relayPool.closeOnAllRelays("thread-root")
                relayPool.closeOnAllRelays("thread-replies")
                _isLoading.value = false
                collectJob.cancel()
            }
        }
    }

    /**
     * Fetch the clicked event from relays, resolve its root ID, and return it.
     * Returns as soon as the event is received — no need to wait for all relays.
     */
    private suspend fun resolveRootFromRelay(
        eventId: String,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        queueProfileFetch: (String) -> Unit
    ): String {
        val eventReceived = CompletableDeferred<NostrEvent>()

        val collectJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                if (subscriptionId == "thread-resolve" && event.id == eventId) {
                    eventRepo.cacheEvent(event)
                    threadEvents[event.id] = event
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        queueProfileFetch(event.pubkey)
                    }
                    eventReceived.complete(event)
                }
            }
        }

        relayPool.sendToAll(ClientMessage.req("thread-resolve", Filter(ids = listOf(eventId))))

        // Wait for the event itself, not EOSE — we only need one copy
        val fetched = withTimeoutOrNull(5_000) { eventReceived.await() }

        relayPool.closeOnAllRelays("thread-resolve")
        collectJob.cancel()

        return if (fetched != null) {
            Nip10.getRootId(fetched) ?: eventId
        } else {
            eventId
        }
    }

    private fun rebuildTree() {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()

        for (event in threadEvents.values) {
            if (event.id == rootId) continue
            if (muteRepo?.isBlocked(event.pubkey) == true) continue
            val parentId = Nip10.getReplyTarget(event) ?: rootId
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
        }

        // Sort children by created_at
        for (children in parentToChildren.values) {
            children.sortBy { it.created_at }
        }

        // DFS flatten — track visited to prevent cycles from causing duplicate keys
        val result = mutableListOf<Pair<NostrEvent, Int>>()
        val visited = mutableSetOf<String>()
        val root = threadEvents[rootId]
        if (root != null) {
            result.add(root to 0)
            visited.add(root.id)
            dfs(rootId, 1, parentToChildren, result, visited)
        }

        _flatThread.value = result

        // Update scroll target index
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
