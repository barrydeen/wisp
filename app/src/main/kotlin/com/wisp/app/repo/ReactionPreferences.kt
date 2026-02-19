package com.wisp.app.repo

import android.content.Context
import org.json.JSONArray

object ReactionPreferences {
    private const val PREFS_NAME = "reaction_prefs"
    private const val KEY_EMOJI_SET = "emoji_set"
    private val DEFAULT_SET = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83E\uDD19", "\uD83D\uDE80")

    fun getReactionSet(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_EMOJI_SET, null) ?: return DEFAULT_SET
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            DEFAULT_SET
        }
    }

    fun setReactionSet(context: Context, emojis: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        emojis.forEach { arr.put(it) }
        prefs.edit().putString(KEY_EMOJI_SET, arr.toString()).apply()
    }

    fun addEmoji(context: Context, emoji: String): List<String> {
        val current = getReactionSet(context)
        if (emoji in current) return current
        val updated = current + emoji
        setReactionSet(context, updated)
        return updated
    }
}
