package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.BookmarkSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BookmarkSetRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val bookmarkSets = mutableMapOf<String, BookmarkSet>()

    private val _ownSets = MutableStateFlow<List<BookmarkSet>>(emptyList())
    val ownSets: StateFlow<List<BookmarkSet>> = _ownSets

    private var ownerPubkey: String? = null

    init {
        loadFromPrefs()
    }

    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnSets()
    }

    fun updateFromEvent(event: NostrEvent) {
        val set = Nip51.parseBookmarkSet(event) ?: return
        val key = "${event.pubkey}:${set.dTag}"
        val existing = bookmarkSets[key]
        if (existing != null && existing.createdAt >= set.createdAt) return
        bookmarkSets[key] = set
        refreshOwnSets()
    }

    fun getSet(pubkey: String, dTag: String): BookmarkSet? {
        return bookmarkSets["$pubkey:$dTag"]
    }

    fun getAllSetsForUser(pubkey: String): List<BookmarkSet> {
        return bookmarkSets.values.filter { it.pubkey == pubkey }
    }

    fun clear() {
        bookmarkSets.clear()
        _ownSets.value = emptyList()
        ownerPubkey = null
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnSets() {
        val owner = ownerPubkey ?: return
        val own = bookmarkSets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        _ownSets.value = own
        saveToPrefs(own)
    }

    private fun saveToPrefs(sets: List<BookmarkSet>) {
        val serializable = sets.map {
            SerializableBookmarkSet(
                it.pubkey, it.dTag, it.name,
                it.eventIds.toList(), it.coordinates.toList(), it.hashtags.toList(),
                it.createdAt
            )
        }
        prefs.edit().putString("bookmark_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("bookmark_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableBookmarkSet>>(str)
            for (s in serializable) {
                val bs = BookmarkSet(
                    s.pubkey, s.dTag, s.name,
                    s.eventIds.toSet(), s.coordinates.toSet(), s.hashtags.toSet(),
                    s.createdAt
                )
                bookmarkSets["${s.pubkey}:${s.dTag}"] = bs
            }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableBookmarkSet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val eventIds: List<String>,
        val coordinates: List<String>,
        val hashtags: List<String>,
        val createdAt: Long
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_bookmark_sets_$pubkeyHex" else "wisp_bookmark_sets"
    }
}
