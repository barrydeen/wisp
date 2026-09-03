package com.wisp.app.repo

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a payment actually settled, or is still in flight.
 *
 * Reporting an in-flight payment as settled tells the user sats left their
 * wallet when they may still return.
 */
enum class PaymentSettlement { COMPLETED, PENDING }

/**
 * Outcome of a successful `pay_invoice` call. [reference] is the preimage for
 * NWC (which only exists once settled) or the payment id for Spark.
 */
data class WalletPayment(
    val reference: String,
    val settlement: PaymentSettlement
)

/**
 * Where a transaction actually ended up.
 *
 * [WalletTransaction] used to carry only `pending`, which cannot express
 * failure: a FAILED payment arrived as `pending = false` and every binary
 * `if (pending) ... else "Completed"` in the UI reported it as completed.
 */
enum class TransactionStatus { PENDING, COMPLETED, FAILED }

interface WalletProvider {
    val balance: StateFlow<Long?>
    val isConnected: StateFlow<Boolean>
    val statusLog: SharedFlow<String>

    /** Emits the amount in msats whenever an incoming payment is received. */
    val paymentReceived: SharedFlow<Long>

    /** Emits Unit whenever the transaction list should be refreshed. */
    val transactionsChanged: SharedFlow<Unit>

    fun hasConnection(): Boolean
    fun connect()
    fun disconnect()
    suspend fun fetchBalance(): Result<Long>
    /**
     * Pay a BOLT11 invoice. A returned [Result.success] means the wallet
     * accepted it — check [WalletPayment.settlement] before telling the user
     * it landed.
     */
    suspend fun payInvoice(bolt11: String): Result<WalletPayment>
    suspend fun makeInvoice(amountMsats: Long, description: String, expirySecs: Int = 3600): Result<String>
    suspend fun listTransactions(limit: Int = 50, offset: Int = 0): Result<List<WalletTransaction>>
}

data class WalletTransaction(
    val type: String,
    val description: String?,
    val paymentHash: String,
    val amountMsats: Long,
    val feeMsats: Long = 0,
    val createdAt: Long,
    val settledAt: Long?,
    /** Pubkey of the counterparty (recipient for outgoing, sender for incoming zaps). */
    val counterpartyPubkey: String? = null,
    val status: TransactionStatus = TransactionStatus.COMPLETED,
    val isOnchain: Boolean = false,
    /**
     * Ticker of the asset this row moved, when it wasn't bitcoin - e.g. "USDB".
     * Null for every sats payment, which is the overwhelming majority.
     *
     * Spark wallets can hold tokens alongside sats, and Payment.amount is
     * documented as "satoshis OR token base units". This app offers no token
     * conversion, but a wallet restored from the same seed in an app that does
     * hands us those payments anyway, and reading their base units as sats
     * turns a 15.77 USDB transfer into "15,766,673 sats".
     *
     * When this is set, amountMsats / feeMsats are zero: there is no honest
     * sats value for a token transfer, so nothing sats-denominated - including
     * fiat conversion - may be derived from this row.
     */
    val assetTicker: String? = null,
    /**
     * Amount already scaled by the token's decimals, at full precision. Kept as
     * a String because token base units are u128 and overflow Long.
     */
    val assetAmount: String? = null,
    /** Fee in the same asset, scaled the same way. Null when the fee is zero. */
    val assetFee: String? = null,
    /**
     * Ticker of the asset this payment was converted FROM, when it was one leg
     * of a conversion - "BTC" for sats into a stablecoin, "USDB" for the way
     * back. Null for an ordinary payment.
     *
     * A conversion is not income or a spend: nothing entered or left the
     * wallet, it changed shape inside it. Labelling one "Received" - which is
     * what the bare row did - reads as money arriving from someone.
     */
    val conversionFromAsset: String? = null
) {
    /** True when this row moved something other than bitcoin. */
    val isTokenTransfer: Boolean get() = assetTicker != null

    val isConversion: Boolean get() = conversionFromAsset != null

    /**
     * Row label for a conversion. Bitcoin reads lowercase - in this sentence
     * it's the asset, not a ticker symbol.
     */
    val conversionLabel: String? get() = conversionFromAsset?.let { from ->
        val name = when (from.uppercase()) {
            "BTC", "SATS", "SAT" -> "bitcoin"
            else -> from
        }
        "Converted from $name"
    }

    /** Row-sized amount: two decimal places, full precision kept in [assetAmount]. */
    val assetAmountCompact: String? get() = assetAmount?.let { TokenAmounts.compact(it) }
    /** In-flight — unconfirmed and unsettled. */
    val pending: Boolean get() = status == TransactionStatus.PENDING

    /** Never settled: the sats were not sent and are back in the wallet. */
    val failed: Boolean get() = status == TransactionStatus.FAILED
}

/**
 * Formatting for non-bitcoin assets that reach the wallet from another app
 * sharing the same seed. Pure string / BigDecimal math so it is unit testable
 * without the SDK.
 */
object TokenAmounts {
    /**
     * Shift a token's base-unit amount by its decimal places.
     *
     * Operates on the decimal string rather than a numeric type on purpose:
     * base units are u128 and overflow Long, and Double loses cents well
     * before that. Trailing zeros are dropped so a whole amount reads "150"
     * rather than "150.000000".
     */
    fun scale(baseUnits: String, decimals: Int): String {
        val digits = baseUnits.trim()
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return baseUnits
        if (decimals <= 0) return digits

        val padded = if (digits.length > decimals) digits
                     else "0".repeat(decimals - digits.length + 1) + digits
        val whole = padded.substring(0, padded.length - decimals)
        val fraction = padded.substring(padded.length - decimals).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /**
     * Round a scaled amount to two places for the transaction row. USDB is
     * dollars, and six places is both unreadable at a glance and wrong for
     * what the number means; the detail row keeps full precision.
     *
     * Dust that would round away to "0.00" keeps its full precision instead -
     * that would otherwise read as nothing having arrived.
     */
    fun compact(scaled: String, places: Int = 2): String {
        if (!scaled.contains('.')) return scaled
        val value = scaled.toBigDecimalOrNull() ?: return scaled
        val rounded = value.setScale(places, java.math.RoundingMode.HALF_UP)
        if (rounded.signum() == 0 && value.signum() != 0) return scaled
        return java.text.DecimalFormat("#,##0.00").format(rounded)
    }
}
