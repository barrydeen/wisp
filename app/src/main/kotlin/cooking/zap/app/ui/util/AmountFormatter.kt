package cooking.zap.app.ui.util

import cooking.zap.app.R
import android.content.Context
import cooking.zap.app.repo.ExchangeRateRepository
import cooking.zap.app.repo.FiatCurrency
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Centralized formatter for bitcoin amounts. Amounts are rendered in sats
 * everywhere; the wallet additionally offers an opt-in dollar-balance view via
 * [formatFiat], which converts sats using the cached rate in [ExchangeRateRepository].
 */
object AmountFormatter {

    /** Short form used inline (feed zap counts, etc.): "1.2k"/"3.4M". */
    fun formatShort(sats: Long, context: Context): String = formatSatsShort(sats)

    /** Full form used for balances / transactions: "1,234 sats". */
    fun formatFull(sats: Long, context: Context): String =
        context.getString(R.string.amount_sats_format, String.format(Locale.getDefault(), "%,d", sats))

    /** Raw sat rendering without the "sats" suffix (e.g. big balance number). */
    fun formatSatsOnly(sats: Long): String = String.format(Locale.getDefault(), "%,d", sats)

    /** Short sat formatter matching the prior inline helper ("1.2k", "3.4M"). */
    fun formatSatsShort(sats: Long): String = when {
        sats >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", sats / 1_000_000.0)
        sats >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", sats / 1_000.0)
        else -> sats.toString()
    }

    /**
     * Returns the fiat-formatted string for the wallet dollar-balance view, or
     * null if the exchange rate is not yet cached. Precision scales with
     * magnitude so tiny amounts don't collapse to "$0.00".
     */
    fun formatFiat(sats: Long, currencyCode: String): String? {
        val fiat = ExchangeRateRepository.satsToFiat(sats, currencyCode) ?: return null
        val currency = ExchangeRateRepository.currencyFor(currencyCode)
        return renderCurrency(fiat, currency)
    }

    private fun renderCurrency(amount: Double, currency: FiatCurrency): String {
        val abs = kotlin.math.abs(amount)
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
        return "${currency.symbol}${formatter.format(amount)}"
    }
}
