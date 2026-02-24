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
    // Inverse index: author -> set of scored relay URLs (up to MIN_REDUNDANCY)
    private var authorToRelays: MutableMap<String, MutableSet<String>> = mutableMapOf()
    // The set of follow pubkeys used to build the current scoreboard
    private var cachedFollowSet: Set<String> = emptySet()
    // Hint-based relay mappings for followed authors without kind 10002 relay lists.
    // Kept separate from authorToRelays so confirmed relay lists can overwrite them.
    private var hintAuthorRelays: MutableMap<String, MutableSet<String>> = mutableMapOf()

    companion object {
        private const val TAG = "RelayScoreBoard"
        private const val MIN_REDUNDANCY = 3

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
     * Recompute the persistent relay set: for each followed author, include up to
     * [MIN_REDUNDANCY] of their write relays so that a single relay going down
     * doesn't lose that author's notes.
     */
    fun recompute(excludeRelays: Set<String> = emptySet()) {
        val follows = contactRepo.getFollowList().map { it.pubkey }
        if (follows.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = mutableMapOf()
            authorToRelays = mutableMapOf()
            hintAuthorRelays = mutableMapOf()
            cachedFollowSet = emptySet()
            saveToPrefs()
            return
        }

        var knownCount = 0
        val newRelayAuthors = mutableMapOf<String, MutableSet<String>>()
        val newAuthorRelays = mutableMapOf<String, MutableSet<String>>()

        for (pubkey in follows) {
            val writeRelays = relayListRepo.getWriteRelays(pubkey) ?: continue
            knownCount++
            val eligible = writeRelays.filter { it !in excludeRelays }.take(MIN_REDUNDANCY)
            for (url in eligible) {
                newRelayAuthors.getOrPut(url) { mutableSetOf() }.add(pubkey)
                newAuthorRelays.getOrPut(pubkey) { mutableSetOf() }.add(url)
            }
        }

        Log.d(TAG, "recompute(): ${follows.size} follows, $knownCount have relay lists, " +
                "${follows.size - knownCount} missing, ${newRelayAuthors.size} unique relays" +
                if (excludeRelays.isNotEmpty()) ", ${excludeRelays.size} excluded" else "")

        if (newRelayAuthors.isEmpty()) {
            scoredRelays = emptyList()
            scoredRelayUrls = emptySet()
            relayAuthorsMap = mutableMapOf()
            authorToRelays = mutableMapOf()
            cachedFollowSet = follows.toSet()
            Log.d(TAG, "No relay lists known — aborting")
            saveToPrefs()
            return
        }

        relayAuthorsMap = newRelayAuthors
        authorToRelays = newAuthorRelays
        hintAuthorRelays = mutableMapOf()  // full rebuild from confirmed data
        cachedFollowSet = follows.toSet()
        rebuildScoredRelays()

        val coveredCount = newAuthorRelays.size
        Log.d(TAG, "Scored ${scoredRelays.size} relays covering $coveredCount/${follows.size} follows " +
                "(${follows.size - coveredCount} uncovered, $knownCount with relay lists)")

        saveToPrefs()
    }

    /**
     * Incrementally add a newly-followed author to the scoreboard.
     * Places them on up to [MIN_REDUNDANCY] of their write relays.
     */
    fun addAuthor(pubkey: String, excludeRelays: Set<String> = emptySet()) {
        if (pubkey in authorToRelays) return // already mapped
        cachedFollowSet = cachedFollowSet + pubkey

        val writeRelays = relayListRepo.getWriteRelays(pubkey)
        if (writeRelays == null) {
            Log.d(TAG, "addAuthor: no relay list for $pubkey, will fall back to sendToAll")
            saveToPrefs()
            return
        }

        // Confirmed relay list replaces any hint-based mappings
        val oldHints = hintAuthorRelays.remove(pubkey)
        if (oldHints != null) {
            for (url in oldHints) {
                relayAuthorsMap[url]?.remove(pubkey)
                if (relayAuthorsMap[url]?.isEmpty() == true) relayAuthorsMap.remove(url)
            }
        }

        val eligible = writeRelays.filter { it !in excludeRelays }.take(MIN_REDUNDANCY)
        if (eligible.isEmpty()) {
            Log.d(TAG, "addAuthor: all write relays for $pubkey are excluded")
            saveToPrefs()
            return
        }

        val relaySet = mutableSetOf<String>()
        for (url in eligible) {
            relayAuthorsMap.getOrPut(url) { mutableSetOf() }.add(pubkey)
            relaySet.add(url)
        }
        authorToRelays[pubkey] = relaySet
        rebuildScoredRelays()
        Log.d(TAG, "addAuthor: $pubkey → ${eligible.joinToString()} (${eligible.size} relays)")

        saveToPrefs()
    }

    /**
     * Incrementally remove an unfollowed author from the scoreboard.
     */
    fun removeAuthor(pubkey: String) {
        cachedFollowSet = cachedFollowSet - pubkey
        val relays = authorToRelays.remove(pubkey) ?: run {
            saveToPrefs()
            return
        }
        for (relay in relays) {
            relayAuthorsMap[relay]?.remove(pubkey)
            if (relayAuthorsMap[relay]?.isEmpty() == true) {
                relayAuthorsMap.remove(relay)
            }
        }
        rebuildScoredRelays()
        Log.d(TAG, "removeAuthor: $pubkey removed from ${relays.size} relays")
        saveToPrefs()
    }

    /**
     * Add hint-based relay mappings for a followed author who has no confirmed
     * kind 10002 relay list. Hints come from p-tag relay URLs and author provenance.
     * When a real relay list arrives via [addAuthor] or [recompute], it overwrites hints.
     */
    fun addHintRelays(pubkey: String, urls: List<String>) {
        if (pubkey !in cachedFollowSet) return  // only for followed authors
        if (pubkey in authorToRelays) return     // already has confirmed relay list
        val validUrls = urls.filter { it.startsWith("wss://") }.map { it.trimEnd('/') }
        if (validUrls.isEmpty()) return

        val hints = hintAuthorRelays.getOrPut(pubkey) { mutableSetOf() }
        var changed = false
        for (url in validUrls) {
            if (hints.size >= MIN_REDUNDANCY) break
            if (hints.add(url)) {
                relayAuthorsMap.getOrPut(url) { mutableSetOf() }.add(pubkey)
                changed = true
            }
        }
        if (changed) {
            rebuildScoredRelays()
            saveToPrefs()
            Log.d(TAG, "addHintRelays: $pubkey → ${hints.joinToString()} (${hints.size} hints)")
        }
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
     * An author may appear under multiple relay keys for redundancy.
     * Authors not covered by any scored relay are returned under the empty-string key
     * (caller should fall back to sendToAll for those).
     */
    fun getRelaysForAuthors(authors: List<String>): Map<String, List<String>> {
        if (scoredRelays.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()
        for (author in authors) {
            val relays = authorToRelays[author]
            if (relays.isNullOrEmpty()) {
                val hints = hintAuthorRelays[author]
                if (!hints.isNullOrEmpty()) {
                    for (relay in hints) {
                        result.getOrPut(relay) { mutableListOf() }.add(author)
                    }
                } else {
                    result.getOrPut("") { mutableListOf() }.add(author)
                }
            } else {
                for (relay in relays) {
                    result.getOrPut(relay) { mutableListOf() }.add(author)
                }
            }
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
        authorToRelays = mutableMapOf()
        hintAuthorRelays = mutableMapOf()
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
        // Hint-based relay mappings (separate from confirmed relay lists)
        if (hintAuthorRelays.isNotEmpty()) {
            val hintEntries = hintAuthorRelays.entries.joinToString("\n") { (pubkey, urls) ->
                "$pubkey\t${urls.joinToString(",")}"
            }
            editor.putString("hint_author_relay_map", hintEntries)
        } else {
            editor.remove("hint_author_relay_map")
        }
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
            val restoredInverse = mutableMapOf<String, MutableSet<String>>()
            for (line in mapStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val url = parts[0]
                val authors = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
                if (authors.isEmpty()) continue
                restoredMap[url] = authors
                for (author in authors) {
                    restoredInverse.getOrPut(author) { mutableSetOf() }.add(url)
                }
            }
            if (restoredMap.isNotEmpty()) {
                relayAuthorsMap = restoredMap
                authorToRelays = restoredInverse
                rebuildScoredRelays()
                Log.d(TAG, "Restored scoreboard from cache: ${scoredRelays.size} relays, ${authorToRelays.size} authors")
            }
        } else {
            // Legacy fallback: restore just URLs (will trigger recompute)
            val urls = prefs.getString("scored_urls", null) ?: return
            if (urls.isBlank()) return
            scoredRelayUrls = urls.split(",").toSet()
        }

        // Restore hint-based relay mappings
        val hintStr = prefs.getString("hint_author_relay_map", null)
        if (!hintStr.isNullOrBlank()) {
            val restoredHints = mutableMapOf<String, MutableSet<String>>()
            for (line in hintStr.split("\n")) {
                val parts = line.split("\t", limit = 2)
                if (parts.size != 2) continue
                val pubkey = parts[0]
                val urls = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
                if (urls.isEmpty()) continue
                // Only restore hints for followed authors without confirmed relay lists
                if (pubkey in cachedFollowSet && pubkey !in authorToRelays) {
                    restoredHints[pubkey] = urls
                    for (url in urls) {
                        relayAuthorsMap.getOrPut(url) { mutableSetOf() }.add(pubkey)
                    }
                }
            }
            if (restoredHints.isNotEmpty()) {
                hintAuthorRelays = restoredHints
                rebuildScoredRelays()
                Log.d(TAG, "Restored ${restoredHints.size} hint relay entries")
            }
        }
    }
}
