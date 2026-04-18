package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.InterestSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InterestRepository(
    private val context: Context,
    private var pubkeyHex: String? = null,
    private val deletedEventsRepo: DeletedEventsRepository? = null
) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val interestSets = mutableMapOf<String, InterestSet>()

    private val _sets = MutableStateFlow<List<InterestSet>>(emptyList())
    val sets: StateFlow<List<InterestSet>> = _sets

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        val set = Nip51.parseInterestSet(event) ?: return
        val pk = pubkeyHex
        if (pk != null && deletedEventsRepo?.isAddressDeleted(Nip51.KIND_INTEREST_SET, pk, set.dTag) == true) {
            return
        }
        val existing = interestSets[set.dTag]
        if (existing != null && existing.createdAt >= set.createdAt) return
        interestSets[set.dTag] = set
        refreshSets()
    }

    fun getSet(dTag: String): InterestSet? = interestSets[dTag]

    fun getAllHashtags(): Set<String> =
        interestSets.values.flatMapTo(mutableSetOf()) { it.hashtags }

    fun isFollowing(hashtag: String): Boolean {
        val lower = hashtag.lowercase()
        return interestSets.values.any { lower in it.hashtags }
    }

    fun findSetsContaining(hashtag: String): List<InterestSet> {
        val lower = hashtag.lowercase()
        return interestSets.values.filter { lower in it.hashtags }
    }

    fun removeSet(dTag: String) {
        interestSets.remove(dTag)
        refreshSets()
    }

    fun clear() {
        interestSets.clear()
        _sets.value = emptyList()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        this.pubkeyHex = pubkeyHex
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshSets() {
        val sorted = interestSets.values.sortedBy { it.name }
        _sets.value = sorted
        saveToPrefs(sorted)
    }

    private fun saveToPrefs(sets: List<InterestSet>) {
        val serializable = sets.map {
            SerializableInterestSet(it.dTag, it.name, it.hashtags.toList(), it.createdAt)
        }
        prefs.edit().putString("interest_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("interest_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableInterestSet>>(str)
            val pk = pubkeyHex
            var dropped = false
            for (s in serializable) {
                if (pk != null && deletedEventsRepo?.isAddressDeleted(Nip51.KIND_INTEREST_SET, pk, s.dTag) == true) {
                    dropped = true
                    continue
                }
                interestSets[s.dTag] = InterestSet(s.dTag, s.name, s.hashtags.toSet(), s.createdAt)
            }
            _sets.value = interestSets.values.sortedBy { it.name }
            if (dropped) saveToPrefs(_sets.value)
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableInterestSet(
        val dTag: String,
        val name: String,
        val hashtags: List<String>,
        val createdAt: Long
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_interests_$pubkeyHex" else "wisp_interests"
    }
}
