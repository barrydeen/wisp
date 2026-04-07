package com.antz.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antz.app.nostr.ClientMessage
import com.antz.app.nostr.Filter
import com.antz.app.nostr.Nip10
import com.antz.app.nostr.Nip57
import com.antz.app.nostr.NostrEvent
import com.antz.app.relay.OutboxRouter
import com.antz.app.relay.RelayPool
import com.antz.app.relay.SubscriptionManager
import com.antz.app.repo.EventRepository
import com.antz.app.repo.MetadataFetcher
import com.antz.app.repo.RelayHintStore
import com.antz.app.repo.RelayListRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ArticleViewModel : ViewModel() {
    private val _article = MutableStateFlow<NostrEvent?>(null)
    val article: StateFlow<NostrEvent?> = _article

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    private val _coverImage = MutableStateFlow<String?>(null)
    val coverImage: StateFlow<String?> = _coverImage

    private val _publishedAt = MutableStateFlow<Long?>(null)
    val publishedAt: StateFlow<Long?> = _publishedAt

    private val _hashtags = MutableStateFlow<List<String>>(emptyList())
    val hashtags: StateFlow<List<String>> = _hashtags

    // Comments
    private val _comments = MutableStateFlow<List<Pair<NostrEvent, Int>>>(emptyList())
    val comments: StateFlow<List<Pair<NostrEvent, Int>>> = _comments

    private val _isCommentsLoading = MutableStateFlow(false)
    val isCommentsLoading: StateFlow<Boolean> = _isCommentsLoading

    private val commentEvents = mutableMapOf<String, NostrEvent>()
    private var collectorJob: Job? = null
    private var engagementCollectorJob: Job? = null
    private var rebuildJob: Job? = null
    private var loadJob: Job? = null
    private var metadataBatchJob: Job? = null
    private var relayPoolRef: RelayPool? = null
    private var topRelayUrls: List<String> = emptyList()
    private var relayListRepoRef: RelayListRepository? = null
    private var relayHintStoreRef: RelayHintStore? = null
    private val activeSubIds = mutableListOf<String>()

    // Incremental metadata tracking
    private val metadataSubscribedIds = mutableSetOf<String>()
    private val pendingMetadataIds = mutableSetOf<String>()
    private var metadataBatchIndex = 0

    fun loadArticle(kind: Int, author: String, dTag: String, eventRepo: EventRepository) {
        viewModelScope.launch {
            val cached = eventRepo.findAddressableEvent(kind, author, dTag)
            if (cached != null) {
                parseAndEmit(cached)
                return@launch
            }

            eventRepo.requestAddressableEvent(kind, author, dTag)

            // Poll for arrival with timeout
            var elapsed = 0L
            while (elapsed < 8000) {
                delay(200)
                elapsed += 200
                val event = eventRepo.findAddressableEvent(kind, author, dTag)
                if (event != null) {
                    parseAndEmit(event)
                    return@launch
                }
            }
            _isLoading.value = false
        }
    }

    fun loadComments(
        author: String,
        dTag: String,
        articleEventId: String?,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        outboxRouter: OutboxRouter,
        subManager: SubscriptionManager,
        metadataFetcher: MetadataFetcher,
        topRelayUrls: List<String>,
        relayListRepo: RelayListRepository? = null,
        relayHintStore: RelayHintStore? = null
    ) {
        this.relayPoolRef = relayPool
        this.topRelayUrls = topRelayUrls
        this.relayListRepoRef = relayListRepo
        this.relayHintStoreRef = relayHintStore
        _isCommentsLoading.value = true

        val coordinate = "30023:$author:$dTag"
        val commentSubId = "article-comments"
        activeSubIds.add(commentSubId)

        // Also subscribe via e-tag if we have the article event ID — many clients
        // reply with e-tags pointing to the article event, not a-tags
        val eTagSubId = "article-comments-etag"
        if (articleEventId != null) {
            activeSubIds.add(eTagSubId)
        }

        // Collect comment events from both subscriptions
        collectorJob?.cancel()
        collectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, relayUrl, subId) ->
                if (subId == commentSubId || subId == eTagSubId) {
                    if (event.kind != 1) return@collect
                    val isNew = event.id !in commentEvents
                    if (isNew) {
                        commentEvents[event.id] = event
                        eventRepo.cacheEvent(event)
                        eventRepo.addEventRelay(event.id, relayUrl)
                        if (eventRepo.getProfileData(event.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(event.pubkey)
                        }
                        // Track for incremental engagement subscriptions
                        synchronized(pendingMetadataIds) {
                            pendingMetadataIds.add(event.id)
                        }
                        scheduleRebuild(articleEventId)
                    }
                }
            }
        }

        // Collect engagement events (reactions, zaps, reposts)
        engagementCollectorJob?.cancel()
        engagementCollectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, _, subId) ->
                if (!subId.startsWith("article-engage")) return@collect
                when (event.kind) {
                    7, 6 -> eventRepo.addEvent(event)
                    9735 -> {
                        eventRepo.addEvent(event)
                        val zapperPubkey = Nip57.getZapperPubkey(event)
                        if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                            metadataFetcher.addToPendingProfiles(zapperPubkey)
                        }
                    }
                }
            }
        }

        // Two-phase loading matching ThreadViewModel pattern
        loadJob = viewModelScope.launch {
            // Phase 1a: Subscribe for comments via `a` tag on author's read relays + top relays
            val commentFilter = Filter(kinds = listOf(1), aTags = listOf(coordinate))
            outboxRouter.subscribeToUserReadRelays(commentSubId, author, commentFilter)
            val aTagMsg = ClientMessage.req(commentSubId, commentFilter)
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, aTagMsg)
            }

            // Phase 1b: Also subscribe via e-tag — many clients reply with e-tags
            if (articleEventId != null) {
                val eTagFilter = Filter(kinds = listOf(1), eTags = listOf(articleEventId))
                outboxRouter.subscribeToUserReadRelays(eTagSubId, author, eTagFilter)
                val eTagMsg = ClientMessage.req(eTagSubId, eTagFilter)
                for (url in topRelayUrls) {
                    relayPool.sendToRelayOrEphemeral(url, eTagMsg)
                }
            }

            // Wait for EOSE from both subscriptions
            subManager.awaitEoseWithTimeout(commentSubId, 5_000)
            if (articleEventId != null) {
                subManager.awaitEoseWithTimeout(eTagSubId, 3_000)
            }
            _isCommentsLoading.value = false
            metadataFetcher.sweepMissingProfiles()

            // Phase 2: Seed reply counts from loaded comments + subscribe engagement
            subscribeEngagement(relayPool, articleEventId, coordinate, author, eventRepo, subManager)

            // Start incremental engagement batching for late-arriving comments
            startMetadataBatching(relayPool, author)
        }
    }

    /**
     * Subscribe for engagement data (reactions, zaps, reposts) on the article event
     * itself and all comment events. Seeds reply counts upfront from loaded comments.
     * Awaits EOSE for article engagement to ensure reliable counts.
     */
    private suspend fun subscribeEngagement(
        relayPool: RelayPool,
        articleEventId: String?,
        coordinate: String,
        authorPubkey: String,
        eventRepo: EventRepository,
        subManager: SubscriptionManager
    ) {
        // Seed reply counts from already-loaded comments (before engagement subscriptions)
        for (event in commentEvents.values) {
            val parentId = Nip10.getReplyTarget(event) ?: articleEventId ?: continue
            eventRepo.addReplyCount(parentId, event.id)
        }

        // Collect all event IDs we need engagement for: article + comments
        val allEventIds = mutableListOf<String>()
        if (articleEventId != null) allEventIds.add(articleEventId)
        allEventIds.addAll(commentEvents.keys)
        if (allEventIds.isEmpty() && articleEventId == null) return

        // Track these as already subscribed for incremental batching
        metadataSubscribedIds.addAll(allEventIds)

        // Phase 1a: Article engagement via e-tag (high priority) — await EOSE
        if (articleEventId != null) {
            val eTagSubId = "article-engage"
            activeSubIds.add(eTagSubId)
            val eTagFilter = Filter(kinds = listOf(7, 6, 9735), eTags = listOf(articleEventId))
            sendToEngagementRelays(relayPool, eTagSubId, eTagFilter, authorPubkey)
            subManager.awaitEoseWithTimeout(eTagSubId, 3_500)
        }

        // Phase 1b: Article engagement via a-tag coordinate — many clients reference
        // addressable events by coordinate ("30023:<pubkey>:<dtag>") instead of event ID
        val aTagSubId = "article-engage-a"
        activeSubIds.add(aTagSubId)
        val aTagFilter = Filter(kinds = listOf(7, 6, 9735), aTags = listOf(coordinate))
        sendToEngagementRelays(relayPool, aTagSubId, aTagFilter, authorPubkey)
        subManager.awaitEoseWithTimeout(aTagSubId, 3_500)

        // Phase 2: Comment engagement (lower priority) — chunked, fire-and-forget
        val commentIds = commentEvents.keys.toList()
        if (commentIds.isNotEmpty()) {
            commentIds.chunked(50).forEachIndexed { index, batch ->
                val subId = "article-engage-${index + 1}"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, authorPubkey)
            }
        }
    }

    /**
     * Batch pending event IDs every 500ms into new engagement subscriptions.
     * Late-arriving comments get their engagement data fetched incrementally.
     */
    private fun startMetadataBatching(relayPool: RelayPool, authorPubkey: String) {
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
                val subId = "article-engage-b$metadataBatchIndex"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, authorPubkey)
            }
        }
    }

    /**
     * Send subscription to author's read relays + top scored relays.
     * Mirrors ThreadViewModel.sendToEngagementRelays().
     */
    private fun sendToEngagementRelays(
        relayPool: RelayPool, subId: String, filter: Filter, authorPubkey: String
    ) {
        val msg = ClientMessage.req(subId, filter)
        val sent = mutableSetOf<String>()
        for (url in getAuthorRelays(authorPubkey)) {
            if (relayPool.sendToRelayOrEphemeral(url, msg)) sent.add(url)
        }
        for (url in topRelayUrls) {
            if (url !in sent) relayPool.sendToRelayOrEphemeral(url, msg)
        }
    }

    private fun getAuthorRelays(pubkey: String): List<String> {
        val nip65 = relayListRepoRef?.getReadRelays(pubkey)
        if (!nip65.isNullOrEmpty()) return nip65
        val hints = relayHintStoreRef?.getHints(pubkey)
        if (!hints.isNullOrEmpty()) return hints.toList()
        return emptyList()
    }

    private fun scheduleRebuild(articleEventId: String?) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(100)
            rebuildTree(articleEventId)
        }
    }

    private fun rebuildTree(articleEventId: String?) {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()

        for (event in commentEvents.values) {
            val replyTarget = Nip10.getReplyTarget(event)
            val parentId = when {
                replyTarget != null && replyTarget in commentEvents -> replyTarget
                replyTarget != null && replyTarget == articleEventId -> "root"
                else -> "root"
            }
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
        }

        for (children in parentToChildren.values) {
            children.sortBy { it.created_at }
        }

        val result = mutableListOf<Pair<NostrEvent, Int>>()
        val visited = mutableSetOf<String>()

        val rootChildren = parentToChildren["root"] ?: emptyList()
        for (child in rootChildren) {
            if (child.id in visited) continue
            visited.add(child.id)
            result.add(child to 0)
            dfs(child.id, 1, parentToChildren, result, visited)
        }

        _comments.value = result
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

    private fun parseAndEmit(event: NostrEvent) {
        _article.value = event
        _title.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        _coverImage.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        _publishedAt.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull()
        _hashtags.value = event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
        _isLoading.value = false
    }

    override fun onCleared() {
        super.onCleared()
        collectorJob?.cancel()
        engagementCollectorJob?.cancel()
        rebuildJob?.cancel()
        loadJob?.cancel()
        metadataBatchJob?.cancel()
        relayPoolRef?.let { pool ->
            for (subId in activeSubIds) pool.closeOnAllRelays(subId)
        }
        activeSubIds.clear()
    }
}
