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
    private var relayAuthorsMap: MutableMap<String, MutableSet<String>> = mutableMapOf()
    // Inverse index: author -> primary scored relay URL (the first relay that covers them)
    private var authorToRelay: MutableMap<String, String> = mutableMapOf()
    // The set of follow pubkeys used to build the current scoreboard
    private var cachedFollowSet: Set<String> = emptySet()

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
     * Returns true if the current follow list differs from what was used to build the cache.
     * Callers should call [recompute] when this returns true.
     */
    fun needsRecompute(): Boolean {
        if (cachedFollowSet.isEmpty()) return true
        val currentFollows = contactRepo.getFollowList().map { it.pubkey }.toSet()
        return currentFollows != cachedFollowSet
    }

    /**
     * Recompute the optimal relay set using greedy set-cover over followed users' write relays.
     */
    fun recompute(excludeRelays: Set<String> = emptySet()) {
        val follows = contactRepo.getFollowList().map { it.pubkey }
        if (follows.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = mutableMapOf()
            authorToRelay = mutableMapOf()
            cachedFollowSet = emptySet()
            saveToPrefs()
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

        // Remove excluded (bad) relays before scoring
        for (url in excludeRelays) relayToAuthors.remove(url)

        Log.d(TAG, "recompute(): ${follows.size} follows, $knownCount have relay lists, " +
                "${follows.size - knownCount} missing, ${relayToAuthors.size} unique relays" +
                if (excludeRelays.isNotEmpty()) ", ${excludeRelays.size} excluded" else "")

        if (relayToAuthors.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = mutableMapOf()
            authorToRelay = mutableMapOf()
            cachedFollowSet = follows.toSet()
            Log.d(TAG, "No relay lists known — aborting")
            saveToPrefs()
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
        relayAuthorsMap = result.associate { it.url to it.authors.toMutableSet() }.toMutableMap()

        // Build inverse author → primary relay index (first relay covering each author in scored order)
        val inverseIndex = mutableMapOf<String, String>()
        for (scored in result) {
            for (author in scored.authors) {
                if (author !in inverseIndex) {
                    inverseIndex[author] = scored.url
                }
            }
        }
        authorToRelay = inverseIndex
        cachedFollowSet = follows.toSet()

        Log.d(TAG, "Scored ${result.size} relays covering ${follows.size - uncovered.size}/${follows.size} follows " +
                "(${uncovered.size} uncovered, $knownCount with relay lists)")

        saveToPrefs()
    }

    /**
     * Incrementally add a newly-followed author to the scoreboard.
     * Looks up their write relays and inserts them into an existing scored relay if possible,
     * otherwise they'll fall back to sendToAll routing.
     */
    fun addAuthor(pubkey: String, excludeRelays: Set<String> = emptySet()) {
        if (pubkey in authorToRelay) return // already mapped
        cachedFollowSet = cachedFollowSet + pubkey

        val writeRelays = relayListRepo.getWriteRelays(pubkey)
        if (writeRelays == null) {
            Log.d(TAG, "addAuthor: no relay list for $pubkey, will fall back to sendToAll")
            saveToPrefs()
            return
        }

        // Try to place on an existing scored relay (prefer the smallest to distribute load)
        var bestRelay: String? = null
        var bestSize = Int.MAX_VALUE
        for (url in writeRelays) {
            if (url in excludeRelays) continue
            val existing = relayAuthorsMap[url]
            if (existing != null && existing.size < bestSize) {
                bestRelay = url
                bestSize = existing.size
            }
        }

        if (bestRelay != null) {
            relayAuthorsMap[bestRelay]!!.add(pubkey)
            authorToRelay[pubkey] = bestRelay
            rebuildScoredRelays()
            Log.d(TAG, "addAuthor: $pubkey → $bestRelay (now ${relayAuthorsMap[bestRelay]!!.size} authors)")
        } else {
            // Their write relays aren't in our scored set — use the first non-excluded one
            val url = writeRelays.firstOrNull { it !in excludeRelays } ?: run {
                Log.d(TAG, "addAuthor: all write relays for $pubkey are excluded")
                saveToPrefs()
                return
            }
            relayAuthorsMap[url] = mutableSetOf(pubkey)
            authorToRelay[pubkey] = url
            rebuildScoredRelays()
            Log.d(TAG, "addAuthor: $pubkey → $url (new relay)")
        }

        saveToPrefs()
    }

    /**
     * Incrementally remove an unfollowed author from the scoreboard.
     */
    fun removeAuthor(pubkey: String) {
        cachedFollowSet = cachedFollowSet - pubkey
        val relay = authorToRelay.remove(pubkey) ?: run {
            saveToPrefs()
            return
        }
        relayAuthorsMap[relay]?.remove(pubkey)
        if (relayAuthorsMap[relay]?.isEmpty() == true) {
            relayAuthorsMap.remove(relay)
        }
        rebuildScoredRelays()
        Log.d(TAG, "removeAuthor: $pubkey removed from $relay")
        saveToPrefs()
    }

    /** Rebuild the scoredRelays list from the current relayAuthorsMap. */
    private fun rebuildScoredRelays() {
        scoredRelays = relayAuthorsMap.map { (url, authors) ->
            ScoredRelay(url, authors.size, authors.toSet())
        }.sortedByDescending { it.coverCount }
        scoredRelayUrls = scoredRelays.map { it.url }.toSet()
    }

    /**
     * Returns relay→authors grouping constrained to scored relays only.
     * Authors not covered by any scored relay are returned under the empty-string key
     * (caller should fall back to sendToAll for those).
     */
    fun getRelaysForAuthors(authors: List<String>): Map<String, List<String>> {
        if (scoredRelays.isEmpty()) return emptyMap()

        // O(n) group-by using the inverse author → relay index
        val result = mutableMapOf<String, MutableList<String>>()
        for (author in authors) {
            val relay = authorToRelay[author] ?: ""
            result.getOrPut(relay) { mutableListOf() }.add(author)
        }
        return result
    }

    fun getScoredRelays(): List<ScoredRelay> = scoredRelays

    /** Returns relay URL → number of followed authors that write to it. */
    fun getCoverageCounts(): Map<String, Int> = relayAuthorsMap.mapValues { it.value.size }

    fun getScoredRelayConfigs(): List<RelayConfig> =
        scoredRelays.map { RelayConfig(it.url, read = true, write = false) }

    fun hasScoredRelays(): Boolean = scoredRelays.isNotEmpty()

    fun clear() {
        scoredRelays = emptyList()
        scoredRelayUrls = emptySet()
        relayAuthorsMap = mutableMapOf()
        authorToRelay = mutableMapOf()
        cachedFollowSet = emptySet()
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        val editor = prefs.edit()
        // Scored relay URLs
        editor.putString("scored_urls", scoredRelays.joinToString(",") { it.url })
        // Author → relay mapping (compact: relay\tauthor1,author2,...)
        val mapEntries = relayAuthorsMap.entries.joinToString("\n") { (url, authors) ->
            "$url\t${authors.joinToString(",")}"
        }
        editor.putString("author_relay_map", mapEntries)
        // Follow set used to build this cache
        editor.putString("cached_follows", cachedFollowSet.joinToString(","))
        editor.apply()
    }

    private fun loadFromPrefs() {
        // Restore follow set
        val followsStr = prefs.getString("cached_follows", null)
        if (!followsStr.isNullOrBlank()) {
            cachedFollowSet = followsStr.split(",").toSet()
        }

        // Restore author → relay map
        val mapStr = prefs.getString("author_relay_map", null)
        if (!mapStr.isNullOrBlank()) {
            val restoredMap = mutableMapOf<String, MutableSet<String>>()
            val restoredInverse = mutableMapOf<String, String>()
            for (line in mapStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val url = parts[0]
                val authors = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
                if (authors.isEmpty()) continue
                restoredMap[url] = authors
                for (author in authors) {
                    if (author !in restoredInverse) {
                        restoredInverse[author] = url
                    }
                }
            }
            if (restoredMap.isNotEmpty()) {
                relayAuthorsMap = restoredMap
                authorToRelay = restoredInverse
                rebuildScoredRelays()
                Log.d(TAG, "Restored scoreboard from cache: ${scoredRelays.size} relays, ${authorToRelay.size} authors")
            }
        } else {
            // Legacy fallback: restore just URLs (will trigger recompute)
            val urls = prefs.getString("scored_urls", null) ?: return
            if (urls.isBlank()) return
            scoredRelayUrls = urls.split(",").toSet()
        }
    }
}
