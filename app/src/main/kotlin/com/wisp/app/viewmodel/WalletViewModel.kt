package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip78
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.RemoteSigner
import com.wisp.app.nostr.toHex
import android.content.ContentResolver
import com.wisp.app.relay.RelayEvent
import com.wisp.app.repo.SigningMode
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.BalanceUnit
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.SparkRepository
import com.wisp.app.repo.WalletMode
import com.wisp.app.repo.WalletModeRepository
import com.wisp.app.repo.WalletProvider
import android.util.Log
import com.wisp.app.repo.WalletTransaction
import com.wisp.app.repo.ZapSender
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

sealed class WalletState {
    object NotConnected : WalletState()
    object Connecting : WalletState()
    data class Connected(val balanceMsats: Long) : WalletState()
    data class Error(val message: String) : WalletState()
}

sealed class BackupStatus {
    object None : BackupStatus()
    object InProgress : BackupStatus()
    object Success : BackupStatus()
    data class Error(val message: String) : BackupStatus()
}

data class BackupEntry(val mnemonic: String, val walletId: String?, val createdAt: Long)

sealed class RestoreFromRelayStatus {
    object Idle : RestoreFromRelayStatus()
    object Searching : RestoreFromRelayStatus()
    data class Found(val mnemonic: String, val walletId: String?, val createdAt: Long) : RestoreFromRelayStatus()
    data class MultipleFound(val backups: List<BackupEntry>) : RestoreFromRelayStatus()
    object NotFound : RestoreFromRelayStatus()
    data class Error(val message: String) : RestoreFromRelayStatus()
}

data class RelayBackupInfo(val relayUrl: String, val hasBackup: Boolean)

sealed class DeleteBackupStatus {
    object Idle : DeleteBackupStatus()
    object InProgress : DeleteBackupStatus()
    object Success : DeleteBackupStatus()
    data class Error(val message: String) : DeleteBackupStatus()
}

sealed class AutoCheckState {
    object Idle : AutoCheckState()
    object Checking : AutoCheckState()
    data class Found(val mnemonic: String, val walletId: String?, val createdAt: Long) : AutoCheckState()
    data class MultipleFound(val backups: List<BackupEntry>) : AutoCheckState()
    object NotFound : AutoCheckState()
}

sealed class FeeState {
    object Idle : FeeState()
    object Loading : FeeState()
    data class Estimated(val feeSats: Long) : FeeState()
    object Unavailable : FeeState()
}

sealed class WalletPage {
    object Home : WalletPage()
    object ModeSelection : WalletPage()
    object NwcSetup : WalletPage()
    object SparkSetup : WalletPage()
    data class SparkBackup(val mnemonic: String) : WalletPage()
    object SendInput : WalletPage()
    object ScanQR : WalletPage()
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
    object Settings : WalletPage()
    object LightningAddressSetup : WalletPage()
    object LightningAddressQR : WalletPage()
    object DeleteWalletConfirm : WalletPage()
    object BackupToRelay : WalletPage()
    object RestoreFromRelay : WalletPage()
}

class WalletViewModel(
    val nwcRepo: NwcRepository,
    val sparkRepo: SparkRepository,
    val walletModeRepo: WalletModeRepository,
    val eventRepo: EventRepository,
    val relayPool: RelayPool,
    val keyRepo: KeyRepository,
    private val contentResolver: ContentResolver? = null
) : ViewModel() {

    private val _walletMode = MutableStateFlow(walletModeRepo.getMode())
    val walletMode: StateFlow<WalletMode> = _walletMode

    private val _balanceUnit = MutableStateFlow(walletModeRepo.getBalanceUnit())
    val balanceUnit: StateFlow<BalanceUnit> = _balanceUnit

    fun setBalanceUnit(unit: BalanceUnit) {
        walletModeRepo.setBalanceUnit(unit)
        _balanceUnit.value = unit
    }

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

    /** Incremented when new profiles arrive for transaction counterparties, to trigger UI refresh. */
    private val _profileRefreshKey = MutableStateFlow(0)
    val profileRefreshKey: StateFlow<Int> = _profileRefreshKey

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Fee estimation
    private val _feeState = MutableStateFlow<FeeState>(FeeState.Idle)
    val feeState: StateFlow<FeeState> = _feeState
    private var _preparedPaymentData: Any? = null

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private val _hasMoreTransactions = MutableStateFlow(true)
    val hasMoreTransactions: StateFlow<Boolean> = _hasMoreTransactions

    // Spark setup
    private val _restoreMnemonic = MutableStateFlow("")
    val restoreMnemonic: StateFlow<String> = _restoreMnemonic

    // Lightning address
    private val _lightningAddress = MutableStateFlow<String?>(null)
    val lightningAddress: StateFlow<String?> = _lightningAddress

    private val _lightningAddressLoading = MutableStateFlow(false)
    val lightningAddressLoading: StateFlow<Boolean> = _lightningAddressLoading

    private val _lightningAddressError = MutableStateFlow<String?>(null)
    val lightningAddressError: StateFlow<String?> = _lightningAddressError

    // Delete wallet confirmation
    private val _deleteConfirmText = MutableStateFlow("")
    val deleteConfirmText: StateFlow<String> = _deleteConfirmText

    // Lightning address availability check
    private val _addressAvailable = MutableStateFlow<Boolean?>(null)
    val addressAvailable: StateFlow<Boolean?> = _addressAvailable

    private val _addressCheckLoading = MutableStateFlow(false)
    val addressCheckLoading: StateFlow<Boolean> = _addressCheckLoading

    // Nostr bio update prompt
    private val _showBioPrompt = MutableStateFlow(false)
    val showBioPrompt: StateFlow<Boolean> = _showBioPrompt

    // Relay backup
    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.None)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus

    private val _restoreFromRelayStatus = MutableStateFlow<RestoreFromRelayStatus>(RestoreFromRelayStatus.Idle)
    val restoreFromRelayStatus: StateFlow<RestoreFromRelayStatus> = _restoreFromRelayStatus

    // Seed backup acknowledgement
    private val _seedBackupAcked = MutableStateFlow(sparkRepo.isSeedBackupAcknowledged())
    val seedBackupAcked: StateFlow<Boolean> = _seedBackupAcked

    // Auto-check for relay backup on SparkSetup entry
    private val _autoCheckState = MutableStateFlow<AutoCheckState>(AutoCheckState.Idle)
    val autoCheckState: StateFlow<AutoCheckState> = _autoCheckState

    // Per-relay backup status
    private val _relayBackupStatuses = MutableStateFlow<List<RelayBackupInfo>>(emptyList())
    val relayBackupStatuses: StateFlow<List<RelayBackupInfo>> = _relayBackupStatuses

    private val _relayBackupCheckLoading = MutableStateFlow(false)
    val relayBackupCheckLoading: StateFlow<Boolean> = _relayBackupCheckLoading

    // Delete relay backup
    private val _deleteBackupStatus = MutableStateFlow<DeleteBackupStatus>(DeleteBackupStatus.Idle)
    val deleteBackupStatus: StateFlow<DeleteBackupStatus> = _deleteBackupStatus

    // True when relay backup check completed and no relays have the backup
    private val _backupMissing = MutableStateFlow(false)
    val backupMissing: StateFlow<Boolean> = _backupMissing

    private val _registeredAddress = MutableStateFlow<String?>(null)

    private var connectJob: Job? = null
    private var statusCollectJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var syncPollJob: Job? = null
    private val httpClient get() = com.wisp.app.relay.HttpClientFactory.createRelayClient()

    init {
        // Wallet backup queries must bypass event dedup so the same event
        // from multiple relays is emitted (needed for per-relay status tracking)
        // and so events already seen earlier in the session are still returned.
        relayPool.registerDedupBypass("relay-status-")
        relayPool.registerDedupBypass("auto-check-")
        relayPool.registerDedupBypass("wallet-backup-")
        relayPool.registerDedupBypass("delete-backup-")

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

        // Auto-fetch lightning address when Spark connected
        if (mode == WalletMode.SPARK && sparkRepo.hasMnemonic()) {
            viewModelScope.launch {
                sparkRepo.isConnected.first { it }
                fetchLightningAddress()
            }
        }
    }

    // --- Navigation ---

    fun navigateTo(page: WalletPage) {
        pageStack.add(page)
        _currentPage.value = page
        // Auto-check relay backup statuses when entering Settings
        if (page is WalletPage.Settings && _walletMode.value == WalletMode.SPARK && keyRepo.isLoggedIn()) {
            checkRelayBackupStatuses()
        }
    }

    fun navigateBack(): Boolean {
        if (pageStack.size <= 1) return false
        val leaving = pageStack.removeAt(pageStack.lastIndex)
        if (leaving is WalletPage.SendConfirm) clearFeeState()
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
        _deleteConfirmText.value = ""
        _lightningAddressError.value = null
        _addressAvailable.value = null
    }

    val isOnHome: Boolean get() = pageStack.size <= 1

    // --- Wallet Mode Selection ---

    fun selectNwcMode() {
        navigateTo(WalletPage.NwcSetup)
    }

    fun selectSparkMode() {
        navigateTo(WalletPage.SparkSetup)
        // Auto-check relays for existing backup if logged in
        if (keyRepo.isLoggedIn()) {
            autoCheckRelayBackup()
        }
    }

    private fun autoCheckRelayBackup() {
        val signer = buildSigner() ?: return
        _autoCheckState.value = AutoCheckState.Checking
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                relayPool.ensureWriteRelaysConnected()
                val tConn = System.currentTimeMillis()
                Log.d("WalletBackup", "autoCheck: ensureWriteRelaysConnected took ${tConn - t0}ms")
                val pubkey = signer.pubkeyHex
                val ts = System.currentTimeMillis()
                val subId = "auto-check-$ts"
                val filter = Nip78.backupFilter(pubkey)
                val seenEventIds = mutableSetOf<String>()
                val events = mutableListOf<NostrEvent>()
                var eoseCount = 0
                var foundSparkBackup = false
                Log.d("WalletBackup", "autoCheck: connected=${relayPool.connectedCount.value} all=${relayPool.getRelayUrls().size}")
                val collectJob = launch {
                    relayPool.relayEvents.collect { relayEvent: RelayEvent ->
                        if (relayEvent.subscriptionId == subId && seenEventIds.add(relayEvent.event.id)) {
                            val dTag = Nip78.extractDTag(relayEvent.event)
                            if (dTag != null && dTag.startsWith("spark-wallet-backup")) {
                                foundSparkBackup = true
                                Log.d("WalletBackup", "autoCheck: spark backup d=$dTag from=${relayEvent.relayUrl} +${System.currentTimeMillis() - tConn}ms")
                            }
                            events.add(relayEvent.event)
                        }
                    }
                }
                val eoseJob = launch {
                    relayPool.eoseSignals.collect { id ->
                        if (id == subId) {
                            eoseCount++
                            Log.d("WalletBackup", "autoCheck: EOSE $eoseCount +${System.currentTimeMillis() - tConn}ms")
                        }
                    }
                }
                yield() // ensure collectors are subscribed before sending REQ
                val allCount = relayPool.getRelayUrls().size
                val minEose = (allCount * 2 + 2) / 3 // 2/3 majority
                relayPool.sendToAll(ClientMessage.req(subId, filter))
                Log.d("WalletBackup", "autoCheck: REQ sent to $allCount relays (need $minEose EOSE) +${System.currentTimeMillis() - tConn}ms")

                // Wait for 2/3 of relays, or early-exit if backup found + majority
                withTimeoutOrNull(10_000) {
                    while (eoseCount < allCount) {
                        delay(200)
                        if (eoseCount >= minEose && (foundSparkBackup || eoseCount >= allCount - 2)) break
                    }
                }
                val tEose = System.currentTimeMillis()
                Log.d("WalletBackup", "autoCheck: EOSE wait done $eoseCount/$allCount events=${events.size} (deduped) took ${tEose - tConn}ms (total ${tEose - t0}ms)")
                collectJob.cancel()
                eoseJob.cancel()
                relayPool.closeOnAllRelays(subId)

                // Filter to spark-wallet-backup events only, group by d-tag
                val sparkEvents = events.filter { event ->
                    val dTag = Nip78.extractDTag(event)
                    dTag != null && dTag.startsWith("spark-wallet-backup") && !Nip78.isDeletedBackup(event)
                }
                Log.d("WalletBackup", "autoCheck: ${sparkEvents.size} valid spark events out of ${events.size} total")

                val newestPerWallet = sparkEvents
                    .groupBy { Nip78.extractDTag(it) }
                    .mapValues { (_, evts) -> evts.maxByOrNull { it.created_at }!! }
                    .values.sortedByDescending { it.created_at }

                if (newestPerWallet.isEmpty()) {
                    Log.d("WalletBackup", "autoCheck: no valid spark backup (total ${System.currentTimeMillis() - t0}ms)")
                    _autoCheckState.value = AutoCheckState.NotFound
                    return@launch
                }

                val tDecrypt = System.currentTimeMillis()
                val decrypted = withContext(Dispatchers.Default) {
                    newestPerWallet.mapNotNull { event ->
                        val mnemonic = Nip78.decryptBackup(signer, event)
                        if (mnemonic != null) {
                            BackupEntry(
                                mnemonic = mnemonic,
                                walletId = Nip78.extractWalletId(event),
                                createdAt = event.created_at
                            )
                        } else {
                            Log.d("WalletBackup", "autoCheck: decrypt FAILED for d=${Nip78.extractDTag(event)}")
                            null
                        }
                    }
                }
                Log.d("WalletBackup", "autoCheck: decrypted ${decrypted.size}/${newestPerWallet.size} in ${System.currentTimeMillis() - tDecrypt}ms")

                when {
                    decrypted.isEmpty() -> {
                        _autoCheckState.value = AutoCheckState.NotFound
                    }
                    decrypted.size == 1 -> {
                        val entry = decrypted.first()
                        Log.d("WalletBackup", "autoCheck: single wallet found, ${entry.mnemonic.split(" ").size} words (total ${System.currentTimeMillis() - t0}ms)")
                        _autoCheckState.value = AutoCheckState.Found(
                            mnemonic = entry.mnemonic,
                            walletId = entry.walletId,
                            createdAt = entry.createdAt
                        )
                    }
                    else -> {
                        Log.d("WalletBackup", "autoCheck: ${decrypted.size} wallets found (total ${System.currentTimeMillis() - t0}ms)")
                        _autoCheckState.value = AutoCheckState.MultipleFound(decrypted)
                    }
                }
            } catch (_: Exception) {
                _autoCheckState.value = AutoCheckState.NotFound
            }
        }
    }

    fun restoreFromAutoCheck() {
        val state = _autoCheckState.value
        if (state is AutoCheckState.Found) {
            _autoCheckState.value = AutoCheckState.Idle
            restoreSparkWallet(state.mnemonic)
        }
    }

    fun selectAutoCheckBackup(entry: BackupEntry) {
        _autoCheckState.value = AutoCheckState.Found(
            mnemonic = entry.mnemonic,
            walletId = entry.walletId,
            createdAt = entry.createdAt
        )
    }

    fun dismissAutoCheck() {
        _autoCheckState.value = AutoCheckState.Idle
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

        // Extract lud16 from NWC URI if present
        if (parsed.lud16 != null) {
            _lightningAddress.value = parsed.lud16
        }

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
        Log.d("WalletBackup", "restoreSparkWallet: validating ${trimmed.split(" ").size} words")
        val validationError = sparkRepo.validateMnemonic(trimmed)
        if (validationError != null) {
            Log.e("WalletVM", "restoreSparkWallet: validation failed: $validationError")
            _sendError.value = validationError
            return
        }
        Log.d("WalletBackup", "restoreSparkWallet: saving and connecting")
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

        // Fetch lightning address and check relay backup once connected
        viewModelScope.launch {
            sparkRepo.isConnected.first { it }
            fetchLightningAddress()
            if (keyRepo.isLoggedIn()) {
                checkRelayBackupStatuses()
            }
        }
    }

    fun showMnemonicBackup() {
        val mnemonic = sparkRepo.getMnemonic() ?: return
        navigateTo(WalletPage.SparkBackup(mnemonic))
    }

    fun acknowledgeSeedBackup() {
        sparkRepo.setSeedBackupAcknowledged(true)
        _seedBackupAcked.value = true
        navigateBack()
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

    fun deleteWallet() {
        if (_walletMode.value == WalletMode.SPARK && _deleteConfirmText.value != "DELETE") return

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
        _lightningAddress.value = null
        _deleteConfirmText.value = ""
        navigateHome()
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
        _lightningAddress.value = null
        navigateHome()
    }

    /**
     * Suspend the active wallet connection for an account switch without clearing
     * stored credentials. The new account's wallet will be loaded by refreshState()
     * after the repo reload completes.
     */
    fun suspendForAccountSwitch() {
        connectJob?.cancel()
        statusCollectJob?.cancel()
        connectionMonitorJob?.cancel()

        when (_walletMode.value) {
            WalletMode.NWC -> nwcRepo.disconnect()
            WalletMode.SPARK -> sparkRepo.disconnect()
            WalletMode.NONE -> {}
        }

        _walletMode.value = WalletMode.NONE
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        _lightningAddress.value = null
    }

    fun updateDeleteConfirmText(value: String) {
        _deleteConfirmText.value = value
    }

    fun refreshState() {
        _walletMode.value = walletModeRepo.getMode()
        val mode = _walletMode.value
        val provider = activeProvider

        if (!provider.hasConnection()) {
            _walletState.value = WalletState.NotConnected
            return
        }

        if (provider.isConnected.value) {
            refreshBalance()
            if (mode == WalletMode.SPARK) fetchLightningAddress()
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

    // --- Lightning Address ---

    fun fetchLightningAddress() {
        if (_walletMode.value != WalletMode.SPARK) return
        viewModelScope.launch {
            _lightningAddressLoading.value = true
            val result = sparkRepo.getLightningAddress()
            result.fold(
                onSuccess = { address ->
                    _lightningAddress.value = address
                },
                onFailure = { /* silently ignore */ }
            )
            _lightningAddressLoading.value = false
        }
    }

    fun checkAddressAvailable(username: String) {
        _addressCheckLoading.value = true
        _addressAvailable.value = null
        _lightningAddressError.value = null
        viewModelScope.launch {
            val result = sparkRepo.checkLightningAddressAvailable(username)
            result.fold(
                onSuccess = { available ->
                    _addressAvailable.value = available
                    if (!available) {
                        _lightningAddressError.value = "Username is already taken"
                    }
                },
                onFailure = { e ->
                    _lightningAddressError.value = e.message ?: "Check failed"
                }
            )
            _addressCheckLoading.value = false
        }
    }

    fun registerLightningAddress(username: String) {
        _lightningAddressLoading.value = true
        _lightningAddressError.value = null
        viewModelScope.launch {
            val result = sparkRepo.registerLightningAddress(username)
            result.fold(
                onSuccess = { fullAddress ->
                    _lightningAddress.value = fullAddress
                    _registeredAddress.value = fullAddress
                    _showBioPrompt.value = true
                    _lightningAddressLoading.value = false
                },
                onFailure = { e ->
                    _lightningAddressError.value = e.message ?: "Registration failed"
                    _lightningAddressLoading.value = false
                }
            )
        }
    }

    fun dismissBioPrompt() {
        _showBioPrompt.value = false
        _registeredAddress.value = null
        navigateBack()
    }

    fun addAddressToNostrBio() {
        val address = _registeredAddress.value ?: return
        val signer = buildSigner() ?: return
        val pubkeyHex = keyRepo.getPubkeyHex() ?: return
        _showBioPrompt.value = false

        viewModelScope.launch {
            try {
                val profile = eventRepo.getProfileData(pubkeyHex)

                // Build kind 0 JSON preserving all existing fields
                val content = buildJsonObject {
                    // Preserve existing fields
                    if (profile != null) {
                        profile.name?.let { put("name", JsonPrimitive(it)) }
                        profile.displayName?.let { put("display_name", JsonPrimitive(it)) }
                        profile.about?.let { put("about", JsonPrimitive(it)) }
                        profile.picture?.let { put("picture", JsonPrimitive(it)) }
                        profile.banner?.let { put("banner", JsonPrimitive(it)) }
                        profile.nip05?.let { put("nip05", JsonPrimitive(it)) }
                    }
                    // Set the lightning address
                    put("lud16", JsonPrimitive(address))
                }.toString()

                val event = signer.signEvent(kind = 0, content = content)
                val msg = ClientMessage.event(event)
                relayPool.sendToWriteRelays(msg)
                // Update local profile cache so the UI reflects the change immediately
                eventRepo.cacheEvent(event)
            } catch (_: Exception) {
                // Silently fail — profile update is best-effort
            }

            _registeredAddress.value = null
            navigateBack()
        }
    }

    fun deleteLightningAddress() {
        _lightningAddressLoading.value = true
        _lightningAddressError.value = null
        viewModelScope.launch {
            val result = sparkRepo.deleteLightningAddress()
            result.fold(
                onSuccess = {
                    _lightningAddress.value = null
                },
                onFailure = { e ->
                    _lightningAddressError.value = e.message ?: "Failed to delete address"
                }
            )
            _lightningAddressLoading.value = false
        }
    }

    fun resetAddressSetupState() {
        _addressAvailable.value = null
        _addressCheckLoading.value = false
        _lightningAddressError.value = null
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

    fun prepareFee(invoice: String) {
        if (_walletMode.value != WalletMode.SPARK) {
            _feeState.value = FeeState.Unavailable
            return
        }
        _feeState.value = FeeState.Loading
        viewModelScope.launch {
            sparkRepo.prepareSendPayment(invoice).fold(
                onSuccess = { (feeSats, prepareData) ->
                    _preparedPaymentData = prepareData
                    _feeState.value = if (feeSats != null) FeeState.Estimated(feeSats) else FeeState.Unavailable
                },
                onFailure = {
                    _feeState.value = FeeState.Unavailable
                }
            )
        }
    }

    private fun clearFeeState() {
        _feeState.value = FeeState.Idle
        _preparedPaymentData = null
    }

    fun payInvoice(invoice: String) {
        navigateTo(WalletPage.Sending(invoice))
        viewModelScope.launch {
            val preparedData = _preparedPaymentData
            clearFeeState()

            val result = if (preparedData != null && _walletMode.value == WalletMode.SPARK) {
                sparkRepo.sendPreparedPayment(preparedData)
            } else {
                activeProvider.payInvoice(invoice)
            }

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
        _hasMoreTransactions.value = true
        viewModelScope.launch {
            // Kick off sync + zap receipt fetch in background (don't block)
            if (_walletMode.value == WalletMode.SPARK) {
                launch { sparkRepo.syncWallet() }
            }
            val zapJob = launch { fetchUserZapReceipts() }

            // Show transactions immediately using whatever zap data we already have cached
            val mapped = withContext(Dispatchers.IO) {
                val zapMaps = eventRepo.getZapReceiptCounterparties()
                val result = activeProvider.listTransactions()
                result.map { txs -> enrichTransactions(txs, zapMaps) }
            }
            mapped.fold(
                onSuccess = { txs ->
                    _transactions.value = txs
                    _hasMoreTransactions.value = txs.size >= 50
                    requestMissingProfiles(txs)
                },
                onFailure = { e -> _transactionsError.value = e.message ?: "Failed to load transactions" }
            )
            _isLoading.value = false

            // After zap receipts arrive, re-enrich to pick up newly fetched counterparties
            zapJob.join()
            val current = _transactions.value
            if (current.isNotEmpty()) {
                val reEnriched = withContext(Dispatchers.IO) {
                    val zapMaps = eventRepo.getZapReceiptCounterparties()
                    enrichTransactions(current, zapMaps)
                }
                if (reEnriched != current) {
                    _transactions.value = reEnriched
                    requestMissingProfiles(reEnriched)
                }
            }
        }
    }

    private fun enrichTransactions(
        txs: List<WalletTransaction>,
        zapMaps: EventRepository.ZapCounterpartyMaps
    ): List<WalletTransaction> {
        return txs.map { tx ->
            if (tx.counterpartyPubkey != null) tx
            else {
                val pubkey = extractCounterpartyPubkey(tx, zapMaps)
                if (pubkey != null && tx.type == "outgoing") {
                    ZapSender.persistRecipient(tx.paymentHash, pubkey)
                }
                tx.copy(counterpartyPubkey = pubkey)
            }
        }
    }

    private fun requestMissingProfiles(txs: List<WalletTransaction>) {
        val missing = txs.mapNotNull { it.counterpartyPubkey }
            .distinct()
            .filter { eventRepo.getProfileData(it) == null }
        missing.forEach { eventRepo.requestProfileIfMissing(it) }
        if (missing.isNotEmpty()) {
            viewModelScope.launch {
                delay(3_000)
                _profileRefreshKey.value++
            }
        }
    }

    fun loadMoreTransactions() {
        if (_isLoadingMore.value) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            val currentSize = _transactions.value.size
            val mapped = withContext(Dispatchers.IO) {
                val zapMaps = eventRepo.getZapReceiptCounterparties()
                val result = activeProvider.listTransactions(limit = 50, offset = currentSize)
                result.map { txs ->
                    txs.map { tx ->
                        if (tx.counterpartyPubkey != null) tx
                        else {
                            val pubkey = extractCounterpartyPubkey(tx, zapMaps)
                            if (pubkey != null && tx.type == "outgoing") {
                                ZapSender.persistRecipient(tx.paymentHash, pubkey)
                            }
                            tx.copy(counterpartyPubkey = pubkey)
                        }
                    }
                }
            }
            mapped.fold(
                onSuccess = { txs ->
                    _transactions.value = _transactions.value + txs
                    _hasMoreTransactions.value = txs.size >= 50
                    val missing = txs.mapNotNull { it.counterpartyPubkey }
                        .distinct()
                        .filter { eventRepo.getProfileData(it) == null }
                    missing.forEach { eventRepo.requestProfileIfMissing(it) }
                    if (missing.isNotEmpty()) {
                        viewModelScope.launch {
                            delay(3_000)
                            _profileRefreshKey.value++
                        }
                    }
                },
                onFailure = { /* silently ignore pagination errors */ }
            )
            _isLoadingMore.value = false
        }
    }

    /**
     * One-shot relay queries for kind 9735 zap receipts involving the current user.
     * Fetches both incoming (p tag = us) and outgoing (P tag = us) receipts.
     * Events auto-persist to ObjectBox via the normal relay event pipeline.
     */
    private suspend fun fetchUserZapReceipts() {
        val pubkey = keyRepo.getPubkeyHex() ?: run {
            Log.d("WalletBackup", "fetchUserZapReceipts: no pubkey")
            return
        }
        val ts = System.currentTimeMillis()

        // Incoming: receipts where we're the recipient (lowercase p tag)
        val inSubId = "zap-rcpt-in-$ts"
        val inFilter = Filter(kinds = listOf(9735), pTags = listOf(pubkey), limit = 1000)
        relayPool.sendToReadRelays(ClientMessage.req(inSubId, inFilter))

        // Outgoing: receipts where we're the sender (uppercase P tag)
        // Not all relays index #P, but those that do will return our outgoing zap receipts.
        val outSubId = "zap-rcpt-out-$ts"
        val outFilter = Filter(kinds = listOf(9735), bigPTags = listOf(pubkey), limit = 1000)
        relayPool.sendToReadRelays(ClientMessage.req(outSubId, outFilter))

        // Wait for EOSE from both in parallel
        coroutineScope {
            launch { withTimeoutOrNull(5_000) { relayPool.eoseSignals.first { it == inSubId } } }
            launch { withTimeoutOrNull(5_000) { relayPool.eoseSignals.first { it == outSubId } } }
        }
        relayPool.closeOnAllRelays(inSubId)
        relayPool.closeOnAllRelays(outSubId)
    }

    /**
     * Extract the counterparty pubkey from a transaction.
     * Uses persisted zap receipts (kind 9735) from ObjectBox — survives app restarts.
     * Falls back to ZapSender in-memory map and description parsing.
     */
    private fun extractCounterpartyPubkey(
        tx: WalletTransaction,
        zapMaps: EventRepository.ZapCounterpartyMaps
    ): String? {
        if (tx.type == "outgoing") {
            zapMaps.recipients[tx.paymentHash]?.let { return it }
            ZapSender.getZapRecipient(tx.paymentHash)?.let { return it }
        } else {
            zapMaps.senders[tx.paymentHash]?.let { return it }
        }

        // Fuzzy match by amount + timestamp (handles Spark wrapped invoices)
        val bucket = tx.createdAt / 60
        for (off in -5L..5L) {
            val key = "${tx.amountMsats}:${bucket + off}"
            zapMaps.byAmountTime[key]?.let { (sender, recipient) ->
                if (tx.type == "outgoing" && recipient != null) return recipient
                if (tx.type == "incoming" && sender != null) return sender
            }
        }

        // Fallback: try parsing description as kind 9734 zap request
        val desc = tx.description ?: return null
        return try {
            val event = NostrEvent.fromJson(desc)
            if (event.kind != 9734) return null
            if (tx.type == "outgoing") {
                event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            } else {
                event.pubkey
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getProfileData(pubkey: String) = eventRepo.getProfileData(pubkey)

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

    // --- Relay Backup / Restore ---

    private fun buildSigner(): NostrSigner? {
        return when (keyRepo.getSigningMode()) {
            SigningMode.LOCAL -> {
                val keypair = keyRepo.getKeypair() ?: return null
                LocalSigner(keypair.privkey, keypair.pubkey)
            }
            SigningMode.REMOTE -> {
                val pubkey = keyRepo.getPubkeyHex() ?: return null
                val pkg = keyRepo.getSignerPackage() ?: return null
                val cr = contentResolver ?: return null
                RemoteSigner(pubkey, cr, pkg)
            }
            SigningMode.READ_ONLY -> null
        }
    }

    fun backupToRelay() {
        val mnemonic = sparkRepo.getMnemonic() ?: return
        val signer = buildSigner() ?: run {
            _backupStatus.value = BackupStatus.Error("No signing key available")
            return
        }

        _backupStatus.value = BackupStatus.InProgress
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                relayPool.ensureWriteRelaysConnected()
                val tConn = System.currentTimeMillis()
                Log.d("WalletBackup", "backup: ensureWriteRelaysConnected took ${tConn - t0}ms")
                val event = withContext(Dispatchers.Default) {
                    Nip78.createBackupEvent(signer, mnemonic)
                }
                val tEncrypt = System.currentTimeMillis()
                Log.d("WalletBackup", "backup: createBackupEvent (encrypt+sign) took ${tEncrypt - tConn}ms")
                val msg = ClientMessage.event(event)
                val sent = relayPool.sendToWriteRelays(msg)
                Log.d("WalletBackup", "backup: sendToWriteRelays sent=$sent +${System.currentTimeMillis() - tEncrypt}ms (total ${System.currentTimeMillis() - t0}ms)")
                if (sent > 0) {
                    _backupStatus.value = BackupStatus.Success
                    _backupMissing.value = false
                    // Allow relays time to index the event, then refresh statuses
                    delay(3_000)
                    checkRelayBackupStatuses()
                } else {
                    _backupStatus.value = BackupStatus.Error("No relays accepted the backup")
                }
            } catch (e: Exception) {
                _backupStatus.value = BackupStatus.Error(e.message ?: "Backup failed")
            }
        }
    }

    fun resetBackupStatus() {
        _backupStatus.value = BackupStatus.None
    }

    fun searchRelayBackup() {
        val signer = buildSigner() ?: run {
            _restoreFromRelayStatus.value = RestoreFromRelayStatus.Error("No signing key available")
            return
        }

        _restoreFromRelayStatus.value = RestoreFromRelayStatus.Searching
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                relayPool.ensureWriteRelaysConnected()
                val tConn = System.currentTimeMillis()
                Log.d("WalletBackup", "search: ensureWriteRelaysConnected took ${tConn - t0}ms")
                val pubkey = signer.pubkeyHex
                val ts = System.currentTimeMillis()
                val subId = "wallet-backup-$ts"
                val filter = Nip78.backupFilter(pubkey)
                // Collect events until enough relays EOSE
                val seenEventIds = mutableSetOf<String>()
                val events = mutableListOf<NostrEvent>()
                var eoseCount = 0
                var foundSparkBackup = false
                Log.d("WalletBackup", "search: connected=${relayPool.connectedCount.value} read=${relayPool.getReadRelayUrls().size} all=${relayPool.getRelayUrls().size}")
                val collectJob = launch {
                    relayPool.relayEvents.collect { relayEvent: RelayEvent ->
                        if (relayEvent.subscriptionId == subId && seenEventIds.add(relayEvent.event.id)) {
                            val dTag = Nip78.extractDTag(relayEvent.event)
                            if (dTag != null && dTag.startsWith("spark-wallet-backup")) {
                                foundSparkBackup = true
                                Log.d("WalletBackup", "search: spark backup d=$dTag from=${relayEvent.relayUrl} +${System.currentTimeMillis() - tConn}ms")
                            }
                            events.add(relayEvent.event)
                        }
                    }
                }
                val eoseJob = launch {
                    relayPool.eoseSignals.collect { id ->
                        if (id == subId) {
                            eoseCount++
                            Log.d("WalletBackup", "search: EOSE $eoseCount +${System.currentTimeMillis() - tConn}ms")
                        }
                    }
                }
                yield() // ensure collectors are subscribed before sending REQ
                val allCount = relayPool.getRelayUrls().size
                val minEose = (allCount * 2 + 2) / 3 // 2/3 majority
                relayPool.sendToAll(ClientMessage.req(subId, filter))
                Log.d("WalletBackup", "search: REQ sent to $allCount relays (need $minEose EOSE) +${System.currentTimeMillis() - tConn}ms")

                // Wait for 2/3 of relays, or early-exit if backup found + majority
                withTimeoutOrNull(10_000) {
                    while (eoseCount < allCount) {
                        delay(200)
                        if (eoseCount >= minEose && (foundSparkBackup || eoseCount >= allCount - 2)) break
                    }
                }
                val tEose = System.currentTimeMillis()
                Log.d("WalletBackup", "search: EOSE wait done $eoseCount/$allCount events=${events.size} (deduped) took ${tEose - tConn}ms (total ${tEose - t0}ms)")
                collectJob.cancel()
                eoseJob.cancel()
                relayPool.closeOnAllRelays(subId)

                // Filter to spark-wallet-backup events only, group by d-tag (keep newest per wallet)
                val sparkEvents = events.filter { event ->
                    val dTag = Nip78.extractDTag(event)
                    dTag != null && dTag.startsWith("spark-wallet-backup") && !Nip78.isDeletedBackup(event)
                }
                Log.d("WalletBackup", "search: ${sparkEvents.size} valid spark events out of ${events.size} total")

                // Group by d-tag and keep the newest event per wallet
                val newestPerWallet = sparkEvents
                    .groupBy { Nip78.extractDTag(it) }
                    .mapValues { (_, evts) -> evts.maxByOrNull { it.created_at }!! }
                    .values.sortedByDescending { it.created_at }

                if (newestPerWallet.isEmpty()) {
                    Log.d("WalletBackup", "search: no valid spark backup (total ${System.currentTimeMillis() - t0}ms)")
                    _restoreFromRelayStatus.value = RestoreFromRelayStatus.NotFound
                    return@launch
                }

                // Decrypt all unique wallet backups
                val tDecrypt = System.currentTimeMillis()
                val decrypted = withContext(Dispatchers.Default) {
                    newestPerWallet.mapNotNull { event ->
                        val mnemonic = Nip78.decryptBackup(signer, event)
                        if (mnemonic != null) {
                            BackupEntry(
                                mnemonic = mnemonic,
                                walletId = Nip78.extractWalletId(event),
                                createdAt = event.created_at
                            )
                        } else {
                            Log.d("WalletBackup", "search: decrypt FAILED for d=${Nip78.extractDTag(event)}")
                            null
                        }
                    }
                }
                Log.d("WalletBackup", "search: decrypted ${decrypted.size}/${newestPerWallet.size} in ${System.currentTimeMillis() - tDecrypt}ms")

                when {
                    decrypted.isEmpty() -> {
                        Log.d("WalletBackup", "search: all decryptions failed (total ${System.currentTimeMillis() - t0}ms)")
                        _restoreFromRelayStatus.value = RestoreFromRelayStatus.NotFound
                    }
                    decrypted.size == 1 -> {
                        val entry = decrypted.first()
                        Log.d("WalletBackup", "search: single wallet found, ${entry.mnemonic.split(" ").size} words (total ${System.currentTimeMillis() - t0}ms)")
                        _restoreFromRelayStatus.value = RestoreFromRelayStatus.Found(
                            mnemonic = entry.mnemonic,
                            walletId = entry.walletId,
                            createdAt = entry.createdAt
                        )
                    }
                    else -> {
                        Log.d("WalletBackup", "search: ${decrypted.size} wallets found (total ${System.currentTimeMillis() - t0}ms)")
                        _restoreFromRelayStatus.value = RestoreFromRelayStatus.MultipleFound(decrypted)
                    }
                }
            } catch (e: Exception) {
                Log.e("WalletBackup", "search: error", e)
                _restoreFromRelayStatus.value = RestoreFromRelayStatus.Error(e.message ?: "Search failed")
            }
        }
    }

    fun restoreFromRelayBackup() {
        val status = _restoreFromRelayStatus.value
        Log.d("WalletBackup", "restoreFromRelayBackup: status=${status::class.simpleName}")
        if (status !is RestoreFromRelayStatus.Found) return
        Log.d("WalletBackup", "restoreFromRelayBackup: restoring wallet ${status.walletId}, ${status.mnemonic.split(" ").size} words")
        restoreSparkWallet(status.mnemonic)
        _restoreFromRelayStatus.value = RestoreFromRelayStatus.Idle
        navigateHome()
    }

    fun selectBackupToRestore(entry: BackupEntry) {
        _restoreFromRelayStatus.value = RestoreFromRelayStatus.Found(
            mnemonic = entry.mnemonic,
            walletId = entry.walletId,
            createdAt = entry.createdAt
        )
    }

    fun resetRestoreFromRelayStatus() {
        _restoreFromRelayStatus.value = RestoreFromRelayStatus.Idle
    }

    // --- Relay Backup Status & Delete ---

    fun checkRelayBackupStatuses() {
        val mnemonic = sparkRepo.getMnemonic() ?: return
        val pubkey = keyRepo.getPubkeyHex() ?: return

        _relayBackupCheckLoading.value = true
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                relayPool.ensureWriteRelaysConnected()
                val tConn = System.currentTimeMillis()
                Log.d("WalletBackup", "statusCheck: ensureWriteRelaysConnected took ${tConn - t0}ms")
                val relayUrls = relayPool.getRelayUrls()
                val filter = Nip78.backupFilterForDTag(pubkey, mnemonic)
                val ts = System.currentTimeMillis()
                val subId = "relay-status-$ts"

                // Track which relays returned a valid backup
                val relaysWithBackup = mutableSetOf<String>()
                // Track EOSE count to know when all relays have responded
                var eoseCount = 0
                val relayCount = relayUrls.size
                val minEose = (relayCount * 2 + 2) / 3 // 2/3 majority

                val collectJob = launch {
                    relayPool.relayEvents.collect { relayEvent: RelayEvent ->
                        if (relayEvent.subscriptionId == subId && !Nip78.isDeletedBackup(relayEvent.event)) {
                            Log.d("WalletBackup", "statusCheck: backup found on ${relayEvent.relayUrl} +${System.currentTimeMillis() - tConn}ms")
                            relaysWithBackup.add(relayEvent.relayUrl)
                        }
                    }
                }

                val eoseJob = launch {
                    relayPool.eoseSignals.collect { id ->
                        if (id == subId) {
                            eoseCount++
                            Log.d("WalletBackup", "statusCheck: EOSE $eoseCount/$relayCount +${System.currentTimeMillis() - tConn}ms")
                        }
                    }
                }
                yield() // ensure both collectors are subscribed before sending REQ

                relayPool.sendToAll(ClientMessage.req(subId, filter))
                Log.d("WalletBackup", "statusCheck: REQ sent to $relayCount relays (need $minEose EOSE)")

                // Wait until 2/3 of relays respond or timeout
                withTimeoutOrNull(8_000) {
                    while (eoseCount < relayCount) {
                        delay(200)
                        if (eoseCount >= minEose) break
                    }
                }
                Log.d("WalletBackup", "statusCheck: done EOSE=$eoseCount/$relayCount backupOn=${relaysWithBackup.size} relays (total ${System.currentTimeMillis() - t0}ms)")
                collectJob.cancel()
                eoseJob.cancel()
                relayPool.closeOnAllRelays(subId)

                val statuses = relayUrls.map { url ->
                    RelayBackupInfo(relayUrl = url, hasBackup = url in relaysWithBackup)
                }
                _relayBackupStatuses.value = statuses
                // Only flag backup as missing if a majority of relays responded
                val minResponses = (relayCount + 1) / 2 // at least half
                _backupMissing.value = eoseCount >= minResponses && statuses.none { it.hasBackup }
            } catch (_: Exception) {
                // Keep existing statuses on error
            }
            _relayBackupCheckLoading.value = false
        }
    }

    fun deleteRelayBackup() {
        val signer = buildSigner() ?: run {
            _deleteBackupStatus.value = DeleteBackupStatus.Error("No signing key available")
            return
        }

        _deleteBackupStatus.value = DeleteBackupStatus.InProgress
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                // Query relays for ALL backup events from this author
                val pubkey = signer.pubkeyHex
                val ts = System.currentTimeMillis()
                val subId = "delete-backup-$ts"
                val filter = Nip78.backupFilter(pubkey)

                val seenEventIds = mutableSetOf<String>()
                val events = mutableListOf<NostrEvent>()
                var eoseCount = 0
                val relayCount = relayPool.getReadRelayUrls().size
                val minEose = (relayCount * 2 + 2) / 3 // 2/3 majority
                val collectJob = launch {
                    relayPool.relayEvents.collect { relayEvent: RelayEvent ->
                        if (relayEvent.subscriptionId == subId && seenEventIds.add(relayEvent.event.id)) {
                            Log.d("WalletBackup", "delete: event d=${Nip78.extractDTag(relayEvent.event)} from=${relayEvent.relayUrl} +${System.currentTimeMillis() - t0}ms")
                            events.add(relayEvent.event)
                        }
                    }
                }
                val eoseJob = launch {
                    relayPool.eoseSignals.collect { id ->
                        if (id == subId) {
                            eoseCount++
                            Log.d("WalletBackup", "delete: EOSE $eoseCount/$relayCount +${System.currentTimeMillis() - t0}ms")
                        }
                    }
                }
                yield()
                relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
                Log.d("WalletBackup", "delete: REQ sent to $relayCount read relays (need $minEose EOSE)")

                withTimeoutOrNull(8_000) {
                    while (eoseCount < relayCount) {
                        delay(200)
                        if (eoseCount >= minEose) break
                    }
                }
                val tQuery = System.currentTimeMillis()
                Log.d("WalletBackup", "delete: query done EOSE=$eoseCount/$relayCount events=${events.size} (deduped) took ${tQuery - t0}ms")
                collectJob.cancel()
                eoseJob.cancel()
                relayPool.closeOnAllRelays(subId)

                // Tombstone every unique d-tag found (skip already-deleted ones)
                val dTags = events
                    .filter { !Nip78.isDeletedBackup(it) }
                    .mapNotNull { Nip78.extractDTag(it) }
                    .distinct()

                if (dTags.isEmpty()) {
                    Log.d("WalletBackup", "delete: no active backups to delete (total ${System.currentTimeMillis() - t0}ms)")
                    _deleteBackupStatus.value = DeleteBackupStatus.Success
                    _relayBackupStatuses.value = emptyList()
                    return@launch
                }

                var totalSent = 0
                for (dTag in dTags) {
                    val tombstone = withContext(Dispatchers.Default) {
                        Nip78.createDeleteEventForDTag(signer, dTag)
                    }
                    totalSent += relayPool.sendToAllRelays(ClientMessage.event(tombstone))
                }
                Log.d("WalletBackup", "delete: sent ${dTags.size} tombstones to $totalSent relay slots (total ${System.currentTimeMillis() - t0}ms)")

                if (totalSent > 0) {
                    _deleteBackupStatus.value = DeleteBackupStatus.Success
                    // Allow relays time to index tombstones, then refresh statuses
                    delay(3_000)
                    checkRelayBackupStatuses()
                } else {
                    _deleteBackupStatus.value = DeleteBackupStatus.Error("No relays accepted the delete")
                }
            } catch (e: Exception) {
                _deleteBackupStatus.value = DeleteBackupStatus.Error(e.message ?: "Delete failed")
            }
        }
    }

    fun resetDeleteBackupStatus() {
        _deleteBackupStatus.value = DeleteBackupStatus.Idle
    }
}
