package com.antz.app.ui.theme

import androidx.compose.ui.graphics.Color

object Themes {
    val themes = listOf(
        ThemePreset(
            name = "custom",
            displayName = "Custom",
            dark = ThemeColors(
                primary = Color(0xFFFF6B35),
                secondary = Color(0xFFFF9472),
                background = Color(0xFF131215),
                surface = Color(0xFF1F1E21),
                surfaceVariant = Color(0xFF2B2A2E),
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                onSurfaceVariant = Color(0xFF9998A0),
                outline = Color(0xFF343338),
                zapColor = Color(0xFFFF6B35),
                repostColor = Color(0xFF4CAF50),
                bookmarkColor = Color(0xFFFF6B35),
                paidColor = Color(0xFFFFD54F)
            ),
            light = ThemeColors(
                primary = Color(0xFFD4501A),
                secondary = Color(0xFFFF9472),
                background = Color(0xFFD8D8D8),
                surface = Color(0xFFE8E8E8),
                surfaceVariant = Color(0xFFCDCDCD),
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                onSurfaceVariant = Color(0xFF333333),
                outline = Color(0xFF999999),
                zapColor = Color(0xFFB8401A),
                repostColor = Color(0xFF2E7D32),
                bookmarkColor = Color(0xFFB8401A),
                paidColor = Color(0xFFC9A000)
            )
        ),
        ThemePreset(
            name = "nord",
            displayName = "Nord",
            dark = ThemeColors(
                primary = Color(0xFF88C0D0),
                secondary = Color(0xFF81A1C1),
                background = Color(0xFF2E3440),
                surface = Color(0xFF3B4252),
                surfaceVariant = Color(0xFF434C5E),
                onBackground = Color(0xFFD8DEE9),
                onSurface = Color(0xFFD8DEE9),
                onSurfaceVariant = Color(0xFFECEFF4),
                outline = Color(0xFF4C566A),
                zapColor = Color(0xFFEBcb8b),
                repostColor = Color(0xFFA3BE8C),
                bookmarkColor = Color(0xFFEBcb8b),
                paidColor = Color(0xFFEBcb8b)
            ),
            light = ThemeColors(
                primary = Color(0xFF456085),
                secondary = Color(0xFF81A1C1),
                background = Color(0xFFDDE4EC),
                surface = Color(0xFFD0D8E2),
                surfaceVariant = Color(0xFFC0CAD8),
                onBackground = Color(0xFF2E3440),
                onSurface = Color(0xFF2E3440),
                onSurfaceVariant = Color(0xFF2E3440),
                outline = Color(0xFF8A96A8),
                zapColor = Color(0xFFB5862E),
                repostColor = Color(0xFF5B7A3A),
                bookmarkColor = Color(0xFFB5862E),
                paidColor = Color(0xFFB5862E)
            )
        ),
        ThemePreset(
            name = "dracula",
            displayName = "Dracula",
            dark = ThemeColors(
                primary = Color(0xFFFF79C6),
                secondary = Color(0xFFBD93F9),
                background = Color(0xFF282A36),
                surface = Color(0xFF2E3040),
                surfaceVariant = Color(0xFF3E4158),
                onBackground = Color(0xFFF8F8F2),
                onSurface = Color(0xFFF8F8F2),
                onSurfaceVariant = Color(0xFFB4B8D8),
                outline = Color(0xFF4A4D6E),
                zapColor = Color(0xFFFFB86C),
                repostColor = Color(0xFF50FA7B),
                bookmarkColor = Color(0xFFFFB86C),
                paidColor = Color(0xFFF1FA8C)
            ),
            light = ThemeColors(
                primary = Color(0xFFD05090),
                secondary = Color(0xFF9A70C8),
                background = Color(0xFFEAEAE0),
                surface = Color(0xFFE0E0D8),
                surfaceVariant = Color(0xFFD0D0C8),
                onBackground = Color(0xFF282A36),
                onSurface = Color(0xFF282A36),
                onSurfaceVariant = Color(0xFF333340),
                outline = Color(0xFF9E9E98),
                zapColor = Color(0xFFD4894A),
                repostColor = Color(0xFF2E8A4A),
                bookmarkColor = Color(0xFFD4894A),
                paidColor = Color(0xFFC9B000)
            )
        ),
        ThemePreset(
            name = "gruvbox",
            displayName = "Gruvbox",
            dark = ThemeColors(
                primary = Color(0xFFFE8019),
                secondary = Color(0xFFFB4934),
                background = Color(0xFF282828),
                surface = Color(0xFF3C3836),
                surfaceVariant = Color(0xFF504945),
                onBackground = Color(0xFFEBDBB2),
                onSurface = Color(0xFFEBDBB2),
                onSurfaceVariant = Color(0xFFA89984),
                outline = Color(0xFF665C54),
                zapColor = Color(0xFFFE8019),
                repostColor = Color(0xFF8EC07C),
                bookmarkColor = Color(0xFFFE8019),
                paidColor = Color(0xFFD79921)
            ),
            light = ThemeColors(
                primary = Color(0xFFA04810),
                secondary = Color(0xFF8B2010),
                background = Color(0xFFF5F0E5),
                surface = Color(0xFFEBE5D8),
                surfaceVariant = Color(0xFFDED6C8),
                onBackground = Color(0xFF3C3836),
                onSurface = Color(0xFF3C3836),
                onSurfaceVariant = Color(0xFF665C54),
                outline = Color(0xFFB8A888),
                zapColor = Color(0xFFB85A10),
                repostColor = Color(0xFF5B7A3A),
                bookmarkColor = Color(0xFFB85A10),
                paidColor = Color(0xFFA07018)
            )
        ),
        ThemePreset(
            name = "everforest",
            displayName = "Everforest",
            dark = ThemeColors(
                primary = Color(0xFFA7C080),
                secondary = Color(0xFF83C092),
                background = Color(0xFF1E2326),
                surface = Color(0xFF2E383C),
                surfaceVariant = Color(0xFF374145),
                onBackground = Color(0xFFD3C6AA),
                onSurface = Color(0xFFD3C6AA),
                onSurfaceVariant = Color(0xFF9DA9A0),
                outline = Color(0xFF414B50),
                zapColor = Color(0xFFE69875),
                repostColor = Color(0xFFA7C080),
                bookmarkColor = Color(0xFFE69875),
                paidColor = Color(0xFFDBBC7F)
            ),
            light = ThemeColors(
                primary = Color(0xFF6A7800),
                secondary = Color(0xFF35A77C),
                background = Color(0xFFEBE5D0),
                surface = Color(0xFFDDD6C0),
                surfaceVariant = Color(0xFFD4CBB4),
                onBackground = Color(0xFF4F5B62),
                onSurface = Color(0xFF4F5B62),
                onSurfaceVariant = Color(0xFF404A50),
                outline = Color(0xFF959088),
                zapColor = Color(0xFFB07850),
                repostColor = Color(0xFF5A7A3A),
                bookmarkColor = Color(0xFFB07850),
                paidColor = Color(0xFF908030)
            )
        ),
        ThemePreset(
            name = "kanagawa",
            displayName = "Kanagawa",
            dark = ThemeColors(
                primary = Color(0xFFCB4B62),
                secondary = Color(0xFF7E9CD8),
                background = Color(0xFF1F1F28),
                surface = Color(0xFF2A2A37),
                surfaceVariant = Color(0xFF363646),
                onBackground = Color(0xFFDCD7BA),
                onSurface = Color(0xFFDCD7BA),
                onSurfaceVariant = Color(0xFFC8C093),
                outline = Color(0xFF6B6B80),
                zapColor = Color(0xFFFF9E3B),
                repostColor = Color(0xFF76946A),
                bookmarkColor = Color(0xFFCB4B62),
                paidColor = Color(0xFFE6C384)
            ),
            light = ThemeColors(
                primary = Color(0xFFCB4B62),
                secondary = Color(0xFF7E9CD8),
                background = Color(0xFFF6F3E8),
                surface = Color(0xFFECE8DC),
                surfaceVariant = Color(0xFFE0DCD0),
                onBackground = Color(0xFF3A3630),
                onSurface = Color(0xFF3A3630),
                onSurfaceVariant = Color(0xFF6A6658),
                outline = Color(0xFFB8B0A0),
                zapColor = Color(0xFFE6A03B),
                repostColor = Color(0xFF6A9A5A),
                bookmarkColor = Color(0xFFD27E99),
                paidColor = Color(0xFFB09040)
            )
        )
    )

    fun getTheme(name: String): ThemePreset = themes.find { it.name == name } ?: themes.first()
    fun getThemeNames(): List<String> = themes.map { it.name }
}

data class ThemePreset(
    val name: String,
    val displayName: String,
    val dark: ThemeColors,
    val light: ThemeColors
)

data class ThemeColors(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val zapColor: Color,
    val repostColor: Color,
    val bookmarkColor: Color,
    val paidColor: Color
)
