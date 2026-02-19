package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BookmarkRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds

    private var idSet = HashSet<String>()
    private var coordinateSet = HashSet<String>()
    private var hashtagSet = HashSet<String>()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_BOOKMARK_LIST) return
        if (event.created_at <= lastUpdated) return
        val bookmarkList = Nip51.parseBookmarkList(event)
        idSet = HashSet(bookmarkList.eventIds)
        coordinateSet = HashSet(bookmarkList.coordinates)
        hashtagSet = HashSet(bookmarkList.hashtags)
        _bookmarkedIds.value = idSet.toSet()
        lastUpdated = event.created_at
        saveToPrefs()
    }

    fun addBookmark(eventId: String) {
        idSet.add(eventId)
        _bookmarkedIds.value = idSet.toSet()
        saveToPrefs()
    }

    fun removeBookmark(eventId: String) {
        idSet.remove(eventId)
        _bookmarkedIds.value = idSet.toSet()
        saveToPrefs()
    }

    fun isBookmarked(eventId: String): Boolean = idSet.contains(eventId)

    fun getBookmarkedIds(): Set<String> = idSet.toSet()
    fun getCoordinates(): Set<String> = coordinateSet.toSet()
    fun getHashtags(): Set<String> = hashtagSet.toSet()

    fun clear() {
        _bookmarkedIds.value = emptySet()
        idSet = HashSet()
        coordinateSet = HashSet()
        hashtagSet = HashSet()
        lastUpdated = 0
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("bookmark_ids", idSet.toSet())
            .putStringSet("bookmark_coords", coordinateSet.toSet())
            .putStringSet("bookmark_hashtags", hashtagSet.toSet())
            .putLong("bookmark_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("bookmark_updated", 0)
        val ids = prefs.getStringSet("bookmark_ids", null)
        if (ids != null) {
            idSet = HashSet(ids)
            _bookmarkedIds.value = idSet.toSet()
        }
        val coords = prefs.getStringSet("bookmark_coords", null)
        if (coords != null) coordinateSet = HashSet(coords)
        val tags = prefs.getStringSet("bookmark_hashtags", null)
        if (tags != null) hashtagSet = HashSet(tags)
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_bookmarks_$pubkeyHex" else "wisp_bookmarks"
    }
}
