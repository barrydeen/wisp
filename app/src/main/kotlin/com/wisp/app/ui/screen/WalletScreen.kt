package com.wisp.app.ui.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wisp.app.BuildConfig
import com.wisp.app.R
import com.wisp.app.repo.WalletMode
import com.wisp.app.repo.WalletTransaction
import com.wisp.app.ui.component.SatsNumpad
import com.wisp.app.viewmodel.AutoCheckState
import com.wisp.app.viewmodel.FeeState
import com.wisp.app.viewmodel.BackupStatus
import com.wisp.app.viewmodel.DeleteBackupStatus
import com.wisp.app.viewmodel.RelayBackupInfo
import com.wisp.app.viewmodel.BackupEntry
import com.wisp.app.viewmodel.RestoreFromRelayStatus
import com.wisp.app.viewmodel.WalletPage
import com.wisp.app.viewmodel.WalletState
import com.wisp.app.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit
) {
    val walletState by viewModel.walletState.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()

    // Always refresh wallet state when this screen appears
    LaunchedEffect(Unit) {
        viewModel.refreshState()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_wallet)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.navigateBack()) {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when (walletState) {
            is WalletState.NotConnected,
            is WalletState.Connecting,
            is WalletState.Error -> {
                // RestoreFromRelay has its own verticalScroll, so render it outside the scrolling Column
                if (currentPage is WalletPage.RestoreFromRelay) {
                    RestoreFromRelayContent(
                        status = viewModel.restoreFromRelayStatus.collectAsState().value,
                        onSearch = { viewModel.searchRelayBackup() },
                        onRestore = { viewModel.restoreFromRelayBackup() },
                        onSelectBackup = { viewModel.selectBackupToRestore(it) },
                        onCancel = {
                            viewModel.resetRestoreFromRelayStatus()
                            viewModel.navigateBack()
                        },
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (currentPage) {
                            is WalletPage.NwcSetup -> WalletConnectionContent(
                                walletState = walletState,
                                connectionString = viewModel.connectionString.collectAsState().value,
                                statusLines = viewModel.statusLines.collectAsState().value,
                                onConnectionStringChange = { viewModel.updateConnectionString(it) },
                                onConnect = { viewModel.connectNwcWallet() },
                                onDisconnect = { viewModel.disconnectWallet() }
                            )
                            is WalletPage.SparkSetup -> SparkSetupContent(
                                walletState = walletState,
                                statusLines = viewModel.statusLines.collectAsState().value,
                                restoreMnemonic = viewModel.restoreMnemonic.collectAsState().value,
                                error = viewModel.sendError.collectAsState().value,
                                autoCheckState = viewModel.autoCheckState.collectAsState().value,
                                onCreateWallet = { viewModel.generateSparkWallet() },
                                onRestoreMnemonicChange = { viewModel.updateRestoreMnemonic(it) },
                                onRestoreWallet = { viewModel.restoreSparkWallet() },
                                onRestoreFromRelay = {
                                    viewModel.resetRestoreFromRelayStatus()
                                    viewModel.navigateTo(WalletPage.RestoreFromRelay)
                                },
                                onRestoreFromAutoCheck = { viewModel.restoreFromAutoCheck() },
                                onSelectAutoCheckBackup = { viewModel.selectAutoCheckBackup(it) },
                                onDismissAutoCheck = { viewModel.dismissAutoCheck() },
                                isLoggedIn = viewModel.keyRepo.isLoggedIn(),
                                onDisconnect = { viewModel.disconnectWallet() }
                            )
                            is WalletPage.SparkBackup -> {
                                val page = currentPage as WalletPage.SparkBackup
                                SparkBackupContent(
                                    mnemonic = page.mnemonic,
                                    onConfirm = { viewModel.confirmSparkBackup() }
                                )
                            }
                            else -> WalletModeSelectionContent(
                                onSelectNwc = { viewModel.selectNwcMode() },
                                onSelectSpark = { viewModel.selectSparkMode() }
                            )
                        }
                    }
                }
            }
            is WalletState.Connected -> {
                val balanceMsats = (walletState as WalletState.Connected).balanceMsats
                when (currentPage) {
                    is WalletPage.SparkBackup -> {
                        val page = currentPage as WalletPage.SparkBackup
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            SparkBackupContent(
                                mnemonic = page.mnemonic,
                                onConfirm = { viewModel.acknowledgeSeedBackup() }
                            )
                        }
                    }
                    is WalletPage.Home -> WalletHomeContent(
                        balanceMsats = balanceMsats,
                        walletMode = viewModel.walletMode.collectAsState().value,
                        showSettingsAlert = viewModel.walletMode.collectAsState().value == WalletMode.SPARK
                                && !viewModel.seedBackupAcked.collectAsState().value,
                        onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                        onReceive = {
                            viewModel.navigateTo(WalletPage.ReceiveAmount)
                        },
                        onTransactions = {
                            viewModel.loadTransactions()
                            viewModel.navigateTo(WalletPage.Transactions)
                        },
                        onRefresh = { viewModel.refreshBalance() },
                        onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                        lightningAddress = viewModel.lightningAddress.collectAsState().value,
                        onSetupAddress = {
                            viewModel.resetAddressSetupState()
                            viewModel.navigateTo(WalletPage.LightningAddressSetup)
                        },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.SendInput -> SendInputContent(
                        input = viewModel.sendInput.collectAsState().value,
                        error = viewModel.sendError.collectAsState().value,
                        onInputChange = { viewModel.updateSendInput(it) },
                        onNext = { viewModel.processInput() },
                        onScanQR = { viewModel.navigateTo(WalletPage.ScanQR) },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.ScanQR -> ScanQRContent(
                        onResult = { scanned ->
                            viewModel.updateSendInput(scanned)
                            viewModel.navigateTo(WalletPage.SendInput)
                        },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.SendAmount -> {
                        val page = currentPage as WalletPage.SendAmount
                        SendAmountContent(
                            address = page.address,
                            amount = viewModel.sendAmount.collectAsState().value,
                            error = viewModel.sendError.collectAsState().value,
                            isLoading = viewModel.isLoading.collectAsState().value,
                            onDigit = { viewModel.updateSendAmount(it) },
                            onBackspace = { viewModel.sendAmountBackspace() },
                            onConfirm = {
                                val sats = viewModel.sendAmount.value.toLongOrNull() ?: return@SendAmountContent
                                viewModel.resolveLightningAddress(page.address, sats)
                            },
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.SendConfirm -> {
                        val page = currentPage as WalletPage.SendConfirm
                        val feeState by viewModel.feeState.collectAsState()
                        val walletMode by viewModel.walletMode.collectAsState()
                        LaunchedEffect(page.invoice) {
                            viewModel.prepareFee(page.invoice)
                        }
                        SendConfirmContent(
                            invoice = page.invoice,
                            amountSats = page.amountSats,
                            description = page.description,
                            feeState = feeState,
                            networkName = when (walletMode) {
                                WalletMode.SPARK -> "Spark"
                                WalletMode.NWC -> "NWC"
                                else -> "Unknown"
                            },
                            onPay = { viewModel.payInvoice(page.invoice) },
                            onCancel = { viewModel.navigateBack() },
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.Sending -> SendingContent(
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.SendResult -> {
                        val page = currentPage as WalletPage.SendResult
                        SendResultContent(
                            success = page.success,
                            message = page.message,
                            onDone = { viewModel.navigateHome() },
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.ReceiveAmount -> ReceiveAmountContent(
                        amount = viewModel.receiveAmount.collectAsState().value,
                        isLoading = viewModel.isLoading.collectAsState().value,
                        onDigit = { viewModel.updateReceiveAmount(it) },
                        onBackspace = { viewModel.receiveAmountBackspace() },
                        onConfirm = {
                            val sats = viewModel.receiveAmount.value.toLongOrNull() ?: return@ReceiveAmountContent
                            viewModel.generateInvoice(sats)
                        },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.ReceiveInvoice -> {
                        val page = currentPage as WalletPage.ReceiveInvoice
                        ReceiveInvoiceContent(
                            invoice = page.invoice,
                            amountSats = page.amountSats,
                            onDone = { viewModel.navigateHome() },
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.ReceiveSuccess -> {
                        val page = currentPage as WalletPage.ReceiveSuccess
                        ReceiveSuccessContent(
                            amountSats = page.amountSats,
                            onDone = { viewModel.navigateHome() },
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.Transactions -> {
                        // Observe profile refresh key so UI recomposes when new profiles arrive
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        TransactionHistoryContent(
                            transactions = viewModel.transactions.collectAsState().value,
                            error = viewModel.transactionsError.collectAsState().value,
                            isLoading = viewModel.isLoading.collectAsState().value,
                            isLoadingMore = viewModel.isLoadingMore.collectAsState().value,
                            hasMore = viewModel.hasMoreTransactions.collectAsState().value,
                            onLoadMore = { viewModel.loadMoreTransactions() },
                            profileLookup = { viewModel.getProfileData(it) },
                            profileRefreshKey = profileKey,
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.Settings -> WalletSettingsContent(
                        walletMode = viewModel.walletMode.collectAsState().value,
                        lightningAddress = viewModel.lightningAddress.collectAsState().value,
                        lightningAddressLoading = viewModel.lightningAddressLoading.collectAsState().value,
                        onSetupAddress = {
                            viewModel.resetAddressSetupState()
                            viewModel.navigateTo(WalletPage.LightningAddressSetup)
                        },
                        onShowAddressQR = { viewModel.navigateTo(WalletPage.LightningAddressQR) },
                        onDeleteAddress = { viewModel.deleteLightningAddress() },
                        onBackupMnemonic = { viewModel.showMnemonicBackup() },
                        onBackupToRelay = {
                            viewModel.resetBackupStatus()
                            viewModel.navigateTo(WalletPage.BackupToRelay)
                        },
                        onDeleteWallet = { viewModel.navigateTo(WalletPage.DeleteWalletConfirm) },
                        relayBackupStatuses = viewModel.relayBackupStatuses.collectAsState().value,
                        relayBackupCheckLoading = viewModel.relayBackupCheckLoading.collectAsState().value,
                        deleteBackupStatus = viewModel.deleteBackupStatus.collectAsState().value,
                        isLoggedIn = viewModel.keyRepo.isLoggedIn(),
                        onCheckRelayBackups = { viewModel.checkRelayBackupStatuses() },
                        onDeleteRelayBackup = { viewModel.deleteRelayBackup() },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.LightningAddressSetup -> LightningAddressSetupContent(
                        addressAvailable = viewModel.addressAvailable.collectAsState().value,
                        addressCheckLoading = viewModel.addressCheckLoading.collectAsState().value,
                        isLoading = viewModel.lightningAddressLoading.collectAsState().value,
                        error = viewModel.lightningAddressError.collectAsState().value,
                        showBioPrompt = viewModel.showBioPrompt.collectAsState().value,
                        onCheckAvailability = { viewModel.checkAddressAvailable(it) },
                        onRegister = { viewModel.registerLightningAddress(it) },
                        onAddToBio = { viewModel.addAddressToNostrBio() },
                        onDismissBioPrompt = { viewModel.dismissBioPrompt() },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.LightningAddressQR -> LightningAddressQRContent(
                        address = viewModel.lightningAddress.value ?: "",
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.DeleteWalletConfirm -> DeleteWalletConfirmContent(
                        confirmText = viewModel.deleteConfirmText.collectAsState().value,
                        onConfirmTextChange = { viewModel.updateDeleteConfirmText(it) },
                        onDelete = { viewModel.deleteWallet() },
                        onCancel = { viewModel.navigateBack() },
                        walletMode = viewModel.walletMode.collectAsState().value,
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.BackupToRelay -> BackupToRelayContent(
                        backupStatus = viewModel.backupStatus.collectAsState().value,
                        onBackup = { viewModel.backupToRelay() },
                        onDone = {
                            viewModel.resetBackupStatus()
                            viewModel.navigateBack()
                        },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.RestoreFromRelay -> RestoreFromRelayContent(
                        status = viewModel.restoreFromRelayStatus.collectAsState().value,
                        onSearch = { viewModel.searchRelayBackup() },
                        onRestore = { viewModel.restoreFromRelayBackup() },
                        onSelectBackup = { viewModel.selectBackupToRestore(it) },
                        onCancel = {
                            viewModel.resetRestoreFromRelayStatus()
                            viewModel.navigateBack()
                        },
                        modifier = Modifier.padding(padding)
                    )
                    else -> {
                        // ModeSelection, NwcSetup, SparkSetup — shouldn't appear while connected
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            walletMode = viewModel.walletMode.collectAsState().value,
                            showSettingsAlert = viewModel.walletMode.collectAsState().value == WalletMode.SPARK
                                    && !viewModel.seedBackupAcked.collectAsState().value,
                            onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                            onReceive = { viewModel.navigateTo(WalletPage.ReceiveAmount) },
                            onTransactions = {
                                viewModel.loadTransactions()
                                viewModel.navigateTo(WalletPage.Transactions)
                            },
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            onSetupAddress = {
                                viewModel.resetAddressSetupState()
                                viewModel.navigateTo(WalletPage.LightningAddressSetup)
                            },
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }
}

// --- Connection UI (not connected / connecting / error) ---

@Composable
private fun WalletConnectionContent(
    walletState: WalletState,
    connectionString: String,
    statusLines: List<String>,
    onConnectionStringChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val isConnecting = walletState is WalletState.Connecting

    Spacer(Modifier.height(16.dp))

    Text(
        stringResource(R.string.wallet_nwc),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.wallet_connect_lightning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    Text(
        stringResource(R.string.wallet_recommended),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(12.dp))

    val wallets = listOf(
        "rizful.com" to "Rizful",
        "coinos.io" to "Coinos",
        "getalby.com" to "Alby",
        "cashu.me" to "Cashu.me",
        "minibits.cash" to "Minibits"
    )

    wallets.forEach { (domain, name) ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$domain"))
                    context.startActivity(intent)
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = "https://$domain/favicon.ico",
                    contentDescription = name,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    OutlinedTextField(
        value = connectionString,
        onValueChange = onConnectionStringChange,
        label = { Text(stringResource(R.string.wallet_nwc_connection_string)) },
        placeholder = { Text("nostr+walletconnect://...") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3,
        enabled = !isConnecting
    )

    if (walletState is WalletState.Error) {
        Spacer(Modifier.height(8.dp))
        Text(
            walletState.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = connectionString.isNotBlank() && !isConnecting
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallet_connecting))
        } else {
            Text(stringResource(R.string.wallet_connect))
        }
    }

    if (statusLines.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            statusLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (isConnecting) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_cancel))
        }
    }

    Spacer(Modifier.height(32.dp))
}

// --- Home screen ---

@Composable
private fun WalletHomeContent(
    balanceMsats: Long,
    walletMode: WalletMode = WalletMode.NWC,
    showSettingsAlert: Boolean = false,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTransactions: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    lightningAddress: String? = null,
    onSetupAddress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val balanceSats = balanceMsats / 1000
    var balanceHidden by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar: history (left) and settings (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onTransactions) {
                Icon(
                    Icons.Default.Receipt,
                    contentDescription = "Transaction History",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettings) {
                Box {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showSettingsAlert) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFFD32F2F), CircleShape)
                                .align(Alignment.TopEnd)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Balance — tap to hide/show
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { balanceHidden = !balanceHidden }
        ) {
            if (balanceHidden) {
                Text(
                    "* * * * *",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.wallet_tap_to_reveal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%,d".format(balanceSats),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.wallet_sats),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }

        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_balance),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Lightning address or setup prompt
        if (walletMode == WalletMode.SPARK && lightningAddress == null) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetupAddress() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, Color(0xFFE6A040))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = Color(0xFFE6A040),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wallet_set_up_lightning),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.wallet_get_username),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (lightningAddress != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                lightningAddress,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.weight(1f))

        // Large circular Send / Receive buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Send button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.wallet_send),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.wallet_send),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Receive button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onReceive() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.wallet_receive),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.wallet_receive),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Send input ---

@Composable
private fun SendInputContent(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
    onScanQR: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Gallery image picker → decode QR from image
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue
                    if (value != null) {
                        // Strip common URI prefixes
                        val cleaned = value
                            .removePrefix("lightning:")
                            .removePrefix("LIGHTNING:")
                            .removePrefix("bitcoin:")
                            .removePrefix("BITCOIN:")
                        onInputChange(cleaned)
                    }
                }
                .addOnCompleteListener { scanner.close() }
        } catch (e: Exception) {
            Log.e("WalletScreen", "Failed to decode QR from image", e)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.wallet_send),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.wallet_lightning_address_invoice)) },
            placeholder = { Text(stringResource(R.string.placeholder_user_domain)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = {
                    clipboardManager.getText()?.text?.let { onInputChange(it) }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.wallet_paste))
                }
            }
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(24.dp))

        // Scan QR / Import from Gallery buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onScanQR,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wallet_scan_qr))
            }
            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wallet_gallery))
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = input.isNotBlank()
        ) {
            Text(stringResource(R.string.wallet_next))
        }
    }
}

// --- QR Scanner ---

@Composable
private fun ScanQRContent(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_scan_qr_code),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        if (!hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.wallet_camera_permission),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text(stringResource(R.string.wallet_grant_permission))
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val options = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            val scanner = BarcodeScanning.getClient(options)

                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                @androidx.camera.core.ExperimentalGetImage
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !scanned) {
                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage, imageProxy.imageInfo.rotationDegrees
                                    )
                                    scanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            val value = barcodes.firstOrNull()?.rawValue
                                            if (value != null && !scanned) {
                                                scanned = true
                                                val cleaned = value
                                                    .removePrefix("lightning:")
                                                    .removePrefix("LIGHTNING:")
                                                    .removePrefix("bitcoin:")
                                                    .removePrefix("BITCOIN:")
                                                onResult(cleaned)
                                            }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (e: Exception) {
                                Log.e("ScanQR", "Camera bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Viewfinder overlay
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .align(Alignment.Center)
                        .background(Color.Transparent)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeW = 3.dp.toPx()
                        val cornerLen = 30.dp.toPx()
                        val color = Color.White
                        // Top-left corner
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(cornerLen, 0f), strokeWidth = strokeW)
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(0f, cornerLen), strokeWidth = strokeW)
                        // Top-right corner
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width - cornerLen, 0f), strokeWidth = strokeW)
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width, cornerLen), strokeWidth = strokeW)
                        // Bottom-left corner
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(cornerLen, size.height), strokeWidth = strokeW)
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(0f, size.height - cornerLen), strokeWidth = strokeW)
                        // Bottom-right corner
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width - cornerLen, size.height), strokeWidth = strokeW)
                        drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height - cornerLen), strokeWidth = strokeW)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_point_camera),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(Modifier.height(16.dp))
    }
}

// --- Send amount (for lightning addresses) ---

@Composable
private fun SendAmountContent(
    address: String,
    amount: String,
    error: String?,
    isLoading: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_send_to),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            address,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_resolving), style = MaterialTheme.typography.bodyMedium)
        } else {
            SatsNumpad(
                amount = amount,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onConfirm = onConfirm,
                confirmEnabled = amount.isNotEmpty() && (amount.toLongOrNull() ?: 0) > 0
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// --- Send confirm ---

@Composable
private fun SendConfirmContent(
    invoice: String,
    amountSats: Long?,
    description: String?,
    feeState: FeeState,
    networkName: String,
    onPay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feeSats = (feeState as? FeeState.Estimated)?.feeSats
    val totalSats = if (amountSats != null && feeSats != null) amountSats + feeSats else amountSats

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.wallet_confirm_payment),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (amountSats != null) {
            Text(
                "%,d ${stringResource(R.string.wallet_sats)}".format(amountSats),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(24.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // To
                SummaryRow(
                    label = stringResource(R.string.placeholder_to),
                    value = truncateInvoice(invoice)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Network
                SummaryRow(
                    label = stringResource(R.string.placeholder_network),
                    value = networkName
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Fees
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.wallet_fees),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (feeState) {
                        is FeeState.Loading -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        is FeeState.Estimated -> Text(
                            "%,d ${stringResource(R.string.wallet_sats)}".format(feeState.feeSats),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        else -> Text(
                            "\u2014",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Total spent
                SummaryRow(
                    label = stringResource(R.string.wallet_total_spent),
                    value = if (totalSats != null) "%,d ${stringResource(R.string.wallet_sats)}".format(totalSats) else amountSats?.let { "%,d ${stringResource(R.string.wallet_sats)}".format(it) } ?: "\u2014",
                    bold = true
                )
            }
        }

        if (description != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPay,
            modifier = Modifier.fillMaxWidth(),
            enabled = feeState !is FeeState.Loading
        ) {
            Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallet_pay))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_cancel))
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun truncateInvoice(invoice: String): String {
    val lower = invoice.lowercase()
    return if (lower.length > 16) {
        lower.take(8) + "..." + lower.takeLast(6)
    } else {
        lower
    }
}

// --- Sending animation ---

@Composable
private fun SendingContent(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sending")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ElectricBolt,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .scale(scale),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        )
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.wallet_sending_payment),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Send result ---

@Composable
private fun SendResultContent(
    success: Boolean,
    message: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text(stringResource(R.string.btn_done))
        }
    }
}

// --- Receive amount ---

@Composable
private fun ReceiveAmountContent(
    amount: String,
    isLoading: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_receive),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_creating_invoice), style = MaterialTheme.typography.bodyMedium)
        } else {
            SatsNumpad(
                amount = amount,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onConfirm = onConfirm,
                confirmEnabled = amount.isNotEmpty() && (amount.toLongOrNull() ?: 0) > 0
            )
        }
    }
}

// --- Receive invoice (QR code) ---

@Composable
private fun ReceiveInvoiceContent(
    invoice: String,
    amountSats: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    val qrBitmap = remember(invoice) {
        val writer = QRCodeWriter()
        val data = "lightning:$invoice"
        val matrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            "%,d ${stringResource(R.string.wallet_sats)}".format(amountSats),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_invoice_qr_code),
            modifier = Modifier
                .size(280.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(12.dp)
        )

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    invoice,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(invoice))
                }, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_invoice),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        FilledTonalButton(onClick = onDone) {
            Text(stringResource(R.string.btn_done))
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Receive Success ---

@Composable
private fun ReceiveSuccessContent(
    amountSats: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    // Animation progress: 0 → 1 over ~1.6s
    val animProgress = remember { Animatable(0f) }
    // Checkmark fade-in after rings finish
    val checkAlpha = remember { Animatable(0f) }
    // Text + button fade-in
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 1: Rings radiate out (0 → 1)
        animProgress.animateTo(1f, tween(1600, easing = LinearEasing))
        // Phase 2: Checkmark fades in
        checkAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        // Phase 3: Text and button
        contentAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Ring animation + checkmark occupy the same centered space
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            // Concentric rings radiating outward
            Canvas(modifier = Modifier.size(200.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxRadius = size.width / 2f

                // 4 rings, staggered in time like the wisp logo layers
                val ringCount = 4
                for (i in 0 until ringCount) {
                    // Each ring starts at a staggered offset
                    val stagger = i * 0.15f
                    val ringProgress = ((animProgress.value - stagger) / (1f - stagger))
                        .coerceIn(0f, 1f)

                    if (ringProgress <= 0f) continue

                    // Ring expands from core (10%) to full radius
                    val radius = maxRadius * (0.1f + ringProgress * 0.9f)

                    // Fade: appear, peak, then fade out
                    val alpha = if (ringProgress < 0.3f) {
                        ringProgress / 0.3f  // fade in
                    } else {
                        1f - ((ringProgress - 0.3f) / 0.7f)  // fade out
                    }.coerceIn(0f, 1f)

                    // Outer rings are thinner and more transparent
                    val baseAlpha = 1f - (i * 0.2f)
                    val strokeWidth = (4f - i * 0.6f).coerceAtLeast(1.5f)

                    drawCircle(
                        color = primary.copy(alpha = alpha * baseAlpha * 0.8f),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth * density
                        )
                    )
                }

                // Glowing core dot that shrinks as rings expand
                val coreAlpha = (1f - animProgress.value).coerceIn(0f, 1f)
                if (coreAlpha > 0f) {
                    // Outer glow
                    drawCircle(
                        color = primary.copy(alpha = coreAlpha * 0.3f),
                        radius = 24f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    // Bright core
                    drawCircle(
                        color = primary.copy(alpha = coreAlpha),
                        radius = 10f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    // Hot center
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = coreAlpha),
                        radius = 4f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                }
            }

            // Checkmark circle fades in at the same center position
            if (checkAlpha.value > 0f) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            primary.copy(alpha = checkAlpha.value * 0.15f),
                            CircleShape
                        )
                        .padding(12.dp),
                    tint = primary.copy(alpha = checkAlpha.value)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.wallet_payment_received),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha.value)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "%,d ${stringResource(R.string.wallet_sats)}".format(amountSats),
            style = MaterialTheme.typography.headlineMedium,
            color = primary.copy(alpha = contentAlpha.value)
        )

        Spacer(Modifier.height(32.dp))

        if (contentAlpha.value > 0.5f) {
            Button(onClick = onDone) {
                Text(stringResource(R.string.btn_done))
            }
        }
    }
}

// --- Transaction History ---

@Composable
@Suppress("UNUSED_PARAMETER")
private fun TransactionHistoryContent(
    transactions: List<WalletTransaction>,
    error: String?,
    isLoading: Boolean,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    profileLookup: (String) -> com.wisp.app.nostr.ProfileData?,
    profileRefreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Text(
            stringResource(R.string.wallet_transactions),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (error.contains("NOT_IMPLEMENTED", ignoreCase = true) ||
                            error.contains("not supported", ignoreCase = true))
                            stringResource(R.string.wallet_not_supported)
                        else error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            transactions.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.wallet_no_transactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn {
                    items(transactions) { tx ->
                        TransactionRow(tx, profileLookup)
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text(stringResource(R.string.wallet_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: WalletTransaction,
    profileLookup: (String) -> com.wisp.app.nostr.ProfileData?
) {
    val isIncoming = tx.type == "incoming"
    val amountSats = tx.amountMsats / 1000
    val profile = tx.counterpartyPubkey?.let { profileLookup(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar or direction icon
        if (profile?.picture != null) {
            AsyncImage(
                model = profile.picture,
                contentDescription = profile.displayString,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isIncoming) Color(0xFF2E7D32).copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = if (isIncoming) stringResource(R.string.wallet_received) else stringResource(R.string.wallet_sent),
                    tint = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Name/description + time
        Column(modifier = Modifier.weight(1f)) {
            val displayLabel = if (profile != null) {
                profile.displayString
            } else {
                // Try to get a meaningful description; skip raw zap request JSON
                val desc = tx.description?.takeIf {
                    it.isNotBlank() && it != "null" && !it.trimStart().startsWith("{")
                }
                desc ?: if (isIncoming) stringResource(R.string.wallet_received) else stringResource(R.string.wallet_sent)
            }
            Text(
                displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatRelativeTime(tx.settledAt ?: tx.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Amount + fee
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (isIncoming) "+" else "-"}%,d".format(amountSats),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.wallet_sats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isIncoming && tx.feeMsats > 0) {
                val feeSats = tx.feeMsats / 1000
                Text(
                    stringResource(R.string.wallet_fee, feeSats),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- Wallet Mode Selection ---

@Composable
private fun WalletModeSelectionContent(
    onSelectNwc: () -> Unit,
    onSelectSpark: () -> Unit
) {
    Spacer(Modifier.height(16.dp))

    Text(
        stringResource(R.string.wallet_connect_wallet),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.wallet_choose_how),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectSpark() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, Color(0xFFE6A040))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.wallet_create_new),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.wallet_create_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectNwc() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.wallet_nwc),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.wallet_nwc_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(32.dp))
}

// --- Spark Setup ---

@Composable
private fun SparkSetupContent(
    walletState: WalletState,
    statusLines: List<String>,
    restoreMnemonic: String,
    error: String?,
    autoCheckState: AutoCheckState = AutoCheckState.Idle,
    onCreateWallet: () -> Unit,
    onRestoreMnemonicChange: (String) -> Unit,
    onRestoreWallet: () -> Unit,
    onRestoreFromRelay: () -> Unit = {},
    onRestoreFromAutoCheck: () -> Unit = {},
    onSelectAutoCheckBackup: (BackupEntry) -> Unit = {},
    onDismissAutoCheck: () -> Unit = {},
    isLoggedIn: Boolean = false,
    onDisconnect: () -> Unit
) {
    val isConnecting = walletState is WalletState.Connecting

    Spacer(Modifier.height(16.dp))

    Text(
        stringResource(R.string.wallet_spark),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Create a new non-custodial Lightning wallet or restore from a recovery phrase.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    // Auto-check states
    when (autoCheckState) {
        is AutoCheckState.Checking -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Checking for existing backup...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        is AutoCheckState.Found -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF68D391).copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.dp, Color(0xFF68D391).copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Existing wallet found",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF68D391)
                    )
                    Spacer(Modifier.height(4.dp))
                    val words = autoCheckState.mnemonic.split(" ")
                    Text(
                        "${words.size}-word recovery phrase",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    if (autoCheckState.walletId != null) {
                        Text(
                            "Wallet ID: ${autoCheckState.walletId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                    Text(
                        "Saved: ${dateFormat.format(java.util.Date(autoCheckState.createdAt * 1000))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRestoreFromAutoCheck,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6A040))
                    ) {
                        Text("Restore This Wallet")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDismissAutoCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create New Wallet")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        is AutoCheckState.MultipleFound -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Multiple wallets found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose a backup to restore:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                    autoCheckState.backups.forEach { entry ->
                        Card(
                            onClick = { onSelectAutoCheckBackup(entry) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, Color(0xFFE6A040))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    if (entry.walletId != null) "Wallet ${entry.walletId}" else "Spark wallet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    dateFormat.format(java.util.Date(entry.createdAt * 1000)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = onDismissAutoCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create New Wallet")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        else -> {} // Idle or NotFound — show normal UI below
    }

    if (!isConnecting && autoCheckState !is AutoCheckState.Found && autoCheckState !is AutoCheckState.MultipleFound) {
        Button(
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create New Wallet")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Or restore an existing wallet",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = restoreMnemonic,
            onValueChange = onRestoreMnemonicChange,
            label = { Text("Recovery phrase") },
            placeholder = { Text("Enter 12 or 24 words...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onRestoreWallet,
            modifier = Modifier.fillMaxWidth(),
            enabled = restoreMnemonic.isNotBlank()
        ) {
            Text("Restore Wallet")
        }

        if (isLoggedIn) {
            Spacer(Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onRestoreFromRelay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore From Relays")
            }
        }
    }

    if (walletState is WalletState.Error) {
        Spacer(Modifier.height(8.dp))
        Text(
            walletState.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (isConnecting) {
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(8.dp))
        Text("Connecting...", style = MaterialTheme.typography.bodyMedium)
    }

    if (statusLines.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            statusLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (isConnecting) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }

    Spacer(Modifier.height(32.dp))
}

// --- Spark Backup ---

@Composable
private fun SparkBackupContent(
    mnemonic: String,
    onConfirm: () -> Unit
) {
    val words = mnemonic.split(" ")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))

    Text(
        "Recovery Phrase",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Write down these words in order and store them safely. This is the only way to recover your wallet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(Modifier.height(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Display words in two columns
            for (i in words.indices step 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        "${i + 1}. ${words[i]}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (i + 1 < words.size) {
                        Text(
                            "${i + 2}. ${words[i + 1]}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    OutlinedButton(
        onClick = {
            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(mnemonic))
            copied = true
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(if (copied) "Copied!" else "Copy to Clipboard")
    }

    Spacer(Modifier.height(12.dp))

    Text(
        "This recovery phrase is specific to Spark wallets. It will not work with other Lightning or Bitcoin wallet apps.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("I've Backed This Up")
    }

    Spacer(Modifier.height(32.dp))
}

// --- Wallet Settings ---

@Composable
private fun WalletSettingsContent(
    walletMode: WalletMode,
    lightningAddress: String?,
    lightningAddressLoading: Boolean = false,
    onSetupAddress: () -> Unit,
    onShowAddressQR: () -> Unit,
    onDeleteAddress: () -> Unit = {},
    onBackupMnemonic: () -> Unit,
    onBackupToRelay: () -> Unit = {},
    onDeleteWallet: () -> Unit,
    relayBackupStatuses: List<RelayBackupInfo> = emptyList(),
    relayBackupCheckLoading: Boolean = false,
    deleteBackupStatus: DeleteBackupStatus = DeleteBackupStatus.Idle,
    isLoggedIn: Boolean = false,
    onCheckRelayBackups: () -> Unit = {},
    onDeleteRelayBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Lightning Address section (Spark only)
        if (walletMode == WalletMode.SPARK) {
            Spacer(Modifier.height(24.dp))

            Text(
                "Lightning Address",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            if (lightningAddressLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (lightningAddress != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lightningAddress,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(lightningAddress))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy address",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onShowAddressQR,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("QR Code")
                    }
                    OutlinedButton(
                        onClick = onSetupAddress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Change")
                    }
                }

                Spacer(Modifier.height(4.dp))

                TextButton(
                    onClick = onDeleteAddress,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))
                ) {
                    Text("Remove Lightning Address")
                }
            } else {
                OutlinedButton(
                    onClick = onSetupAddress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Set Up Lightning Address")
                }
            }
        }

        // Security section
        Spacer(Modifier.height(24.dp))

        Text(
            "Security",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))

        if (walletMode == WalletMode.SPARK) {
            OutlinedButton(
                onClick = onBackupMnemonic,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Backup Recovery Phrase")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onBackupToRelay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Backup to Nostr Relays")
            }

            // Relay backup status section (when logged in)
            if (isLoggedIn) {
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Relay Backup Status",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onCheckRelayBackups,
                        enabled = !relayBackupCheckLoading
                    ) {
                        if (relayBackupCheckLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Check relay backups",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (relayBackupStatuses.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            relayBackupStatuses.forEach { info ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (info.hasBackup) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                                CircleShape
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        info.relayUrl.removePrefix("wss://").removePrefix("ws://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    var showDeleteDialog by remember { mutableStateOf(false) }

                    TextButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F)),
                        enabled = deleteBackupStatus !is DeleteBackupStatus.InProgress
                    ) {
                        if (deleteBackupStatus is DeleteBackupStatus.InProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFD32F2F)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Delete Relay Backup")
                    }

                    if (deleteBackupStatus is DeleteBackupStatus.Success) {
                        Text(
                            "Backup deleted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (deleteBackupStatus is DeleteBackupStatus.Error) {
                        Text(
                            deleteBackupStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Delete Relay Backup?") },
                            text = {
                                Text("This will delete your wallet backup from your relays. Make sure you have your recovery phrase saved elsewhere.")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDeleteDialog = false
                                        onDeleteRelayBackup()
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }

        // Disclaimer
        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Text(
                "IMPORTANT: Wisp never holds user funds. You manage your own wallet and are responsible for securing it properly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onDeleteWallet,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White
            )
        ) {
            Text(if (walletMode == WalletMode.NWC) "Disconnect" else "Delete Wallet")
        }

        // Footer
        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (walletMode == WalletMode.SPARK) {
                Icon(
                    painter = painterResource(R.drawable.ic_spark_logo),
                    contentDescription = "Spark",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Spark",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_breez_logo),
                    contentDescription = "Breez",
                    modifier = Modifier.height(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.wallet_spark_sdk, BuildConfig.BREEZ_SDK_VERSION),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.wallet_nwc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Lightning Address Setup ---

@Composable
private fun LightningAddressSetupContent(
    addressAvailable: Boolean?,
    addressCheckLoading: Boolean,
    isLoading: Boolean,
    error: String?,
    showBioPrompt: Boolean,
    onCheckAvailability: (String) -> Unit,
    onRegister: (String) -> Unit,
    onAddToBio: () -> Unit,
    onDismissBioPrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    var username by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Lightning Address",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Choose a username to get a lightning address for receiving payments.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                label = { Text("Username") },
                placeholder = { Text("satoshi") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "@breez.tips",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // Availability indicator
        if (addressAvailable == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Available!",
                    color = Color(0xFF2E7D32),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (addressAvailable == false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Not available",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        if (addressCheckLoading || isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            OutlinedButton(
                onClick = { onCheckAvailability(username) },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.length >= 3
            ) {
                Text("Check Availability")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onRegister(username) },
                modifier = Modifier.fillMaxWidth(),
                enabled = addressAvailable == true && username.length >= 3
            ) {
                Text("Register")
            }
        }
    }

    // Bio update prompt dialog
    if (showBioPrompt) {
        AlertDialog(
            onDismissRequest = onDismissBioPrompt,
            title = { Text("Update Nostr Profile?") },
            text = { Text("Add this lightning address to your Nostr profile so others can zap you?") },
            confirmButton = {
                TextButton(onClick = onAddToBio) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBioPrompt) {
                    Text("No")
                }
            }
        )
    }
}

// --- Lightning Address QR ---

@Composable
private fun LightningAddressQRContent(
    address: String,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val qrBitmap = remember(address) {
        if (address.isBlank()) return@remember null
        val writer = QRCodeWriter()
        val matrix = writer.encode(address, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Lightning Address",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Lightning Address QR Code",
                modifier = Modifier
                    .size(280.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    address,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(address))
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy address",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, address)
                }
                context.startActivity(Intent.createChooser(intent, "Share Lightning Address"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Delete Wallet Confirmation ---

@Composable
private fun DeleteWalletConfirmContent(
    confirmText: String,
    onConfirmTextChange: (String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    walletMode: WalletMode = WalletMode.SPARK,
    modifier: Modifier = Modifier
) {
    val isNwc = walletMode == WalletMode.NWC

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFFD32F2F).copy(alpha = 0.1f), CircleShape)
                .padding(16.dp),
            tint = Color(0xFFD32F2F)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            if (isNwc) "Disconnect NWC" else "Delete Wallet",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFD32F2F)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            if (isNwc) "This will remove the NWC connection string from this device. You can reconnect anytime with a new connection string."
            else "This will permanently delete your wallet from this device. Your funds cannot be recovered without your recovery phrase.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isNwc) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Make sure you have backed up your recovery phrase before proceeding.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD32F2F),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = confirmText,
                onValueChange = onConfirmTextChange,
                label = { Text("Type DELETE to confirm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = isNwc || confirmText == "DELETE",
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White
            )
        ) {
            Text(if (isNwc) "Disconnect" else "Delete Wallet")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Backup to Relay ---

@Composable
private fun BackupToRelayContent(
    backupStatus: BackupStatus,
    onBackup: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Backup to Nostr Relays",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Encrypt your wallet recovery phrase with NIP-44 and publish it to your Nostr relays. Only you can decrypt it with your Nostr private key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Text(
                "Your mnemonic is encrypted to your own public key using NIP-44. Relay operators cannot read it. Compatible with other Spark wallet apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        when (backupStatus) {
            is BackupStatus.None -> {
                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Backup Now")
                }
            }
            is BackupStatus.InProgress -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Encrypting and publishing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is BackupStatus.Success -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            CircleShape
                        )
                        .padding(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Backup complete",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Your wallet has been encrypted and published to your Nostr relays.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
            is BackupStatus.Error -> {
                Text(
                    backupStatus.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Restore from Relay ---

@Composable
private fun RestoreFromRelayContent(
    status: RestoreFromRelayStatus,
    onSearch: () -> Unit,
    onRestore: () -> Unit,
    onSelectBackup: (BackupEntry) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Restore From Relays",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Search your Nostr relays for an encrypted wallet backup. The backup will be decrypted with your Nostr private key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        when (status) {
            is RestoreFromRelayStatus.Idle -> {
                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search Relays")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
            is RestoreFromRelayStatus.Searching -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Searching relays for backup...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is RestoreFromRelayStatus.Found -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            CircleShape
                        )
                        .padding(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Backup found!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                val words = status.mnemonic.split(" ")
                Text(
                    "${words.size}-word recovery phrase",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (status.walletId != null) {
                    Text(
                        "Wallet ID: ${status.walletId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                Text(
                    "Saved: ${dateFormat.format(java.util.Date(status.createdAt * 1000))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore This Wallet")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
            is RestoreFromRelayStatus.MultipleFound -> {
                Text(
                    "Multiple wallets found",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Choose a backup to restore:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                status.backups.forEach { entry ->
                    Card(
                        onClick = { onSelectBackup(entry) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Color(0xFFE6A040))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (entry.walletId != null) "Wallet ${entry.walletId}" else "Spark wallet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                dateFormat.format(java.util.Date(entry.createdAt * 1000)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
            is RestoreFromRelayStatus.NotFound -> {
                Text(
                    "No wallet backup found on your relays.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search Again")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
            is RestoreFromRelayStatus.Error -> {
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> {
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp * 1000))
        }
    }
}
