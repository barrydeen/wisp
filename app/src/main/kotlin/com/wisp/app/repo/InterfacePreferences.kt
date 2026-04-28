package com.wisp.app.repo

import android.content.Context

class InterfacePreferences(context: Context) {
    companion object {
        val postUndoTimerOptions = listOf(5, 10, 15, 20, 30)
    }

    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    fun getAccentColor(): Int = prefs.getInt("accent_color", 0xFFFF9800.toInt())
    fun setAccentColor(colorInt: Int) = prefs.edit().putInt("accent_color", colorInt).apply()

    fun isLargeText(): Boolean = prefs.getBoolean("large_text", false)
    fun setLargeText(enabled: Boolean) = prefs.edit().putBoolean("large_text", enabled).apply()

    fun isNewNotesButtonHidden(): Boolean = prefs.getBoolean("new_notes_button_hidden", false)
    fun setNewNotesButtonHidden(hidden: Boolean) = prefs.edit().putBoolean("new_notes_button_hidden", hidden).apply()

    fun getTheme(): String = prefs.getString("theme", "custom") ?: "custom"
    fun setTheme(theme: String) = prefs.edit().putString("theme", theme).apply()

    fun isClientTagEnabled(): Boolean = prefs.getBoolean("client_tag_enabled", true)
    fun setClientTagEnabled(enabled: Boolean) = prefs.edit().putBoolean("client_tag_enabled", enabled).apply()

    fun isAutoLoadMedia(): Boolean = prefs.getBoolean("auto_load_media", true)
    fun setAutoLoadMedia(enabled: Boolean) = prefs.edit().putBoolean("auto_load_media", enabled).apply()

    fun isVideoAutoPlay(): Boolean = prefs.getBoolean("video_auto_play", true)
    fun setVideoAutoPlay(enabled: Boolean) = prefs.edit().putBoolean("video_auto_play", enabled).apply()

    fun getLanguage(): String = prefs.getString("language", "system") ?: "system"
    fun setLanguage(language: String) = prefs.edit().putString("language", language).apply()

    fun isZapBoltIcon(): Boolean = prefs.getBoolean("zap_bolt_icon", false)
    fun setZapBoltIcon(enabled: Boolean) = prefs.edit().putBoolean("zap_bolt_icon", enabled).apply()

    fun isLiveStreamsHidden(): Boolean = prefs.getBoolean("live_streams_hidden", false)
    fun setLiveStreamsHidden(hidden: Boolean) = prefs.edit().putBoolean("live_streams_hidden", hidden).apply()

    fun isPostUndoTimerEnabled(): Boolean = prefs.getBoolean("post_undo_timer_enabled", true)
    fun setPostUndoTimerEnabled(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_enabled", enabled).apply()

    fun getPostUndoTimerSeconds(): Int {
        val stored = prefs.getInt("post_undo_timer_seconds", 10)
        return if (stored in postUndoTimerOptions) stored else 10
    }
    fun setPostUndoTimerSeconds(seconds: Int) = prefs.edit().putInt("post_undo_timer_seconds", seconds).apply()

    fun isPostUndoTimerForReplies(): Boolean = prefs.getBoolean("post_undo_timer_for_replies", false)
    fun setPostUndoTimerForReplies(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_for_replies", enabled).apply()

    /** Reset all interface preferences to defaults (called on full logout). */
    fun reset() {
        prefs.edit()
            .remove("accent_color")
            .remove("theme")
            .remove("large_text")
            .remove("new_notes_button_hidden")
            .remove("zap_bolt_icon")
            .remove("dark_theme")
            .remove("balance_hidden")
            .remove("live_streams_hidden")
            .remove("post_undo_timer_enabled")
            .remove("post_undo_timer_seconds")
            .remove("post_undo_timer_for_replies")
            .apply()
    }
}
