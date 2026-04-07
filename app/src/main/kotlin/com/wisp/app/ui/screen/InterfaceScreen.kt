package com.wisp.app.ui.screen

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.wisp.app.R
import com.wisp.app.repo.DiagnosticLogger
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.LocaleRepository
import com.wisp.app.ui.theme.ThemePreset
import com.wisp.app.ui.theme.Themes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterfaceScreen(
    application: Application,
    interfacePrefs: InterfacePreferences,
    onBack: () -> Unit,
    onChanged: () -> Unit
) {
    var isLargeText by remember { mutableStateOf(interfacePrefs.isLargeText()) }
    var newNotesHidden by remember { mutableStateOf(interfacePrefs.isNewNotesButtonHidden()) }
    var clientTagEnabled by remember { mutableStateOf(interfacePrefs.isClientTagEnabled()) }
    var autoLoadMedia by remember { mutableStateOf(interfacePrefs.isAutoLoadMedia()) }
    var videoAutoPlay by remember { mutableStateOf(interfacePrefs.isVideoAutoPlay()) }
    var liveStreamsHidden by remember { mutableStateOf(interfacePrefs.isLiveStreamsHidden()) }
    var selectedTheme by remember { mutableStateOf(interfacePrefs.getTheme()) }
    var isCustomTheme by remember { mutableStateOf(selectedTheme == "custom") }
    var selectedLanguage by remember { mutableStateOf(interfacePrefs.getLanguage()) }
    var languagesExpanded by remember { mutableStateOf(false) }

    val savedColor = remember { Color(interfacePrefs.getAccentColor()) }
    val initialHsv = remember {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(savedColor.toArgb(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }

    val currentColor = remember(hue, saturation, brightness) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_interface)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Language section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { languagesExpanded = !languagesExpanded }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = LocaleRepository.getLanguageDisplayName(selectedLanguage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (languagesExpanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (languagesExpanded) {
                Spacer(Modifier.height(8.dp))
                LocaleRepository.supportedLanguages.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedLanguage = language.code
                                interfacePrefs.setLanguage(language.code)
                                LocaleRepository.applyLanguage(application, language.code)
                                languagesExpanded = false
                                (application as? android.app.Activity)?.let { activity ->
                                    activity.finish()
                                    activity.startActivity(activity.intent)
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedLanguage == language.code) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedLanguage == language.code) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Text Size section
            Text(
                text = stringResource(R.string.settings_text_size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_large_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_increase_text_size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isLargeText,
                    onCheckedChange = {
                        isLargeText = it
                        interfacePrefs.setLargeText(it)
                        onChanged()
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Popular Themes section
            var themesExpanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { themesExpanded = !themesExpanded }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_popular_themes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_choose_color_scheme),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (themesExpanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (themesExpanded) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Themes.themes.forEach { theme ->
                        ThemeCard(
                            theme = theme,
                            isSelected = selectedTheme == theme.name,
                            isDark = true,
                            onClick = {
                                selectedTheme = theme.name
                                isCustomTheme = theme.name == "custom"
                                interfacePrefs.setTheme(theme.name)
                                onChanged()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (isCustomTheme) {
                // Accent Color section — collapsed by default, tap to expand
                var colorPickerExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { colorPickerExpanded = !colorPickerExpanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(currentColor)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_accent_color),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap to customize",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = if (colorPickerExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = colorPickerExpanded) {
                    Column {
                        Spacer(Modifier.height(12.dp))

                        // Saturation/Brightness square
                        SatBrightnessSquare(
                            hue = hue,
                            saturation = saturation,
                            brightness = brightness,
                            onChanged = { s, b ->
                                saturation = s
                                brightness = b
                                interfacePrefs.setAccentColor(
                                    android.graphics.Color.HSVToColor(floatArrayOf(hue, s, b))
                                )
                                onChanged()
                            }
                        )

                        Spacer(Modifier.height(12.dp))

                        // Hue slider
                        HueBar(
                            hue = hue,
                            onHueChanged = { h ->
                                hue = h
                                interfacePrefs.setAccentColor(
                                    android.graphics.Color.HSVToColor(floatArrayOf(h, saturation, brightness))
                                )
                                onChanged()
                            }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // New Notes Button section
            Text(
                text = stringResource(R.string.settings_new_notes_button),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_hide_new_notes_button), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_hide_new_notes_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = newNotesHidden,
                    onCheckedChange = {
                        newNotesHidden = it
                        interfacePrefs.setNewNotesButtonHidden(it)
                        onChanged()
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Media section
            Text(
                text = stringResource(R.string.settings_media),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_load_media), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_auto_load_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoLoadMedia,
                    onCheckedChange = {
                        autoLoadMedia = it
                        interfacePrefs.setAutoLoadMedia(it)
                        onChanged()
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_video_autoplay), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_video_autoplay_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = videoAutoPlay,
                    onCheckedChange = {
                        videoAutoPlay = it
                        interfacePrefs.setVideoAutoPlay(it)
                        onChanged()
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_hide_live_streams), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_hide_live_streams_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = liveStreamsHidden,
                    onCheckedChange = {
                        liveStreamsHidden = it
                        interfacePrefs.setLiveStreamsHidden(it)
                        onChanged()
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Client Tag section
            Text(
                text = stringResource(R.string.settings_client_tag),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_tag_notes), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_tag_notes_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = clientTagEnabled,
                    onCheckedChange = {
                        clientTagEnabled = it
                        interfacePrefs.setClientTagEnabled(it)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Zap icon toggle
            var zapBoltIcon by remember { mutableStateOf(interfacePrefs.isZapBoltIcon()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Zap Icon", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Outlined.CurrencyBitcoin,
                        contentDescription = "Bitcoin symbol",
                        tint = if (!zapBoltIcon) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable {
                                zapBoltIcon = false
                                interfacePrefs.setZapBoltIcon(false)
                            }
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = "Lightning bolt",
                        tint = if (zapBoltIcon) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .height(24.dp)
                            .clickable {
                                zapBoltIcon = true
                                interfacePrefs.setZapBoltIcon(true)
                            }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Version — long-press 5 times to reveal diagnostic mode
            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
            }
            var tapCount by remember { mutableIntStateOf(0) }
            var diagnosticRevealed by remember { mutableStateOf(DiagnosticLogger.isEnabled) }
            var diagnosticEnabled by remember { mutableStateOf(DiagnosticLogger.isEnabled) }

            Text(
                text = "Wisp v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        tapCount++
                        if (tapCount >= 5) diagnosticRevealed = true
                    }
                    .padding(vertical = 8.dp)
            )

            if (diagnosticRevealed) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.diagnostic_mode), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.diagnostic_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = diagnosticEnabled,
                        onCheckedChange = {
                            diagnosticEnabled = it
                            DiagnosticLogger.setEnabled(context, it)
                        }
                    )
                }

                if (diagnosticEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.share_logs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    val logFile = DiagnosticLogger.getLogFile(context)
                                    if (logFile.exists()) {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            logFile
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_diagnostic_logs)))
                                    }
                                }
                                .padding(vertical = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.clear_logs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { DiagnosticLogger.clear(context) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChanged: (Float) -> Unit
) {
    val hueColors = remember {
        List(7) { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))) }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onHueChanged((offset.x / size.width * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChanged((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(hueColors))

        // Indicator
        val x = (hue / 360f) * size.width
        drawCircle(
            color = Color.White,
            radius = 14.dp.toPx(),
            center = Offset(x.coerceIn(14.dp.toPx(), size.width - 14.dp.toPx()), size.height / 2),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
private fun SatBrightnessSquare(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChanged: (saturation: Float, brightness: Float) -> Unit
) {
    val pureHueColor = remember(hue) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val b = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    onChanged(s, b)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val b = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    onChanged(s, b)
                }
            }
    ) {
        // White to hue color (horizontal: saturation)
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
        // Transparent to black (vertical: brightness)
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        // Indicator
        val x = (saturation * size.width).coerceIn(10f, size.width - 10f)
        val y = ((1f - brightness) * size.height).coerceIn(10f, size.height - 10f)
        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.Black,
            radius = 12.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
private fun ThemeCard(
    theme: ThemePreset,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val colors = if (isDark) theme.dark else theme.light

    Card(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(colors.primary, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(colors.secondary, RoundedCornerShape(4.dp))
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(colors.background, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface
            )
        }
    }
}
