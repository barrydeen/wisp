package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Nip47
import com.wisp.app.repo.NwcRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class WalletState {
    object NotConnected : WalletState()
    object Connecting : WalletState()
    data class Connected(val balanceMsats: Long) : WalletState()
    data class Error(val message: String) : WalletState()
}

class WalletViewModel(val nwcRepo: NwcRepository) : ViewModel() {

    private val _walletState = MutableStateFlow<WalletState>(
        if (nwcRepo.hasConnection()) WalletState.Connecting else WalletState.NotConnected
    )
    val walletState: StateFlow<WalletState> = _walletState

    private val _connectionString = MutableStateFlow("")
    val connectionString: StateFlow<String> = _connectionString

    private val _generatedInvoice = MutableStateFlow<String?>(null)
    val generatedInvoice: StateFlow<String?> = _generatedInvoice

    init {
        if (nwcRepo.hasConnection()) {
            connectWallet(nwcRepo.getConnectionString() ?: "")
        }
    }

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

        _walletState.value = WalletState.Connecting
        nwcRepo.saveConnectionString(trimmed)
        nwcRepo.connect()

        viewModelScope.launch {
            // Wait for connection then fetch balance to validate
            nwcRepo.isConnected.collect { connected ->
                if (connected) {
                    refreshBalance()
                    return@collect
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
        nwcRepo.disconnect()
        nwcRepo.clearConnection()
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _generatedInvoice.value = null
    }

    fun generateInvoice(amountSats: Long, description: String) {
        viewModelScope.launch {
            val result = nwcRepo.makeInvoice(amountSats * 1000, description)
            result.fold(
                onSuccess = { invoice -> _generatedInvoice.value = invoice },
                onFailure = { _generatedInvoice.value = null }
            )
        }
    }

    fun clearInvoice() {
        _generatedInvoice.value = null
    }

    fun refreshState() {
        _generatedInvoice.value = null
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
}
