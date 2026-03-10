package com.wisp.app.ui.theme

import androidx.compose.ui.graphics.Color

object Themes {
    val themes = listOf(
        ThemePreset(
            name = "custom",
            displayName = "Custom",
            dark = ThemeColors(
                primary = Color(0xFFFF9800),
                secondary = Color(0xFFFFB74D),
                background = Color(0xFF131215),
                surface = Color(0xFF1F1E21),
                surfaceVariant = Color(0xFF2B2A2E),
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                onSurfaceVariant = Color(0xFF9998A0),
                outline = Color(0xFF343338)
            ),
            light = ThemeColors(
                primary = Color(0xFFFF9800),
                secondary = Color(0xFFFFB74D),
                background = Color(0xFFECECEC),
                surface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFFE0E0E0),
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                onSurfaceVariant = Color(0xFF555555),
                outline = Color(0xFFBBBBBB)
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
                outline = Color(0xFF4C566A)
            ),
            light = ThemeColors(
                primary = Color(0xFF5E81AC),
                secondary = Color(0xFF81A1C1),
                background = Color(0xFFECEFF4),
                surface = Color(0xFFE5E9F0),
                surfaceVariant = Color(0xFFD8DEE9),
                onBackground = Color(0xFF2E3440),
                onSurface = Color(0xFF2E3440),
                onSurfaceVariant = Color(0xFF3B4252),
                outline = Color(0xFFB8C5D6)
            )
        ),
        ThemePreset(
            name = "dracula",
            displayName = "Dracula",
            dark = ThemeColors(
                primary = Color(0xFFBD93F9),
                secondary = Color(0xFFFF79C6),
                background = Color(0xFF282A36),
                surface = Color(0xFF2E3040),
                surfaceVariant = Color(0xFF3E4158),
                onBackground = Color(0xFFF8F8F2),
                onSurface = Color(0xFFF8F8F2),
                onSurfaceVariant = Color(0xFFB4B8D8),
                outline = Color(0xFF4A4D6E)
            ),
            light = ThemeColors(
                primary = Color(0xFFBD93F9),
                secondary = Color(0xFFFF79C6),
                background = Color(0xFFF8F8F2),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFE8E8E8),
                onBackground = Color(0xFF282A36),
                onSurface = Color(0xFF282A36),
                onSurfaceVariant = Color(0xFF44475A),
                outline = Color(0xFFCAC8D5)
            )
        ),
        ThemePreset(
            name = "gruvbox",
            displayName = "Gruvbox",
            dark = ThemeColors(
                primary = Color(0xFFFB4934),
                secondary = Color(0xFFFE8019),
                background = Color(0xFF282828),
                surface = Color(0xFF3C3836),
                surfaceVariant = Color(0xFF504945),
                onBackground = Color(0xFFEBDBB2),
                onSurface = Color(0xFFEBDBB2),
                onSurfaceVariant = Color(0xFFA89984),
                outline = Color(0xFF665C54)
            ),
            light = ThemeColors(
                primary = Color(0xFFCC241D),
                secondary = Color(0xFFFE8019),
                background = Color(0xFFFBF1C7),
                surface = Color(0xFFEBDBB2),
                surfaceVariant = Color(0xFFD5C4A1),
                onBackground = Color(0xFF3C3836),
                onSurface = Color(0xFF3C3836),
                onSurfaceVariant = Color(0xFF665C54),
                outline = Color(0xFFC4B596)
            )
        ),
        ThemePreset(
            name = "rosepine",
            displayName = "Rose Pine",
            dark = ThemeColors(
                primary = Color(0xFFE88B7B),
                secondary = Color(0xFFC4A7E7),
                background = Color(0xFF191724),
                surface = Color(0xFF26233A),
                surfaceVariant = Color(0xFF363449),
                onBackground = Color(0xFFE0DEF4),
                onSurface = Color(0xFFE0DEF4),
                onSurfaceVariant = Color(0xFF6E6A86),
                outline = Color(0xFF44475A)
            ),
            light = ThemeColors(
                primary = Color(0xFFE05A54),
                secondary = Color(0xFF9C6ADE),
                background = Color(0xFFFAF4ED),
                surface = Color(0xFFF4EEE4),
                surfaceVariant = Color(0xFFE8DFD3),
                onBackground = Color(0xFF57534E),
                onSurface = Color(0xFF57534E),
                onSurfaceVariant = Color(0xFF797069),
                outline = Color(0xFFD4D0C8)
            )
        ),
        ThemePreset(
            name = "catppuccin",
            displayName = "Catppuccin",
            dark = ThemeColors(
                primary = Color(0xFF89B4FA),
                secondary = Color(0xFFCBA6F7),
                background = Color(0xFF1E1E2E),
                surface = Color(0xFF313244),
                surfaceVariant = Color(0xFF45475A),
                onBackground = Color(0xFFCDD6F4),
                onSurface = Color(0xFFCDD6F4),
                onSurfaceVariant = Color(0xFFBAC2DE),
                outline = Color(0xFF585B70)
            ),
            light = ThemeColors(
                primary = Color(0xFF1E66F5),
                secondary = Color(0xFF8839EF),
                background = Color(0xFFEFF1F5),
                surface = Color(0xFFE6E9EF),
                surfaceVariant = Color(0xFFCCD0DA),
                onBackground = Color(0xFF4C4F69),
                onSurface = Color(0xFF4C4F69),
                onSurfaceVariant = Color(0xFF5C5F77),
                outline = Color(0xFFBCC0CC)
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
                outline = Color(0xFF414B50)
            ),
            light = ThemeColors(
                primary = Color(0xFF8DA101),
                secondary = Color(0xFF35A77C),
                background = Color(0xFFF3EAD3),
                surface = Color(0xFFF2EFDF),
                surfaceVariant = Color(0xFFE5DFC5),
                onBackground = Color(0xFF4F5B62),
                onSurface = Color(0xFF4F5B62),
                onSurfaceVariant = Color(0xFF829181),
                outline = Color(0xFFB8B4A5)
            )
        ),
        ThemePreset(
            name = "onedark",
            displayName = "One Dark",
            dark = ThemeColors(
                primary = Color(0xFF61AFEF),
                secondary = Color(0xFFC678DD),
                background = Color(0xFF282C34),
                surface = Color(0xFF1E2228),
                surfaceVariant = Color(0xFF2C313C),
                onBackground = Color(0xFFB0B8C4),
                onSurface = Color(0xFFB0B8C4),
                onSurfaceVariant = Color(0xFF9DA5B4),
                outline = Color(0xFF4B5263)
            ),
            light = ThemeColors(
                primary = Color(0xFF61AFEF),
                secondary = Color(0xFFC678DD),
                background = Color(0xFFFAFAFA),
                surface = Color(0xFFF0F0F0),
                surfaceVariant = Color(0xFFE0E0E0),
                onBackground = Color(0xFF282C34),
                onSurface = Color(0xFF282C34),
                onSurfaceVariant = Color(0xFF4B5263),
                outline = Color(0xFFD0D0D0)
            )
        ),
        ThemePreset(
            name = "tokyonight",
            displayName = "Tokyo Night",
            dark = ThemeColors(
                primary = Color(0xFF2AC3DE),
                secondary = Color(0xFFF7768E),
                background = Color(0xFF16161E),
                surface = Color(0xFF1F2335),
                surfaceVariant = Color(0xFF365A77),
                onBackground = Color(0xFFC0CAF5),
                onSurface = Color(0xFFC0CAF5),
                onSurfaceVariant = Color(0xFFA9B1D6),
                outline = Color(0xFF365A77)
            ),
            light = ThemeColors(
                primary = Color(0xFF2AC3DE),
                secondary = Color(0xFFF7768E),
                background = Color(0xFFEEF1F8),
                surface = Color(0xFFE8EAEF),
                surfaceVariant = Color(0xFFD0D4E0),
                onBackground = Color(0xFF1A1B26),
                onSurface = Color(0xFF1A1B26),
                onSurfaceVariant = Color(0xFF414868),
                outline = Color(0xFFB8BDD0)
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
    val outline: Color
)
