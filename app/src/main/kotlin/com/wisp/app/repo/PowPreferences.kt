package com.wisp.app.repo

import android.content.Context

class PowPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    fun isNotePowEnabled(): Boolean = prefs.getBoolean("pow_note_enabled", true)
    fun setNotePowEnabled(enabled: Boolean) = prefs.edit().putBoolean("pow_note_enabled", enabled).apply()

    fun getNoteDifficulty(): Int = prefs.getInt("pow_note_difficulty", 16)
    fun setNoteDifficulty(bits: Int) = prefs.edit().putInt("pow_note_difficulty", bits.coerceIn(8, 32)).apply()

    fun isReactionPowEnabled(): Boolean = prefs.getBoolean("pow_reaction_enabled", true)
    fun setReactionPowEnabled(enabled: Boolean) = prefs.edit().putBoolean("pow_reaction_enabled", enabled).apply()

    fun getReactionDifficulty(): Int = prefs.getInt("pow_reaction_difficulty", 12)
    fun setReactionDifficulty(bits: Int) = prefs.edit().putInt("pow_reaction_difficulty", bits.coerceIn(8, 32)).apply()
}
