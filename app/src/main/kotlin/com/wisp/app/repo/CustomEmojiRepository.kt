package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.EmojiSet
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.UserEmojiList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class CustomEmojiRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _userEmojiList = MutableStateFlow<UserEmojiList?>(null)
    val userEmojiList: StateFlow<UserEmojiList?> = _userEmojiList

    private val _ownSets = MutableStateFlow<List<EmojiSet>>(emptyList())
    val ownSets: StateFlow<List<EmojiSet>> = _ownSets

    private val emojiSets = java.util.concurrent.ConcurrentHashMap<String, EmojiSet>()

    private val _resolvedEmojis = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedEmojis: StateFlow<Map<String, String>> = _resolvedEmojis

    private val eventEmojiCache = LruCache<String, Map<String, String>>(2000)

    // Unicode emoji shortcuts (no URL needed, just the emoji character)
    private val _unicodeEmojis = MutableStateFlow<List<String>>(emptyList())
    val unicodeEmojis: StateFlow<List<String>> = _unicodeEmojis

    // Emoji frequency tracking — sorted picker shows most-used first
    private val _emojiFrequency = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _sortedUnicodeEmojis = MutableStateFlow<List<String>>(emptyList())
    val sortedUnicodeEmojis: StateFlow<List<String>> = _sortedUnicodeEmojis

    private var ownerPubkey: String? = pubkeyHex

    companion object {
        private val DEFAULT_UNICODE_EMOJIS = listOf("\uD83E\uDDE1", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80", "\uD83E\uDD17", "\uD83D\uDE02", "\uD83D\uDE22", "\uD83D\uDC68\u200D\uD83D\uDCBB", "\uD83D\uDC40", "\u2705", "\uD83E\uDD21", "\uD83D\uDC38", "\uD83D\uDC80", "\u26A1", "\uD83D\uDE4F", "\uD83C\uDF46")
        private val OLD_DEFAULT_UNICODE_EMOJIS = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80")

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_custom_emoji_$pubkeyHex" else "wisp_custom_emoji"
    }

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        when (event.kind) {
            Nip30.KIND_USER_EMOJI_LIST -> {
                val current = _userEmojiList.value
                if (current != null && event.created_at <= current.createdAt) return
                val list = Nip30.parseUserEmojiList(event)
                _userEmojiList.value = list
                resolveEmojis()
                saveToPrefs()
            }
            Nip30.KIND_EMOJI_SET -> {
                val set = Nip30.parseEmojiSet(event) ?: return
                val key = "${set.pubkey}:${set.dTag}"
                val existing = emojiSets[key]
                if (existing != null && event.created_at <= existing.createdAt) return
                emojiSets[key] = set
                if (set.pubkey == ownerPubkey) {
                    _ownSets.value = emojiSets.values.filter { it.pubkey == ownerPubkey }.sortedBy { it.title }
                    saveOwnSetsToPrefs()
                }
                resolveEmojis()
                saveReferencedSetsToPrefs()
            }
        }
    }

    fun resolveEmojis() {
        val map = mutableMapOf<String, String>()
        // Direct emojis from user's kind 10030
        _userEmojiList.value?.emojis?.forEach { emoji ->
            if (emoji.url.isNotBlank()) {
                map[emoji.shortcode] = emoji.url
            }
        }
        // Emojis from referenced sets
        _userEmojiList.value?.setReferences?.forEach { ref ->
            val parsed = Nip30.parseSetReference(ref)
            if (parsed != null) {
                val key = "${parsed.second}:${parsed.third}"
                emojiSets[key]?.emojis?.forEach { emoji ->
                    map.putIfAbsent(emoji.shortcode, emoji.url)
                }
            }
        }
        _resolvedEmojis.value = map
        recomputeSortedEmojis()
    }

    fun getSetReferences(): List<String> {
        return _userEmojiList.value?.setReferences ?: emptyList()
    }

    fun getEventEmojis(eventId: String): Map<String, String>? = eventEmojiCache.get(eventId)

    fun cacheEventEmojis(eventId: String, map: Map<String, String>) {
        if (map.isNotEmpty()) eventEmojiCache.put(eventId, map)
    }

    fun getUnicodeEmojis(): List<String> = _unicodeEmojis.value

    fun addUnicodeEmoji(emoji: String): List<String> {
        val current = _unicodeEmojis.value
        if (emoji in current) return current
        val updated = current + emoji
        _unicodeEmojis.value = updated
        saveUnicodeToPrefs()
        recomputeSortedEmojis()
        return updated
    }

    fun removeUnicodeEmoji(emoji: String): List<String> {
        val updated = _unicodeEmojis.value.filter { it != emoji }
        _unicodeEmojis.value = updated
        saveUnicodeToPrefs()
        recomputeSortedEmojis()
        return updated
    }

    fun setUnicodeEmojis(emojis: List<String>) {
        _unicodeEmojis.value = emojis
        saveUnicodeToPrefs()
        recomputeSortedEmojis()
    }

    fun recordEmojiUsage(emoji: String) {
        val freq = _emojiFrequency.value.toMutableMap()
        freq[emoji] = (freq[emoji] ?: 0) + 1
        _emojiFrequency.value = freq
        recomputeSortedEmojis()
        saveFrequencyToPrefs()
    }

    private fun recomputeSortedEmojis() {
        val freq = _emojiFrequency.value
        // Combine unicode emojis and custom :shortcode: keys into one frequency-sorted list
        val customKeys = _resolvedEmojis.value.keys.map { ":$it:" }
        val all = _unicodeEmojis.value + customKeys
        _sortedUnicodeEmojis.value = all.sortedByDescending { freq[it] ?: 0 }
    }

    fun clear() {
        _userEmojiList.value = null
        _ownSets.value = emptyList()
        emojiSets.clear()
        _resolvedEmojis.value = emptyMap()
        eventEmojiCache.evictAll()
        _unicodeEmojis.value = emptyList()
        _emojiFrequency.value = emptyMap()
        _sortedUnicodeEmojis.value = emptyList()
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        ownerPubkey = pubkeyHex
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        val list = _userEmojiList.value ?: return
        val emojisArr = JSONArray()
        for (emoji in list.emojis) {
            val obj = JSONObject()
            obj.put("shortcode", emoji.shortcode)
            obj.put("url", emoji.url)
            emojisArr.put(obj)
        }
        val refsArr = JSONArray()
        for (ref in list.setReferences) refsArr.put(ref)

        prefs.edit()
            .putString("user_emoji_list", emojisArr.toString())
            .putString("user_emoji_refs", refsArr.toString())
            .putLong("user_emoji_updated", list.createdAt)
            .apply()
    }

    private fun saveOwnSetsToPrefs() {
        val sets = _ownSets.value
        val arr = JSONArray()
        for (set in sets) {
            val obj = JSONObject()
            obj.put("pubkey", set.pubkey)
            obj.put("dTag", set.dTag)
            obj.put("title", set.title)
            obj.put("createdAt", set.createdAt)
            val emojisArr = JSONArray()
            for (emoji in set.emojis) {
                val emojiObj = JSONObject()
                emojiObj.put("shortcode", emoji.shortcode)
                emojiObj.put("url", emoji.url)
                emojisArr.put(emojiObj)
            }
            obj.put("emojis", emojisArr)
            arr.put(obj)
        }
        prefs.edit().putString("own_sets", arr.toString()).apply()
    }

    private fun saveReferencedSetsToPrefs() {
        val refs = _userEmojiList.value?.setReferences ?: return
        val referencedKeys = refs.mapNotNull { ref ->
            val parsed = Nip30.parseSetReference(ref) ?: return@mapNotNull null
            "${parsed.second}:${parsed.third}"
        }.toSet()
        val setsToSave = emojiSets.filter { it.key in referencedKeys && it.value.pubkey != ownerPubkey }
        if (setsToSave.isEmpty()) {
            prefs.edit().remove("referenced_sets").apply()
            return
        }
        val arr = JSONArray()
        for ((_, set) in setsToSave) {
            val obj = JSONObject()
            obj.put("pubkey", set.pubkey)
            obj.put("dTag", set.dTag)
            obj.put("title", set.title)
            obj.put("createdAt", set.createdAt)
            val emojisArr = JSONArray()
            for (emoji in set.emojis) {
                val emojiObj = JSONObject()
                emojiObj.put("shortcode", emoji.shortcode)
                emojiObj.put("url", emoji.url)
                emojisArr.put(emojiObj)
            }
            obj.put("emojis", emojisArr)
            arr.put(obj)
        }
        prefs.edit().putString("referenced_sets", arr.toString()).apply()
    }

    private fun saveUnicodeToPrefs() {
        val arr = JSONArray()
        _unicodeEmojis.value.forEach { arr.put(it) }
        prefs.edit().putString("unicode_emojis", arr.toString()).apply()
    }

    private fun saveFrequencyToPrefs() {
        val obj = JSONObject()
        for ((emoji, count) in _emojiFrequency.value) obj.put(emoji, count)
        prefs.edit().putString("emoji_frequency", obj.toString()).apply()
    }

    private fun loadFromPrefs() {
        // Load unicode emojis
        val unicodeJson = prefs.getString("unicode_emojis", null)
        if (unicodeJson != null) {
            try {
                val arr = JSONArray(unicodeJson)
                val list = (0 until arr.length()).map { arr.getString(it) }
                // Migrate: if stored list matches old defaults exactly, upgrade to new defaults
                if (list == OLD_DEFAULT_UNICODE_EMOJIS) {
                    _unicodeEmojis.value = DEFAULT_UNICODE_EMOJIS
                    saveUnicodeToPrefs()
                } else {
                    _unicodeEmojis.value = list
                }
            } catch (_: Exception) {
                _unicodeEmojis.value = DEFAULT_UNICODE_EMOJIS
            }
        } else {
            // Migration: try to load from old ReactionPreferences
            val oldPrefsName = if (ownerPubkey != null) "reaction_prefs_$ownerPubkey" else "reaction_prefs"
            val oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
            val oldJson = oldPrefs.getString("emoji_set", null)
            if (oldJson != null) {
                try {
                    val arr = JSONArray(oldJson)
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    _unicodeEmojis.value = list
                } catch (_: Exception) {
                    _unicodeEmojis.value = DEFAULT_UNICODE_EMOJIS
                }
            } else {
                _unicodeEmojis.value = DEFAULT_UNICODE_EMOJIS
            }
            saveUnicodeToPrefs()
        }

        // Load emoji frequency
        val freqJson = prefs.getString("emoji_frequency", null)
        if (freqJson != null) {
            try {
                val obj = JSONObject(freqJson)
                val map = mutableMapOf<String, Int>()
                for (key in obj.keys()) map[key] = obj.getInt(key)
                _emojiFrequency.value = map
            } catch (_: Exception) {}
        }
        recomputeSortedEmojis()

        // Load user emoji list
        val emojisJson = prefs.getString("user_emoji_list", null)
        val refsJson = prefs.getString("user_emoji_refs", null)
        val updated = prefs.getLong("user_emoji_updated", 0)
        if (emojisJson != null) {
            try {
                val emojisArr = JSONArray(emojisJson)
                val emojis = (0 until emojisArr.length()).map { i ->
                    val obj = emojisArr.getJSONObject(i)
                    CustomEmoji(obj.getString("shortcode"), obj.getString("url"))
                }
                val refs = if (refsJson != null) {
                    val arr = JSONArray(refsJson)
                    (0 until arr.length()).map { arr.getString(it) }
                } else emptyList()
                _userEmojiList.value = UserEmojiList(emojis, refs, updated)
            } catch (_: Exception) {}
        }

        // Load own sets
        val setsJson = prefs.getString("own_sets", null)
        if (setsJson != null) {
            try {
                val arr = JSONArray(setsJson)
                val sets = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val emojisArr = obj.getJSONArray("emojis")
                    val emojis = (0 until emojisArr.length()).map { j ->
                        val emojiObj = emojisArr.getJSONObject(j)
                        CustomEmoji(emojiObj.getString("shortcode"), emojiObj.getString("url"))
                    }
                    EmojiSet(
                        pubkey = obj.getString("pubkey"),
                        dTag = obj.getString("dTag"),
                        title = obj.getString("title"),
                        emojis = emojis,
                        createdAt = obj.getLong("createdAt")
                    )
                }
                _ownSets.value = sets
                for (set in sets) emojiSets["${set.pubkey}:${set.dTag}"] = set
            } catch (_: Exception) {}
        }

        // Load referenced third-party sets
        val refSetsJson = prefs.getString("referenced_sets", null)
        if (refSetsJson != null) {
            try {
                val arr = JSONArray(refSetsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val emojisArr = obj.getJSONArray("emojis")
                    val emojis = (0 until emojisArr.length()).map { j ->
                        val emojiObj = emojisArr.getJSONObject(j)
                        CustomEmoji(emojiObj.getString("shortcode"), emojiObj.getString("url"))
                    }
                    val set = EmojiSet(
                        pubkey = obj.getString("pubkey"),
                        dTag = obj.getString("dTag"),
                        title = obj.getString("title"),
                        emojis = emojis,
                        createdAt = obj.getLong("createdAt")
                    )
                    emojiSets.putIfAbsent("${set.pubkey}:${set.dTag}", set)
                }
            } catch (_: Exception) {}
        }

        resolveEmojis()
    }
}
