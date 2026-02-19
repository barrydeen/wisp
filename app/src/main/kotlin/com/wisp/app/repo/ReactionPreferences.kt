package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class ReactionPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EMOJI_SET = "emoji_set"
        private val DEFAULT_SET = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80")

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "reaction_prefs_$pubkeyHex" else "reaction_prefs"
    }

    fun getReactionSet(): List<String> {
        val json = prefs.getString(KEY_EMOJI_SET, null) ?: return DEFAULT_SET
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            DEFAULT_SET
        }
    }

    fun setReactionSet(emojis: List<String>) {
        val arr = JSONArray()
        emojis.forEach { arr.put(it) }
        prefs.edit().putString(KEY_EMOJI_SET, arr.toString()).apply()
    }

    fun addEmoji(emoji: String): List<String> {
        val current = getReactionSet()
        if (emoji in current) return current
        val updated = current + emoji
        setReactionSet(updated)
        return updated
    }

    fun reload(pubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    }
}
