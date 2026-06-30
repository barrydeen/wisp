package cooking.zap.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.repo.InterfacePreferences
import cooking.zap.app.repo.ZapPreferences
import cooking.zap.app.repo.ZapPreset
import cooking.zap.app.ui.theme.WispThemeColors
import cooking.zap.app.ui.util.AmountFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zap composer — iOS-faithful layout in a draggable bottom sheet.
 *
 * Layout, top to bottom:
 *   1. Toolbar           — Close (left, pill) / Presets (right, orange pill)
 *   2. Recipient row     — avatar + display name + lud16 + copy
 *   3. Hero amount       — editable BasicTextField styled as the big orange number
 *   4. Preset strip      — wrapping FlowRow of pills + Custom-with-plus chip
 *   5. Message field     — single-line OutlinedTextField
 *   6. Privacy dropdown  — Public / Anonymous / Private with helper text
 *   7. Instant zaps      — toggle bound to the per-account quick-zap setting
 *   8. Zap button        — full-width orange action button; >10K = soft confirm, >1M = disabled
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    zapPrefsRepo: ZapPreferences? = null,
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
    profileLookup: (String) -> ProfileData? = { null },
    /** False when the recipient's profile has no lightning address. */
    recipientHasLud16: Boolean = true,
) {
    if (!isWalletConnected || !recipientHasLud16) {
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
        // Wallet connected but no lud16: fall through so the send surfaces the error.
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val accent = WispThemeColors.zapColor

    val interfacePrefs = remember { InterfacePreferences(context) }
    val effectiveZapPrefsRepo = remember(zapPrefsRepo) {
        zapPrefsRepo ?: ZapPreferences(context)
    }
    var presets by remember { mutableStateOf(effectiveZapPrefsRepo.getPresets().sortedBy { it.amountSats }) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
        if (initialSatsHint == null) {
            val seedSats = interfacePrefs.getQuickZapAmountSats()
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
        customAmount.toLongOrNull() ?: 0L
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
    ) {
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
                                contentDescription = stringResource(R.string.action_copy),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ── 3. Hero amount (editable) ───────────────────────────
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
                        visualTransformation = ThousandsSeparatorTransformation,
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
                    "sats",
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
                            seedCustomAmount(preset.amountSats.toString()) { customAmountTfv = it }
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
                            seedCustomAmount(customAmount) { customAmountTfv = it }
                        }
                        runCatching { amountFocusRequester.requestFocus() }
                    },
                    onPlusClick = {
                        if (canSavePreset) {
                            presets = effectiveZapPrefsRepo.addPreset(
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
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bolt),
                            contentDescription = null,
                            tint = if (instantZapsEnabled) accent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Instant zaps",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
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
                    if (instantZapsEnabled) {
                        val instantSats = interfacePrefs.getQuickZapAmountSats()
                        val instantMsg = interfacePrefs.getQuickZapMessage()
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            thickness = 0.5.dp
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "%,d sats".format(instantSats),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accent
                                )
                                if (instantMsg.isNotBlank()) {
                                    Text(
                                        "·",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        instantMsg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                "configure in Presets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            } // end scrollable content Column

            // ── 8. Zap button — pinned to bottom ────────────────────
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
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Zap ${"%,d".format(effectiveAmount)} sats",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            }
        }
    }

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
            interfacePrefs = interfacePrefs,
            onDismiss = { showEditPresetsSheet = false },
            onSave = { newList ->
                effectiveZapPrefsRepo.setPresets(newList)
                presets = newList.sortedBy { it.amountSats }
                // Refresh the instant zap toggle state in case the user changed it
                instantZapsEnabled = interfacePrefs.isQuickZapEnabled()
                showEditPresetsSheet = false
            }
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

private const val ZAP_SOFT_CONFIRM_SATS = 10_000L
private const val ZAP_HARD_CAP_SATS = 1_000_000L

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

private fun seedCustomAmount(text: String, set: (TextFieldValue) -> Unit) {
    set(TextFieldValue(text = text, selection = TextRange(0, text.length)))
}

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
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onPlusClick),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.25f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Save preset",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPresetsSheet(
    initial: List<ZapPreset>,
    accent: Color,
    interfacePrefs: InterfacePreferences,
    onDismiss: () -> Unit,
    onSave: (List<ZapPreset>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var rows by remember {
        mutableStateOf(initial.map { EditableRow(it.amountSats.toString(), it.message) })
    }
    var instantEnabled by remember { mutableStateOf(interfacePrefs.isQuickZapEnabled()) }
    // Track the instant zap amount by index so it follows row edits
    var instantIdx by remember {
        val savedSats = interfacePrefs.getQuickZapAmountSats()
        val idx = initial.indexOfFirst { it.amountSats == savedSats }
        mutableStateOf(if (idx >= 0) idx else 0)
    }

    fun closeSheet(commit: Boolean) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (commit) {
                val parsed = rows.mapNotNull { r ->
                    val sats = r.amount.toLongOrNull() ?: return@mapNotNull null
                    if (sats <= 0) null else ZapPreset(sats, r.message.trim())
                }
                // Persist instant zap settings
                interfacePrefs.setQuickZapEnabled(instantEnabled)
                val safeIdx = instantIdx.coerceIn(0, (parsed.size - 1).coerceAtLeast(0))
                parsed.getOrNull(safeIdx)?.let { preset ->
                    interfacePrefs.setQuickZapAmountSats(preset.amountSats)
                    interfacePrefs.setQuickZapMessage(preset.message)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ──────────────────────────────────────────────
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
            Spacer(Modifier.height(16.dp))

            // ── Instant zap toggle ──────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = if (instantEnabled) accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Instant zaps",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Long-press the bolt to send the zap preset instantly",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = instantEnabled,
                        onCheckedChange = { instantEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Preset rows ─────────────────────────────────────────
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
                        val isInstantRow = instantEnabled && idx == instantIdx
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { target ->
                                if (target == SwipeToDismissBoxValue.EndToStart) {
                                    rows = rows.toMutableList().also { it.removeAt(idx) }
                                    if (instantIdx >= rows.size) instantIdx = (rows.size - 1).coerceAtLeast(0)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
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
                                    .padding(start = 14.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = row.amount,
                                    onValueChange = { newVal ->
                                        val filtered = newVal.filter { it.isDigit() }
                                        rows = rows.toMutableList().also { it[idx] = it[idx].copy(amount = filtered) }
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
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                                        rows = rows.toMutableList().also { it[idx] = it[idx].copy(message = sanitized) }
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
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                // Radio button — tap to designate as the instant zap preset
                                androidx.compose.material3.RadioButton(
                                    selected = isInstantRow,
                                    onClick = { if (instantEnabled) instantIdx = idx },
                                    enabled = instantEnabled,
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                        selectedColor = accent,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        disabledSelectedColor = accent.copy(alpha = 0.4f),
                                        disabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                    )
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
        }
    }
}

private data class EditableRow(val amount: String, val message: String)
