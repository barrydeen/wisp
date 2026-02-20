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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.ZapPreset
import kotlin.math.sin
import kotlin.random.Random

private val LightningYellow = Color(0xFFFFD700)
private val LightningOrange = Color(0xFFFF9800)
private val LightningAmber = Color(0xFFFFC107)
private val LightningDark = Color(0xFFE65100)
private val BoltGlow = Color(0x40FFD700)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String) -> Unit,
    onGoToWallet: () -> Unit
) {
    if (!isWalletConnected) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Wallet Not Connected") },
            text = { Text("Connect a Lightning wallet to send zaps.") },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onGoToWallet()
                }) {
                    Text("Go to Wallet")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
        return
    }

    val context = LocalContext.current
    var presets by remember { mutableStateOf(ZapPreferences(context).getPresets()) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
    var customAmount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }

    val effectiveAmount = if (isCustom) {
        customAmount.toLongOrNull() ?: 0L
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
            color = MaterialTheme.colorScheme.surface,
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
                    // Header with animated bolt
                    AnimatedBoltHeader()

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Send Zap",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))

                    // Amount display
                    if (effectiveAmount > 0) {
                        Text(
                            text = formatDisplayAmount(effectiveAmount),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = LightningOrange,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Text(
                            text = "sats",
                            style = MaterialTheme.typography.labelLarge,
                            color = LightningOrange.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Preset chips header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Quick Amounts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            if (editMode) {
                                TextButton(onClick = { editMode = false }) {
                                    Text("Done", color = LightningOrange, fontSize = 12.sp)
                                }
                            } else {
                                IconButton(
                                    onClick = { editMode = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        "Edit",
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
                                        if (preset.message.isNotEmpty()) {
                                            message = preset.message
                                        }
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

                        // Add preset chip
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showSaveDialog = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add preset",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Save",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Custom amount chip
                        ZapChipButton(
                            label = "Custom",
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
                                onValueChange = { customAmount = it.filter { c -> c.isDigit() } },
                                label = { Text("Amount (sats)") },
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
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // Message input
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Message (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LightningOrange,
                            focusedLabelColor = LightningOrange,
                            cursorColor = LightningOrange
                        )
                    )

                    // Show saved message hint for preset
                    if (!isCustom && selectedPreset?.message?.isNotEmpty() == true) {
                        Text(
                            text = "Preset message: \"${selectedPreset?.message}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, start = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message })
                            },
                            enabled = effectiveAmount > 0,
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LightningOrange,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Filled.ElectricBolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Zap ${formatDisplayAmount(effectiveAmount)}",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Save preset dialog
    if (showSaveDialog) {
        SaveZapPresetDialog(
            onSave = { preset ->
                presets = ZapPreferences(context).addPreset(preset)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

@Composable
private fun AnimatedBoltHeader() {
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
                            LightningYellow.copy(alpha = 0.4f),
                            LightningOrange.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Bolt icon
        Icon(
            Icons.Filled.ElectricBolt,
            contentDescription = null,
            tint = LightningYellow,
            modifier = Modifier
                .size(36.dp)
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
                    LightningYellow.copy(alpha = 0.03f),
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
                color = LightningYellow.copy(alpha = animatedAlpha)
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
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.ElectricBolt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = formatDisplayAmount(preset.amountSats),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preset.message.isNotEmpty()) {
                    Text(
                        text = preset.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        fontSize = 10.sp
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
                    contentDescription = "Remove",
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
                    Icons.Filled.ElectricBolt,
                    contentDescription = null,
                    tint = LightningOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Save Zap Preset")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount (sats)") },
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
                    onValueChange = { presetMessage = it },
                    label = { Text("Default message (optional)") },
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
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDisplayAmount(sats: Long): String = when {
    sats >= 1_000_000 -> String.format("%.1fM", sats / 1_000_000.0)
    sats >= 10_000 -> String.format("%.1fk", sats / 1_000.0)
    sats >= 1_000 -> String.format("%,d", sats)
    else -> sats.toString()
}
