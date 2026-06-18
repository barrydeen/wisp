package cooking.zap.app.ui.util

import android.content.Context
import cooking.zap.app.R
import cooking.zap.app.repo.ExchangeRateRepository
import cooking.zap.app.repo.FiatCurrency
import cooking.zap.app.repo.FiatPreferences
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Centralized formatter for bitcoin amounts. When Fiat Mode is enabled in
 * [FiatPreferences], renders the amount as fiat using the cached rate in
 * [ExchangeRateRepository]. Otherwise falls back to the prior sat formatting.
 */
object AmountFormatter {

    /** Short form used inline (feed zap counts, etc.). Sats: "1.2k"/"3.4M". Fiat: "$0.12". */
    fun formatShort(sats: Long, context: Context): String {
        val prefs = FiatPreferences.get(context)
        return if (prefs.isFiatMode()) {
            formatFiat(sats, prefs.getCurrency()) ?: formatSatsShort(sats)
        } else {
            formatSatsShort(sats)
        }
    }

    /** Full form used for balances / transactions. Sats: "1,234 sats". Fiat: "$12.34". */
    fun formatFull(sats: Long, context: Context): String {
        val prefs = FiatPreferences.get(context)
        return if (prefs.isFiatMode()) {
            formatFiat(sats, prefs.getCurrency())
                ?: context.getString(R.string.amount_sats_format, String.format(Locale.getDefault(), "%,d", sats))
        } else {
            context.getString(R.string.amount_sats_format, String.format(Locale.getDefault(), "%,d", sats))
        }
    }

    /** Raw sat rendering without the "sats" suffix (e.g. big balance number). */
    fun formatSatsOnly(sats: Long): String = String.format(Locale.getDefault(), "%,d", sats)

    /** Short sat formatter matching the prior inline helper ("1.2k", "3.4M"). */
    fun formatSatsShort(sats: Long): String = when {
        sats >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", sats / 1_000_000.0)
        sats >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", sats / 1_000.0)
        else -> sats.toString()
    }

    /**
     * Returns the fiat-formatted string, or null if the rate is not yet cached.
     * Picks precision automatically based on magnitude so tiny zaps don't show "$0.00".
     */
    fun formatFiat(sats: Long, currencyCode: String): String? {
        val fiat = ExchangeRateRepository.satsToFiat(sats, currencyCode) ?: return null
        val currency = ExchangeRateRepository.currencyFor(currencyCode)
        return renderCurrency(fiat, currency)
    }

    private fun renderCurrency(amount: Double, currency: FiatCurrency): String {
        val abs = kotlin.math.abs(amount)
        // Sub-dollar tiers use `#` (optional) past the decimal so trailing
        // zeros drop — "$0.84" instead of "$0.840", "$0.8" instead of
        // "$0.800". The cap grows as the value shrinks so we don't lose
        // precision on tiny amounts. Whole-dollar amounts keep two decimal
        // places (the expected retail convention: "$1.00", not "$1").
        val pattern = when {
            abs == 0.0 -> "#,##0.00"
            abs < 0.001 -> "#,##0.######"
            abs < 0.01 -> "#,##0.####"
            abs < 1.0 -> "#,##0.###"
            abs >= 1000.0 -> "#,##0"
            else -> "#,##0.00"
        }
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        val formatter = DecimalFormat(pattern, symbols)
        val formatted = formatter.format(amount)
        return "${currency.symbol}$formatted"
    }
}
