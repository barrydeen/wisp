package cooking.zap.app.repo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [hasInAppWallet] — Note Review finding 0.6, exactly. The
 * mode-must-not-be-NONE leg is the documented footgun: FeedViewModel's
 * `activeWalletProvider` maps [WalletMode.NONE] to the NWC repo too, so
 * a stale saved `nwc_uri` would otherwise read as "has wallet".
 */
class InAppWalletCheckTest {

    private fun provider(configured: Boolean): WalletProvider = object : WalletProvider {
        override val balance: StateFlow<Long?> = MutableStateFlow(null)
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusLog: SharedFlow<String> = MutableSharedFlow()
        override val paymentReceived: SharedFlow<Long> = MutableSharedFlow()
        override val transactionsChanged: SharedFlow<Unit> = MutableSharedFlow()
        override fun hasConnection(): Boolean = configured
        override fun connect() {}
        override fun disconnect() {}
        override suspend fun fetchBalance(): Result<Long> = Result.success(0)
        override suspend fun payInvoice(bolt11: String): Result<String> =
            Result.failure(UnsupportedOperationException())
        override suspend fun makeInvoice(amountMsats: Long, description: String, expirySecs: Int): Result<String> =
            Result.failure(UnsupportedOperationException())
        override suspend fun listTransactions(limit: Int, offset: Int): Result<List<WalletTransaction>> =
            Result.success(emptyList())
    }

    @Test
    fun modeNone_isNeverAnInAppWallet_evenWithAStaleConnection() {
        // The footgun leg: NONE maps to the NWC repo in activeWalletProvider,
        // and a stale nwc_uri makes hasConnection() true.
        assertFalse(hasInAppWallet(WalletMode.NONE, provider(configured = true)))
        assertFalse(hasInAppWallet(WalletMode.NONE, provider(configured = false)))
    }

    @Test
    fun configuredNwcAndSpark_routeInApp() {
        assertTrue(hasInAppWallet(WalletMode.NWC, provider(configured = true)))
        assertTrue(hasInAppWallet(WalletMode.SPARK, provider(configured = true)))
    }

    @Test
    fun unconfiguredProvider_routesExternal() {
        assertFalse(hasInAppWallet(WalletMode.NWC, provider(configured = false)))
        assertFalse(hasInAppWallet(WalletMode.SPARK, provider(configured = false)))
    }
}
