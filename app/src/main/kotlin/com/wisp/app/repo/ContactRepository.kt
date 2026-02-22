package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class ContactRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _followList = MutableStateFlow<List<Nip02.FollowEntry>>(emptyList())
    val followList: StateFlow<List<Nip02.FollowEntry>> = _followList

    private var followSet = HashSet<String>()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != 3) return
        if (event.created_at <= lastUpdated) return
        val entries = Nip02.parseFollowList(event)
        _followList.value = entries
        followSet = HashSet(entries.map { it.pubkey })
        lastUpdated = event.created_at
        saveToPrefs(entries)
    }

    fun isFollowing(pubkey: String): Boolean = followSet.contains(pubkey)

    fun getFollowList(): List<Nip02.FollowEntry> = _followList.value

    fun clear() {
        _followList.value = emptyList()
        followSet = HashSet()
        lastUpdated = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs(entries: List<Nip02.FollowEntry>) {
        val serializable = entries.map { SerializableFollow(it.pubkey, it.relayHint, it.petname) }
        prefs.edit()
            .putString("follows", json.encodeToString(serializable))
            .putLong("follows_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("follows_updated", 0)
        val str = prefs.getString("follows", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableFollow>>(str)
            val entries = serializable.map { Nip02.FollowEntry(it.pubkey, it.relayHint, it.petname) }
            _followList.value = entries
            followSet = HashSet(entries.map { it.pubkey })
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableFollow(
        val pubkey: String,
        val relayHint: String? = null,
        val petname: String? = null
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_contacts_$pubkeyHex" else "wisp_contacts"
    }
}
