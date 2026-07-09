package cooking.zap.app.repo

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the account has a usable in-app wallet, per Note Review
 * finding 0.6 — the exact analog of web's wallet kinds 3 (NWC) /
 * 4 (Spark) routing. The [mode] check is MANDATORY: FeedViewModel's
 * `activeWalletProvider` maps [WalletMode.NONE] to the NWC repo too, so
 * a stale saved `nwc_uri` could otherwise read as "has wallet".
 * [WalletProvider.hasConnection] is configured-ness (saved URI /
 * mnemonic), deliberately not the live `isConnected` socket state — a
 * configured-but-idle wallet still routes in-app; `payInvoice` connects
 * on demand.
 */
fun hasInAppWallet(mode: WalletMode, provider: WalletProvider): Boolean =
    mode != WalletMode.NONE && provider.hasConnection()

interface WalletProvider {
    val balance: StateFlow<Long?>
    val isConnected: StateFlow<Boolean>
    val statusLog: SharedFlow<String>

    /** Emits the amount in msats whenever an incoming payment is received. */
    val paymentReceived: SharedFlow<Long>

    /**
     * Emits whenever the transaction set may have changed (e.g. a payment
     * went pending or settled, a deposit was claimed) so the UI can reload.
     */
    val transactionsChanged: SharedFlow<Unit>

    fun hasConnection(): Boolean
    fun connect()
    fun disconnect()
    suspend fun fetchBalance(): Result<Long>
    suspend fun payInvoice(bolt11: String): Result<String>
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
    /** True while the payment is unconfirmed/unsettled (e.g. an in-flight Lightning payment). */
    val pending: Boolean = false,
    /** True for on-chain Bitcoin deposits/withdrawals (vs Lightning). */
    val isOnchain: Boolean = false
)
