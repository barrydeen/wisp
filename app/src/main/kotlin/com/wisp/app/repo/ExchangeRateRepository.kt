package com.wisp.app.repo

import android.content.Context
import android.util.Log
import com.wisp.app.relay.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException

data class FiatCurrency(val code: String, val symbol: String, val name: String)

object ExchangeRateRepository {

    private const val TAG = "ExchangeRateRepo"
    private const val PREFS_NAME = "wisp_exchange_rates"
    private const val KEY_RATES_JSON = "rates_json"
    private const val KEY_UPDATED_AT = "rates_updated_at"

    val SUPPORTED: List<FiatCurrency> = listOf(
        FiatCurrency("USD", "$", "US Dollar"),
        FiatCurrency("EUR", "€", "Euro"),
        FiatCurrency("GBP", "£", "British Pound"),
        FiatCurrency("JPY", "¥", "Japanese Yen"),
        FiatCurrency("CAD", "$", "Canadian Dollar"),
        FiatCurrency("AUD", "$", "Australian Dollar"),
        FiatCurrency("CHF", "Fr", "Swiss Franc"),
        FiatCurrency("CNY", "¥", "Chinese Yuan"),
        FiatCurrency("INR", "₹", "Indian Rupee"),
        FiatCurrency("BRL", "R$", "Brazilian Real"),
        FiatCurrency("MXN", "$", "Mexican Peso"),
        FiatCurrency("KRW", "₩", "South Korean Won"),
        FiatCurrency("SGD", "$", "Singapore Dollar"),
        FiatCurrency("ZAR", "R", "South African Rand"),
        FiatCurrency("HKD", "$", "Hong Kong Dollar"),
        FiatCurrency("NZD", "$", "New Zealand Dollar"),
        FiatCurrency("SEK", "kr", "Swedish Krona"),
        FiatCurrency("NOK", "kr", "Norwegian Krone"),
        FiatCurrency("DKK", "kr", "Danish Krone"),
        FiatCurrency("PLN", "zł", "Polish Złoty"),
        FiatCurrency("TRY", "₺", "Turkish Lira"),
        FiatCurrency("THB", "฿", "Thai Baht"),
        FiatCurrency("IDR", "Rp", "Indonesian Rupiah"),
        FiatCurrency("PHP", "₱", "Philippine Peso"),
        FiatCurrency("AED", "د.إ", "UAE Dirham"),
        FiatCurrency("SAR", "﷼", "Saudi Riyal")
    )

    private val vsCurrencies: String by lazy {
        SUPPORTED.joinToString(",") { it.code.lowercase() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _rates = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Map of UPPERCASE currency code → BTC price in that currency. */
    val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    private val _updatedAtMs = MutableStateFlow(0L)
    val updatedAtMs: StateFlow<Long> = _updatedAtMs.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadCached()
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        scope.launch {
            try {
                val url = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=$vsCurrencies"
                val client = HttpClientFactory.createHttpClient(
                    connectTimeoutSeconds = 10,
                    readTimeoutSeconds = 15
                )
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Rate fetch failed: HTTP ${response.code}")
                        return@launch
                    }
                    val body = response.body?.string() ?: return@launch
                    val parsed = parseRates(body)
                    if (parsed.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        _rates.value = parsed
                        _updatedAtMs.value = now
                        saveCached(ctx, body, now)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Rate fetch network error: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Rate fetch error: ${e.message}", e)
            }
        }
    }

    /**
     * Converts sats to fiat in the given currency.
     * Returns null if no cached rate is available yet.
     */
    fun satsToFiat(sats: Long, currency: String): Double? {
        val btcPrice = _rates.value[currency.uppercase()] ?: return null
        return sats.toDouble() / 100_000_000.0 * btcPrice
    }

    fun currencyFor(code: String): FiatCurrency =
        SUPPORTED.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SUPPORTED[0]

    private fun loadCached() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val body = prefs.getString(KEY_RATES_JSON, null) ?: return
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        try {
            val parsed = parseRates(body)
            if (parsed.isNotEmpty()) {
                _rates.value = parsed
                _updatedAtMs.value = updatedAt
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached rates: ${e.message}")
        }
    }

    private fun saveCached(context: Context, body: String, updatedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RATES_JSON, body)
            .putLong(KEY_UPDATED_AT, updatedAt)
            .apply()
    }

    private fun parseRates(body: String): Map<String, Double> {
        val root = json.parseToJsonElement(body).jsonObject
        val btc = root["bitcoin"]?.jsonObject ?: return emptyMap()
        val out = mutableMapOf<String, Double>()
        for ((key, value) in btc) {
            val price = value.jsonPrimitive.doubleOrNull ?: continue
            out[key.uppercase()] = price
        }
        return out
    }
}
