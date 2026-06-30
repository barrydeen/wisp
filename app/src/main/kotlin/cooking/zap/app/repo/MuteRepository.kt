package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import cooking.zap.app.nostr.MuteList
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MuteRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys

    private val _mutedWords = MutableStateFlow<Set<String>>(emptySet())
    val mutedWords: StateFlow<Set<String>> = _mutedWords

    private val _mutedThreads = MutableStateFlow<Set<String>>(emptySet())
    val mutedThreads: StateFlow<Set<String>> = _mutedThreads

    // Copy-on-write + @Volatile. These are read OFF the main thread — OnlyFood's
    // confined collector calls isBlocked()/containsMutedWord(), and the main feed's
    // background processing calls isThreadMuted(). Every mutation assigns a FRESH
    // set instead of mutating in place, so a reader always iterates an immutable
    // snapshot: no torn reads, no ConcurrentModificationException. @Volatile
    // publishes the new reference to those reader threads.
    @Volatile
    private var blockedSet: Set<String> = emptySet()
    @Volatile
    private var wordSet: Set<String> = emptySet()
    @Volatile
    private var threadSet: Set<String> = emptySet()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        val muteList = Nip51.parseMuteList(event)
        blockedSet = muteList.pubkeys.toSet()
        wordSet = muteList.words.toSet()
        _blockedPubkeys.value = blockedSet
        _mutedWords.value = wordSet
        lastUpdated = event.created_at
        saveToPrefs()
    }

    suspend fun loadFromEvent(event: NostrEvent, signer: NostrSigner) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        val publicMutes = Nip51.parseMuteList(event)
        val privateMutes = if (event.content.isNotBlank()) {
            try {
                val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                Nip51.parsePrivateTags(decrypted)
            } catch (_: Exception) {
                MuteList()
            }
        } else MuteList()
        blockedSet = (publicMutes.pubkeys + privateMutes.pubkeys).toSet()
        wordSet = (publicMutes.words + privateMutes.words).toSet()
        _blockedPubkeys.value = blockedSet
        _mutedWords.value = wordSet
        lastUpdated = event.created_at
        saveToPrefs()
    }

    fun blockUser(pubkey: String) {
        blockedSet = blockedSet + pubkey
        _blockedPubkeys.value = blockedSet
        saveToPrefs()
    }

    fun unblockUser(pubkey: String) {
        blockedSet = blockedSet - pubkey
        _blockedPubkeys.value = blockedSet
        saveToPrefs()
    }

    fun isBlocked(pubkey: String): Boolean = blockedSet.contains(pubkey)

    fun addMutedWord(word: String) {
        wordSet = wordSet + word.lowercase()
        _mutedWords.value = wordSet
        saveToPrefs()
    }

    fun removeMutedWord(word: String) {
        wordSet = wordSet - word.lowercase()
        _mutedWords.value = wordSet
        saveToPrefs()
    }

    fun containsMutedWord(content: String): Boolean {
        // Snapshot the volatile reference once: the set is never mutated in place,
        // so iterating this local can't observe a concurrent write.
        val words = wordSet
        if (words.isEmpty()) return false
        val lower = content.lowercase()
        return words.any { lower.contains(it) }
    }

    fun muteThread(rootEventId: String) {
        threadSet = threadSet + rootEventId
        _mutedThreads.value = threadSet
        saveToPrefs()
    }

    fun unmuteThread(rootEventId: String) {
        threadSet = threadSet - rootEventId
        _mutedThreads.value = threadSet
        saveToPrefs()
    }

    fun isThreadMuted(rootEventId: String): Boolean = threadSet.contains(rootEventId)

    fun getBlockedPubkeys(): Set<String> = blockedSet.toSet()

    fun getMutedWords(): Set<String> = wordSet.toSet()

    fun clear() {
        _blockedPubkeys.value = emptySet()
        _mutedWords.value = emptySet()
        _mutedThreads.value = emptySet()
        blockedSet = emptySet()
        wordSet = emptySet()
        threadSet = emptySet()
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
            .putStringSet("blocked_pubkeys", blockedSet.toSet())
            .putStringSet("muted_words", wordSet.toSet())
            .putStringSet("muted_threads", threadSet.toSet())
            .putLong("mute_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("mute_updated", 0)
        val pubkeys = prefs.getStringSet("blocked_pubkeys", null)
        if (pubkeys != null) {
            blockedSet = pubkeys.toSet()
            _blockedPubkeys.value = blockedSet
        }
        val words = prefs.getStringSet("muted_words", null)
        if (words != null) {
            wordSet = words.toSet()
            _mutedWords.value = wordSet
        }
        val threads = prefs.getStringSet("muted_threads", null)
        if (threads != null) {
            threadSet = threads.toSet()
            _mutedThreads.value = threadSet
        }
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_mutes_$pubkeyHex" else "wisp_mutes"
    }
}
