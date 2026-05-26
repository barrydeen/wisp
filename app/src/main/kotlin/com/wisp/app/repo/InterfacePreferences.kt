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

    fun getMediaLayoutStyle(): MediaLayoutStyle =
        MediaLayoutStyle.fromKey(prefs.getString("media_layout_style", null))
    fun setMediaLayoutStyle(style: MediaLayoutStyle) =
        prefs.edit().putString("media_layout_style", style.key).apply()

    fun getLanguage(): String = prefs.getString("language", "system") ?: "system"
    fun setLanguage(language: String) = prefs.edit().putString("language", language).apply()

    fun isZapBoltIcon(): Boolean = prefs.getBoolean("zap_bolt_icon", false)
    fun setZapBoltIcon(enabled: Boolean) = prefs.edit().putBoolean("zap_bolt_icon", enabled).apply()

    fun isLiveStreamsHidden(): Boolean = prefs.getBoolean("live_streams_hidden", false)
    fun setLiveStreamsHidden(hidden: Boolean) = prefs.edit().putBoolean("live_streams_hidden", hidden).apply()

    fun isAutoTranslate(): Boolean = prefs.getBoolean("auto_translate", false)
    fun setAutoTranslate(enabled: Boolean) = prefs.edit().putBoolean("auto_translate", enabled).apply()

    fun isPostUndoTimerEnabled(): Boolean = prefs.getBoolean("post_undo_timer_enabled", true)
    fun setPostUndoTimerEnabled(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_enabled", enabled).apply()

    fun getPostUndoTimerSeconds(): Int {
        val stored = prefs.getInt("post_undo_timer_seconds", 10)
        return if (stored in postUndoTimerOptions) stored else 10
    }
    fun setPostUndoTimerSeconds(seconds: Int) = prefs.edit().putInt("post_undo_timer_seconds", seconds).apply()

    fun isPostUndoTimerForReplies(): Boolean = prefs.getBoolean("post_undo_timer_for_replies", false)
    fun setPostUndoTimerForReplies(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_for_replies", enabled).apply()

    // ── Instant (a.k.a. quick) zaps ─────────────────────────────────────────
    // Hold-to-zap on the post-card fires immediately at the configured
    // amount when enabled; tap still opens the composer.
    //
    // Keys are scoped per-account via activePubkey (companion object) so
    // switching accounts never inherits the previous account's values.
    // Call reload(pubkey) on every account switch to update the scope.

    private fun quickZapKey(base: String): String =
        activePubkey?.let { "${base}_$it" } ?: base

    fun isQuickZapEnabled(): Boolean = prefs.getBoolean(quickZapKey("quick_zap_enabled"), false)
    fun setQuickZapEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(quickZapKey("quick_zap_enabled"), enabled).apply()

    fun getQuickZapAmountSats(): Long =
        prefs.getLong(quickZapKey("quick_zap_amount_sats"), 21L).coerceIn(1L, QUICK_ZAP_MAX_SATS)
    fun setQuickZapAmountSats(amount: Long) {
        // Hard clamp at 10K sats so an instant zap never bypasses the soft
        // confirmation dialog in the ZapSheet (which fires at >10K).
        val clamped = amount.coerceIn(1L, QUICK_ZAP_MAX_SATS)
        prefs.edit().putLong(quickZapKey("quick_zap_amount_sats"), clamped).apply()
    }

    fun getQuickZapAmountFiat(): Double =
        prefs.getString(quickZapKey("quick_zap_amount_fiat"), "0.10")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.10
    fun setQuickZapAmountFiat(amount: Double) {
        // Fiat clamp happens at fire time against the cached exchange rate
        // (callers in ZapSheet do `min(localFiat, sats→fiat(10_000))`).
        val clamped = amount.coerceAtLeast(0.0)
        prefs.edit().putString(quickZapKey("quick_zap_amount_fiat"), clamped.toString()).apply()
    }

    fun getQuickZapMessage(): String = prefs.getString(quickZapKey("quick_zap_message"), "") ?: ""
    fun setQuickZapMessage(message: String) =
        prefs.edit().putString(quickZapKey("quick_zap_message"), message).apply()

    /**
     * Update the active account scope for instant-zap keys. Call on every
     * account switch AND at initial app launch. On the very first call with
     * a non-null pubkey (activePubkey was null), migrates any values that
     * were stored under the old unscoped keys so existing settings survive
     * the upgrade to per-account storage.
     */
    fun reload(pubkey: String?) {
        val wasNull = activePubkey == null
        activePubkey = pubkey
        if (wasNull && pubkey != null) migrateGlobalIfNeeded(pubkey)
    }

    private fun migrateGlobalIfNeeded(pubkey: String) {
        val migKey = "quick_zap_migrated_v1_$pubkey"
        if (prefs.getBoolean(migKey, false)) return
        val edit = prefs.edit().putBoolean(migKey, true)
        if (!prefs.contains("quick_zap_amount_sats_$pubkey") && prefs.contains("quick_zap_amount_sats"))
            edit.putLong("quick_zap_amount_sats_$pubkey", prefs.getLong("quick_zap_amount_sats", 21L))
        if (!prefs.contains("quick_zap_amount_fiat_$pubkey") && prefs.contains("quick_zap_amount_fiat"))
            prefs.getString("quick_zap_amount_fiat", null)?.let { edit.putString("quick_zap_amount_fiat_$pubkey", it) }
        if (!prefs.contains("quick_zap_enabled_$pubkey") && prefs.contains("quick_zap_enabled"))
            edit.putBoolean("quick_zap_enabled_$pubkey", prefs.getBoolean("quick_zap_enabled", false))
        if (!prefs.contains("quick_zap_message_$pubkey") && prefs.contains("quick_zap_message"))
            prefs.getString("quick_zap_message", null)?.let { edit.putString("quick_zap_message_$pubkey", it) }
        edit.apply()
    }

    companion object {
        /** Shared across all InterfacePreferences instances — updated by reload(). */
        @Volatile var activePubkey: String? = null
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
            .apply()
    }
}
