package cooking.zap.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cooking.zap.app.viewmodel.CookingTimer
import cooking.zap.app.viewmodel.CookingTimerViewModel

// ──────────────────────────────────────────────────────────────────────────────
// Entry point
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingUtilitiesSheet(
    timerViewModel: CookingTimerViewModel,
    onMinimize: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by rememberSaveable { mutableStateOf(Tab.TIMER) }

    // Request POST_NOTIFICATIONS on Android 13+ so timer alerts reach the shade.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled by the system; no local state needed */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            timerViewModel.needsNotificationPermission
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Extra breathing room above the tab strip
            Spacer(Modifier.height(8.dp))
            SheetHeader(
                activeTab = activeTab,
                onTabChange = { activeTab = it },
                onMinimize = onMinimize,
                onClose = onDismiss
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            when (activeTab) {
                Tab.TIMER -> TimerTabContent(viewModel = timerViewModel)
                Tab.CONVERTER -> ConverterTabContent()
            }
        }
    }
}

private enum class Tab { TIMER, CONVERTER }

// ──────────────────────────────────────────────────────────────────────────────
// Sheet header
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun SheetHeader(
    activeTab: Tab,
    onTabChange: (Tab) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabPill(
            label = "Timer",
            icon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) },
            selected = activeTab == Tab.TIMER,
            onClick = { onTabChange(Tab.TIMER) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        TabPill(
            label = "Converter",
            icon = { Icon(Icons.Outlined.Calculate, contentDescription = null, modifier = Modifier.size(16.dp)) },
            selected = activeTab == Tab.CONVERTER,
            onClick = { onTabChange(Tab.CONVERTER) }
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onMinimize, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Minimize",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "tab_bg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tab_fg"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides fg
        ) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Timer tab
// ──────────────────────────────────────────────────────────────────────────────

private data class CookingPreset(val emoji: String, val name: String, val minutes: Int)

private val COOKING_PRESETS = listOf(
    CookingPreset("🥚", "Poached", 4),
    CookingPreset("🥚", "Hard Boiled", 10),
    CookingPreset("🍝", "Pasta", 8),
    CookingPreset("🍚", "Rice", 18),
    CookingPreset("🥩", "Steak Rest", 5),
    CookingPreset("🥦", "Steam Veg", 7),
    CookingPreset("🥘", "Casserole", 45),
    CookingPreset("🥔", "Baked Potato", 60)
)

private val QUICK_MINUTES = listOf(1, 3, 5, 10, 15, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerTabContent(viewModel: CookingTimerViewModel) {
    val timers by viewModel.timers.collectAsState()
    var label by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(5) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Add-timer row
        item {
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = if (minutes == 0) "" else minutes.toString(),
                    onValueChange = { v ->
                        minutes = v.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(72.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (minutes > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(enabled = minutes > 0) {
                            viewModel.addTimer(label, minutes)
                            label = ""
                            minutes = 5
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+",
                        fontSize = 24.sp,
                        color = if (minutes > 0) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Active timers — one horizontal row, evenly spaced
        item {
            if (timers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active timers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timers.forEach { timer ->
                        ActiveTimerCard(
                            timer = timer,
                            onReset = { viewModel.resetTimer(timer.id) },
                            onRemove = { viewModel.removeTimer(timer.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (timers.any { it.isFinished }) {
                    TextButton(
                        onClick = { viewModel.clearFinished() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear finished") }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // Quick time buttons — evenly spaced single row
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QUICK_MINUTES.forEach { min ->
                    OutlinedButton(
                        onClick = { viewModel.addTimer("", min) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp, vertical = 9.dp
                        )
                    ) {
                        Text(
                            "${min}m",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // Cooking presets
        item {
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text(
                "COOKING PRESETS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em
            )
            Spacer(Modifier.height(12.dp))
        }

        val rows = COOKING_PRESETS.chunked(4)
        items(rows) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { preset ->
                    PresetCard(
                        emoji = preset.emoji,
                        name = preset.name,
                        minutes = preset.minutes,
                        onClick = { viewModel.addTimer(preset.name, preset.minutes) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ActiveTimerCard(
    timer: CookingTimer,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (timer.totalSeconds > 0)
        timer.remainingSeconds.toFloat() / timer.totalSeconds else 0f

    val surfaceColor by animateColorAsState(
        if (timer.isFinished) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "timer_card_bg"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(surfaceColor)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = timer.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (timer.isFinished) "Done!" else formatSeconds(timer.remainingSeconds),
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = (-0.01).em,
                fontFeatureSettings = "tnum",
                lineHeight = 32.sp
            ),
            color = if (timer.isFinished) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
        if (!timer.isFinished) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (timer.isFinished) {
                IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Restart",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    emoji: String,
    name: String,
    minutes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${minutes} min",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Converter tab
// ──────────────────────────────────────────────────────────────────────────────

private enum class UnitCategory { Volume, Weight, Temperature }

private data class MeasUnit(
    val display: String,
    val abbrev: String,
    val category: UnitCategory,
    val toBase: Double = 1.0
) {
    fun convertToBase(value: Double): Double = when {
        category == UnitCategory.Temperature && display == "°F" -> (value - 32) * 5.0 / 9.0
        else -> value * toBase
    }
    fun convertFromBase(base: Double): Double = when {
        category == UnitCategory.Temperature && display == "°F" -> base * 9.0 / 5.0 + 32
        else -> base / toBase
    }
}

private val ALL_UNITS = listOf(
    MeasUnit("tsp", "tsp", UnitCategory.Volume, 4.92892),
    MeasUnit("tbsp", "tbsp", UnitCategory.Volume, 14.7868),
    MeasUnit("cup", "cup", UnitCategory.Volume, 236.588),
    MeasUnit("fl oz", "fl oz", UnitCategory.Volume, 29.5735),
    MeasUnit("mL", "mL", UnitCategory.Volume, 1.0),
    MeasUnit("L", "L", UnitCategory.Volume, 1000.0),
    MeasUnit("g", "g", UnitCategory.Weight, 1.0),
    MeasUnit("kg", "kg", UnitCategory.Weight, 1000.0),
    MeasUnit("oz", "oz", UnitCategory.Weight, 28.3495),
    MeasUnit("lb", "lb", UnitCategory.Weight, 453.592),
    MeasUnit("°C", "°C", UnitCategory.Temperature),
    MeasUnit("°F", "°F", UnitCategory.Temperature)
)

private data class QuickPreset(val amount: Double, val unit: MeasUnit)

private val QUICK_PRESETS = listOf(
    QuickPreset(1.0, ALL_UNITS.first { it.abbrev == "tsp" }),
    QuickPreset(1.0, ALL_UNITS.first { it.abbrev == "tbsp" }),
    QuickPreset(1.0, ALL_UNITS.first { it.abbrev == "cup" }),
    QuickPreset(250.0, ALL_UNITS.first { it.abbrev == "mL" }),
    QuickPreset(100.0, ALL_UNITS.first { it.abbrev == "g" }),
    QuickPreset(1.0, ALL_UNITS.first { it.abbrev == "oz" })
)

private fun convert(amount: Double, from: MeasUnit, to: MeasUnit): Double? {
    if (from.category != to.category) return null
    if (from == to) return amount
    return to.convertFromBase(from.convertToBase(amount))
}

internal fun formatResult(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.4g".format(value).trimEnd('0').trimEnd('.')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConverterTabContent() {
    var amountText by rememberSaveable { mutableStateOf("") }
    var fromUnit by remember { mutableStateOf(ALL_UNITS.first { it.abbrev == "cup" }) }
    var toUnit by remember { mutableStateOf(ALL_UNITS.first { it.abbrev == "mL" }) }

    val amount = amountText.toDoubleOrNull()
    val result = if (amount != null) convert(amount, fromUnit, toUnit) else null

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(Modifier.height(20.dp))
            Text("Amount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("From", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            UnitPicker(
                selected = fromUnit,
                onSelect = { u ->
                    if (u.category != toUnit.category)
                        toUnit = ALL_UNITS.first { it.category == u.category && it != u }
                    fromUnit = u
                }
            )
            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { val tmp = fromUnit; fromUnit = toUnit; toUnit = tmp },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SwapVert,
                        contentDescription = "Swap units",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("To", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            UnitPicker(
                selected = toUnit,
                onSelect = { u ->
                    if (u.category != fromUnit.category)
                        fromUnit = ALL_UNITS.first { it.category == u.category && it != u }
                    toUnit = u
                }
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (result != null) "${formatResult(result)} ${toUnit.abbrev}" else "—",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.02).em,
                    color = if (result != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text(
                "QUICK PRESETS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QUICK_PRESETS.forEach { preset ->
                    OutlinedButton(
                        onClick = {
                            amountText = formatResult(preset.amount)
                            fromUnit = preset.unit
                            if (toUnit.category != preset.unit.category)
                                toUnit = ALL_UNITS.first { it.category == preset.unit.category && it != preset.unit }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp, vertical = 9.dp
                        )
                    ) {
                        Text(
                            "${formatResult(preset.amount)} ${preset.unit.abbrev}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(selected: MeasUnit, onSelect: (MeasUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.display,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UnitCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            category.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    onClick = {},
                    enabled = false
                )
                ALL_UNITS.filter { it.category == category }.forEach { unit ->
                    DropdownMenuItem(
                        text = { Text(unit.display) },
                        onClick = { onSelect(unit); expanded = false },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
