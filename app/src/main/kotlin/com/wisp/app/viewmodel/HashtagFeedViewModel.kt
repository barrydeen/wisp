package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private var loadJob: Job? = null
    private val activeSubIds = mutableListOf<String>()
    private var topRelayUrls: List<String> = emptyList()
    private var loadCounter = 0
    private var noteSub = "hashtag-notes-0"
    private var engagePrefix = "hashtag-engage-0"

    fun loadHashtag(
        tag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        topRelayUrls: List<String> = emptyList()
    ) {
        _setName.value = null
        loadTags(listOf(tag), tag, relayPool, eventRepo, topRelayUrls)
    }

    fun loadHashtags(
        tags: List<String>,
        name: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        topRelayUrls: List<String> = emptyList()
    ) {
        _setName.value = name
        loadTags(tags, tags.firstOrNull() ?: "", relayPool, eventRepo, topRelayUrls)
    }

    private fun loadTags(
        tags: List<String>,
        displayTag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        topRelayUrls: List<String> = emptyList()
    ) {
        if (tags.isEmpty()) return

        loadJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }

        _hashtag.value = displayTag
        _notes.value = emptyList()
        _isLoading.value = true
        relayPoolRef = relayPool
        eventRepoRef = eventRepo
        this.topRelayUrls = topRelayUrls

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
                        when (relayEvent.event.kind) {
                            7 -> eventRepo.addEvent(relayEvent.event)
                            9735 -> eventRepo.addEvent(relayEvent.event)
                            1 -> {
                                val parentId = Nip10.getReplyTarget(relayEvent.event)
                                if (parentId != null) eventRepo.addReplyCount(parentId, relayEvent.event.id)
                            }
                        }
                    }
                }
            }

            // Send REQs after collectors are active — use #t filter, not search query
            val noteFilter = Filter(kinds = listOf(1), tTags = tags, limit = 100)
            val noteReq = ClientMessage.req(currentNoteSub, noteFilter)
            activeSubIds.add(currentNoteSub)

            relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, noteReq)
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, noteReq)
            }

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
        val eventIds = _notes.value.map { it.id }.distinct()
        if (eventIds.isEmpty()) return

        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = if (index == 0) engagePrefix else "$engagePrefix-$index"
            activeSubIds.add(subId)
            val filters = listOf(
                Filter(kinds = listOf(7), eTags = batch),
                Filter(kinds = listOf(9735), eTags = batch),
                Filter(kinds = listOf(1), eTags = batch)
            )
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filters))
            }
            relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, ClientMessage.req(subId, filters))
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
        relayPoolRef?.let { closeAllSubs(it) }
    }
}
