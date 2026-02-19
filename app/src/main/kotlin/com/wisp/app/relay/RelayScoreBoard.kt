package com.wisp.app.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.RelayListRepository

data class ScoredRelay(val url: String, val coverCount: Int, val authors: Set<String>)

class RelayScoreBoard(
    private val context: Context,
    private val relayListRepo: RelayListRepository,
    private val contactRepo: ContactRepository,
    pubkeyHex: String? = null
) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private var scoredRelays: List<ScoredRelay> = emptyList()
    private var scoredRelayUrls: Set<String> = emptySet()
    // relay URL -> set of authors covered by that relay
    private var relayAuthorsMap: Map<String, Set<String>> = emptyMap()

    companion object {
        private const val TAG = "RelayScoreBoard"
        const val MAX_SCORED_RELAYS = 75

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_relay_scores_$pubkeyHex" else "wisp_relay_scores"
    }

    init {
        loadFromPrefs()
    }

    /**
     * Recompute the optimal relay set using greedy set-cover over followed users' write relays.
     */
    fun recompute() {
        val follows = contactRepo.getFollowList().map { it.pubkey }
        if (follows.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = emptyMap()
            return
        }

        // Build relay -> authors mapping from known relay lists
        val relayToAuthors = mutableMapOf<String, MutableSet<String>>()
        var knownCount = 0
        for (pubkey in follows) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey) ?: continue
            knownCount++
            for (url in writeRelays) {
                relayToAuthors.getOrPut(url) { mutableSetOf() }.add(pubkey)
            }
        }

        if (relayToAuthors.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = emptyMap()
            Log.d(TAG, "No relay lists known for $knownCount/${follows.size} follows")
            return
        }

        // Greedy set-cover: pick relay covering most uncovered follows, repeat
        val uncovered = follows.toMutableSet()
        val result = mutableListOf<ScoredRelay>()
        val remainingRelays = relayToAuthors.toMutableMap()

        while (uncovered.isNotEmpty() && result.size < MAX_SCORED_RELAYS && remainingRelays.isNotEmpty()) {
            // Find relay that covers the most uncovered authors
            var bestUrl: String? = null
            var bestCover: Set<String> = emptySet()
            for ((url, authors) in remainingRelays) {
                val cover = authors.intersect(uncovered)
                if (cover.size > bestCover.size) {
                    bestUrl = url
                    bestCover = cover
                }
            }
            if (bestUrl == null || bestCover.isEmpty()) break

            result.add(ScoredRelay(bestUrl, bestCover.size, bestCover))
            uncovered.removeAll(bestCover)
            remainingRelays.remove(bestUrl)
        }

        scoredRelays = result
        scoredRelayUrls = result.map { it.url }.toSet()
        relayAuthorsMap = result.associate { it.url to it.authors }

        Log.d(TAG, "Scored ${result.size} relays covering ${follows.size - uncovered.size}/${follows.size} follows " +
                "(${uncovered.size} uncovered, $knownCount with relay lists)")

        saveToPrefs()
    }

    /**
     * Returns relay→authors grouping constrained to scored relays only.
     * Authors not covered by any scored relay are returned under the empty-string key
     * (caller should fall back to sendToAll for those).
     */
    fun getRelaysForAuthors(authors: List<String>): Map<String, List<String>> {
        if (scoredRelays.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()
        val covered = mutableSetOf<String>()

        for ((url, relayAuthors) in relayAuthorsMap) {
            val overlap = authors.filter { it in relayAuthors }
            if (overlap.isNotEmpty()) {
                result[url] = overlap.toMutableList()
                covered.addAll(overlap)
            }
        }

        // Authors not in any scored relay → returned under "" key for fallback
        val uncovered = authors.filter { it !in covered }
        if (uncovered.isNotEmpty()) {
            result[""] = uncovered.toMutableList()
        }

        return result
    }

    fun getScoredRelays(): List<ScoredRelay> = scoredRelays

    fun getScoredRelayConfigs(): List<RelayConfig> =
        scoredRelays.map { RelayConfig(it.url, read = true, write = false) }

    fun hasScoredRelays(): Boolean = scoredRelays.isNotEmpty()

    fun clear() {
        scoredRelays = emptyList()
        scoredRelayUrls = emptySet()
        relayAuthorsMap = emptyMap()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        val urls = scoredRelays.joinToString(",") { it.url }
        prefs.edit().putString("scored_urls", urls).apply()
    }

    private fun loadFromPrefs() {
        val urls = prefs.getString("scored_urls", null) ?: return
        if (urls.isBlank()) return
        // Restore just URLs — full recompute will happen on next refresh
        scoredRelayUrls = urls.split(",").toSet()
    }
}
