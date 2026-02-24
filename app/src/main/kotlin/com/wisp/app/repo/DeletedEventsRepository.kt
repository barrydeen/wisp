package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences

class DeletedEventsRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private var deletedIds = HashSet<String>()

    init {
        loadFromPrefs()
    }

    fun markDeleted(eventId: String) {
        deletedIds.add(eventId)
        saveToPrefs()
    }

    fun isDeleted(eventId: String): Boolean = deletedIds.contains(eventId)

    fun clear() {
        deletedIds = HashSet()
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("deleted_event_ids", deletedIds.toSet())
            .apply()
    }

    private fun loadFromPrefs() {
        val ids = prefs.getStringSet("deleted_event_ids", null)
        if (ids != null) {
            deletedIds = HashSet(ids)
        }
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_deleted_events_$pubkeyHex" else "wisp_deleted_events"
    }
}
