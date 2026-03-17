package com.wisp.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.core.graphics.ColorUtils

val LocalThemeEffect = androidx.compose.runtime.staticCompositionLocalOf { ThemeEffect.NONE }

private val WispTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp)
)

private val WispTypographyLarge = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    bodySmall = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 13.sp)
)

private fun lightenColor(color: Color, fraction: Float = 0.3f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.7f).coerceIn(0f, 1f)
    hsl[2] = (hsl[2] + (1f - hsl[2]) * fraction).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun createGlowTypography(glowColor: Color): Typography {
    val glow = Shadow(color = glowColor.copy(alpha = 0.6f), blurRadius = 8f, offset = androidx.compose.ui.geometry.Offset.Zero)
    val glowStrong = Shadow(color = glowColor.copy(alpha = 0.8f), blurRadius = 12f, offset = androidx.compose.ui.geometry.Offset.Zero)
    return Typography(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, shadow = glowStrong),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, shadow = glow),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, shadow = glow),
        bodyMedium = TextStyle(fontSize = 14.sp, shadow = glow),
        bodySmall = TextStyle(fontSize = 12.sp, shadow = glow),
        labelSmall = TextStyle(fontSize = 11.sp, shadow = glow)
    )
}

private fun createGlowTypographyLarge(glowColor: Color): Typography {
    val glow = Shadow(color = glowColor.copy(alpha = 0.6f), blurRadius = 8f, offset = androidx.compose.ui.geometry.Offset.Zero)
    val glowStrong = Shadow(color = glowColor.copy(alpha = 0.8f), blurRadius = 12f, offset = androidx.compose.ui.geometry.Offset.Zero)
    return Typography(
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, shadow = glowStrong),
        titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, shadow = glow),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, shadow = glow),
        bodyMedium = TextStyle(fontSize = 16.sp, shadow = glow),
        bodySmall = TextStyle(fontSize = 14.sp, shadow = glow),
        labelSmall = TextStyle(fontSize = 13.sp, shadow = glow)
    )
}

@Composable
fun WispTheme(
    isDarkTheme: Boolean = true,
    accentColor: Color = Color(0xFFFF9800),
    isLargeText: Boolean = false,
    themeName: String = "custom",
    content: @Composable () -> Unit
) {
    val themePreset = remember(themeName) { Themes.getTheme(themeName) }
    val themeEffect = remember(themeName) { Themes.getThemeEffect(themeName) }
    val isCustomTheme = themeName == "custom"

    val primary = if (isCustomTheme) accentColor else themePreset.dark.primary
    val secondary = remember(primary) { lightenColor(primary) }

    val colorScheme = if (isDarkTheme) {
        if (isCustomTheme) {
            darkColorScheme(
                primary = accentColor,
                onPrimary = Color.White,
                secondary = secondary,
                background = Color(0xFF131215),
                surface = Color(0xFF1F1E21),
                surfaceVariant = Color(0xFF2B2A2E),
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                onSurfaceVariant = Color(0xFF9998A0),
                outline = Color(0xFF343338)
            )
        } else {
            val colors = themePreset.dark
            darkColorScheme(
                primary = colors.primary,
                onPrimary = Color.White,
                secondary = colors.secondary,
                background = colors.background,
                surface = colors.surface,
                surfaceVariant = colors.surfaceVariant,
                onBackground = colors.onBackground,
                onSurface = colors.onSurface,
                onSurfaceVariant = colors.onSurfaceVariant,
                outline = colors.outline
            )
        }
    } else {
        if (isCustomTheme) {
            lightColorScheme(
                primary = accentColor,
                onPrimary = Color.White,
                secondary = secondary,
                background = Color(0xFFECECEC),
                surface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFFE0E0E0),
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                onSurfaceVariant = Color(0xFF6B6B6B),
                outline = Color(0xFFCCCCCC)
            )
        } else {
            val colors = themePreset.light
            lightColorScheme(
                primary = colors.primary,
                onPrimary = Color.White,
                secondary = colors.secondary,
                background = colors.background,
                surface = colors.surface,
                surfaceVariant = colors.surfaceVariant,
                onBackground = colors.onBackground,
                onSurface = colors.onSurface,
                onSurfaceVariant = colors.onSurfaceVariant,
                outline = colors.outline
            )
        }
    }

    val typography = when {
        themeEffect == ThemeEffect.CRT && isDarkTheme && isLargeText -> createGlowTypographyLarge(primary)
        themeEffect == ThemeEffect.CRT && isDarkTheme -> createGlowTypography(primary)
        isLargeText -> WispTypographyLarge
        else -> WispTypography
    }

    val effectiveEffect = if (isDarkTheme) themeEffect else ThemeEffect.NONE

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = {
            CompositionLocalProvider(LocalThemeEffect provides effectiveEffect) {
                Box(modifier = Modifier) {
                    content()
                    ThemeEffectOverlay(
                        effect = effectiveEffect,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
            }
        }
    )
}
