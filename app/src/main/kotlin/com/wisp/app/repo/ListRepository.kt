package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ListRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val followSets = mutableMapOf<String, FollowSet>()

    private val _ownLists = MutableStateFlow<List<FollowSet>>(emptyList())
    val ownLists: StateFlow<List<FollowSet>> = _ownLists

    private val _selectedList = MutableStateFlow<FollowSet?>(null)
    val selectedList: StateFlow<FollowSet?> = _selectedList

    private var ownerPubkey: String? = null

    init {
        loadFromPrefs()
    }

    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnLists()
    }

    fun updateFromEvent(event: NostrEvent) {
        val followSet = Nip51.parseFollowSet(event) ?: return
        val key = "${event.pubkey}:${followSet.dTag}"
        val existing = followSets[key]
        if (existing != null && existing.createdAt >= followSet.createdAt) return
        followSets[key] = followSet
        refreshOwnLists()
        // Update selected list if it matches
        val sel = _selectedList.value
        if (sel != null && sel.pubkey == followSet.pubkey && sel.dTag == followSet.dTag) {
            _selectedList.value = followSet
        }
    }

    fun selectList(followSet: FollowSet?) {
        _selectedList.value = followSet
    }

    fun getListMembers(pubkey: String, dTag: String): Set<String> {
        return followSets["$pubkey:$dTag"]?.members ?: emptySet()
    }

    fun getList(pubkey: String, dTag: String): FollowSet? {
        return followSets["$pubkey:$dTag"]
    }

    /** Remove a list from local state (after sending deletion event to relays). */
    fun removeList(pubkey: String, dTag: String) {
        followSets.remove("$pubkey:$dTag")
        val sel = _selectedList.value
        if (sel != null && sel.pubkey == pubkey && sel.dTag == dTag) {
            _selectedList.value = null
        }
        refreshOwnLists()
    }

    fun getAllListsForUser(pubkey: String): List<FollowSet> {
        return followSets.values.filter { it.pubkey == pubkey }
    }

    fun clear() {
        followSets.clear()
        _ownLists.value = emptyList()
        _selectedList.value = null
        ownerPubkey = null
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnLists() {
        val owner = ownerPubkey ?: return
        val own = followSets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        _ownLists.value = own
        saveToPrefs(own)
    }

    private fun saveToPrefs(lists: List<FollowSet>) {
        val serializable = lists.map {
            SerializableFollowSet(it.pubkey, it.dTag, it.name, it.members.toList(), it.createdAt)
        }
        prefs.edit().putString("follow_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("follow_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableFollowSet>>(str)
            for (s in serializable) {
                val fs = FollowSet(s.pubkey, s.dTag, s.name, s.members.toSet(), s.createdAt)
                followSets["${s.pubkey}:${s.dTag}"] = fs
            }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableFollowSet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val members: List<String>,
        val createdAt: Long
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_lists_$pubkeyHex" else "wisp_lists"
    }
}
