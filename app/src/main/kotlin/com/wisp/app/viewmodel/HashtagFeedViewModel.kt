package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MuteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HashtagFeedViewModel(app: Application) : AndroidViewModel(app) {
    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _hashtag = MutableStateFlow("")
    val hashtag: StateFlow<String> = _hashtag

    private val _setName = MutableStateFlow<String?>(null)
    val setName: StateFlow<String?> = _setName

    private var relayPoolRef: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var outboxRouterRef: OutboxRouter? = null
    private var relayScoreBoardRef: RelayScoreBoard? = null
    private var loadJob: Job? = null
    private var muteObserverJob: Job? = null
    private val activeSubIds = mutableListOf<String>()
    private var loadCounter = 0
    private var noteSub = "hashtag-notes-0"
    private var engagePrefix = "hashtag-engage-0"

    fun loadHashtag(
        tag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        _setName.value = null
        loadTags(listOf(tag), tag, relayPool, eventRepo, muteRepo, outboxRouter, relayScoreBoard)
    }

    fun loadHashtags(
        tags: List<String>,
        name: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        _setName.value = name
        loadTags(tags, tags.firstOrNull() ?: "", relayPool, eventRepo, muteRepo, outboxRouter, relayScoreBoard)
    }

    private fun loadTags(
        tags: List<String>,
        displayTag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        if (tags.isEmpty()) return

        loadJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }

        _hashtag.value = displayTag
        _notes.value = emptyList()
        _isLoading.value = true
        relayPoolRef = relayPool
        eventRepoRef = eventRepo
        outboxRouterRef = outboxRouter
        relayScoreBoardRef = relayScoreBoard

        muteObserverJob?.cancel()
        if (muteRepo != null) {
            muteObserverJob = muteRepo.blockedPubkeys
                .onEach { blocked ->
                    _notes.value = _notes.value.filter { it.pubkey !in blocked }
                }
                .launchIn(viewModelScope)
        }

        loadCounter++
        noteSub = "hashtag-notes-$loadCounter"
        engagePrefix = "hashtag-engage-$loadCounter"

        val currentNoteSub = noteSub

        val seenIds = mutableSetOf<String>()

        loadJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == currentNoteSub) {
                        val event = relayEvent.event
                        if (event.kind == 1 && event.id !in seenIds) {
                            if (muteRepo?.isBlocked(event.pubkey) == true) return@collect
                            if (muteRepo?.containsMutedWord(event.content) == true) return@collect
                            seenIds.add(event.id)
                            eventRepo.cacheEvent(event)
                            eventRepo.requestProfileIfMissing(event.pubkey)
                            val current = _notes.value.toMutableList()
                            current.add(event)
                            current.sortByDescending { it.created_at }
                            _notes.value = current
                        }
                    }
                    if (relayEvent.subscriptionId.startsWith(engagePrefix)) {
                        val e = relayEvent.event
                        when (e.kind) {
                            6, 7, 1018, 9735 -> eventRepo.addEvent(e)
                            1 -> {
                                val parentId = Nip10.getReplyTarget(e)
                                if (parentId != null) eventRepo.addReplyCount(parentId, e.id)
                            }
                            5 -> eventRepo.addEvent(e)
                        }
                    }
                }
            }

            // Send REQs after collectors are active — use #t filter, not search query
            val noteFilter = Filter(kinds = listOf(1), tTags = tags, limit = 100)
            val noteReq = ClientMessage.req(currentNoteSub, noteFilter)
            activeSubIds.add(currentNoteSub)

            relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, noteReq)

            // Wait for EOSE or timeout, then subscribe engagement
            withTimeoutOrNull(5_000) {
                relayPool.eoseSignals.first { it == currentNoteSub }
            }
            _isLoading.value = false

            // Fetch profiles for all note authors
            for (note in _notes.value) {
                eventRepo.requestProfileIfMissing(note.pubkey)
            }

            subscribeEngagement(relayPool, eventRepo)

            // Keep collecting for another 10s then clean up
            delay(10_000)
            closeAllSubs(relayPool)
            eventJob.cancel()
        }
    }

    private fun subscribeEngagement(relayPool: RelayPool, eventRepo: EventRepository) {
        val notes = _notes.value
        if (notes.isEmpty()) return

        val router = outboxRouterRef
        if (router != null) {
            val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
            for (note in notes) {
                eventsByAuthor.getOrPut(note.pubkey) { mutableListOf() }.add(note.id)
            }
            val safetyNet = relayScoreBoardRef?.getScoredRelays()?.take(5)?.map { it.url } ?: emptyList()
            router.subscribeEngagementByAuthors(engagePrefix, eventsByAuthor, activeSubIds, safetyNet)
        } else {
            // Fallback: query search relay directly
            val eventIds = notes.map { it.id }.distinct()
            eventIds.chunked(50).forEachIndexed { index, batch ->
                val subId = if (index == 0) engagePrefix else "$engagePrefix-$index"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(1, 5, 6, 7, 1018, 9735), eTags = batch, limit = 500)
                relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, ClientMessage.req(subId, filter))
            }
        }
    }

    private fun closeAllSubs(relayPool: RelayPool) {
        for (subId in activeSubIds) {
            relayPool.closeOnAllRelays(subId)
        }
        activeSubIds.clear()
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        muteObserverJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }
    }
}
