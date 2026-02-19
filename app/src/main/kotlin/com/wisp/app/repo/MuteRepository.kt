package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.MuteList
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MuteRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_mutes", Context.MODE_PRIVATE)

    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys

    private val _mutedWords = MutableStateFlow<Set<String>>(emptySet())
    val mutedWords: StateFlow<Set<String>> = _mutedWords

    private var blockedSet = HashSet<String>()
    private var wordSet = HashSet<String>()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        val muteList = Nip51.parseMuteList(event)
        blockedSet = HashSet(muteList.pubkeys)
        wordSet = HashSet(muteList.words)
        _blockedPubkeys.value = blockedSet.toSet()
        _mutedWords.value = wordSet.toSet()
        lastUpdated = event.created_at
        saveToPrefs()
    }

    fun blockUser(pubkey: String) {
        blockedSet.add(pubkey)
        _blockedPubkeys.value = blockedSet.toSet()
        saveToPrefs()
    }

    fun unblockUser(pubkey: String) {
        blockedSet.remove(pubkey)
        _blockedPubkeys.value = blockedSet.toSet()
        saveToPrefs()
    }

    fun isBlocked(pubkey: String): Boolean = blockedSet.contains(pubkey)

    fun addMutedWord(word: String) {
        wordSet.add(word.lowercase())
        _mutedWords.value = wordSet.toSet()
        saveToPrefs()
    }

    fun removeMutedWord(word: String) {
        wordSet.remove(word.lowercase())
        _mutedWords.value = wordSet.toSet()
        saveToPrefs()
    }

    fun containsMutedWord(content: String): Boolean {
        if (wordSet.isEmpty()) return false
        val lower = content.lowercase()
        return wordSet.any { lower.contains(it) }
    }

    fun getBlockedPubkeys(): Set<String> = blockedSet.toSet()

    fun getMutedWords(): Set<String> = wordSet.toSet()

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("blocked_pubkeys", blockedSet.toSet())
            .putStringSet("muted_words", wordSet.toSet())
            .putLong("mute_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("mute_updated", 0)
        val pubkeys = prefs.getStringSet("blocked_pubkeys", null)
        if (pubkeys != null) {
            blockedSet = HashSet(pubkeys)
            _blockedPubkeys.value = blockedSet.toSet()
        }
        val words = prefs.getStringSet("muted_words", null)
        if (words != null) {
            wordSet = HashSet(words)
            _mutedWords.value = wordSet.toSet()
        }
    }
}
