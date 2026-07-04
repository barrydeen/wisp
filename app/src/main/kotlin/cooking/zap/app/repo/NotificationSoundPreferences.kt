package cooking.zap.app.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-selectable notification sound effects, split by category to keep audible
 * triage: replies vs. other activity (reactions/reposts/mentions/votes) each get
 * their own tone. Zaps use a fixed dedicated sound (not selectable here). A
 * singleton with StateFlows so a change in settings applies live (the sound
 * wrappers observe these), not just on restart. Selecting [NONE] is silent.
 */
class NotificationSoundPreferences private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    /** A selectable sound: raw-resource name + display label. */
    data class Sound(val rawName: String, val label: String)

    private val _replySound = MutableStateFlow(prefs.getString(KEY_REPLY, DEFAULT_REPLY) ?: DEFAULT_REPLY)
    val replySound: StateFlow<String> = _replySound.asStateFlow()

    private val _activitySound = MutableStateFlow(prefs.getString(KEY_ACTIVITY, DEFAULT_ACTIVITY) ?: DEFAULT_ACTIVITY)
    val activitySound: StateFlow<String> = _activitySound.asStateFlow()

    /**
     * Master switch — when off, all in-app notification sounds (replies, activity,
     * zaps) and the posted-notification sound are silenced. Shared with the
     * Notifications screen's mute toggle via the [KEY_ENABLED] pref key.
     */
    private val _soundsEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val soundsEnabled: StateFlow<Boolean> = _soundsEnabled.asStateFlow()

    fun setReplySound(name: String) = set(KEY_REPLY, name, _replySound)
    fun setActivitySound(name: String) = set(KEY_ACTIVITY, name, _activitySound)

    fun setSoundsEnabled(enabled: Boolean) {
        if (_soundsEnabled.value == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _soundsEnabled.value = enabled
    }

    private fun set(key: String, name: String, flow: MutableStateFlow<String>) {
        if (flow.value == name) return
        prefs.edit().putString(key, name).apply()
        flow.value = name
    }

    companion object {
        const val NONE = "none"
        /** Fixed sound for zap notifications — dedicated, not user-selectable. */
        const val ZAP_SOUND = "zap_thunder"
        private const val KEY_REPLY = "sound_reply"
        private const val KEY_ACTIVITY = "sound_activity"
        // Reuses the existing master-mute key so the Notifications-screen toggle
        // and the settings switch stay in sync.
        private const val KEY_ENABLED = "notif_sound_enabled"
        private const val DEFAULT_REPLY = "oven_ding"
        private const val DEFAULT_ACTIVITY = "soda_open"

        /** Selectable options for the reply / other-activity pickers (the zap sound is separate). */
        val SOUNDS = listOf(
            Sound("oven_ding", "Oven Ding"),
            Sound("soda_open", "Soda Pop"),
            Sound("dinner_bell", "Dinner Bell"),
            Sound("door_bell", "Door Bell"),
            Sound("frying_pan", "Frying Pan"),
            Sound("cartoon_bite", "Cartoon Bite"),
            Sound("yum_yum", "Yum Yum"),
            Sound("glass_toast", "Glass Toast"),
            Sound(NONE, "None (silent)"),
        )

        fun labelFor(rawName: String): String =
            SOUNDS.firstOrNull { it.rawName == rawName }?.label ?: "Oven Ding"

        @Volatile
        private var INSTANCE: NotificationSoundPreferences? = null

        fun get(context: Context): NotificationSoundPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationSoundPreferences(context.applicationContext).also { INSTANCE = it }
            }
    }
}
