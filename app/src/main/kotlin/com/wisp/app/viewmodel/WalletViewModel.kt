package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.Nip47
import com.wisp.app.nostr.Nip57
import com.wisp.app.relay.Relay
import com.wisp.app.repo.NwcRepository
import kotlinx.coroutines.Job
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
    object Transactions : WalletPage()
}

class WalletViewModel(val nwcRepo: NwcRepository) : ViewModel() {

    private val _walletState = MutableStateFlow<WalletState>(
        if (nwcRepo.hasConnection()) WalletState.Connecting else WalletState.NotConnected
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
    private val _transactions = MutableStateFlow<List<Nip47.Transaction>>(emptyList())
    val transactions: StateFlow<List<Nip47.Transaction>> = _transactions

    private val _transactionsError = MutableStateFlow<String?>(null)
    val transactionsError: StateFlow<String?> = _transactionsError

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var connectJob: Job? = null
    private var statusCollectJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private val httpClient by lazy { Relay.createClient() }

    init {
        // Connection only happens when the wallet tab is opened (here) or
        // on-demand when sending a zap (FeedViewModel.sendZap handles that).
        if (nwcRepo.hasConnection()) {
            _connectionString.value = nwcRepo.getConnectionString() ?: ""
            connectWallet(nwcRepo.getConnectionString() ?: "")
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
        pageStack.clear()
        pageStack.add(WalletPage.Home)
        _currentPage.value = WalletPage.Home
        _sendInput.value = ""
        _sendAmount.value = ""
        _sendError.value = null
        _receiveAmount.value = ""
    }

    val isOnHome: Boolean get() = pageStack.size <= 1

    // --- Connection ---

    fun updateConnectionString(value: String) {
        _connectionString.value = value
    }

    fun connectWallet(uri: String = _connectionString.value) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return

        val parsed = Nip47.parseConnectionString(trimmed)
        if (parsed == null) {
            _walletState.value = WalletState.Error("Invalid connection string")
            return
        }

        _statusLines.value = emptyList()
        _walletState.value = WalletState.Connecting
        nwcRepo.saveConnectionString(trimmed)
        _connectionString.value = trimmed

        statusCollectJob?.cancel()
        statusCollectJob = viewModelScope.launch {
            nwcRepo.statusLog.collect { line ->
                _statusLines.value = _statusLines.value + line
            }
        }

        nwcRepo.connect()

        // Initial connection with timeout
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val connected = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                nwcRepo.isConnected.first { it }
            }
            if (connected == null && _walletState.value is WalletState.Connecting) {
                _statusLines.value = _statusLines.value + "Connection timed out (10s)"
                _walletState.value = WalletState.Error("Connection timed out")
            } else if (connected == true) {
                val result = nwcRepo.fetchBalance()
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

        // Persistent monitor: handle disconnect/reconnect after initial connection
        connectionMonitorJob?.cancel()
        connectionMonitorJob = viewModelScope.launch {
            nwcRepo.isConnected.collect { connected ->
                if (connected && _walletState.value !is WalletState.Connected) {
                    val result = nwcRepo.fetchBalance()
                    result.fold(
                        onSuccess = { balanceMsats ->
                            _walletState.value = WalletState.Connected(balanceMsats)
                        },
                        onFailure = { /* keep current state */ }
                    )
                } else if (!connected && _walletState.value is WalletState.Connected) {
                    _walletState.value = WalletState.Connecting
                }
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            val result = nwcRepo.fetchBalance()
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
        nwcRepo.disconnect()
        nwcRepo.clearConnection()
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        navigateHome()
    }

    fun refreshState() {
        if (nwcRepo.hasConnection()) {
            if (nwcRepo.isConnected.value) {
                refreshBalance()
            } else {
                _walletState.value = WalletState.Connecting
                connectWallet(nwcRepo.getConnectionString() ?: "")
            }
        } else {
            _walletState.value = WalletState.NotConnected
            _connectionString.value = ""
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
            val result = nwcRepo.payInvoice(invoice)
            result.fold(
                onSuccess = {
                    // Replace Sending page with result
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
            val result = nwcRepo.makeInvoice(amountSats * 1000, "")
            result.fold(
                onSuccess = { invoice ->
                    navigateTo(WalletPage.ReceiveInvoice(invoice, amountSats))
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
            val result = nwcRepo.listTransactions()
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
}
