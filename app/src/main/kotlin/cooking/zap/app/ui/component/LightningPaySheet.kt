package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cooking.zap.app.R
import cooking.zap.app.repo.ZapPreferences
import cooking.zap.app.ui.util.AmountFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How much the recipient is asking for, and whether the payer gets to choose. */
sealed class LightningPayAmount {
    data class Fixed(val sats: Long) : LightningPayAmount()
    data object AnyAmount : LightningPayAmount()
    data class Range(val minSats: Long, val maxSats: Long) : LightningPayAmount()
}

private const val PAY_SOFT_CONFIRM_SATS = 10_000L
private const val PAY_HARD_CAP_SATS = 1_000_000L

/**
 * Shared amount + confirm bottom sheet for paying an arbitrary bolt11 invoice or
 * LNURL/lightning-address pill in note content — the same shell as [ZapDialog]'s
 * hero-amount/preset-pill UI, minus the zap-specific bits (message, privacy,
 * receipts) that don't apply since there's no Nostr recipient pubkey involved.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LightningPaySheet(
    recipientLabel: String,
    amount: LightningPayAmount,
    description: String? = null,
    onDismiss: () -> Unit,
    onPay: (amountSats: Long) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val range = amount as? LightningPayAmount.Range
    var customAmountTfv by remember {
        val initialText = when (amount) {
            is LightningPayAmount.Fixed -> amount.sats.toString()
            is LightningPayAmount.Range -> amount.minSats.toString()
            LightningPayAmount.AnyAmount -> ""
        }
        mutableStateOf(TextFieldValue(text = initialText, selection = TextRange(0, initialText.length)))
    }
    var showLargeAmountConfirm by remember { mutableStateOf(false) }
    val amountFocusRequester = remember { FocusRequester() }

    val effectiveAmount: Long = when (amount) {
        is LightningPayAmount.Fixed -> amount.sats
        LightningPayAmount.AnyAmount -> 0L
        is LightningPayAmount.Range -> customAmountTfv.text.toLongOrNull() ?: 0L
    }
    val outOfRange = range != null && (effectiveAmount < range.minSats || effectiveAmount > range.maxSats)
    val overHardCap = range != null && effectiveAmount > PAY_HARD_CAP_SATS
    val canPay = when (amount) {
        LightningPayAmount.AnyAmount, is LightningPayAmount.Fixed -> true
        is LightningPayAmount.Range -> effectiveAmount > 0 && !outOfRange && !overHardCap
    }

    LaunchedEffect(Unit) {
        if (range != null) {
            delay(450)
            runCatching { amountFocusRequester.requestFocus() }
        }
    }

    fun attemptPay() {
        if (!canPay) return
        if (effectiveAmount > PAY_SOFT_CONFIRM_SATS) {
            showLargeAmountConfirm = true
        } else {
            closeSheet()
            onPay(effectiveAmount)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                recipientLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(20.dp))

            if (amount is LightningPayAmount.AnyAmount) {
                Text(
                    stringResource(R.string.lightning_invoice_any_amount),
                    style = TextStyle(color = accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                )
            } else if (range != null) {
                val heroStyle = TextStyle(
                    color = accent,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                val invisibleSelection = remember(accent) {
                    TextSelectionColors(handleColor = accent, backgroundColor = Color.Transparent)
                }
                CompositionLocalProvider(LocalTextSelectionColors provides invisibleSelection) {
                    BasicTextField(
                        value = customAmountTfv,
                        onValueChange = { newTfv ->
                            customAmountTfv = newTfv.copy(text = newTfv.text.filter { it.isDigit() })
                        },
                        textStyle = heroStyle,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = ThousandsSeparatorTransformation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(amountFocusRequester),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                                if (customAmountTfv.text.isEmpty()) {
                                    Text("0", style = heroStyle.copy(color = accent.copy(alpha = 0.35f)))
                                }
                                inner()
                            }
                        }
                    )
                }
            } else {
                Text(
                    "%,d".format(effectiveAmount),
                    style = TextStyle(color = accent, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                )
            }
            if (amount !is LightningPayAmount.AnyAmount) {
                Text("sats", color = accent.copy(alpha = 0.75f), style = MaterialTheme.typography.titleSmall)
            }

            if (range != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.lnurl_pay_amount_range, range.minSats, range.maxSats),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outOfRange) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )

                val presets = remember(range) {
                    ZapPreferences(context).getPresets()
                        .filter { it.amountSats in range.minSats..range.maxSats }
                }
                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            PresetPill(
                                label = AmountFormatter.formatShort(preset.amountSats, context),
                                selected = effectiveAmount == preset.amountSats,
                                accent = accent,
                                onClick = {
                                    seedCustomAmount(preset.amountSats.toString()) { customAmountTfv = it }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (overHardCap) {
                Text(
                    "Max ${"%,d".format(PAY_HARD_CAP_SATS)} sats",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { attemptPay() },
                enabled = canPay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                    disabledContainerColor = accent.copy(alpha = 0.35f)
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    modifier = Modifier.width(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (amount is LightningPayAmount.AnyAmount) stringResource(R.string.lightning_invoice_btn_pay)
                    else "Pay ${"%,d".format(effectiveAmount)} sats",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
    }

    if (showLargeAmountConfirm) {
        AlertDialog(
            onDismissRequest = { showLargeAmountConfirm = false },
            title = { Text(stringResource(R.string.lnurl_pay_confirm_amount, effectiveAmount)) },
            text = { Text("This is a large amount, double-check before sending.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLargeAmountConfirm = false
                        closeSheet()
                        onPay(effectiveAmount)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text(stringResource(R.string.lightning_invoice_btn_pay), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLargeAmountConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
