package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import cooking.zap.app.nostr.NostrEvent

class DeletedEventsRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private var deletedIds = HashSet<String>()
    // coord -> the created_at of the newest deletion seen for it. Per NIP-09 a
    // deletion only voids events with created_at <= that time, so a newer
    // replaceable event for the same address legitimately revives it (e.g. a
    // re-create from the web or another device). Callers that want the old
    // permanent-tombstone behavior simply omit the timestamp (defaults to MAX).
    private var deletedAddresses = HashMap<String, Long>()

    init {
        loadFromPrefs()
    }

    fun markDeleted(eventId: String) {
        if (deletedIds.add(eventId)) saveIdsToPrefs()
    }

    fun isDeleted(eventId: String): Boolean = deletedIds.contains(eventId)

    /**
     * Mark an addressable event coordinate as deleted as of [deletedAt] (the
     * deletion event's created_at). Keeps the newest deletion time seen. Coord
     * format: "kind:pubkey:dTag". Omit [deletedAt] to tombstone permanently.
     */
    fun markDeletedAddress(coord: String, deletedAt: Long = Long.MAX_VALUE) {
        val prev = deletedAddresses[coord]
        if (prev == null || deletedAt > prev) {
            deletedAddresses[coord] = deletedAt
            saveAddressesToPrefs()
        }
    }

    fun markDeletedAddress(kind: Int, pubkey: String, dTag: String, deletedAt: Long = Long.MAX_VALUE) =
        markDeletedAddress(addressCoord(kind, pubkey, dTag), deletedAt)

    /** Lift a tombstone — used when an addressable event is deliberately
     *  (re)published locally for the same coordinate, superseding a prior delete. */
    fun unmarkDeletedAddress(coord: String) {
        if (deletedAddresses.remove(coord) != null) saveAddressesToPrefs()
    }

    fun unmarkDeletedAddress(kind: Int, pubkey: String, dTag: String) =
        unmarkDeletedAddress(addressCoord(kind, pubkey, dTag))

    fun isAddressDeleted(coord: String): Boolean = deletedAddresses.containsKey(coord)

    fun isAddressDeleted(kind: Int, pubkey: String, dTag: String): Boolean =
        deletedAddresses.containsKey(addressCoord(kind, pubkey, dTag))

    /**
     * The created_at of the newest deletion recorded for [coord], or null if the
     * coordinate has never been deleted. Compare an inbound event's created_at
     * against this: suppress only when `event.created_at <= deletionTime`.
     */
    fun deletionTimeForAddress(coord: String): Long? = deletedAddresses[coord]

    fun deletionTimeForAddress(kind: Int, pubkey: String, dTag: String): Long? =
        deletedAddresses[addressCoord(kind, pubkey, dTag)]

    /** Check whether an event should be treated as deleted. For addressable events (30000-39999),
     *  derive the coord from the event's own d-tag and honor the deletion timestamp. */
    fun isEventDeleted(event: NostrEvent): Boolean {
        if (deletedIds.contains(event.id)) return true
        if (event.kind in 30000..39999) {
            val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            val deletionTime = deletedAddresses[addressCoord(event.kind, event.pubkey, dTag)]
            return deletionTime != null && event.created_at <= deletionTime
        }
        return false
    }

    fun clear() {
        deletedIds = HashSet()
        deletedAddresses = HashMap()
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
        // Each entry is "<deletedAt>:<coord>". The timestamp is a decimal Long
        // (no ':'), so splitting on the first ':' unambiguously recovers the
        // coord even though the coord itself contains colons.
        prefs.edit()
            .putStringSet(
                "deleted_event_address_times",
                deletedAddresses.entries.map { "${it.value}:${it.key}" }.toSet(),
            )
            .apply()
    }

    private fun loadFromPrefs() {
        prefs.getStringSet("deleted_event_ids", null)?.let { deletedIds = HashSet(it) }
        val timed = prefs.getStringSet("deleted_event_address_times", null)
        if (timed != null) {
            val map = HashMap<String, Long>()
            for (entry in timed) {
                val sep = entry.indexOf(':')
                if (sep <= 0) continue
                val ts = entry.substring(0, sep).toLongOrNull() ?: continue
                map[entry.substring(sep + 1)] = ts
            }
            deletedAddresses = map
        } else {
            // Legacy migration: the old format was a bare coord set with no
            // timestamp — treat those as permanent tombstones (MAX).
            prefs.getStringSet("deleted_event_addresses", null)?.let { old ->
                deletedAddresses = HashMap(old.associateWith { Long.MAX_VALUE })
            }
        }
    }

    companion object {
        fun addressCoord(kind: Int, pubkey: String, dTag: String): String = "$kind:$pubkey:$dTag"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_deleted_events_$pubkeyHex" else "wisp_deleted_events"
    }
}
