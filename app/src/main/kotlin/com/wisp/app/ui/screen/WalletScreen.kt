package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.viewmodel.WalletState
import com.wisp.app.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit
) {
    val walletState by viewModel.walletState.collectAsState()
    val connectionString by viewModel.connectionString.collectAsState()
    val generatedInvoice by viewModel.generatedInvoice.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (walletState) {
                is WalletState.NotConnected, is WalletState.Error -> {
                    NotConnectedContent(
                        walletState = walletState,
                        connectionString = connectionString,
                        onConnectionStringChange = { viewModel.updateConnectionString(it) },
                        onConnect = { viewModel.connectWallet() }
                    )
                }
                is WalletState.Connecting -> {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Connecting to wallet...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is WalletState.Connected -> {
                    ConnectedContent(
                        balanceMsats = (walletState as WalletState.Connected).balanceMsats,
                        generatedInvoice = generatedInvoice,
                        onRefresh = { viewModel.refreshBalance() },
                        onGenerateInvoice = { amount, desc -> viewModel.generateInvoice(amount, desc) },
                        onClearInvoice = { viewModel.clearInvoice() },
                        onDisconnect = { viewModel.disconnectWallet() }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotConnectedContent(
    walletState: WalletState,
    connectionString: String,
    onConnectionStringChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    val context = LocalContext.current

    Spacer(Modifier.height(16.dp))

    Text(
        "Nostr Wallet Connect",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Connect a Lightning wallet to send and receive zaps. Paste a NWC connection string from your wallet provider.",
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
        "getalby.com" to "Alby"
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
        maxLines = 3
    )

    if (walletState is WalletState.Error) {
        Spacer(Modifier.height(8.dp))
        Text(
            (walletState as WalletState.Error).message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = connectionString.isNotBlank()
    ) {
        Text("Connect")
    }

    Spacer(Modifier.height(32.dp))
}

@Composable
private fun ConnectedContent(
    balanceMsats: Long,
    generatedInvoice: String?,
    onRefresh: () -> Unit,
    onGenerateInvoice: (Long, String) -> Unit,
    onClearInvoice: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val balanceSats = balanceMsats / 1000

    Spacer(Modifier.height(16.dp))

    // Balance card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%,d".format(balanceSats),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "sats",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh balance",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // Receive section
    Text(
        "Receive",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(12.dp))

    var invoiceAmount by remember { mutableStateOf("") }
    var invoiceDescription by remember { mutableStateOf("") }

    OutlinedTextField(
        value = invoiceAmount,
        onValueChange = { invoiceAmount = it.filter { c -> c.isDigit() } },
        label = { Text("Amount (sats)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = invoiceDescription,
        onValueChange = { invoiceDescription = it },
        label = { Text("Description (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = {
            val amount = invoiceAmount.toLongOrNull() ?: return@Button
            onGenerateInvoice(amount, invoiceDescription)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = invoiceAmount.isNotBlank()
    ) {
        Text("Generate Invoice")
    }

    if (generatedInvoice != null) {
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Invoice",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Invoice", generatedInvoice))
                        }
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        generatedInvoice,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(32.dp))

    OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text("Disconnect Wallet")
    }

    Spacer(Modifier.height(32.dp))
}
