package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelaySet
import com.wisp.app.relay.RelayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RelaySetRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val relaySets = mutableMapOf<String, RelaySet>()

    private val _ownRelaySets = MutableStateFlow<List<RelaySet>>(emptyList())
    val ownRelaySets: StateFlow<List<RelaySet>> = _ownRelaySets

    private val _favoriteRelays = MutableStateFlow<List<String>>(emptyList())
    val favoriteRelays: StateFlow<List<String>> = _favoriteRelays

    private var ownerPubkey: String? = null
    private var favoriteRelaysTimestamp: Long = 0

    init {
        loadFromPrefs()
    }

    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnSets()
    }

    fun updateRelaySetFromEvent(event: NostrEvent) {
        val set = Nip51.parseRelaySetNamed(event) ?: return
        val key = "${event.pubkey}:${set.dTag}"
        val existing = relaySets[key]
        if (existing != null && existing.createdAt >= set.createdAt) return
        relaySets[key] = set
        refreshOwnSets()
    }

    fun updateFavoriteRelaysFromEvent(event: NostrEvent) {
        if (event.created_at <= favoriteRelaysTimestamp) return
        favoriteRelaysTimestamp = event.created_at
        val urls = Nip51.parseRelaySet(event)
        _favoriteRelays.value = urls
        saveFavoritesToPrefs(urls)
    }

    fun getSet(pubkey: String, dTag: String): RelaySet? {
        return relaySets["$pubkey:$dTag"]
    }

    fun isFavorite(url: String): Boolean = url in _favoriteRelays.value

    fun addFavorite(url: String): List<String> {
        val current = _favoriteRelays.value.toMutableList()
        val normalized = url.trim().trimEnd('/')
        if (normalized !in current) current.add(normalized)
        _favoriteRelays.value = current
        saveFavoritesToPrefs(current)
        return current
    }

    fun removeFavorite(url: String): List<String> {
        val current = _favoriteRelays.value.toMutableList()
        current.remove(url.trim().trimEnd('/'))
        _favoriteRelays.value = current
        saveFavoritesToPrefs(current)
        return current
    }

    fun addRelayToSet(url: String, dTag: String): RelaySet? {
        val owner = ownerPubkey ?: return null
        val key = "$owner:$dTag"
        val existing = relaySets[key] ?: return null
        val normalized = url.trim().trimEnd('/')
        if (normalized in existing.relays) return existing
        val updated = existing.copy(relays = existing.relays + normalized)
        relaySets[key] = updated
        refreshOwnSets()
        return updated
    }

    fun removeRelayFromSet(url: String, dTag: String): RelaySet? {
        val owner = ownerPubkey ?: return null
        val key = "$owner:$dTag"
        val existing = relaySets[key] ?: return null
        val updated = existing.copy(relays = existing.relays - url.trim().trimEnd('/'))
        relaySets[key] = updated
        refreshOwnSets()
        return updated
    }

    fun createRelaySet(name: String, relays: Set<String>, dTag: String? = null): RelaySet? {
        val owner = ownerPubkey ?: return null
        val tag = dTag ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val set = RelaySet(
            pubkey = owner,
            dTag = tag,
            name = name,
            relays = relays.map { it.trim().trimEnd('/') }.filter { RelayConfig.isAcceptableUrl(it) }.toSet(),
            createdAt = System.currentTimeMillis() / 1000
        )
        relaySets["$owner:$tag"] = set
        refreshOwnSets()
        return set
    }

    fun removeRelaySet(dTag: String) {
        val owner = ownerPubkey ?: return
        relaySets.remove("$owner:$dTag")
        refreshOwnSets()
    }

    fun clear() {
        relaySets.clear()
        _ownRelaySets.value = emptyList()
        _favoriteRelays.value = emptyList()
        ownerPubkey = null
        favoriteRelaysTimestamp = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnSets() {
        val owner = ownerPubkey ?: return
        val own = relaySets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        _ownRelaySets.value = own
        saveSetsToPrefs(own)
    }

    private fun saveSetsToPrefs(sets: List<RelaySet>) {
        val serializable = sets.map {
            SerializableRelaySet(it.pubkey, it.dTag, it.name, it.relays.toList(), it.createdAt)
        }
        prefs.edit().putString("relay_sets", json.encodeToString(serializable)).apply()
    }

    private fun saveFavoritesToPrefs(urls: List<String>) {
        prefs.edit().putString("favorite_relays", json.encodeToString(urls)).apply()
    }

    private fun loadFromPrefs() {
        val setsStr = prefs.getString("relay_sets", null)
        if (setsStr != null) {
            try {
                val serializable = json.decodeFromString<List<SerializableRelaySet>>(setsStr)
                for (s in serializable) {
                    val rs = RelaySet(s.pubkey, s.dTag, s.name, s.relays.toSet(), s.createdAt)
                    relaySets["${s.pubkey}:${s.dTag}"] = rs
                }
            } catch (_: Exception) {}
        }
        val favsStr = prefs.getString("favorite_relays", null)
        if (favsStr != null) {
            try {
                _favoriteRelays.value = json.decodeFromString<List<String>>(favsStr)
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class SerializableRelaySet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val relays: List<String>,
        val createdAt: Long
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_relay_sets_$pubkeyHex" else "wisp_relay_sets"
    }
}
