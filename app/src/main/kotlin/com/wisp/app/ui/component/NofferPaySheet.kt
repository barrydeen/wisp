package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.NofferData
import com.wisp.app.nostr.NofferException
import com.wisp.app.nostr.NofferPricing
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toNpub
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.NofferClient
import com.wisp.app.repo.ZapSender
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.ui.util.AmountFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun shortNpub(pubkeyHex: String): String =
    pubkeyHex.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" }

private fun priceLabel(price: Long, currency: String?): String =
    if (!currency.isNullOrEmpty()) "${AmountFormatter.formatSatsOnly(price)} $currency"
    else "${AmountFormatter.formatSatsOnly(price)} sats"

/**
 * Inline "Pay offer" card rendered for a CLINK `noffer1…` found in a note body.
 * Tapping opens [NofferPaySheet]. Resolves the recipient's name itself so it
 * reads consistently across feed and thread — the offer recipient is usually
 * not the note author, so callers' profile maps often haven't fetched it.
 */
@Composable
fun NofferCard(
    noffer: NofferData,
    eventRepo: EventRepository? = null,
    onPayInvoice: (suspend (String) -> Boolean)? = null
) {
    val zapColor = WispThemeColors.zapColor
    var showSheet by remember { mutableStateOf(false) }

    val profileVersion = eventRepo?.profileVersion?.collectAsState()
    val profile = remember(noffer.pubkey, profileVersion?.value) {
        eventRepo?.getProfileData(noffer.pubkey)
    }
    LaunchedEffect(noffer.pubkey) {
        eventRepo?.requestProfileIfMissing(noffer.pubkey)
    }
    val recipientName = profile?.displayString ?: shortNpub(noffer.pubkey)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = zapColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, zapColor.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { showSheet = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(zapColor.copy(alpha = 0.15f), CircleShape)
            ) {
                ZapBoltIcon(tint = zapColor, size = 16)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Pay offer",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    recipientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            val price = noffer.price
            if (price != null && price > 0) {
                Text(
                    priceLabel(price, noffer.currency),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = zapColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (showSheet) {
        NofferPaySheet(
            noffer = noffer,
            recipientProfile = profile,
            onPayInvoice = onPayInvoice,
            onDismiss = { showSheet = false }
        )
    }
}

@Composable
private fun ZapBoltIcon(tint: Color, size: Int) {
    val useZapBolt = com.wisp.app.ui.util.useBoltIcon()
    if (useZapBolt) {
        Icon(
            painter = painterResource(R.drawable.ic_bolt),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Icon(
            Icons.Outlined.CurrencyBitcoin,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size.dp)
        )
    }
}

/**
 * Bottom sheet that requests a bolt11 invoice for a CLINK offer and pays it
 * with the active wallet via [onPayInvoice], or falls back to a scannable
 * bare-`noffer` QR for an external CLINK-aware wallet (Zeus, ShockWallet, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NofferPaySheet(
    noffer: NofferData,
    recipientProfile: ProfileData? = null,
    onPayInvoice: (suspend (String) -> Boolean)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zapColor = WispThemeColors.zapColor
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Spontaneous offers require the payer to name an amount. Fixed/Variable
    // let the service decide (Fixed from the offer, Variable on request).
    val needsAmountField = noffer.pricing == NofferPricing.SPONTANEOUS

    var amountText by remember {
        mutableStateOf(
            if (needsAmountField && (noffer.price ?: 0) > 0) noffer.price.toString() else ""
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    var didPay by remember { mutableStateOf(false) }
    var showExternal by remember { mutableStateOf(false) }

    val recipientName = recipientProfile?.displayString ?: shortNpub(noffer.pubkey)
    val amountSats = amountText.filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0 }
    val canPay = onPayInvoice != null && !inFlight && (!needsAmountField || amountSats != null)

    fun pay() {
        val payInvoice = onPayInvoice ?: return
        scope.launch {
            inFlight = true
            status = null
            try {
                val keypair = withContext(Dispatchers.IO) { KeyRepository(context.applicationContext).getKeypair() }
                if (keypair == null) {
                    status = "Sign in to pay an offer."
                    return@launch
                }
                val bolt11 = NofferClient.requestInvoice(
                    noffer = noffer,
                    keypair = keypair,
                    amountSats = if (needsAmountField) amountSats else null
                )
                // Map the payment hash to the offer's service pubkey so the
                // wallet transaction history resolves the payee, same as zaps.
                Bolt11.decode(bolt11)?.paymentHash?.let {
                    ZapSender.persistRecipient(it, noffer.pubkey)
                }
                if (payInvoice(bolt11)) {
                    didPay = true
                } else {
                    status = "Payment failed."
                }
            } catch (err: NofferException) {
                status = if (err.code == 5 && err.rangeMin != null && err.rangeMax != null) {
                    "Amount must be between ${AmountFormatter.formatSatsOnly(err.rangeMin!!)} and ${AmountFormatter.formatSatsOnly(err.rangeMax!!)} sats."
                } else {
                    err.message ?: "The offer request failed."
                }
            } catch (err: Exception) {
                status = err.message ?: "The offer request failed."
            } finally {
                inFlight = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Full-height sheet to match the iOS pay modal — inset below the
        // status bar so the drag handle clears the camera cutout.
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Recipient header
            ProfilePicture(url = recipientProfile?.picture, size = 64)
            Spacer(Modifier.height(8.dp))
            Text(
                recipientName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            if (didPay) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = zapColor,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("Payment sent", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                // Offer details
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Offer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                when (noffer.pricing) {
                                    NofferPricing.FIXED -> "Fixed price"
                                    NofferPricing.VARIABLE -> "Variable price"
                                    NofferPricing.SPONTANEOUS -> "Pay what you want"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val price = noffer.price
                        if (price != null && price > 0 && !needsAmountField) {
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "Amount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    priceLabel(price, noffer.currency),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = zapColor
                                )
                            }
                        }
                    }
                }

                if (needsAmountField) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { new -> amountText = new.filter { it.isDigit() } },
                        label = { Text("Amount in sats") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { pay() },
                    enabled = canPay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = zapColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (inFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(if (onPayInvoice == null) "Connect a wallet to pay" else "Pay")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // External wallet fallback — bare-noffer QR per spec
                TextButton(onClick = { showExternal = !showExternal }) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp), tint = zapColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showExternal) "Hide QR code" else "Pay with another wallet",
                        color = zapColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showExternal) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = zapColor
                    )
                }

                AnimatedVisibility(visible = showExternal) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(8.dp))
                        val qr = remember(noffer.raw) { generateQrBitmap(noffer.raw) }
                        Box(
                            modifier = Modifier
                                .size(230.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "CLINK offer QR code",
                                modifier = Modifier.size(206.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Scan with Zeus, ShockWallet, or another CLINK-aware wallet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(noffer.raw))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = zapColor)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy offer", color = zapColor)
                        }
                    }
                }
            }
        }
    }
}
