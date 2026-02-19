package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _users = MutableStateFlow<List<ProfileData>>(emptyList())
    val users: StateFlow<List<ProfileData>> = _users

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _lists = MutableStateFlow<List<FollowSet>>(emptyList())
    val lists: StateFlow<List<FollowSet>> = _lists

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private var searchJob: Job? = null
    private var relayPool: RelayPool? = null

    private val userSubId = "search-users"
    private val noteSubId = "search-notes"
    private val listSubId = "search-lists"

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }

    fun search(query: String, relayPool: RelayPool, eventRepo: EventRepository) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }

        searchJob?.cancel()
        this.relayPool = relayPool

        // Close previous subscriptions
        closeSubscriptions(relayPool)

        _query.value = trimmed
        _isSearching.value = true
        _users.value = emptyList()
        _notes.value = emptyList()
        _lists.value = emptyList()

        val userFilter = Filter(kinds = listOf(0), search = trimmed, limit = 20)
        val noteFilter = Filter(kinds = listOf(1), search = trimmed, limit = 50)
        val listFilter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), search = trimmed, limit = 20)

        val userReq = ClientMessage.req(userSubId, userFilter)
        val noteReq = ClientMessage.req(noteSubId, noteFilter)
        val listReq = ClientMessage.req(listSubId, listFilter)

        val searchRelays = keyRepo.getSearchRelays()

        if (searchRelays.isNotEmpty()) {
            for (url in searchRelays) {
                relayPool.sendToRelayOrEphemeral(url, userReq)
                relayPool.sendToRelayOrEphemeral(url, noteReq)
                relayPool.sendToRelayOrEphemeral(url, listReq)
            }
        } else {
            relayPool.sendToAll(userReq)
            relayPool.sendToAll(noteReq)
            relayPool.sendToAll(listReq)
        }

        val seenUserPubkeys = mutableSetOf<String>()
        val seenNoteIds = mutableSetOf<String>()
        val seenListKeys = mutableSetOf<String>()
        var userEose = false
        var noteEose = false
        var listEose = false

        searchJob = viewModelScope.launch {
            // Collect events
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    when (relayEvent.subscriptionId) {
                        userSubId -> {
                            val event = relayEvent.event
                            if (event.kind == 0 && event.pubkey !in seenUserPubkeys) {
                                seenUserPubkeys.add(event.pubkey)
                                eventRepo.cacheEvent(event)
                                val profile = ProfileData.fromEvent(event)
                                if (profile != null) {
                                    _users.value = _users.value + profile
                                }
                            }
                        }
                        noteSubId -> {
                            val event = relayEvent.event
                            if (event.kind == 1 && event.id !in seenNoteIds) {
                                seenNoteIds.add(event.id)
                                _notes.value = _notes.value + event
                                eventRepo.cacheEvent(event)
                            }
                        }
                        listSubId -> {
                            val event = relayEvent.event
                            val key = "${event.pubkey}:${event.id}"
                            if (event.kind == Nip51.KIND_FOLLOW_SET && key !in seenListKeys) {
                                seenListKeys.add(key)
                                val followSet = Nip51.parseFollowSet(event)
                                if (followSet != null) {
                                    _lists.value = _lists.value + followSet
                                }
                            }
                        }
                    }
                }
            }

            // Collect EOSE signals
            val eoseJob = launch {
                relayPool.eoseSignals.collect { subId ->
                    when (subId) {
                        userSubId -> userEose = true
                        noteSubId -> noteEose = true
                        listSubId -> listEose = true
                    }
                    if (userEose && noteEose && listEose) {
                        _isSearching.value = false
                    }
                }
            }

            // Timeout after 5 seconds
            delay(5000)
            _isSearching.value = false
            closeSubscriptions(relayPool)
            eventJob.cancel()
            eoseJob.cancel()
        }
    }

    fun clear() {
        searchJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _query.value = ""
        _users.value = emptyList()
        _notes.value = emptyList()
        _lists.value = emptyList()
        _isSearching.value = false
    }

    private fun closeSubscriptions(relayPool: RelayPool) {
        val closeUsers = ClientMessage.close(userSubId)
        val closeNotes = ClientMessage.close(noteSubId)
        val closeLists = ClientMessage.close(listSubId)
        relayPool.sendToAll(closeUsers)
        relayPool.sendToAll(closeNotes)
        relayPool.sendToAll(closeLists)
    }
}
