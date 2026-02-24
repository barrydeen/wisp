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
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HashtagFeedViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _hashtag = MutableStateFlow("")
    val hashtag: StateFlow<String> = _hashtag

    private var relayPoolRef: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var loadJob: Job? = null
    private val activeSubIds = mutableListOf<String>()
    private var topRelayUrls: List<String> = emptyList()

    companion object {
        private const val NOTE_SUB = "hashtag-notes"
    }

    fun loadHashtag(
        tag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        topRelayUrls: List<String> = emptyList()
    ) {
        if (tag == _hashtag.value && _notes.value.isNotEmpty()) return

        loadJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }

        _hashtag.value = tag
        _notes.value = emptyList()
        _isLoading.value = true
        relayPoolRef = relayPool
        eventRepoRef = eventRepo
        this.topRelayUrls = topRelayUrls

        val noteFilter = Filter(kinds = listOf(1), tTags = listOf(tag), limit = 100)
        val noteReq = ClientMessage.req(NOTE_SUB, noteFilter)
        activeSubIds.add(NOTE_SUB)

        val searchRelays = keyRepo.getSearchRelays().ifEmpty { SearchViewModel.DEFAULT_SEARCH_RELAYS }
        for (url in searchRelays) {
            relayPool.sendToRelayOrEphemeral(url, noteReq)
        }
        // Also query top scored relays
        for (url in topRelayUrls) {
            relayPool.sendToRelayOrEphemeral(url, noteReq)
        }

        val seenIds = mutableSetOf<String>()

        loadJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == NOTE_SUB) {
                        val event = relayEvent.event
                        if (event.kind == 1 && event.id !in seenIds) {
                            seenIds.add(event.id)
                            eventRepo.cacheEvent(event)
                            val current = _notes.value.toMutableList()
                            current.add(event)
                            current.sortByDescending { it.created_at }
                            _notes.value = current
                        }
                    }
                    if (relayEvent.subscriptionId.startsWith("hashtag-engage")) {
                        when (relayEvent.event.kind) {
                            7 -> eventRepo.addEvent(relayEvent.event)
                            9735 -> eventRepo.addEvent(relayEvent.event)
                            1 -> {
                                val rootId = Nip10.getRootId(relayEvent.event)
                                    ?: Nip10.getReplyTarget(relayEvent.event)
                                if (rootId != null) eventRepo.addReplyCount(rootId, relayEvent.event.id)
                            }
                        }
                    }
                }
            }

            // Wait for EOSE or timeout, then subscribe engagement
            withTimeoutOrNull(5_000) {
                relayPool.eoseSignals.first { it == NOTE_SUB }
            }
            _isLoading.value = false
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
            val subId = if (index == 0) "hashtag-engage" else "hashtag-engage-$index"
            activeSubIds.add(subId)
            val filters = listOf(
                Filter(kinds = listOf(7), eTags = batch),
                Filter(kinds = listOf(9735), eTags = batch),
                Filter(kinds = listOf(1), eTags = batch)
            )
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filters))
            }
            // Also send to search relays
            val searchRelays = keyRepo.getSearchRelays().ifEmpty { SearchViewModel.DEFAULT_SEARCH_RELAYS }
            for (url in searchRelays) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filters))
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
        relayPoolRef?.let { closeAllSubs(it) }
    }
}
