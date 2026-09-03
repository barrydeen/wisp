package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.wisp.app.repo.WithdrawOnchainQuote
import com.wisp.app.repo.WithdrawOnchainSpeed
import com.wisp.app.repo.normalizeBitcoinAddress

/**
 * "Withdraw on-chain" - drain the whole Spark balance to a Bitcoin address.
 *
 * Exists mostly for a user who believes their wallet is broken: it is the
 * escape hatch that gets their money somewhere they already trust. So it leads
 * with what will happen, quotes a real fee from the SDK before anything is
 * signed, and never claims more than it can deliver.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawOnchainSheet(
    onQuote: suspend (String, WithdrawOnchainSpeed) -> Result<WithdrawOnchainQuote>,
    onConfirm: suspend (WithdrawOnchainQuote) -> Result<String>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var address by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(WithdrawOnchainSpeed.MEDIUM) }
    var quote by remember { mutableStateOf<WithdrawOnchainQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentPaymentId by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    if (showScanner) {
        ModalBottomSheet(onDismissRequest = { showScanner = false }) {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                QrScanner(
                    onResult = { raw ->
                        // BIP-21 QRs encode "bitcoin:addr?amount=..."; the SDK
                        // parser wants the bare address.
                        address = normalizeBitcoinAddress(raw)
                        quote = null
                        error = null
                        showScanner = false
                    },
                    promptText = "Scan a Bitcoin address"
                )
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                "Withdraw on-chain",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            val paymentId = sentPaymentId
            if (paymentId != null) {
                Text("Sent", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                Text(
                    "Your funds are on their way. On-chain transactions take time to " +
                        "confirm - the wallet will show the payment as pending until it does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(paymentId))
                }) { Text("Copy payment ID") }
                // Sparkscan, not mempool.space: this is Spark's payment
                // identifier, not a Bitcoin txid, so a mempool lookup 404s.
                TextButton(onClick = { uriHandler.openUri("https://sparkscan.io/tx/$paymentId") }) {
                    Text("View on Sparkscan")
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }

            // Warning
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFF9800).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800),
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "This empties your wallet",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF9800)
                    )
                }
                Text(
                    "Everything spendable is sent to the Bitcoin address you enter. " +
                        "On-chain fees are deducted from the amount, so you receive less " +
                        "than the balance shown.\n\nThis is a best effort at recovering " +
                        "your funds. Bitcoin sent on-chain cannot be undone, and a wrong " +
                        "address means the money is gone - check it carefully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Address
            Text("Bitcoin address", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    // Any edit invalidates the quote - it was priced for a
                    // different destination.
                    quote = null
                    error = null
                },
                placeholder = { Text("bc1...") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { address = normalizeBitcoinAddress(it) }
                            quote = null
                        }) { Icon(Icons.Default.ContentPaste, "Paste") }
                        // Scanning beats pasting for an irreversible send: no
                        // truncation, no clipboard hijack, no transcription.
                        IconButton(onClick = { showScanner = true }) {
                            Icon(Icons.Default.QrCodeScanner, "Scan")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Speed
            Text("Confirmation speed", style = MaterialTheme.typography.labelMedium)
            WithdrawOnchainSpeed.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { speed = option; quote = null }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = speed == option, onClick = { speed = option; quote = null })
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quote
            val q = quote
            if (quoting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Getting a fee quote...", style = MaterialTheme.typography.bodySmall)
                }
            } else if (q != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuoteRow("Wallet balance", "%,d sats".format(q.spendSats))
                    QuoteRow("On-chain fee", "- %,d sats".format(q.feeSats))
                    HorizontalDivider()
                    QuoteRow("You receive", "%,d sats".format(q.netSats), emphasis = true)
                    if (q.isUneconomical) {
                        Text(
                            "The fee is larger than the balance, so there would be nothing " +
                                "left to send. Try Economy speed, or wait until the balance is higher.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (q.feePercent >= 10) {
                        // Draining a small balance can cost a large share of
                        // it. Better seen before confirming than after.
                        Text(
                            "Fees take ${q.feePercent.toInt()}% of this balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (q == null) {
                        scope.launch {
                            quoting = true; error = null
                            onQuote(address.trim(), speed)
                                .onSuccess { quote = it }
                                .onFailure { error = it.message ?: "Something went wrong." }
                            quoting = false
                        }
                    } else {
                        showConfirm = true
                    }
                },
                enabled = address.isNotBlank() && !quoting && !sending && q?.isUneconomical != true,
                colors = if (q == null) ButtonDefaults.buttonColors()
                         else ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (q == null) "Get quote" else "Withdraw on-chain")
            }
        }
    }

    if (showConfirm && quote != null) {
        val confirmed = quote!!
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Withdraw everything on-chain?") },
            text = {
                Text(
                    "This cannot be undone. %,d sats go to ${confirmed.address}."
                        .format(confirmed.netSats)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        sending = true; error = null
                        onConfirm(confirmed)
                            .onSuccess { sentPaymentId = it }
                            .onFailure { error = it.message ?: "Something went wrong." }
                        sending = false
                    }
                }) { Text("Withdraw", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QuoteRow(label: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasis) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
