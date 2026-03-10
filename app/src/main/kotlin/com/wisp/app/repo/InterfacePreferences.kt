package com.wisp.app.repo

import android.content.Context

class InterfacePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    fun getAccentColor(): Int = prefs.getInt("accent_color", 0xFFFF9800.toInt())
    fun setAccentColor(colorInt: Int) = prefs.edit().putInt("accent_color", colorInt).apply()

    fun isLargeText(): Boolean = prefs.getBoolean("large_text", false)
    fun setLargeText(enabled: Boolean) = prefs.edit().putBoolean("large_text", enabled).apply()

    fun isNewNotesButtonHidden(): Boolean = prefs.getBoolean("new_notes_button_hidden", false)
    fun setNewNotesButtonHidden(hidden: Boolean) = prefs.edit().putBoolean("new_notes_button_hidden", hidden).apply()

    fun getTheme(): String = prefs.getString("theme", "custom") ?: "custom"
    fun setTheme(theme: String) = prefs.edit().putString("theme", theme).apply()
}
