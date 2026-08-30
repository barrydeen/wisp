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
    val isOnchain: Boolean = false
) {
    /** In-flight — unconfirmed and unsettled. */
    val pending: Boolean get() = status == TransactionStatus.PENDING

    /** Never settled: the sats were not sent and are back in the wallet. */
    val failed: Boolean get() = status == TransactionStatus.FAILED
}
