package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wisp.app.nostr.Nip47
import com.wisp.app.ui.component.SatsNumpad
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (walletState !is WalletState.Connected || viewModel.isOnHome) {
                            onBack()
                        } else {
                            viewModel.navigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    WalletConnectionContent(
                        walletState = walletState,
                        connectionString = viewModel.connectionString.collectAsState().value,
                        statusLines = viewModel.statusLines.collectAsState().value,
                        onConnectionStringChange = { viewModel.updateConnectionString(it) },
                        onConnect = { viewModel.connectWallet() },
                        onDisconnect = { viewModel.disconnectWallet() }
                    )
                }
            }
            is WalletState.Connected -> {
                val balanceMsats = (walletState as WalletState.Connected).balanceMsats
                when (currentPage) {
                    is WalletPage.Home -> WalletHomeContent(
                        balanceMsats = balanceMsats,
                        onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                        onReceive = {
                            viewModel.navigateTo(WalletPage.ReceiveAmount)
                        },
                        onTransactions = {
                            viewModel.loadTransactions()
                            viewModel.navigateTo(WalletPage.Transactions)
                        },
                        onRefresh = { viewModel.refreshBalance() },
                        onDisconnect = { viewModel.disconnectWallet() },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.SendInput -> SendInputContent(
                        input = viewModel.sendInput.collectAsState().value,
                        error = viewModel.sendError.collectAsState().value,
                        onInputChange = { viewModel.updateSendInput(it) },
                        onNext = { viewModel.processInput() },
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
                        SendConfirmContent(
                            amountSats = page.amountSats,
                            paymentHash = page.paymentHash,
                            description = page.description,
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
                    is WalletPage.Transactions -> TransactionHistoryContent(
                        transactions = viewModel.transactions.collectAsState().value,
                        error = viewModel.transactionsError.collectAsState().value,
                        isLoading = viewModel.isLoading.collectAsState().value,
                        modifier = Modifier.padding(padding)
                    )
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
        "Nostr Wallet Connect",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Connect a Lightning wallet to send and receive sats. Paste a NWC connection string from your wallet provider.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    Text(
        "Recommended wallets",
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
        label = { Text("NWC Connection String") },
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
            Text("Connecting...")
        } else {
            Text("Connect")
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
            Text("Cancel")
        }
    }

    Spacer(Modifier.height(32.dp))
}

// --- Home screen ---

@Composable
private fun WalletHomeContent(
    balanceMsats: Long,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTransactions: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val balanceSats = balanceMsats / 1000

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Bitcoin icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "₿",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(Modifier.height(24.dp))

        // Balance
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%,d".format(balanceSats),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "sats",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh balance",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(40.dp))

        // Send / Receive buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSend,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
            Button(
                onClick = onReceive,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Receive")
            }
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onTransactions) {
            Text("Transaction History")
        }

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = onDisconnect,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Disconnect")
        }

        Spacer(Modifier.height(16.dp))
    }
}

// --- Send input ---

@Composable
private fun SendInputContent(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Send",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Lightning address or invoice") },
            placeholder = { Text("user@domain.com or lnbc...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = {
                    clipboardManager.getText()?.text?.let { onInputChange(it) }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
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

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = input.isNotBlank()
        ) {
            Text("Next")
        }
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
            "Send to",
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
            Text("Resolving...", style = MaterialTheme.typography.bodyMedium)
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
    amountSats: Long?,
    paymentHash: String?,
    description: String?,
    onPay: () -> Unit,
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
            "Confirm Payment",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (amountSats != null) {
                    Text(
                        "%,d sats".format(amountSats),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (paymentHash != null) {
                    Text(
                        "Payment Hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        paymentHash.take(16) + "..." + paymentHash.takeLast(16),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (description != null) {
                    Text(
                        "Description",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPay,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pay")
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
            "Sending payment...",
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
            Text("Done")
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
            "Receive",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Creating invoice...", style = MaterialTheme.typography.bodyMedium)
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
            "%,d sats".format(amountSats),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Invoice QR Code",
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
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy invoice",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(onClick = onDone) {
            Text("Done")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Transaction History ---

@Composable
private fun TransactionHistoryContent(
    transactions: List<Nip47.Transaction>,
    error: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Text(
            "Transactions",
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
                            "Not supported by your wallet"
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
                        "No transactions yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn {
                    items(transactions) { tx ->
                        TransactionRow(tx)
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: Nip47.Transaction) {
    val isIncoming = tx.type == "incoming"
    val amountSats = tx.amount / 1000

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction icon
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
                contentDescription = if (isIncoming) "Received" else "Sent",
                tint = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        // Description + time
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tx.description?.takeIf { it.isNotBlank() } ?: if (isIncoming) "Received" else "Sent",
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

        // Amount
        Text(
            "${if (isIncoming) "+" else "-"}%,d".format(amountSats),
            style = MaterialTheme.typography.titleMedium,
            color = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "sats",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
