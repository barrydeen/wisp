package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PinRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _pinnedIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds

    private var idSet = HashSet<String>()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_PIN_LIST) return
        if (event.created_at <= lastUpdated) return
        idSet = HashSet(Nip51.parsePinList(event))
        _pinnedIds.value = idSet.toSet()
        lastUpdated = event.created_at
        saveToPrefs()
    }

    fun pinEvent(eventId: String) {
        idSet.add(eventId)
        _pinnedIds.value = idSet.toSet()
        saveToPrefs()
    }

    fun unpinEvent(eventId: String) {
        idSet.remove(eventId)
        _pinnedIds.value = idSet.toSet()
        saveToPrefs()
    }

    fun isPinned(eventId: String): Boolean = idSet.contains(eventId)

    fun getPinnedIds(): Set<String> = idSet.toSet()

    fun clear() {
        _pinnedIds.value = emptySet()
        idSet = HashSet()
        lastUpdated = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("pinned_ids", idSet.toSet())
            .putLong("pin_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("pin_updated", 0)
        val ids = prefs.getStringSet("pinned_ids", null)
        if (ids != null) {
            idSet = HashSet(ids)
            _pinnedIds.value = idSet.toSet()
        }
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_pins_$pubkeyHex" else "wisp_pins"
    }
}
