package com.wisp.app.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Tracks relay health via sessions and accumulates lifetime stats.
 *
 * A session starts when a relay connects while the app is active and ends either
 * when the app pauses (recorded normally) or when the relay disconnects mid-session
 * (counted as a failure). An `appIsActive` flag on RelayPool gates all tracking.
 */
class RelayHealthTracker(
    private val context: Context,
    pubkeyHex: String?
) {
    companion object {
        private const val TAG = "RelayHealthTracker"
        private const val MAX_SESSION_HISTORY = 10
        private const val MIN_SESSIONS_FOR_EVAL = 3
        private const val MIN_SESSION_DURATION_MS = 30_000L
        private const val BAD_ZERO_EVENT_SESSIONS = 5
        private const val BAD_DISCONNECT_SESSIONS = 4
        private const val BAD_RATE_LIMIT_SESSIONS = 3

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_relay_health_$pubkeyHex" else "wisp_relay_health"
    }

    // -- Data classes --

    private data class ActiveSession(
        var eventsReceived: Int = 0,
        var midSessionFailures: Int = 0,
        var rateLimitHits: Int = 0,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class SessionRecord(
        val eventsReceived: Int,
        val hadMidSessionFailure: Boolean,
        val hadRateLimit: Boolean,
        val durationMs: Long
    )

    data class RelayStats(
        var totalEventsReceived: Long = 0,
        var totalEventsSent: Long = 0,
        var bytesReceived: Long = 0,
        var bytesSent: Long = 0,
        var totalConnections: Int = 0,
        var totalConnectedMs: Long = 0,
        var totalFailures: Int = 0,
        var totalRateLimits: Int = 0,
        var firstSeenAt: Long = 0,
        var lastConnectedAt: Long = 0
    )

    // -- State --

    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val activeSessions = mutableMapOf<String, ActiveSession>()
    private val sessionHistory = mutableMapOf<String, MutableList<SessionRecord>>()
    private val lifetimeStats = mutableMapOf<String, RelayStats>()
    private val _badRelays = mutableSetOf<String>()

    var onBadRelaysChanged: (() -> Unit)? = null

    init {
        loadFromPrefs()
    }

    // -- Session lifecycle --

    fun onRelayConnected(url: String) {
        activeSessions[url] = ActiveSession()
        val stats = getOrCreateStats(url)
        stats.totalConnections++
        stats.lastConnectedAt = System.currentTimeMillis()
        if (stats.firstSeenAt == 0L) stats.firstSeenAt = stats.lastConnectedAt
        Log.d(TAG, "Session started: $url")
    }

    fun onEventReceived(url: String, byteSize: Int) {
        activeSessions[url]?.eventsReceived?.let {
            activeSessions[url]!!.eventsReceived = it + 1
        }
        val stats = getOrCreateStats(url)
        stats.totalEventsReceived++
        stats.bytesReceived += byteSize
    }

    fun onEventSent(url: String, byteSize: Int) {
        val stats = getOrCreateStats(url)
        stats.totalEventsSent++
        stats.bytesSent += byteSize
    }

    fun onBytesReceived(url: String, size: Int) {
        getOrCreateStats(url).bytesReceived += size
    }

    fun onBytesSent(url: String, size: Int) {
        getOrCreateStats(url).bytesSent += size
    }

    fun onRateLimitHit(url: String) {
        activeSessions[url]?.rateLimitHits?.let {
            activeSessions[url]!!.rateLimitHits = it + 1
        }
        getOrCreateStats(url).totalRateLimits++
        Log.d(TAG, "Rate limit hit: $url")
    }

    /**
     * Record all active sessions normally (app going to background after >=30s).
     * No failure penalty for the disconnect itself.
     */
    fun closeAllSessions() {
        val now = System.currentTimeMillis()
        for ((url, session) in activeSessions) {
            val duration = now - session.startedAt
            recordSession(url, session, duration, isMidSessionFailure = false)
            // Accumulate connected time
            getOrCreateStats(url).totalConnectedMs += duration
        }
        activeSessions.clear()
        evaluateAllRelays()
        saveToPrefs()
        Log.d(TAG, "Closed all sessions (${sessionHistory.size} relays tracked)")
    }

    /**
     * Record a single relay disconnect as a failure (relay-side only, app is active).
     */
    fun closeSession(url: String) {
        val session = activeSessions.remove(url) ?: return
        val duration = System.currentTimeMillis() - session.startedAt
        getOrCreateStats(url).totalConnectedMs += duration
        getOrCreateStats(url).totalFailures++
        recordSession(url, session, duration, isMidSessionFailure = true)
        evaluateRelay(url)
        saveToPrefs()
        Log.d(TAG, "Session closed (failure): $url, events=${session.eventsReceived}, duration=${duration / 1000}s")
    }

    /**
     * Throw away all active sessions without recording (short app pause <30s).
     */
    fun discardAllSessions() {
        activeSessions.clear()
        Log.d(TAG, "Discarded all sessions (short pause)")
    }

    // -- Query --

    fun isBad(url: String): Boolean = url in _badRelays

    fun getBadRelays(): Set<String> = _badRelays.toSet()

    fun clearBadRelay(url: String) {
        if (_badRelays.remove(url)) {
            sessionHistory.remove(url)
            saveToPrefs()
            onBadRelaysChanged?.invoke()
            Log.d(TAG, "Cleared bad relay: $url")
        }
    }

    fun getStats(url: String): RelayStats? = lifetimeStats[url]

    fun getAllStats(): Map<String, RelayStats> = lifetimeStats.toMap()

    // -- Account management --

    fun clear() {
        activeSessions.clear()
        sessionHistory.clear()
        lifetimeStats.clear()
        _badRelays.clear()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    // -- Internal --

    private fun getOrCreateStats(url: String): RelayStats =
        lifetimeStats.getOrPut(url) { RelayStats() }

    private fun recordSession(url: String, session: ActiveSession, durationMs: Long, isMidSessionFailure: Boolean) {
        val record = SessionRecord(
            eventsReceived = session.eventsReceived,
            hadMidSessionFailure = isMidSessionFailure,
            hadRateLimit = session.rateLimitHits > 0,
            durationMs = durationMs
        )
        val history = sessionHistory.getOrPut(url) { mutableListOf() }
        history.add(record)
        if (history.size > MAX_SESSION_HISTORY) {
            history.removeAt(0)
        }
    }

    private fun evaluateAllRelays() {
        val previousBad = _badRelays.toSet()
        for (url in sessionHistory.keys) {
            evaluateRelayInternal(url)
        }
        if (_badRelays != previousBad) {
            onBadRelaysChanged?.invoke()
        }
    }

    private fun evaluateRelay(url: String) {
        val previousBad = _badRelays.toSet()
        evaluateRelayInternal(url)
        if (_badRelays != previousBad) {
            onBadRelaysChanged?.invoke()
        }
    }

    private fun evaluateRelayInternal(url: String) {
        val history = sessionHistory[url] ?: return
        if (history.size < MIN_SESSIONS_FOR_EVAL) return

        val zeroEventSessions = history.count {
            it.eventsReceived == 0 && it.durationMs >= MIN_SESSION_DURATION_MS
        }
        val disconnectSessions = history.count { it.hadMidSessionFailure }
        val rateLimitSessions = history.count { it.hadRateLimit }

        val wasBad = url in _badRelays
        val isBadNow = zeroEventSessions >= BAD_ZERO_EVENT_SESSIONS ||
                disconnectSessions >= BAD_DISCONNECT_SESSIONS ||
                rateLimitSessions >= BAD_RATE_LIMIT_SESSIONS

        if (isBadNow && !wasBad) {
            _badRelays.add(url)
            Log.w(TAG, "Relay marked BAD: $url (zero=$zeroEventSessions, disconnects=$disconnectSessions, rateLimit=$rateLimitSessions)")
        }
    }

    // -- Persistence --

    private fun saveToPrefs() {
        val editor = prefs.edit()

        // Bad relays
        editor.putStringSet("bad_relays", _badRelays.toSet())

        // Session history: url -> "events,failure,ratelimit,duration;..."
        val historyEntries = sessionHistory.entries.joinToString("\n") { (url, records) ->
            val recordStr = records.joinToString(";") { r ->
                "${r.eventsReceived},${if (r.hadMidSessionFailure) 1 else 0},${if (r.hadRateLimit) 1 else 0},${r.durationMs}"
            }
            "$url\t$recordStr"
        }
        editor.putString("session_history", historyEntries)

        // Lifetime stats: url -> "evRcv,evSent,byRcv,bySent,conns,connMs,fails,rl,first,last"
        val statsEntries = lifetimeStats.entries.joinToString("\n") { (url, s) ->
            "$url\t${s.totalEventsReceived},${s.totalEventsSent},${s.bytesReceived},${s.bytesSent}," +
                    "${s.totalConnections},${s.totalConnectedMs},${s.totalFailures},${s.totalRateLimits}," +
                    "${s.firstSeenAt},${s.lastConnectedAt}"
        }
        editor.putString("lifetime_stats", statsEntries)

        editor.apply()
    }

    private fun loadFromPrefs() {
        // Bad relays
        prefs.getStringSet("bad_relays", null)?.let { _badRelays.addAll(it) }

        // Session history
        val historyStr = prefs.getString("session_history", null)
        if (!historyStr.isNullOrBlank()) {
            for (line in historyStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val url = parts[0]
                val records = parts[1].split(";").mapNotNull { r ->
                    val fields = r.split(",")
                    if (fields.size != 4) return@mapNotNull null
                    try {
                        SessionRecord(
                            eventsReceived = fields[0].toInt(),
                            hadMidSessionFailure = fields[1] == "1",
                            hadRateLimit = fields[2] == "1",
                            durationMs = fields[3].toLong()
                        )
                    } catch (_: NumberFormatException) { null }
                }
                if (records.isNotEmpty()) {
                    sessionHistory[url] = records.toMutableList()
                }
            }
        }

        // Lifetime stats
        val statsStr = prefs.getString("lifetime_stats", null)
        if (!statsStr.isNullOrBlank()) {
            for (line in statsStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val url = parts[0]
                val fields = parts[1].split(",")
                if (fields.size != 10) continue
                try {
                    lifetimeStats[url] = RelayStats(
                        totalEventsReceived = fields[0].toLong(),
                        totalEventsSent = fields[1].toLong(),
                        bytesReceived = fields[2].toLong(),
                        bytesSent = fields[3].toLong(),
                        totalConnections = fields[4].toInt(),
                        totalConnectedMs = fields[5].toLong(),
                        totalFailures = fields[6].toInt(),
                        totalRateLimits = fields[7].toInt(),
                        firstSeenAt = fields[8].toLong(),
                        lastConnectedAt = fields[9].toLong()
                    )
                } catch (_: NumberFormatException) {}
            }
        }

        if (_badRelays.isNotEmpty()) {
            Log.d(TAG, "Loaded ${_badRelays.size} bad relays, ${lifetimeStats.size} relay stats")
        }
    }
}
