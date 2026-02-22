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

    private var ownerPubkey: String? = pubkeyHex

    companion object {
        private val DEFAULT_UNICODE_EMOJIS = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80")

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
    }

    fun getMissingSetReferences(): List<String> {
        val refs = _userEmojiList.value?.setReferences ?: return emptyList()
        return refs.filter { ref ->
            val parsed = Nip30.parseSetReference(ref) ?: return@filter false
            val key = "${parsed.second}:${parsed.third}"
            !emojiSets.containsKey(key)
        }
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
        return updated
    }

    fun setUnicodeEmojis(emojis: List<String>) {
        _unicodeEmojis.value = emojis
        saveUnicodeToPrefs()
    }

    fun clear() {
        _userEmojiList.value = null
        _ownSets.value = emptyList()
        emojiSets.clear()
        _resolvedEmojis.value = emptyMap()
        eventEmojiCache.evictAll()
        _unicodeEmojis.value = emptyList()
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

    private fun saveUnicodeToPrefs() {
        val arr = JSONArray()
        _unicodeEmojis.value.forEach { arr.put(it) }
        prefs.edit().putString("unicode_emojis", arr.toString()).apply()
    }

    private fun loadFromPrefs() {
        // Load unicode emojis
        val unicodeJson = prefs.getString("unicode_emojis", null)
        if (unicodeJson != null) {
            try {
                val arr = JSONArray(unicodeJson)
                val list = (0 until arr.length()).map { arr.getString(it) }
                _unicodeEmojis.value = list
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

        resolveEmojis()
    }
}
