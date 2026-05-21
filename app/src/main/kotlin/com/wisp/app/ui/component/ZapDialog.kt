package com.wisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Message

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wisp.app.R
import com.wisp.app.repo.ExchangeRateRepository
import com.wisp.app.repo.FiatCurrency
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.ZapPreset
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.ui.util.AmountFormatter
import androidx.compose.runtime.collectAsState
import kotlin.math.sin
import kotlin.random.Random

private val LightningYellow: Color
    @Composable get() = WispThemeColors.zapColor

private val LightningOrange: Color
    @Composable get() = WispThemeColors.zapColor

private val LightningAmber: Color
    @Composable get() = WispThemeColors.zapColor.copy(alpha = 0.7f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    canPrivateZap: Boolean = false,
    /**
     * Lock the zap to DIP-03 private mode (private + anon toggles hidden, isPrivate held true).
     * Used when zapping a NIP-17 private reply — falling back to a public zap would attach an
     * e-tag pointing at the rumor id on public relays.
     */
    forcePrivate: Boolean = false,
    /** When opening from a quick preset (e.g. chat actions sheet), pre-select that amount in sats. */
    initialSatsHint: Int? = null
) {
    if (!isWalletConnected) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.zap_wallet_not_connected)) },
            text = { Text(stringResource(R.string.zap_connect_wallet)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onGoToWallet()
                }) {
                    Text(stringResource(R.string.btn_go_to_wallet))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
        return
    }

    val context = LocalContext.current
    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
    var presets by remember { mutableStateOf(ZapPreferences(context).getPresets().sortedBy { it.amountSats }) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
    var customAmount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(false) }
    var isPrivate by remember(forcePrivate) { mutableStateOf(forcePrivate) }
    var editMode by remember { mutableStateOf(false) }
    var instantZapsEnabled by remember { mutableStateOf(interfacePrefs.isQuickZapEnabled()) }
    var showLargeAmountConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(initialSatsHint) {
        val hint = initialSatsHint ?: return@LaunchedEffect
        val h = hint.toLong().coerceAtLeast(1L)
        val fromPrefs = ZapPreferences(context).getPresets().sortedBy { it.amountSats }
        val match = fromPrefs.find { it.amountSats == h }
        if (match != null) {
            selectedPreset = match
            isCustom = false
            message = match.message
        } else {
            isCustom = true
            customAmount = h.toString()
            message = ""
        }
    }

    // Seed amount from the configured instant-zap amount on first open,
    // so the user's "preferred opening amount" surfaces even when the
    // long-press shortcut is disabled. iOS does the same — the instant
    // amount is the implicit default, distinct from the preset list.
    LaunchedEffect(Unit) {
        if (initialSatsHint != null) return@LaunchedEffect
        val seedSats = if (fiatMode) {
            // Fiat-mode customAmount is cents (last two digits). Seed the
            // saved fiat amount in major units → cents.
            val major = interfacePrefs.getQuickZapAmountFiat()
            (major * 100.0).toLong().coerceAtLeast(0L)
        } else {
            interfacePrefs.getQuickZapAmountSats()
        }
        if (seedSats > 0) {
            isCustom = true
            customAmount = seedSats.toString()
            message = interfacePrefs.getQuickZapMessage()
        }
    }

    val effectiveAmount = if (isCustom) {
        if (fiatMode) {
            // Register-style: customAmount is a digit-only string that's
            // interpreted as cents (last two digits). "21" → $0.21,
            // "2100" → $21.00.
            val cents = customAmount.toLongOrNull() ?: 0L
            if (cents > 0) {
                ExchangeRateRepository.fiatToSats(cents.toDouble() / 100.0, fiatCurrency) ?: 0L
            } else 0L
        } else {
            customAmount.toLongOrNull() ?: 0L
        }
    } else {
        selectedPreset?.amountSats ?: 0L
    }

    val effectiveMessage = if (isCustom) message else (selectedPreset?.message ?: "")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = WispThemeColors.backgroundColor,
            tonalElevation = 8.dp
        ) {
            Box {
                // Background lightning effect
                LightningBackground(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp))
                )

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header with animated bolt — switches to a coin-stack
                    // glyph in fiat mode so the icon reads as money rather
                    // than zap. Mirrors the iOS dynamic `zapImage`.
                    AnimatedBoltHeader(fiatMode = fiatMode)

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = stringResource(
                            if (fiatMode) R.string.zap_send_money else R.string.zap_send
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))

                    // Amount display — tap to edit directly. In fiat mode the
                    // input is interpreted register-style: digits fill from the
                    // cents place ("21" → $0.21, "2100" → $21.00). The field
                    // is bound to the raw digit string and a
                    // `VisualTransformation` renders it as the formatted
                    // dollar string with the cursor pinned to the end — that
                    // way backspace removes the rightmost digit and Compose
                    // can map cursor positions cleanly (binding the field
                    // directly to the formatted string and rewriting it on
                    // every keystroke broke backspace because Compose
                    // couldn't map the cursor between the old and new
                    // formatted strings).
                    val currency = if (fiatMode) {
                        ExchangeRateRepository.currencyFor(fiatCurrency)
                    } else null
                    val centsTransformation = remember(currency) {
                        currency?.let { CentsVisualTransformation(it) }
                    }
                    if (isCustom) {
                        BasicTextField(
                            value = customAmount,
                            onValueChange = { new ->
                                customAmount = if (fiatMode) sanitizeFiatInput(new) else new.filter { c -> c.isDigit() }
                            },
                            visualTransformation = if (fiatMode && centsTransformation != null) {
                                centsTransformation
                            } else VisualTransformation.None,
                            textStyle = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = LightningOrange,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            cursorBrush = SolidColor(LightningOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.Center) {
                                    if (customAmount.isEmpty()) {
                                        Text(
                                            text = if (fiatMode) "${currency?.symbol ?: ""}0.00" else "0",
                                            style = MaterialTheme.typography.displaySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = LightningOrange.copy(alpha = 0.3f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (!fiatMode) {
                            Text(
                                text = stringResource(R.string.zap_sats),
                                style = MaterialTheme.typography.labelLarge,
                                color = LightningOrange.copy(alpha = 0.7f)
                            )
                        }
                    } else if (effectiveAmount > 0) {
                        Text(
                            text = AmountFormatter.formatShort(effectiveAmount, context),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = LightningOrange,
                            modifier = Modifier
                                .clickable {
                                    // Seed the field with the equivalent cents
                                    // digit string so the user can refine the
                                    // selected preset rather than starting from
                                    // empty in fiat mode.
                                    customAmount = if (fiatMode) {
                                        seedRegisterCents(effectiveAmount, fiatCurrency)
                                    } else {
                                        effectiveAmount.toString()
                                    }
                                    isCustom = true
                                }
                                .padding(vertical = 4.dp)
                        )
                        if (!fiatMode) {
                            Text(
                                text = stringResource(R.string.zap_sats),
                                style = MaterialTheme.typography.labelLarge,
                                color = LightningOrange.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Preset chips header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.zap_quick_amounts),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            if (editMode) {
                                TextButton(onClick = { editMode = false }) {
                                    Text(stringResource(R.string.btn_done), color = LightningOrange, fontSize = 12.sp)
                                }
                            } else {
                                IconButton(
                                    onClick = { editMode = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.btn_edit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Preset amount chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        presets.forEach { preset ->
                            ZapPresetChip(
                                preset = preset,
                                isSelected = !isCustom && selectedPreset == preset,
                                editMode = editMode,
                                onClick = {
                                    if (!editMode) {
                                        selectedPreset = preset
                                        isCustom = false
                                        message = preset.message
                                    }
                                },
                                onRemove = {
                                    presets = ZapPreferences(context).removePreset(preset)
                                    if (selectedPreset == preset) {
                                        selectedPreset = presets.firstOrNull()
                                    }
                                }
                            )
                        }

                        // Custom amount chip
                        ZapChipButton(
                            label = stringResource(R.string.btn_custom),
                            isSelected = isCustom,
                            onClick = { isCustom = true }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Custom amount input
                    AnimatedVisibility(
                        visible = isCustom,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            OutlinedTextField(
                                value = customAmount,
                                onValueChange = { new ->
                                    customAmount = if (fiatMode) sanitizeFiatInput(new) else new.filter { c -> c.isDigit() }
                                },
                                visualTransformation = if (fiatMode && centsTransformation != null) {
                                    centsTransformation
                                } else VisualTransformation.None,
                                label = {
                                    Text(
                                        if (fiatMode) {
                                            stringResource(R.string.placeholder_amount_currency, currency?.symbol ?: "")
                                        } else {
                                            stringResource(R.string.placeholder_amount_sats)
                                        }
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = LightningOrange,
                                    focusedLabelColor = LightningOrange,
                                    cursorColor = LightningOrange
                                )
                            )
                            // Save-as-preset only makes sense for whole-sat
                            // amounts; in fiat mode we always derive sats
                            // through the exchange rate, which can drift.
                            val saveAmount = if (fiatMode) 0L else (customAmount.toLongOrNull() ?: 0L)
                            if (saveAmount > 0) {
                                Spacer(Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        val preset = ZapPreset(saveAmount, message.trim())
                                        presets = ZapPreferences(context).addPreset(preset)
                                        selectedPreset = presets.firstOrNull { it.amountSats == saveAmount }
                                        isCustom = false
                                        customAmount = ""
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_save))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // Message input
                    OutlinedTextField(
                        value = message,
                        onValueChange = { new -> if (!NsecPasteGuard.blockIfNsec(message, new)) message = new },
                        label = { Text(stringResource(R.string.placeholder_message_optional)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LightningOrange,
                            focusedLabelColor = LightningOrange,
                            cursorColor = LightningOrange
                        )
                    )

                    // Clear message when switching away from a preset with a saved message
                    // (message is pre-filled when selecting a preset with a message)

                    Spacer(Modifier.height(16.dp))

                    if (forcePrivate) {
                        // Parent is a private reply — zap is locked to DIP-03 mode and the
                        // anon/private toggles are hidden. A small label keeps the user
                        // oriented; the lock icon mirrors the orange lock used elsewhere.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = LightningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.zap_private_locked_for_private_reply),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    } else {
                    // Anonymous toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.btn_anonymous),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isAnonymous,
                            onCheckedChange = {
                                isAnonymous = it
                                if (it) isPrivate = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LightningOrange,
                                checkedTrackColor = LightningOrange.copy(alpha = 0.5f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    // Private toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.btn_private),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (canPrivateZap) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            if (!canPrivateZap) {
                                Text(
                                    text = stringResource(R.string.zap_both_parties_need_dm_relays),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = {
                                isPrivate = it
                                if (it) isAnonymous = false
                            },
                            enabled = canPrivateZap,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LightningOrange,
                                checkedTrackColor = LightningOrange.copy(alpha = 0.5f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                    } // end !forcePrivate

                    Spacer(Modifier.height(12.dp))

                    // Instant zaps toggle — bound to the same setting as the
                    // Interface screen, so the user can flip it from the
                    // sheet without navigating away. Matches iOS.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (fiatMode) "Instant payments" else "Instant zaps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = instantZapsEnabled,
                            onCheckedChange = {
                                instantZapsEnabled = it
                                interfacePrefs.setQuickZapEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LightningOrange,
                                checkedTrackColor = LightningOrange.copy(alpha = 0.5f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    // 1,000,000-sat hard cap warning.
                    val overCap = effectiveAmount > ZAP_HARD_CAP_SATS
                    if (overCap) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Max ${"%,d".format(ZAP_HARD_CAP_SATS)} sats per zap",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_cancel))
                        }

                        Button(
                            onClick = {
                                // 10K-sat soft confirmation — large amount
                                // intercepts the tap and routes through a
                                // confirm dialog. Below 10K fires immediately.
                                if (effectiveAmount > ZAP_SOFT_CONFIRM_SATS) {
                                    showLargeAmountConfirm = true
                                } else {
                                    onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                                }
                            },
                            enabled = effectiveAmount > 0 && !overCap,
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LightningOrange,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (fiatMode) R.drawable.ic_coin_stack else R.drawable.ic_bolt
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (fiatMode) {
                                    stringResource(
                                        R.string.zap_x_amount,
                                        AmountFormatter.formatShort(effectiveAmount, context)
                                    )
                                } else {
                                    stringResource(R.string.zap_x_sats, effectiveAmount)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // 10K-sat soft-confirmation dialog. Large zaps surface a "double-check
    // before sending" prompt so a stray preset tap doesn't drain a wallet.
    if (showLargeAmountConfirm) {
        AlertDialog(
            onDismissRequest = { showLargeAmountConfirm = false },
            title = { Text("Zap %,d sats?".format(effectiveAmount)) },
            text = { Text("This is a large amount, double-check before sending.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLargeAmountConfirm = false
                        onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LightningOrange)
                ) {
                    Text("Send", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLargeAmountConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

// Hard ceilings shared with the iOS port. Soft = confirmation dialog,
// hard = disables the Zap button entirely.
private const val ZAP_SOFT_CONFIRM_SATS = 10_000L
private const val ZAP_HARD_CAP_SATS = 1_000_000L

/**
 * Translate raw Lightning/SDK error strings into plain user-facing
 * copy. Mirrors iOS `ZapAnimationStore.friendlyMessage(for:)`.
 * Fallback: extract the substring between the first `("` and `")`
 * (Swift enum description wrapper) if present; otherwise pass through
 * the raw error.
 */
internal fun friendlyZapErrorMessage(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isEmpty()) return "Zap failed."
    val lower = msg.lowercase()
    return when {
        "insufficient funds" in lower || "insufficient balance" in lower ->
            "Not enough sats in your wallet."
        "no route" in lower || "route not found" in lower || "unreachable" in lower ->
            "Couldn't find a payment route to the recipient. Try again later."
        "expired" in lower || "invoice has expired" in lower ->
            "The lightning invoice expired before it could be paid. Try again."
        "timeout" in lower || "timed out" in lower ->
            "The payment timed out. Check your connection and try again."
        "no lud16" in lower || "no lightning address" in lower ->
            "This account doesn't have a lightning address."
        "lnurl" in lower && "400" in lower ->
            "The recipient's lightning provider rejected this zap. Try a different amount."
        "amount too small" in lower || "below minimum" in lower ->
            "Amount is below the recipient's minimum. Try a larger zap."
        "amount too large" in lower || "above maximum" in lower ->
            "Amount is above the recipient's maximum. Try a smaller zap."
        else -> {
            val start = msg.indexOf("(\"")
            val end = msg.indexOf("\")", startIndex = (start + 2).coerceAtLeast(0))
            if (start >= 0 && end > start + 2) msg.substring(start + 2, end) else msg
        }
    }
}

/**
 * Register-style sanitiser: digits only. The fiat-mode amount field is
 * interpreted as integer cents (last two digits = cents place), so
 * decimal points and other punctuation in user input are stripped — the
 * decimal in the displayed string ("$0.21") is presentation-only.
 */
private fun sanitizeFiatInput(text: String): String =
    text.filter { it.isDigit() }

/**
 * Format a digit-only string as `$X.XX` register-style — last two digits
 * are cents, everything before them is whole dollars. "21" → "$0.21",
 * "2100" → "$21.00". Used for the empty-field placeholder; the live
 * field display goes through [CentsVisualTransformation] so Compose's
 * cursor mapping works correctly.
 */
private fun formatRegisterCents(digits: String, currency: FiatCurrency): String {
    val cents = digits.toLongOrNull() ?: 0L
    val dollars = cents.toDouble() / 100.0
    val formatter = java.text.DecimalFormat("#,##0.00")
    return "${currency.symbol}${formatter.format(dollars)}"
}

/**
 * Renders a digit-only field value as the formatted register-style
 * dollar string and pegs the cursor to the end. The previous approach
 * — binding the TextField directly to the formatted string and
 * re-formatting in `onValueChange` — broke backspace on Android: when
 * Compose's internal text changed from "$0.2" (after the user removed
 * the last char) to our re-formatted "$0.02", Compose couldn't map the
 * old cursor position into the new string and effectively snapped it
 * to the start, so subsequent backspaces just moved the cursor instead
 * of deleting. With a [VisualTransformation] the field is bound to the
 * raw digits, the cursor lives in raw-string coordinates, and the
 * `OffsetMapping` always points to the end of the formatted view.
 */
private class CentsVisualTransformation(
    private val currency: FiatCurrency
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = formatRegisterCents(text.text, currency)
        val rawLength = text.text.length
        val transformedLength = formatted.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = transformedLength
            override fun transformedToOriginal(offset: Int): Int = rawLength
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/**
 * Cents-as-digits seed for the custom-amount field. Converts the current
 * sats amount through the cached rate, rounds to the nearest cent, and
 * returns the digit string (e.g. 1234 cents → "1234"). Empty for zero
 * or when no rate is cached.
 */
private fun seedRegisterCents(amountSats: Long, currencyCode: String): String {
    val dollars = ExchangeRateRepository.satsToFiat(amountSats, currencyCode) ?: return ""
    val cents = (dollars * 100.0).toLong()
    return if (cents > 0) cents.toString() else ""
}

@Composable
private fun AnimatedBoltHeader(fiatMode: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "bolt")

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val boltScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "boltScale"
    )

    val zapColor = WispThemeColors.zapColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(64.dp)
    ) {
        // Glow circle behind bolt
        Box(
            modifier = Modifier
                .size(56.dp)
                .alpha(glowAlpha)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            zapColor.copy(alpha = 0.4f),
                            zapColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Bolt / coin-stack icon depending on mode
        Icon(
            painter = painterResource(
                if (fiatMode) R.drawable.ic_coin_stack else R.drawable.ic_bolt
            ),
            contentDescription = null,
            tint = zapColor,
            modifier = Modifier
                .size(30.dp)
                .scale(boltScale)
        )
    }
}

@Composable
private fun LightningBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "lightning_bg")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val zapColor = WispThemeColors.zapColor

    // Stable random values for bolt positions
    val boltData = remember {
        List(5) { i ->
            Triple(
                Random(i * 42).nextFloat(),       // x position (0..1)
                Random(i * 42 + 1).nextFloat(),    // y position (0..1)
                Random(i * 42 + 2).nextFloat() * 0.3f + 0.1f  // size scale
            )
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Subtle gradient overlay at the top
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    zapColor.copy(alpha = 0.03f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.4f
            )
        )

        // Animated mini lightning bolts scattered in background
        boltData.forEach { (xFrac, yFrac, scale) ->
            val animatedAlpha = (sin((phase + xFrac) * Math.PI * 2).toFloat() * 0.5f + 0.5f) * 0.06f
            val cx = xFrac * w
            val cy = yFrac * h
            val boltSize = 20f * scale

            drawMiniBolt(
                center = Offset(cx, cy),
                size = boltSize,
                color = zapColor.copy(alpha = animatedAlpha)
            )
        }
    }
}

private fun DrawScope.drawMiniBolt(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - size)
        lineTo(center.x - size * 0.4f, center.y + size * 0.1f)
        lineTo(center.x + size * 0.1f, center.y + size * 0.1f)
        lineTo(center.x - size * 0.1f, center.y + size)
        lineTo(center.x + size * 0.4f, center.y - size * 0.1f)
        lineTo(center.x - size * 0.1f, center.y - size * 0.1f)
        close()
    }
    drawPath(path, color, style = Fill)
}

@Composable
private fun ZapPresetChip(
    preset: ZapPreset,
    isSelected: Boolean,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chipScale"
    )

    Box {
        Surface(
            modifier = Modifier
                .scale(scale)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) LightningOrange else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(listOf(LightningYellow, LightningOrange))
                )
            } else {
                null
            },
            shadowElevation = if (isSelected) 4.dp else 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(3.dp))
                }
                val chipContext = LocalContext.current
                Text(
                    text = AmountFormatter.formatShort(preset.amountSats, chipContext),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preset.message.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.Message,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = if (isSelected) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Remove badge in edit mode
        if (editMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.btn_remove),
                    modifier = Modifier
                        .padding(2.dp)
                        .size(16.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ZapChipButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) LightningOrange else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                1.5.dp,
                Brush.linearGradient(listOf(LightningYellow, LightningOrange))
            )
        } else {
            null
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SaveZapPresetDialog(
    onSave: (ZapPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var presetMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    tint = LightningOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_save))
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.placeholder_amount_sats)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LightningOrange,
                        focusedLabelColor = LightningOrange,
                        cursorColor = LightningOrange
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = presetMessage,
                    onValueChange = { new -> if (!NsecPasteGuard.blockIfNsec(presetMessage, new)) presetMessage = new },
                    label = { Text(stringResource(R.string.placeholder_message_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LightningOrange,
                        focusedLabelColor = LightningOrange,
                        cursorColor = LightningOrange
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sats = amount.toLongOrNull() ?: return@Button
                    onSave(ZapPreset(sats, presetMessage.trim()))
                },
                enabled = (amount.toLongOrNull() ?: 0L) > 0,
                colors = ButtonDefaults.buttonColors(containerColor = LightningOrange)
            ) {
                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

