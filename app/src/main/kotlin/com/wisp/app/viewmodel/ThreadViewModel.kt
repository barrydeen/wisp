package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
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

    private val threadEvents = mutableMapOf<String, NostrEvent>()
    private var rootId: String = ""

    fun loadThread(
        eventId: String,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        queueProfileFetch: (String) -> Unit,
        outboxRouter: OutboxRouter? = null
    ) {
        rootId = eventId

        // Load root from cache immediately
        val cached = eventRepo.getEvent(eventId)
        if (cached != null) {
            _rootEvent.value = cached
            threadEvents[cached.id] = cached
            rebuildTree()
        }

        viewModelScope.launch {
            // Start collecting BEFORE sending REQs to avoid race condition
            val collectJob = launch {
                relayPool.relayEvents.collect { (event, _, subscriptionId) ->
                    if (subscriptionId == "thread-root" || subscriptionId == "thread-replies") {
                        eventRepo.cacheEvent(event)
                        if (event.kind == 1) {
                            val isNew = event.id !in threadEvents
                            threadEvents[event.id] = event
                            if (subscriptionId == "thread-root" && event.id == eventId) {
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
            var rootRelayCount = relayPool.getRelayUrls().size.coerceAtLeast(1)
            if (cached == null) {
                relayPool.sendToAll(ClientMessage.req("thread-root", Filter(ids = listOf(eventId))))
            }

            // Route replies to OP's read relays if we know the author
            val repliesFilter = Filter(kinds = listOf(1), eTags = listOf(eventId))
            val rootAuthor = cached?.pubkey
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
                    var rootEoseCount = if (cached != null) rootRelayCount else 0
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

    private fun rebuildTree() {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()

        for (event in threadEvents.values) {
            if (event.id == rootId) continue
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
