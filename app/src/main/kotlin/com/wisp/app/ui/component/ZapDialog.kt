package com.wisp.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.ExchangeRateRepository
import com.wisp.app.repo.FiatCurrency
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.ZapPreset
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.ui.util.AmountFormatter
import kotlinx.coroutines.launch

/**
 * Zap composer — iOS-faithful layout in a draggable bottom sheet.
 *
 * Layout, top to bottom:
 *   1. Toolbar           — Close (left, pill) / Presets (right, orange pill)
 *   2. Recipient row     — avatar + display name + lud16 + copy
 *                          (hidden if no `profileLookup` data for the
 *                          `recipientPubkey`)
 *   3. Hero amount       — editable BasicTextField styled as the big
 *                          orange number; doubles as the amount input,
 *                          matching iOS.
 *   4. Preset strip      — wrapping FlowRow of pills + Custom-with-plus chip
 *   5. Message field     — single-line OutlinedTextField
 *   6. Privacy dropdown  — Public / Anonymous / Private with helper text
 *   7. Instant zaps      — toggle bound to `quickZapEnabled` setting
 *   8. Zap button        — full-width orange action button. Over 1M sats
 *                          disables it; over 10K routes through a
 *                          soft-confirmation dialog.
 *
 * Wrapping `ModalBottomSheet` provides drag-handle dismiss, scrim-tap
 * dismiss, and a partial-height presentation so the sheet doesn't take
 * over the whole viewport — fixes the "impossible to dismiss" complaint
 * from the previous Dialog-based version.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    /**
     * Per-account preset store. Must be the same instance the
     * `AppSettingsRepository` registered its `onSyncedFieldChanged`
     * callback on, otherwise preset writes from the dialog land in a
     * different SharedPreferences file than NIP-78 reads from on
     * publish/restore — the symptom is presets appearing not to sync
     * between Android and iOS.
     */
    zapPrefsRepo: ZapPreferences,
    canPrivateZap: Boolean = false,
    /**
     * Lock the zap to DIP-03 private mode (private + anon toggles hidden, isPrivate held true).
     * Used when zapping a NIP-17 private reply — falling back to a public zap would attach an
     * e-tag pointing at the rumor id on public relays.
     */
    forcePrivate: Boolean = false,
    /** When opening from a quick preset (e.g. chat actions sheet), pre-select that amount in sats. */
    initialSatsHint: Int? = null,
    /** Recipient pubkey for the optional recipient header row. */
    recipientPubkey: String? = null,
    /** Profile lookup for the recipient header row. Returns null if unknown. */
    profileLookup: (String) -> ProfileData? = { null }
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
                }) { Text(stringResource(R.string.btn_go_to_wallet)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val accent = WispThemeColors.zapColor

    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
    var presets by remember { mutableStateOf(zapPrefsRepo.getPresets().sortedBy { it.amountSats }) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
    // TextFieldValue so we can pre-select the seeded amount on focus —
    // the first keystroke then replaces the whole seed, matching the
    // iOS "first-keystroke-replaces-seed" UX.
    var customAmountTfv by remember { mutableStateOf(TextFieldValue("")) }
    val customAmount = customAmountTfv.text
    var message by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(false) }
    var isPrivate by remember(forcePrivate) { mutableStateOf(forcePrivate) }
    var instantZapsEnabled by remember { mutableStateOf(interfacePrefs.isQuickZapEnabled()) }
    var showLargeAmountConfirm by remember { mutableStateOf(false) }
    var showEditPresetsSheet by remember { mutableStateOf(false) }
    var privacyMenuExpanded by remember { mutableStateOf(false) }
    val amountFocusRequester = remember { FocusRequester() }

    val recipientProfile = recipientPubkey?.let { profileLookup(it) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    LaunchedEffect(initialSatsHint) {
        val hint = initialSatsHint ?: return@LaunchedEffect
        val h = hint.toLong().coerceAtLeast(1L)
        val match = presets.find { it.amountSats == h }
        if (match != null) {
            selectedPreset = match
            isCustom = false
            message = match.message
        } else {
            isCustom = true
            seedCustomAmount(h.toString()) { customAmountTfv = it }
            message = ""
        }
    }

    // Seed amount from the configured instant-zap amount on first open.
    // Auto-focus the field after the sheet's mount + transition has
    // settled (~450ms) — matches the iOS deferral. The seed is selected
    // so the first keystroke replaces it entirely.
    LaunchedEffect(Unit) {
        if (initialSatsHint == null) {
            val seedSats = if (fiatMode) {
                val major = interfacePrefs.getQuickZapAmountFiat()
                (major * 100.0).toLong().coerceAtLeast(0L)
            } else {
                interfacePrefs.getQuickZapAmountSats()
            }
            if (seedSats > 0) {
                isCustom = true
                seedCustomAmount(seedSats.toString()) { customAmountTfv = it }
                message = interfacePrefs.getQuickZapMessage()
            }
        }
        delay(450)
        runCatching { amountFocusRequester.requestFocus() }
    }

    val effectiveAmount: Long = if (isCustom) {
        if (fiatMode) {
            val cents = customAmount.toLongOrNull() ?: 0L
            if (cents > 0) ExchangeRateRepository.fiatToSats(cents.toDouble() / 100.0, fiatCurrency) ?: 0L else 0L
        } else {
            customAmount.toLongOrNull() ?: 0L
        }
    } else {
        selectedPreset?.amountSats ?: 0L
    }
    val effectiveMessage = if (isCustom) message else (selectedPreset?.message ?: "")
    val overHardCap = effectiveAmount > ZAP_HARD_CAP_SATS
    val canSavePreset = isCustom && effectiveAmount > 0 &&
        presets.none { it.amountSats == effectiveAmount } &&
        presets.size < 8

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        // Drag handle replaces the iOS "swipe down" affordance.
    ) {
        // Two-row stack: scrollable content on top, pinned Zap button
        // at the bottom. `fillMaxSize()` locks the sheet to the full
        // available height from open — without it the sheet sizes to
        // content, and the keyboard rising 450ms later forces a second
        // layout pass that visibly jumps the sheet taller. `imePadding`
        // then lifts the stack above the keyboard within the fixed
        // sheet bounds so the Zap button stays visible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // ── 1. Toolbar ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(text = stringResource(R.string.btn_close), onClick = { closeSheet() })
                PillButton(
                    text = "Presets",
                    onClick = { showEditPresetsSheet = true },
                    contentColor = accent,
                    borderColor = accent.copy(alpha = 0.45f)
                )
            }

            // ── 2. Recipient row (optional) ─────────────────────────
            if (recipientProfile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = recipientProfile.picture,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            recipientProfile.displayString,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        if (!recipientProfile.lud16.isNullOrBlank()) {
                            Text(
                                recipientProfile.lud16,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    if (!recipientProfile.lud16.isNullOrBlank()) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(recipientProfile.lud16!!))
                        }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy lightning address",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ── 3. Hero amount (editable) ───────────────────────────
            // The hero IS the input — matches iOS. Typed digits update
            // the value directly; preset taps seed it; visual
            // transformation inserts thousands separators in bitcoin
            // mode so the displayed number stays readable while the
            // underlying state stays as raw digits.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val heroStyle = TextStyle(
                    color = accent,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                // Hide the text-selection background so the seeded
                // select-all (which powers first-keystroke-replaces-seed)
                // doesn't paint an ugly box behind the hero number. iOS
                // achieves the same UX with no visible selection rect.
                val invisibleSelection = remember(accent) {
                    TextSelectionColors(
                        handleColor = accent,
                        backgroundColor = Color.Transparent
                    )
                }
                CompositionLocalProvider(LocalTextSelectionColors provides invisibleSelection) {
                    BasicTextField(
                        value = customAmountTfv,
                        onValueChange = { newTfv ->
                            val filtered = newTfv.text.filter { it.isDigit() }
                            customAmountTfv = newTfv.copy(text = filtered)
                            if (filtered.isNotEmpty()) isCustom = true
                        },
                        textStyle = heroStyle,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = if (fiatMode) VisualTransformation.None
                            else ThousandsSeparatorTransformation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(amountFocusRequester),
                        decorationBox = { inner ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (customAmountTfv.text.isEmpty()) {
                                    Text(
                                        "0",
                                        style = heroStyle.copy(color = accent.copy(alpha = 0.35f))
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Text(
                    if (fiatMode) ExchangeRateRepository.currencyFor(fiatCurrency).code else "sats",
                    color = accent.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // ── 4. Preset strip ─────────────────────────────────────
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val selected = !isCustom && selectedPreset?.amountSats == preset.amountSats
                    PresetPill(
                        label = AmountFormatter.formatShort(preset.amountSats, context),
                        selected = selected,
                        accent = accent,
                        onClick = {
                            selectedPreset = preset
                            isCustom = false
                            // Seed the hero with the preset's value so
                            // the big number reflects the selection.
                            seedCustomAmount(preset.amountSats.toString()) { customAmountTfv = it }
                            // Auto-fill the preset's optional default
                            // message only when the message field is
                            // currently empty (don't clobber typing).
                            if (preset.message.isNotEmpty() && message.isBlank()) {
                                message = preset.message
                            }
                        }
                    )
                }
                CustomPlusPill(
                    label = if (isCustom && effectiveAmount > 0)
                        AmountFormatter.formatShort(effectiveAmount, context)
                    else "Custom",
                    selected = isCustom,
                    accent = accent,
                    showPlus = canSavePreset,
                    onClick = {
                        isCustom = true
                        if (effectiveAmount == 0L) {
                            customAmountTfv = TextFieldValue("")
                        } else {
                            // Re-seed and select-all so first keystroke replaces.
                            seedCustomAmount(customAmount) { customAmountTfv = it }
                        }
                        runCatching { amountFocusRequester.requestFocus() }
                    },
                    onPlusClick = {
                        // Save the current custom amount as a new preset
                        if (canSavePreset) {
                            presets = zapPrefsRepo.addPreset(
                                ZapPreset(effectiveAmount, message.trim())
                            ).sortedBy { it.amountSats }
                        }
                    }
                )
            }

            // ── 5. Message ──────────────────────────────────────────
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Message (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 6. Privacy dropdown ─────────────────────────────────
            if (!forcePrivate) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { privacyMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (icon, label, helper) = when {
                                isPrivate -> Triple(Icons.Filled.Lock, "Private", "Recipient only — sent via DM-relay route.")
                                isAnonymous -> Triple(Icons.Outlined.VisibilityOff, "Anonymous", "Recipient won't see your identity.")
                                else -> Triple(Icons.Outlined.Visibility, "Public", null)
                            }
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                if (helper != null) {
                                    Text(
                                        helper,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = privacyMenuExpanded,
                        onDismissRequest = { privacyMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Public") },
                            leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                            onClick = {
                                isPrivate = false
                                isAnonymous = false
                                privacyMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Anonymous") },
                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                            onClick = {
                                isAnonymous = true
                                isPrivate = false
                                privacyMenuExpanded = false
                            }
                        )
                        if (canPrivateZap) {
                            DropdownMenuItem(
                                text = { Text("Private") },
                                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                onClick = {
                                    isPrivate = true
                                    isAnonymous = false
                                    privacyMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── 7. Instant zaps toggle ──────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (fiatMode) "Instant payments" else "Instant zaps",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = instantZapsEnabled,
                        onCheckedChange = {
                            instantZapsEnabled = it
                            interfacePrefs.setQuickZapEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }

            } // end scrollable content Column

            // ── 8. Zap button — pinned to the bottom of the sheet ──
            // Lives outside the scrollable region above so it stays on
            // screen even when the keyboard is up. The outer Column's
            // imePadding() ensures it floats above the IME.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (overHardCap) {
                    Text(
                        "Max ${"%,d".format(ZAP_HARD_CAP_SATS)} sats per zap",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {
                        if (effectiveAmount > ZAP_SOFT_CONFIRM_SATS) {
                            showLargeAmountConfirm = true
                        } else {
                            onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                        }
                    },
                    enabled = effectiveAmount > 0 && !overHardCap,
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
                        painter = painterResource(
                            if (fiatMode) R.drawable.ic_coin_stack else R.drawable.ic_bolt
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (fiatMode) "Zap ${AmountFormatter.formatShort(effectiveAmount, context)}"
                        else "Zap ${"%,d".format(effectiveAmount)} sats",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
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
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLargeAmountConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showEditPresetsSheet) {
        EditPresetsSheet(
            initial = presets,
            accent = accent,
            onDismiss = { showEditPresetsSheet = false },
            onSave = { newList ->
                zapPrefsRepo.setPresets(newList)
                presets = newList.sortedBy { it.amountSats }
                showEditPresetsSheet = false
            }
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

private const val ZAP_SOFT_CONFIRM_SATS = 10_000L
private const val ZAP_HARD_CAP_SATS = 1_000_000L

/**
 * Insert thousands separators in the hero number while typing without
 * mutating the underlying raw-digit state. Maps cursor positions so a
 * tap or arrow-key lands on the digit the user expects.
 */
private val ThousandsSeparatorTransformation: VisualTransformation = VisualTransformation { text ->
    val raw = text.text
    if (raw.isEmpty()) return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
    val formatted = try { "%,d".format(raw.toLong()) } catch (_: NumberFormatException) { raw }
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val clamped = offset.coerceIn(0, raw.length)
            val digitsFromRight = raw.length - clamped
            val totalCommas = (raw.length - 1) / 3
            val commasFromRight = ((digitsFromRight - 1).coerceAtLeast(0)) / 3
            val commasBefore = totalCommas - commasFromRight
            return (clamped + commasBefore).coerceIn(0, formatted.length)
        }
        override fun transformedToOriginal(offset: Int): Int {
            val clamped = offset.coerceIn(0, formatted.length)
            var rawOffset = 0
            for (i in 0 until clamped) {
                if (formatted[i] != ',') rawOffset++
            }
            return rawOffset.coerceIn(0, raw.length)
        }
    }
    TransformedText(AnnotatedString(formatted), mapping)
}

/**
 * Seed the custom-amount field with the given text AND select the
 * whole range — so the next keystroke replaces the seed entirely.
 * Lets the user open the sheet, see the configured instant-zap
 * amount, then type a new value over it without backspacing first.
 */
private fun seedCustomAmount(text: String, set: (TextFieldValue) -> Unit) {
    set(TextFieldValue(text = text, selection = TextRange(0, text.length)))
}

/**
 * Pill-shaped text button — used for the toolbar's Close + Presets
 * actions. Border-only by default, fillable via `borderColor`.
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

/** Single preset chip in the FlowRow. Selected = filled accent + white. */
@Composable
private fun PresetPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

/** Custom-amount chip. When selected AND not yet in the preset list,
 *  the trailing + badge becomes tappable to save the value as a preset. */
@Composable
private fun CustomPlusPill(
    label: String,
    selected: Boolean,
    accent: Color,
    showPlus: Boolean,
    onClick: () -> Unit,
    onPlusClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, end = if (showPlus) 4.dp else 18.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
            )
            if (showPlus) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(onClick = onPlusClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Save as preset",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

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
 * iOS-equivalent "Edit Presets" sheet — full list editor reachable from
 * the composer's "Presets" pill. Mirrors the iOS layout: each row has
 * inline editable amount + message text fields, a leading minus icon to
 * remove the row, and a final "+ Add preset" row in accent color. Done
 * persists the list via `zapPrefsRepo.setPresets`, which kicks the
 * NIP-78 debounced publish so the change propagates to the user's other
 * devices.
 *
 * The Add row is disabled while a blank row already exists so the
 * caller can't pile up empty entries (matches iOS behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPresetsSheet(
    initial: List<ZapPreset>,
    accent: Color,
    onDismiss: () -> Unit,
    onSave: (List<ZapPreset>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Working copy — only committed back via `onSave` when Done is
    // pressed, so dismissing via drag-down / scrim discards in-progress
    // edits (matches the iOS sheet's Cancel-on-dismiss semantics).
    var rows by remember {
        mutableStateOf(initial.map { EditableRow(it.amountSats.toString(), it.message) })
    }
    fun closeSheet(commit: Boolean) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (commit) {
                val parsed = rows.mapNotNull { r ->
                    val sats = r.amount.toLongOrNull() ?: return@mapNotNull null
                    if (sats <= 0) null else ZapPreset(sats, r.message.trim())
                }
                onSave(parsed)
            } else {
                onDismiss()
            }
        }
    }
    val hasBlankRow = rows.any { it.amount.isBlank() || (it.amount.toLongOrNull() ?: 0L) == 0L }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // fillMaxHeight expands the sheet to the full available height
        // under the drag handle; ModalBottomSheet's outer container
        // reserves the system insets, so this stops just below the
        // status bar instead of bleeding into it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header row — "Edit Presets" centered, "Done" right-aligned.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(60.dp))
                Text(
                    "Edit Presets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                PillButton(
                    text = stringResource(R.string.btn_done),
                    onClick = { closeSheet(commit = true) },
                    contentColor = accent,
                    borderColor = accent.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column {
                    rows.forEachIndexed { idx, row ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }
                        // Keyed by stable row identity so swiping away one
                        // row doesn't leak its dismiss state into the next
                        // row sliding into its position.
                        val rowKey = remember { java.util.UUID.randomUUID().toString() }
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { target ->
                                if (target == SwipeToDismissBoxValue.EndToStart) {
                                    rows = rows.toMutableList().also { it.removeAt(idx) }
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                // Trailing-swipe affordance — solid iOS-red
                                // panel with a trailing delete glyph. Sized
                                // to fillMaxSize so the panel spans the full
                                // row height and reaches the trailing edge.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFFFF3B30)),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete preset",
                                        tint = Color.White,
                                        modifier = Modifier.padding(end = 24.dp)
                                    )
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = row.amount,
                                    onValueChange = { newVal ->
                                        val filtered = newVal.filter { it.isDigit() }
                                        rows = rows.toMutableList().also {
                                            it[idx] = it[idx].copy(amount = filtered)
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    cursorBrush = SolidColor(accent),
                                    decorationBox = { inner ->
                                        Box {
                                            if (row.amount.isEmpty()) {
                                                Text(
                                                    "Sats",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.7f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = row.message,
                                    onValueChange = { newVal ->
                                        val sanitized = newVal.replace(",", "").replace(":", "")
                                        rows = rows.toMutableList().also {
                                            it[idx] = it[idx].copy(message = sanitized)
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(accent),
                                    decorationBox = { inner ->
                                        Box {
                                            if (row.message.isEmpty()) {
                                                Text(
                                                    "Message (optional)",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.7f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (rows.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                    // Add preset row — iOS disables it while a blank row
                    // already exists so the user finishes the current
                    // entry before adding another.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !hasBlankRow) {
                                rows = rows + EditableRow("", "")
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (hasBlankRow) accent.copy(alpha = 0.35f) else accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Add preset",
                            color = if (hasBlankRow) accent.copy(alpha = 0.35f) else accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Working-copy row used inside the Edit Presets sheet. */
private data class EditableRow(val amount: String, val message: String)
