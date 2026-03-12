package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.Nip57
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.SparkRepository
import com.wisp.app.repo.WalletMode
import com.wisp.app.repo.WalletModeRepository
import com.wisp.app.repo.WalletProvider
import com.wisp.app.repo.WalletTransaction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class WalletState {
    object NotConnected : WalletState()
    object Connecting : WalletState()
    data class Connected(val balanceMsats: Long) : WalletState()
    data class Error(val message: String) : WalletState()
}

sealed class WalletPage {
    object Home : WalletPage()
    object ModeSelection : WalletPage()
    object NwcSetup : WalletPage()
    object SparkSetup : WalletPage()
    data class SparkBackup(val mnemonic: String) : WalletPage()
    object SendInput : WalletPage()
    data class SendAmount(val address: String) : WalletPage()
    data class SendConfirm(
        val invoice: String,
        val amountSats: Long?,
        val paymentHash: String?,
        val description: String?
    ) : WalletPage()
    data class Sending(val invoice: String) : WalletPage()
    data class SendResult(val success: Boolean, val message: String) : WalletPage()
    object ReceiveAmount : WalletPage()
    data class ReceiveInvoice(val invoice: String, val amountSats: Long) : WalletPage()
    data class ReceiveSuccess(val amountSats: Long) : WalletPage()
    object Transactions : WalletPage()
}

class WalletViewModel(
    val nwcRepo: NwcRepository,
    val sparkRepo: SparkRepository,
    val walletModeRepo: WalletModeRepository
) : ViewModel() {

    private val _walletMode = MutableStateFlow(walletModeRepo.getMode())
    val walletMode: StateFlow<WalletMode> = _walletMode

    private val activeProvider: WalletProvider
        get() = when (_walletMode.value) {
            WalletMode.SPARK -> sparkRepo
            else -> nwcRepo
        }

    private val _walletState = MutableStateFlow<WalletState>(
        if (activeProvider.hasConnection()) WalletState.Connecting else WalletState.NotConnected
    )
    val walletState: StateFlow<WalletState> = _walletState

    private val _connectionString = MutableStateFlow("")
    val connectionString: StateFlow<String> = _connectionString

    /** Live status lines emitted during connection */
    private val _statusLines = MutableStateFlow<List<String>>(emptyList())
    val statusLines: StateFlow<List<String>> = _statusLines

    // Page navigation
    private val pageStack = mutableListOf<WalletPage>(WalletPage.Home)
    private val _currentPage = MutableStateFlow<WalletPage>(WalletPage.Home)
    val currentPage: StateFlow<WalletPage> = _currentPage

    // Send flow
    private val _sendInput = MutableStateFlow("")
    val sendInput: StateFlow<String> = _sendInput

    private val _sendAmount = MutableStateFlow("")
    val sendAmount: StateFlow<String> = _sendAmount

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    // Receive flow
    private val _receiveAmount = MutableStateFlow("")
    val receiveAmount: StateFlow<String> = _receiveAmount

    // Transactions
    private val _transactions = MutableStateFlow<List<WalletTransaction>>(emptyList())
    val transactions: StateFlow<List<WalletTransaction>> = _transactions

    private val _transactionsError = MutableStateFlow<String?>(null)
    val transactionsError: StateFlow<String?> = _transactionsError

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Spark setup
    private val _restoreMnemonic = MutableStateFlow("")
    val restoreMnemonic: StateFlow<String> = _restoreMnemonic

    private var connectJob: Job? = null
    private var statusCollectJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var syncPollJob: Job? = null
    private val httpClient get() = com.wisp.app.relay.HttpClientFactory.createRelayClient()

    init {
        val mode = walletModeRepo.getMode()
        when (mode) {
            WalletMode.NWC -> {
                if (nwcRepo.hasConnection()) {
                    _connectionString.value = nwcRepo.getConnectionString() ?: ""
                    connectNwcWallet(nwcRepo.getConnectionString() ?: "")
                }
            }
            WalletMode.SPARK -> {
                if (sparkRepo.hasMnemonic()) {
                    connectSparkWallet()
                }
            }
            WalletMode.NONE -> {}
        }

        // Auto-navigate to success screen when an incoming payment is received
        viewModelScope.launch {
            sparkRepo.paymentReceived.collect { amountMsats ->
                if (_currentPage.value is WalletPage.ReceiveInvoice) {
                    stopSyncPolling()
                    val amountSats = amountMsats / 1000
                    pageStack.removeAt(pageStack.lastIndex)
                    val successPage = WalletPage.ReceiveSuccess(amountSats)
                    pageStack.add(successPage)
                    _currentPage.value = successPage
                    refreshBalance()
                }
            }
        }
        viewModelScope.launch {
            nwcRepo.paymentReceived.collect { amountMsats ->
                if (_currentPage.value is WalletPage.ReceiveInvoice) {
                    val amountSats = amountMsats / 1000
                    pageStack.removeAt(pageStack.lastIndex)
                    val successPage = WalletPage.ReceiveSuccess(amountSats)
                    pageStack.add(successPage)
                    _currentPage.value = successPage
                    refreshBalance()
                }
            }
        }
    }

    // --- Navigation ---

    fun navigateTo(page: WalletPage) {
        pageStack.add(page)
        _currentPage.value = page
    }

    fun navigateBack(): Boolean {
        if (pageStack.size <= 1) return false
        pageStack.removeAt(pageStack.lastIndex)
        _currentPage.value = pageStack.last()
        return true
    }

    fun navigateHome() {
        stopSyncPolling()
        pageStack.clear()
        pageStack.add(WalletPage.Home)
        _currentPage.value = WalletPage.Home
        _sendInput.value = ""
        _sendAmount.value = ""
        _sendError.value = null
        _receiveAmount.value = ""
        _restoreMnemonic.value = ""
    }

    val isOnHome: Boolean get() = pageStack.size <= 1

    // --- Wallet Mode Selection ---

    fun selectNwcMode() {
        navigateTo(WalletPage.NwcSetup)
    }

    fun selectSparkMode() {
        navigateTo(WalletPage.SparkSetup)
    }

    // --- NWC Connection ---

    fun updateConnectionString(value: String) {
        _connectionString.value = value
    }

    fun connectNwcWallet(uri: String = _connectionString.value, silent: Boolean = false) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return

        val parsed = com.wisp.app.nostr.Nip47.parseConnectionString(trimmed)
        if (parsed == null) {
            _walletState.value = WalletState.Error("Invalid connection string")
            return
        }

        walletModeRepo.setMode(WalletMode.NWC)
        _walletMode.value = WalletMode.NWC

        _statusLines.value = emptyList()
        if (!silent) _walletState.value = WalletState.Connecting
        nwcRepo.saveConnectionString(trimmed)
        _connectionString.value = trimmed

        startStatusCollection(nwcRepo)
        nwcRepo.connect()
        startConnectionMonitor(nwcRepo)
    }

    // --- Spark Connection ---

    fun generateSparkWallet() {
        val mnemonic = sparkRepo.newMnemonic()
        sparkRepo.saveMnemonic(mnemonic)
        connectSparkWallet()
    }

    fun updateRestoreMnemonic(value: String) {
        _restoreMnemonic.value = value
    }

    fun restoreSparkWallet(mnemonic: String = _restoreMnemonic.value) {
        val trimmed = mnemonic.trim().lowercase()
        val words = trimmed.split("\\s+".toRegex())
        if (words.size != 12 && words.size != 24) {
            _sendError.value = "Mnemonic must be 12 or 24 words"
            return
        }
        sparkRepo.saveMnemonic(trimmed)
        connectSparkWallet()
    }

    fun confirmSparkBackup() {
        connectSparkWallet()
    }

    private fun connectSparkWallet(silent: Boolean = false) {
        walletModeRepo.setMode(WalletMode.SPARK)
        _walletMode.value = WalletMode.SPARK

        _statusLines.value = emptyList()
        if (!silent) _walletState.value = WalletState.Connecting

        startStatusCollection(sparkRepo)
        sparkRepo.connect()
        startConnectionMonitor(sparkRepo)
    }

    fun showMnemonicBackup() {
        val mnemonic = sparkRepo.getMnemonic() ?: return
        navigateTo(WalletPage.SparkBackup(mnemonic))
    }

    // --- Shared connection helpers ---

    private fun startStatusCollection(provider: WalletProvider) {
        statusCollectJob?.cancel()
        statusCollectJob = viewModelScope.launch {
            provider.statusLog.collect { line ->
                _statusLines.value = _statusLines.value + line
            }
        }
    }

    private fun startConnectionMonitor(provider: WalletProvider) {
        connectJob?.cancel()
        val timeoutMs = if (_walletMode.value == WalletMode.SPARK) 60_000L else 20_000L
        connectJob = viewModelScope.launch {
            val connected = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                provider.isConnected.first { it }
            }
            if (connected == null && _walletState.value !is WalletState.Connected) {
                _statusLines.value = _statusLines.value + "Connection timed out"
                _walletState.value = WalletState.Error("Connection timed out")
            }
        }

        connectionMonitorJob?.cancel()
        connectionMonitorJob = viewModelScope.launch {
            provider.isConnected.collect { connected ->
                if (connected) {
                    val result = provider.fetchBalance()
                    result.fold(
                        onSuccess = { balanceMsats ->
                            _walletState.value = WalletState.Connected(balanceMsats)
                        },
                        onFailure = { e ->
                            _walletState.value = WalletState.Error(e.message ?: "Failed to fetch balance")
                        }
                    )
                }
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            val result = activeProvider.fetchBalance()
            result.fold(
                onSuccess = { balanceMsats ->
                    _walletState.value = WalletState.Connected(balanceMsats)
                },
                onFailure = { e ->
                    _walletState.value = WalletState.Error(e.message ?: "Failed to fetch balance")
                }
            )
        }
    }

    fun disconnectWallet() {
        connectJob?.cancel()
        statusCollectJob?.cancel()
        connectionMonitorJob?.cancel()

        when (_walletMode.value) {
            WalletMode.NWC -> {
                nwcRepo.disconnect()
                nwcRepo.clearConnection()
            }
            WalletMode.SPARK -> {
                sparkRepo.disconnect()
                sparkRepo.clearMnemonic()
            }
            WalletMode.NONE -> {}
        }

        walletModeRepo.setMode(WalletMode.NONE)
        _walletMode.value = WalletMode.NONE
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        navigateHome()
    }

    fun refreshState() {
        val mode = _walletMode.value
        val provider = activeProvider

        if (!provider.hasConnection()) {
            _walletState.value = WalletState.NotConnected
            return
        }

        if (provider.isConnected.value) {
            refreshBalance()
        } else if (_walletState.value is WalletState.Connected) {
            // Was previously connected — reconnect silently
            when (mode) {
                WalletMode.NWC -> connectNwcWallet(nwcRepo.getConnectionString() ?: "", silent = true)
                WalletMode.SPARK -> connectSparkWallet(silent = true)
                WalletMode.NONE -> {}
            }
        } else {
            when (mode) {
                WalletMode.NWC -> connectNwcWallet(nwcRepo.getConnectionString() ?: "")
                WalletMode.SPARK -> connectSparkWallet()
                WalletMode.NONE -> {}
            }
        }
    }

    // --- Send flow ---

    fun updateSendInput(value: String) {
        _sendInput.value = value
        _sendError.value = null
    }

    fun updateSendAmount(digit: Char) {
        val current = _sendAmount.value
        if (current.length < 10) {
            _sendAmount.value = current + digit
        }
    }

    fun sendAmountBackspace() {
        val current = _sendAmount.value
        if (current.isNotEmpty()) {
            _sendAmount.value = current.dropLast(1)
        }
    }

    fun processInput(input: String = _sendInput.value) {
        val trimmed = input.trim().removePrefix("lightning:")
        _sendError.value = null

        when {
            trimmed.lowercase().startsWith("lnbc") -> {
                val decoded = Bolt11.decode(trimmed)
                if (decoded == null) {
                    _sendError.value = "Invalid BOLT11 invoice"
                    return
                }
                if (decoded.isExpired()) {
                    _sendError.value = "Invoice has expired"
                    return
                }
                navigateTo(WalletPage.SendConfirm(
                    invoice = trimmed,
                    amountSats = decoded.amountSats,
                    paymentHash = decoded.paymentHash,
                    description = decoded.description
                ))
            }
            trimmed.contains("@") && trimmed.contains(".") -> {
                _sendAmount.value = ""
                navigateTo(WalletPage.SendAmount(trimmed))
            }
            else -> {
                _sendError.value = "Enter a lightning address (user@domain) or BOLT11 invoice"
            }
        }
    }

    fun resolveLightningAddress(address: String, amountSats: Long) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val payInfo = Nip57.resolveLud16(address, httpClient)
                if (payInfo == null) {
                    _sendError.value = "Could not resolve lightning address"
                    _isLoading.value = false
                    return@launch
                }

                val amountMsats = amountSats * 1000
                if (amountMsats < payInfo.minSendable || amountMsats > payInfo.maxSendable) {
                    _sendError.value = "Amount out of range: ${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sats"
                    _isLoading.value = false
                    return@launch
                }

                val invoice = Nip57.fetchSimpleInvoice(payInfo.callback, amountMsats, httpClient)
                if (invoice == null) {
                    _sendError.value = "Failed to fetch invoice"
                    _isLoading.value = false
                    return@launch
                }

                val decoded = Bolt11.decode(invoice)
                navigateTo(WalletPage.SendConfirm(
                    invoice = invoice,
                    amountSats = decoded?.amountSats ?: amountSats,
                    paymentHash = decoded?.paymentHash,
                    description = decoded?.description ?: address
                ))
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Failed to resolve address"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun payInvoice(invoice: String) {
        navigateTo(WalletPage.Sending(invoice))
        viewModelScope.launch {
            val result = activeProvider.payInvoice(invoice)
            result.fold(
                onSuccess = {
                    pageStack.removeAt(pageStack.lastIndex)
                    val resultPage = WalletPage.SendResult(true, "Payment sent!")
                    pageStack.add(resultPage)
                    _currentPage.value = resultPage
                    refreshBalance()
                },
                onFailure = { e ->
                    pageStack.removeAt(pageStack.lastIndex)
                    val resultPage = WalletPage.SendResult(false, e.message ?: "Payment failed")
                    pageStack.add(resultPage)
                    _currentPage.value = resultPage
                }
            )
        }
    }

    // --- Receive flow ---

    fun updateReceiveAmount(digit: Char) {
        val current = _receiveAmount.value
        if (current.length < 10) {
            _receiveAmount.value = current + digit
        }
    }

    fun receiveAmountBackspace() {
        val current = _receiveAmount.value
        if (current.isNotEmpty()) {
            _receiveAmount.value = current.dropLast(1)
        }
    }

    fun generateInvoice(amountSats: Long) {
        _isLoading.value = true
        viewModelScope.launch {
            val result = activeProvider.makeInvoice(amountSats * 1000, "")
            result.fold(
                onSuccess = { invoice ->
                    navigateTo(WalletPage.ReceiveInvoice(invoice, amountSats))
                    startSyncPolling()
                },
                onFailure = { e ->
                    _sendError.value = e.message ?: "Failed to create invoice"
                }
            )
            _isLoading.value = false
        }
    }

    // --- Transactions ---

    fun loadTransactions() {
        _isLoading.value = true
        _transactionsError.value = null
        viewModelScope.launch {
            val result = activeProvider.listTransactions()
            result.fold(
                onSuccess = { txs ->
                    _transactions.value = txs
                },
                onFailure = { e ->
                    _transactionsError.value = e.message ?: "Failed to load transactions"
                }
            )
            _isLoading.value = false
        }
    }

    // --- Sync polling for receive ---

    private fun startSyncPolling() {
        syncPollJob?.cancel()
        if (_walletMode.value != WalletMode.SPARK) return
        syncPollJob = viewModelScope.launch {
            while (_currentPage.value is WalletPage.ReceiveInvoice) {
                sparkRepo.syncWallet()
                delay(3_000)
            }
        }
    }

    private fun stopSyncPolling() {
        syncPollJob?.cancel()
        syncPollJob = null
    }
}
