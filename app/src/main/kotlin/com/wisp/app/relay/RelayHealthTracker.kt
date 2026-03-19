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
 *
 * Relays are marked bad only when they return a 5xx HTTP error — rate limiting
 * and mid-session disconnects are not penalised.
 */
class RelayHealthTracker(
    private val context: Context,
    pubkeyHex: String?
) {
    companion object {
        private const val TAG = "RelayHealthTracker"
        private const val MAX_SESSION_HISTORY = 10
        private const val MIN_SESSION_DURATION_MS = 30_000L
        private const val BAD_RELAY_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_relay_health_$pubkeyHex" else "wisp_relay_health"
    }

    // -- Data classes --

    private data class ActiveSession(
        var eventsReceived: Int = 0,
        var midSessionFailures: Int = 0,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class SessionRecord(
        val eventsReceived: Int,
        val hadMidSessionFailure: Boolean,
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
    /** URL → timestamp when marked bad. Entries expire after [BAD_RELAY_EXPIRY_MS]. */
    private val _badRelays = mutableMapOf<String, Long>()
    /** URL → human-readable reason why the relay was marked bad. */
    private val _badRelayReasons = mutableMapOf<String, String>()

    var onBadRelaysChanged: (() -> Unit)? = null

    init {
        loadFromPrefs()
    }

    // -- Session lifecycle --

    @Synchronized
    fun onRelayConnected(url: String) {
        activeSessions[url] = ActiveSession()
        val stats = getOrCreateStats(url)
        stats.totalConnections++
        stats.lastConnectedAt = System.currentTimeMillis()
        if (stats.firstSeenAt == 0L) stats.firstSeenAt = stats.lastConnectedAt
        Log.d(TAG, "Session started: $url")
    }

    @Synchronized
    fun onEventReceived(url: String, byteSize: Int) {
        activeSessions[url]?.eventsReceived?.let {
            activeSessions[url]!!.eventsReceived = it + 1
        }
        val stats = getOrCreateStats(url)
        stats.totalEventsReceived++
        stats.bytesReceived += byteSize
    }

    @Synchronized
    fun onEventSent(url: String, byteSize: Int) {
        val stats = getOrCreateStats(url)
        stats.totalEventsSent++
        stats.bytesSent += byteSize
    }

    @Synchronized
    fun onBytesReceived(url: String, size: Int) {
        getOrCreateStats(url).bytesReceived += size
    }

    @Synchronized
    fun onBytesSent(url: String, size: Int) {
        getOrCreateStats(url).bytesSent += size
    }

    @Synchronized
    fun onRateLimitHit(url: String) {
        // Track rate limits as a stat for display purposes only — not used for bad marking.
        getOrCreateStats(url).totalRateLimits++
        Log.d(TAG, "Rate limit hit: $url")
    }

    /**
     * Mark a relay bad immediately due to a 5xx server error.
     */
    @Synchronized
    fun onServerError(url: String, httpCode: Int) {
        if (url in _badRelays) return
        _badRelays[url] = System.currentTimeMillis()
        _badRelayReasons[url] = "Server error HTTP $httpCode"
        onBadRelaysChanged?.invoke()
        saveToPrefs()
        Log.w(TAG, "Relay marked BAD: $url (HTTP $httpCode)")
    }

    /**
     * Record all active sessions normally (app going to background after >=30s).
     * No failure penalty for the disconnect itself.
     */
    @Synchronized
    fun closeAllSessions() {
        val now = System.currentTimeMillis()
        for ((url, session) in activeSessions) {
            val duration = now - session.startedAt
            recordSession(url, session, duration, isMidSessionFailure = false)
            getOrCreateStats(url).totalConnectedMs += duration
        }
        activeSessions.clear()
        saveToPrefs()
        Log.d(TAG, "Closed all sessions (${sessionHistory.size} relays tracked)")
    }

    /**
     * Record a single relay disconnect as a failure (relay-side only, app is active).
     */
    @Synchronized
    fun closeSession(url: String) {
        val session = activeSessions.remove(url) ?: return
        val duration = System.currentTimeMillis() - session.startedAt
        getOrCreateStats(url).totalConnectedMs += duration
        if (duration < MIN_SESSION_DURATION_MS) {
            Log.d(TAG, "Session too short for failure tracking: $url (${duration / 1000}s)")
            return
        }
        getOrCreateStats(url).totalFailures++
        recordSession(url, session, duration, isMidSessionFailure = true)
        saveToPrefs()
        Log.d(TAG, "Session closed (failure): $url, events=${session.eventsReceived}, duration=${duration / 1000}s")
    }

    /**
     * Throw away all active sessions without recording (short app pause <30s).
     */
    @Synchronized
    fun discardAllSessions() {
        activeSessions.clear()
        Log.d(TAG, "Discarded all sessions (short pause)")
    }

    // -- Query --

    fun isBad(url: String): Boolean {
        val markedAt = _badRelays[url] ?: return false
        if (System.currentTimeMillis() - markedAt > BAD_RELAY_EXPIRY_MS) {
            _badRelays.remove(url)
            _badRelayReasons.remove(url)
            sessionHistory.remove(url)
            Log.d(TAG, "Bad relay expired, giving second chance: $url")
            return false
        }
        return true
    }

    fun getBadRelays(): Set<String> {
        val now = System.currentTimeMillis()
        val expired = _badRelays.filter { now - it.value > BAD_RELAY_EXPIRY_MS }.keys
        for (url in expired) {
            _badRelays.remove(url)
            _badRelayReasons.remove(url)
            sessionHistory.remove(url)
            Log.d(TAG, "Bad relay expired: $url")
        }
        return _badRelays.keys.toSet()
    }

    fun clearBadRelay(url: String) {
        if (_badRelays.remove(url) != null) {
            _badRelayReasons.remove(url)
            sessionHistory.remove(url)
            saveToPrefs()
            onBadRelaysChanged?.invoke()
            Log.d(TAG, "Cleared bad relay: $url")
        }
    }

    fun clearAllBadRelays() {
        if (_badRelays.isNotEmpty()) {
            val count = _badRelays.size
            _badRelays.clear()
            _badRelayReasons.clear()
            sessionHistory.clear()
            saveToPrefs()
            onBadRelaysChanged?.invoke()
            Log.d(TAG, "Cleared all $count bad relays")
        }
    }

    fun getStats(url: String): RelayStats? = lifetimeStats[url]

    fun getAllStats(): Map<String, RelayStats> = lifetimeStats.toMap()

    fun getBadRelayReason(url: String): String? = _badRelayReasons[url]

    // -- Session history exposure --

    data class SessionSummary(
        val eventsReceived: Int,
        val hadMidSessionFailure: Boolean,
        val durationMs: Long
    )

    data class ActiveSessionInfo(
        val eventsReceived: Int,
        val durationMs: Long
    )

    @Synchronized
    fun getSessionHistory(url: String): List<SessionSummary> {
        return sessionHistory[url]?.map {
            SessionSummary(it.eventsReceived, it.hadMidSessionFailure, it.durationMs)
        } ?: emptyList()
    }

    @Synchronized
    fun getActiveSession(url: String): ActiveSessionInfo? {
        val session = activeSessions[url] ?: return null
        return ActiveSessionInfo(
            eventsReceived = session.eventsReceived,
            durationMs = System.currentTimeMillis() - session.startedAt
        )
    }

    fun getAllTrackedUrls(): Set<String> = lifetimeStats.keys.toSet()

    // -- Account management --

    fun clear() {
        activeSessions.clear()
        sessionHistory.clear()
        lifetimeStats.clear()
        _badRelays.clear()
        _badRelayReasons.clear()
        prefs.edit().clear().apply()
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
            durationMs = durationMs
        )
        val history = sessionHistory.getOrPut(url) { mutableListOf() }
        history.add(record)
        if (history.size > MAX_SESSION_HISTORY) {
            history.removeAt(0)
        }
    }

    // -- Persistence --

    @Synchronized
    private fun saveToPrefs() {
        val editor = prefs.edit()

        // Bad relays with timestamps: "url\ttimestamp" per line
        val badRelayEntries = _badRelays.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        editor.putString("bad_relays_v2", badRelayEntries)
        editor.remove("bad_relays") // Remove old format

        // Session history: url -> "events,failure,duration;..."
        val historySnapshot = HashMap(sessionHistory)
        val historyEntries = historySnapshot.entries.joinToString("\n") { (url, records) ->
            val recordStr = records.toList().joinToString(";") { r ->
                "${r.eventsReceived},${if (r.hadMidSessionFailure) 1 else 0},${r.durationMs}"
            }
            "$url\t$recordStr"
        }
        editor.putString("session_history_v2", historyEntries)
        editor.remove("session_history") // Remove old format (had rate limit field)

        // Lifetime stats: url -> "evRcv,evSent,byRcv,bySent,conns,connMs,fails,rl,first,last"
        val statsSnapshot = HashMap(lifetimeStats)
        val statsEntries = statsSnapshot.entries.joinToString("\n") { (url, s) ->
            "$url\t${s.totalEventsReceived},${s.totalEventsSent},${s.bytesReceived},${s.bytesSent}," +
                    "${s.totalConnections},${s.totalConnectedMs},${s.totalFailures},${s.totalRateLimits}," +
                    "${s.firstSeenAt},${s.lastConnectedAt}"
        }
        editor.putString("lifetime_stats", statsEntries)

        editor.apply()
    }

    private fun loadFromPrefs() {
        // Bad relays with timestamps (v2 format: "url\ttimestamp" per line)
        val badRelaysV2 = prefs.getString("bad_relays_v2", null)
        val now = System.currentTimeMillis()
        if (!badRelaysV2.isNullOrBlank()) {
            for (line in badRelaysV2.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val url = parts[0]
                    val ts = parts[1].toLongOrNull() ?: now
                    if (now - ts <= BAD_RELAY_EXPIRY_MS) {
                        _badRelays[url] = ts
                    } else {
                        Log.d(TAG, "Skipping expired bad relay on load: $url")
                    }
                }
            }
        } else {
            prefs.getStringSet("bad_relays", null)?.let { oldSet ->
                if (oldSet.isNotEmpty()) {
                    Log.d(TAG, "Discarding ${oldSet.size} bad relays from old format (no timestamps)")
                    prefs.edit().remove("bad_relays").apply()
                }
            }
        }

        // Session history (v2: 3 fields per record — events, failure, duration)
        val historyStr = prefs.getString("session_history_v2", null)
        if (!historyStr.isNullOrBlank()) {
            for (line in historyStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val url = parts[0]
                val records = parts[1].split(";").mapNotNull { r ->
                    val fields = r.split(",")
                    if (fields.size != 3) return@mapNotNull null
                    try {
                        SessionRecord(
                            eventsReceived = fields[0].toInt(),
                            hadMidSessionFailure = fields[1] == "1",
                            durationMs = fields[2].toLong()
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
            Log.d(TAG, "Loaded ${_badRelays.size} bad relays (24h expiry), ${lifetimeStats.size} relay stats")
        }
    }
}
