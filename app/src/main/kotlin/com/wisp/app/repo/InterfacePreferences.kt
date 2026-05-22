package com.wisp.app.repo

import android.content.Context

class InterfacePreferences(context: Context) {
    enum class MediaLayoutStyle(val key: String) {
        GALLERY("gallery"),
        STACK("stack");

        companion object {
            fun fromKey(key: String?): MediaLayoutStyle =
                values().firstOrNull { it.key == key } ?: GALLERY
        }
    }

    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    /**
     * Optional hook fired after any setter that mutates a NIP-78-synced
     * field. AppSettingsRepository registers itself here so a debounced
     * cross-device publish kicks off automatically. Non-synced setters
     * (language, newNotesButtonHidden, liveStreamsHidden, autoTranslate)
     * don't call this.
     */
    @Volatile
    var onSyncedFieldChanged: (() -> Unit)? = null

    private fun fireSync() { onSyncedFieldChanged?.invoke() }

    fun getAccentColor(): Int = prefs.getInt("accent_color", 0xFFFF9800.toInt())
    fun setAccentColor(colorInt: Int) {
        prefs.edit().putInt("accent_color", colorInt).apply()
        fireSync()
    }

    fun isLargeText(): Boolean = prefs.getBoolean("large_text", false)
    fun setLargeText(enabled: Boolean) {
        prefs.edit().putBoolean("large_text", enabled).apply()
        fireSync()
    }

    fun isNewNotesButtonHidden(): Boolean = prefs.getBoolean("new_notes_button_hidden", false)
    fun setNewNotesButtonHidden(hidden: Boolean) = prefs.edit().putBoolean("new_notes_button_hidden", hidden).apply()

    fun getTheme(): String = prefs.getString("theme", "custom") ?: "custom"
    fun setTheme(theme: String) {
        prefs.edit().putString("theme", theme).apply()
        fireSync()
    }

    fun isClientTagEnabled(): Boolean = prefs.getBoolean("client_tag_enabled", true)
    fun setClientTagEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("client_tag_enabled", enabled).apply()
        fireSync()
    }

    fun isAutoLoadMedia(): Boolean = prefs.getBoolean("auto_load_media", true)
    fun setAutoLoadMedia(enabled: Boolean) {
        prefs.edit().putBoolean("auto_load_media", enabled).apply()
        fireSync()
    }

    fun isVideoAutoPlay(): Boolean = prefs.getBoolean("video_auto_play", true)
    fun setVideoAutoPlay(enabled: Boolean) {
        prefs.edit().putBoolean("video_auto_play", enabled).apply()
        fireSync()
    }

    fun getMediaLayoutStyle(): MediaLayoutStyle =
        MediaLayoutStyle.fromKey(prefs.getString("media_layout_style", null))
    fun setMediaLayoutStyle(style: MediaLayoutStyle) {
        prefs.edit().putString("media_layout_style", style.key).apply()
        fireSync()
    }

    fun getLanguage(): String = prefs.getString("language", "system") ?: "system"
    fun setLanguage(language: String) = prefs.edit().putString("language", language).apply()

    fun isZapBoltIcon(): Boolean = prefs.getBoolean("zap_bolt_icon", false)
    fun setZapBoltIcon(enabled: Boolean) {
        prefs.edit().putBoolean("zap_bolt_icon", enabled).apply()
        fireSync()
    }

    fun isLiveStreamsHidden(): Boolean = prefs.getBoolean("live_streams_hidden", false)
    fun setLiveStreamsHidden(hidden: Boolean) = prefs.edit().putBoolean("live_streams_hidden", hidden).apply()

    fun isAutoTranslate(): Boolean = prefs.getBoolean("auto_translate", false)
    fun setAutoTranslate(enabled: Boolean) = prefs.edit().putBoolean("auto_translate", enabled).apply()

    fun isPostUndoTimerEnabled(): Boolean = prefs.getBoolean("post_undo_timer_enabled", true)
    fun setPostUndoTimerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("post_undo_timer_enabled", enabled).apply()
        fireSync()
    }

    fun getPostUndoTimerSeconds(): Int {
        val stored = prefs.getInt("post_undo_timer_seconds", 10)
        return if (stored in postUndoTimerOptions) stored else 10
    }
    fun setPostUndoTimerSeconds(seconds: Int) {
        prefs.edit().putInt("post_undo_timer_seconds", seconds).apply()
        fireSync()
    }

    fun isPostUndoTimerForReplies(): Boolean = prefs.getBoolean("post_undo_timer_for_replies", false)
    fun setPostUndoTimerForReplies(enabled: Boolean) {
        prefs.edit().putBoolean("post_undo_timer_for_replies", enabled).apply()
        fireSync()
    }

    // NIP-78 cross-device sync of UI prefs.
    fun isSyncSettingsToRelays(): Boolean = prefs.getBoolean("sync_settings_to_relays", true)
    fun setSyncSettingsToRelays(enabled: Boolean) = prefs.edit().putBoolean("sync_settings_to_relays", enabled).apply()

    // ── Instant (a.k.a. quick) zaps ─────────────────────────────────────────
    // Hold-to-zap on the post-card fires immediately at the configured
    // amount when enabled; tap still opens the composer.

    fun isQuickZapEnabled(): Boolean = prefs.getBoolean("quick_zap_enabled", false)
    fun setQuickZapEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("quick_zap_enabled", enabled).apply()
        fireSync()
    }

    fun getQuickZapAmountSats(): Long = prefs.getLong("quick_zap_amount_sats", 100L).coerceIn(1L, QUICK_ZAP_MAX_SATS)
    fun setQuickZapAmountSats(amount: Long) {
        // Hard clamp at 10K sats so an instant zap never bypasses the soft
        // confirmation dialog in the ZapSheet (which fires at >10K).
        val clamped = amount.coerceIn(1L, QUICK_ZAP_MAX_SATS)
        prefs.edit().putLong("quick_zap_amount_sats", clamped).apply()
        fireSync()
    }

    fun getQuickZapAmountFiat(): Double =
        prefs.getString("quick_zap_amount_fiat", "0.10")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.10
    fun setQuickZapAmountFiat(amount: Double) {
        // Fiat clamp happens at fire time against the cached exchange rate
        // (callers in ZapSheet do `min(localFiat, sats→fiat(10_000))`).
        val clamped = amount.coerceAtLeast(0.0)
        prefs.edit().putString("quick_zap_amount_fiat", clamped.toString()).apply()
        fireSync()
    }

    fun getQuickZapMessage(): String = prefs.getString("quick_zap_message", "") ?: ""
    fun setQuickZapMessage(message: String) {
        prefs.edit().putString("quick_zap_message", message).apply()
        fireSync()
    }

    // ── iOS round-trip-only fields ──────────────────────────────────────────
    // Stored verbatim so an Android publish doesn't strip iOS-only settings
    // out of the cross-device backup. No Android UI consumes these yet.

    fun getDefaultReaction(): String? = prefs.getString("default_reaction", null)
    fun setDefaultReaction(value: String?) {
        prefs.edit().apply {
            if (value == null) remove("default_reaction") else putString("default_reaction", value)
        }.apply()
    }

    fun getDefaultReactionEnabled(): Boolean? =
        if (prefs.contains("default_reaction_enabled")) prefs.getBoolean("default_reaction_enabled", false) else null
    fun setDefaultReactionEnabled(value: Boolean?) {
        prefs.edit().apply {
            if (value == null) remove("default_reaction_enabled") else putBoolean("default_reaction_enabled", value)
        }.apply()
    }

    fun getColorScheme(): String? = prefs.getString("color_scheme", null)
    fun setColorScheme(value: String?) {
        prefs.edit().apply {
            if (value == null) remove("color_scheme") else putString("color_scheme", value)
        }.apply()
    }

    fun getAnimateAvatars(): Boolean? =
        if (prefs.contains("animate_avatars")) prefs.getBoolean("animate_avatars", true) else null
    fun setAnimateAvatars(value: Boolean?) {
        prefs.edit().apply {
            if (value == null) remove("animate_avatars") else putBoolean("animate_avatars", value)
        }.apply()
    }

    companion object {
        val postUndoTimerOptions = listOf(5, 10, 15, 20, 30)
        const val QUICK_ZAP_MAX_SATS = 10_000L
    }

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
            .remove("auto_translate")
            .remove("media_layout_style")
            .remove("sync_settings_to_relays")
            .apply()
    }
}
