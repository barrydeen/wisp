package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SafetyPreferences(context: Context, pubkeyHex: String? = null) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _spamFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_SPAM_FILTER, true))
    val spamFilterEnabled: StateFlow<Boolean> = _spamFilterEnabled

    private val _wotFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_WOT_FILTER, false))
    val wotFilterEnabled: StateFlow<Boolean> = _wotFilterEnabled

    private val safelistSet = HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
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

    companion object {
        private const val KEY_SPAM_FILTER = "spam_filter_enabled"
        private const val KEY_WOT_FILTER = "wot_filter_enabled"
        private const val KEY_SPAM_SAFELIST = "spam_safelist"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_safety_$pubkeyHex" else "wisp_safety"
    }
}
