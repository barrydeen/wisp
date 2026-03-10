package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.relay.ConsoleLogEntry
import com.wisp.app.relay.ConsoleLogType
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.RelayInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class RelayType { PERSISTENT, DM, EPHEMERAL }
enum class HealthSortMode { STATUS, FAILURES, EVENTS, NAME }
enum class HealthFilter { ALL, BAD_ONLY, ERRORS_ONLY }

data class AuthorSummary(
    val pubkey: String,
    val displayName: String?,
    val picture: String?
)

data class RelayHealthSummary(
    val url: String,
    val isConnected: Boolean,
    val isBad: Boolean,
    val badReason: String?,
    val cooldownRemaining: Int,
    val cooldownReason: String?,
    val stats: RelayHealthTracker.RelayStats?,
    val recentErrors: Int,
    val sessionHistory: List<RelayHealthTracker.SessionSummary>,
    val activeSession: RelayHealthTracker.ActiveSessionInfo?,
    val relayType: RelayType,
    val iconUrl: String? = null,
    val relayName: String? = null,
    val operatorPubkey: String? = null,
    val operatorName: String? = null,
    val operatorPicture: String? = null,
    val inboxAuthors: List<AuthorSummary> = emptyList(),
    val inboxAuthorCount: Int = 0
)

data class RelayHealthState(
    val relays: List<RelayHealthSummary> = emptyList(),
    val sortMode: HealthSortMode = HealthSortMode.STATUS,
    val filter: HealthFilter = HealthFilter.ALL,
    val totalConnected: Int = 0,
    val totalRelays: Int = 0,
    val totalBad: Int = 0
)

class RelayHealthViewModel : ViewModel() {
    private var relayPool: RelayPool? = null
    private var healthTracker: RelayHealthTracker? = null
    private var relayInfoRepo: RelayInfoRepository? = null
    private var eventRepo: EventRepository? = null
    private var scoreBoard: RelayScoreBoard? = null

    private val _state = MutableStateFlow(RelayHealthState())
    val state: StateFlow<RelayHealthState> = _state

    fun init(
        relayPool: RelayPool,
        healthTracker: RelayHealthTracker,
        relayInfoRepo: RelayInfoRepository,
        eventRepo: EventRepository,
        scoreBoard: RelayScoreBoard
    ) {
        if (this.relayPool != null) return
        this.relayPool = relayPool
        this.healthTracker = healthTracker
        this.relayInfoRepo = relayInfoRepo
        this.eventRepo = eventRepo
        this.scoreBoard = scoreBoard

        // Prefetch NIP-11 info for all relays so icons load
        viewModelScope.launch(Dispatchers.IO) {
            relayInfoRepo.prefetchAll(relayPool.getAllRelayUrls())
        }

        viewModelScope.launch {
            while (true) {
                refresh()
                delay(2000)
            }
        }
    }

    fun setSortMode(mode: HealthSortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        refresh()
    }

    fun setFilter(filter: HealthFilter) {
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun clearBadRelay(url: String) {
        healthTracker?.clearBadRelay(url)
        refresh()
    }

    private fun refresh() {
        val pool = relayPool ?: return
        val tracker = healthTracker ?: return
        val infoRepo = relayInfoRepo
        val evRepo = eventRepo
        val consoleLog = pool.consoleLog.value

        val persistentUrls = pool.getRelayUrls().toSet()
        val dmUrls = pool.getDmRelayUrls().toSet()
        val ephemeralUrls = pool.getEphemeralRelayUrls().toSet()
        val trackedUrls = tracker.getAllTrackedUrls()

        val allUrls = persistentUrls + dmUrls + ephemeralUrls + trackedUrls

        val errorCounts = countErrors(consoleLog)
        val badRelays = tracker.getBadRelays()
        val cooldownReasons = extractCooldownReasons(consoleLog)

        // Build relay → authors map from scoreboard
        val scoredRelays = scoreBoard?.getScoredRelays() ?: emptyList()
        val relayAuthors = mutableMapOf<String, Set<String>>()
        val relayAuthorCounts = mutableMapOf<String, Int>()
        for (sr in scoredRelays) {
            relayAuthors[sr.url] = sr.authors
            relayAuthorCounts[sr.url] = sr.coverCount
        }

        val summaries = allUrls.map { url ->
            val relayType = when {
                url in persistentUrls -> RelayType.PERSISTENT
                url in dmUrls -> RelayType.DM
                else -> RelayType.EPHEMERAL
            }
            val info = infoRepo?.getInfo(url)
            val operatorPubkey = info?.pubkey
            val operatorProfile = if (operatorPubkey != null) evRepo?.getProfileData(operatorPubkey) else null

            // Get top inbox authors (show up to 5 profile pictures)
            val authors = relayAuthors[url] ?: emptySet()
            val authorSummaries = authors.take(5).map { pubkey ->
                val profile = evRepo?.getProfileData(pubkey)
                AuthorSummary(
                    pubkey = pubkey,
                    displayName = profile?.displayString,
                    picture = profile?.picture
                )
            }

            RelayHealthSummary(
                url = url,
                isConnected = pool.isRelayConnected(url),
                isBad = url in badRelays,
                badReason = tracker.getBadRelayReason(url),
                cooldownRemaining = pool.getRelayCooldownRemaining(url),
                cooldownReason = cooldownReasons[url],
                stats = tracker.getStats(url),
                recentErrors = errorCounts[url] ?: 0,
                sessionHistory = tracker.getSessionHistory(url),
                activeSession = tracker.getActiveSession(url),
                relayType = relayType,
                iconUrl = infoRepo?.getIconUrl(url),
                relayName = info?.name,
                operatorPubkey = operatorPubkey,
                operatorName = operatorProfile?.displayString,
                operatorPicture = operatorProfile?.picture,
                inboxAuthors = authorSummaries,
                inboxAuthorCount = relayAuthorCounts[url] ?: 0
            )
        }

        val currentState = _state.value
        val filtered = when (currentState.filter) {
            HealthFilter.ALL -> summaries
            HealthFilter.BAD_ONLY -> summaries.filter { it.isBad }
            HealthFilter.ERRORS_ONLY -> summaries.filter { it.recentErrors > 0 || (it.stats?.totalFailures ?: 0) > 0 }
        }

        val sorted = when (currentState.sortMode) {
            HealthSortMode.STATUS -> filtered.sortedWith(
                compareByDescending<RelayHealthSummary> { it.isBad }
                    .thenByDescending { it.cooldownRemaining > 0 }
                    .thenByDescending { !it.isConnected }
                    .thenBy { it.url }
            )
            HealthSortMode.FAILURES -> filtered.sortedByDescending { it.stats?.totalFailures ?: 0 }
            HealthSortMode.EVENTS -> filtered.sortedByDescending { it.stats?.totalEventsReceived ?: 0 }
            HealthSortMode.NAME -> filtered.sortedBy { it.url }
        }

        _state.value = currentState.copy(
            relays = sorted,
            totalConnected = summaries.count { it.isConnected },
            totalRelays = summaries.size,
            totalBad = summaries.count { it.isBad }
        )
    }

    private fun countErrors(log: List<ConsoleLogEntry>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (entry in log) {
            if (entry.type == ConsoleLogType.CONN_FAILURE || entry.type == ConsoleLogType.OK_REJECTED) {
                counts[entry.relayUrl] = (counts[entry.relayUrl] ?: 0) + 1
            }
        }
        return counts
    }

    /** Extract the most recent failure/close message per relay as cooldown reason. */
    private fun extractCooldownReasons(log: List<ConsoleLogEntry>): Map<String, String> {
        val reasons = mutableMapOf<String, String>()
        // Walk newest-first to get most recent reason per relay
        for (entry in log.asReversed()) {
            if (entry.relayUrl in reasons) continue
            if (entry.type == ConsoleLogType.CONN_FAILURE || entry.type == ConsoleLogType.CONN_CLOSED) {
                reasons[entry.relayUrl] = entry.message
            }
        }
        return reasons
    }
}
