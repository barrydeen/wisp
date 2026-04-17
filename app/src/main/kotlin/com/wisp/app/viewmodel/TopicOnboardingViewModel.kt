package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI state for the onboarding topics step. Loads curated "trending" and
 * "all" hashtag sets (kind 30015) from feeds.nostrarchives.com and tracks
 * the user's selections. Publishing is delegated to the existing
 * FeedViewModel.followHashtag / createInterestSet APIs once the user
 * continues — this class only owns the pick-list UI state.
 */
class TopicOnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _popularTopics = MutableStateFlow<List<String>>(emptyList())
    val popularTopics: StateFlow<List<String>> = _popularTopics

    private val _loadingPopular = MutableStateFlow(true)
    val loadingPopular: StateFlow<Boolean> = _loadingPopular

    private var allTopics: List<String> = emptyList()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _selectedTopics = MutableStateFlow<Set<String>>(emptySet())
    val selectedTopics: StateFlow<Set<String>> = _selectedTopics

    private var loadJob: Job? = null

    fun load(relayPool: RelayPool) {
        if (loadJob != null) return
        loadJob = viewModelScope.launch {
            launch { loadTopicsFor(relayPool, TRENDING_RELAY, "trending", "topics-trending") }
            launch { loadTopicsFor(relayPool, ALL_RELAY, "all", "topics-all") }
        }
    }

    private suspend fun loadTopicsFor(
        relayPool: RelayPool,
        relayUrl: String,
        dTag: String,
        subId: String
    ) {
        try {
            val filter = Filter(kinds = listOf(Nip51.KIND_INTEREST_SET), dTags = listOf(dTag))
            relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(subId, filter))

            var newest: NostrEvent? = null
            collectUntilEose(relayPool, subId, 1, 6_000) { event ->
                if (event.kind != Nip51.KIND_INTEREST_SET) return@collectUntilEose
                val curr = newest
                if (curr == null || event.created_at > curr.created_at) newest = event
            }

            val tags = newest?.let { Nip51.parseInterestSet(it)?.hashtags?.toList() } ?: emptyList()
            when (dTag) {
                "trending" -> {
                    _popularTopics.value = tags
                    _loadingPopular.value = false
                }
                "all" -> {
                    allTopics = tags
                    filterSuggestions(_query.value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadTopicsFor($dTag) failed: ${e.message}")
            if (dTag == "trending") _loadingPopular.value = false
        }
    }

    private suspend fun collectUntilEose(
        relayPool: RelayPool,
        subId: String,
        expectedEose: Int,
        timeoutMs: Long,
        onEvent: (NostrEvent) -> Unit
    ) {
        val done = CompletableDeferred<Unit>()
        var eoseCount = 0

        val collectJob = viewModelScope.launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId) onEvent(relayEvent.event)
            }
        }
        val eoseJob = viewModelScope.launch {
            relayPool.eoseSignals.collect { id ->
                if (id == subId) {
                    eoseCount++
                    if (eoseCount >= expectedEose) done.complete(Unit)
                }
            }
        }

        withTimeoutOrNull(timeoutMs) { done.await() }
        collectJob.cancel()
        eoseJob.cancel()
        relayPool.closeOnAllRelays(subId)
    }

    fun updateQuery(q: String) {
        _query.value = q
        filterSuggestions(q)
    }

    private fun filterSuggestions(q: String) {
        val trimmed = q.trim().lowercase().removePrefix("#")
        if (trimmed.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        val selected = _selectedTopics.value
        _suggestions.value = allTopics.asSequence()
            .filter { it.contains(trimmed) && it !in selected }
            .sortedWith(compareBy({ !it.startsWith(trimmed) }, { it.length }, { it }))
            .take(20)
            .toList()
    }

    fun toggleTopic(topic: String) {
        val clean = topic.trim().lowercase().removePrefix("#")
        if (clean.isEmpty()) return
        val current = _selectedTopics.value
        _selectedTopics.value = if (clean in current) current - clean else current + clean
        filterSuggestions(_query.value)
    }

    fun addCustomTopic() {
        val clean = _query.value.trim().lowercase().removePrefix("#")
        if (clean.isEmpty()) return
        _selectedTopics.value = _selectedTopics.value + clean
        _query.value = ""
        _suggestions.value = emptyList()
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        _popularTopics.value = emptyList()
        _loadingPopular.value = true
        allTopics = emptyList()
        _query.value = ""
        _suggestions.value = emptyList()
        _selectedTopics.value = emptySet()
    }

    companion object {
        private const val TAG = "TopicOnboarding"
        private const val TRENDING_RELAY = "wss://feeds.nostrarchives.com/hashtags/trending"
        private const val ALL_RELAY = "wss://feeds.nostrarchives.com/hashtags/all"
    }
}
