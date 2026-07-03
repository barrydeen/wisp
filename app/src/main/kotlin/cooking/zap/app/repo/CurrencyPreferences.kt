package cooking.zap.app.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the display currency used for wallet fiat conversions (balance and
 * transaction amounts). This is only a currency selection — there is no
 * app-wide "fiat mode"; sats remain the standard everywhere outside the
 * wallet's optional dollar-balance view.
 */
class CurrencyPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    private val _currency = MutableStateFlow(prefs.getString(KEY_CURRENCY, "USD") ?: "USD")
    val currency: StateFlow<String> = _currency.asStateFlow()

    fun getCurrency(): String = _currency.value

    fun setCurrency(code: String) {
        if (_currency.value == code) return
        prefs.edit().putString(KEY_CURRENCY, code).apply()
        _currency.value = code
    }

    companion object {
        // Preserve the legacy key so a previously-selected currency carries over.
        private const val KEY_CURRENCY = "fiat_currency"

        @Volatile
        private var INSTANCE: CurrencyPreferences? = null

        fun get(context: Context): CurrencyPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CurrencyPreferences(context.applicationContext).also { INSTANCE = it }
            }
    }
}
