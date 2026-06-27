package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SafetyPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _spamFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_SPAM_FILTER, true))
    val spamFilterEnabled: StateFlow<Boolean> = _spamFilterEnabled

    private val _wotFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_WOT_FILTER, false))
    val wotFilterEnabled: StateFlow<Boolean> = _wotFilterEnabled

    // OnlyFood-specific WoT filter — distinct from the global [wotFilterEnabled].
    // Defaults OFF to mirror the web client, which applies NO web-of-trust gate to
    // the #foodstr discovery feed (structural + mute only). Kept as an opt-in toggle
    // for users who want to narrow the stranger-heavy feed to their trust set.
    private val _onlyFoodWotEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLYFOOD_WOT, false))
    val onlyFoodWotEnabled: StateFlow<Boolean> = _onlyFoodWotEnabled

    private var safelistSet =
        HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
    private val _spamSafelist = MutableStateFlow<Set<String>>(safelistSet.toSet())
    val spamSafelist: StateFlow<Set<String>> = _spamSafelist

    fun setSpamFilterEnabled(enabled: Boolean) {
        _spamFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SPAM_FILTER, enabled).apply()
    }

    fun setWotFilterEnabled(enabled: Boolean) {
        _wotFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WOT_FILTER, enabled).apply()
    }

    fun setOnlyFoodWotEnabled(enabled: Boolean) {
        _onlyFoodWotEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ONLYFOOD_WOT, enabled).apply()
    }

    fun isSpamSafelisted(pubkey: String): Boolean = safelistSet.contains(pubkey)

    fun addToSpamSafelist(pubkey: String) {
        safelistSet.add(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    fun removeFromSpamSafelist(pubkey: String) {
        safelistSet.remove(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    /** Re-point to the new account's prefs file and refresh all StateFlows. */
    fun reload(newPubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(newPubkeyHex), Context.MODE_PRIVATE)
        _spamFilterEnabled.value = prefs.getBoolean(KEY_SPAM_FILTER, true)
        _wotFilterEnabled.value = prefs.getBoolean(KEY_WOT_FILTER, false)
        _onlyFoodWotEnabled.value = prefs.getBoolean(KEY_ONLYFOOD_WOT, false)
        safelistSet = HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
        _spamSafelist.value = safelistSet.toSet()
    }

    companion object {
        private const val KEY_SPAM_FILTER = "spam_filter_enabled"
        private const val KEY_WOT_FILTER = "wot_filter_enabled"
        private const val KEY_ONLYFOOD_WOT = "onlyfood_wot_enabled"
        private const val KEY_SPAM_SAFELIST = "spam_safelist"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_safety_$pubkeyHex" else "wisp_safety"
    }
}
