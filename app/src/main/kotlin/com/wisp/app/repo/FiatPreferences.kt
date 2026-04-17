package com.wisp.app.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FiatPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    private val _fiatMode = MutableStateFlow(prefs.getBoolean(KEY_FIAT_MODE, false))
    val fiatMode: StateFlow<Boolean> = _fiatMode.asStateFlow()

    private val _currency = MutableStateFlow(prefs.getString(KEY_CURRENCY, "USD") ?: "USD")
    val currency: StateFlow<String> = _currency.asStateFlow()

    fun isFiatMode(): Boolean = _fiatMode.value

    fun setFiatMode(enabled: Boolean) {
        if (_fiatMode.value == enabled) return
        prefs.edit().putBoolean(KEY_FIAT_MODE, enabled).apply()
        _fiatMode.value = enabled
    }

    fun getCurrency(): String = _currency.value

    fun setCurrency(code: String) {
        if (_currency.value == code) return
        prefs.edit().putString(KEY_CURRENCY, code).apply()
        _currency.value = code
    }

    companion object {
        private const val KEY_FIAT_MODE = "fiat_mode_enabled"
        private const val KEY_CURRENCY = "fiat_currency"

        @Volatile
        private var INSTANCE: FiatPreferences? = null

        fun get(context: Context): FiatPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FiatPreferences(context.applicationContext).also { INSTANCE = it }
            }
    }
}
