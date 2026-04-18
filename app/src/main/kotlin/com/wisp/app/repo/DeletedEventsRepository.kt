package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.NostrEvent

class DeletedEventsRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private var deletedIds = HashSet<String>()
    private var deletedAddresses = HashSet<String>()

    init {
        loadFromPrefs()
    }

    fun markDeleted(eventId: String) {
        if (deletedIds.add(eventId)) saveIdsToPrefs()
    }

    fun isDeleted(eventId: String): Boolean = deletedIds.contains(eventId)

    /** Mark an addressable event coordinate as deleted. Coord format: "kind:pubkey:dTag". */
    fun markDeletedAddress(coord: String) {
        if (deletedAddresses.add(coord)) saveAddressesToPrefs()
    }

    fun markDeletedAddress(kind: Int, pubkey: String, dTag: String) =
        markDeletedAddress(addressCoord(kind, pubkey, dTag))

    fun isAddressDeleted(coord: String): Boolean = deletedAddresses.contains(coord)

    fun isAddressDeleted(kind: Int, pubkey: String, dTag: String): Boolean =
        deletedAddresses.contains(addressCoord(kind, pubkey, dTag))

    /** Check whether an event should be treated as deleted. For addressable events (30000-39999),
     *  derive the coord from the event's own d-tag. */
    fun isEventDeleted(event: NostrEvent): Boolean {
        if (deletedIds.contains(event.id)) return true
        if (event.kind in 30000..39999) {
            val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            return deletedAddresses.contains(addressCoord(event.kind, event.pubkey, dTag))
        }
        return false
    }

    fun clear() {
        deletedIds = HashSet()
        deletedAddresses = HashSet()
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveIdsToPrefs() {
        prefs.edit()
            .putStringSet("deleted_event_ids", deletedIds.toSet())
            .apply()
    }

    private fun saveAddressesToPrefs() {
        prefs.edit()
            .putStringSet("deleted_event_addresses", deletedAddresses.toSet())
            .apply()
    }

    private fun loadFromPrefs() {
        prefs.getStringSet("deleted_event_ids", null)?.let { deletedIds = HashSet(it) }
        prefs.getStringSet("deleted_event_addresses", null)?.let { deletedAddresses = HashSet(it) }
    }

    companion object {
        fun addressCoord(kind: Int, pubkey: String, dTag: String): String = "$kind:$pubkey:$dTag"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_deleted_events_$pubkeyHex" else "wisp_deleted_events"
    }
}
