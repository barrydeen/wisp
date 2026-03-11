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
                primary = Color(0xFFCC7000),
                secondary = Color(0xFFFFB74D),
                background = Color(0xFFD8D8D8),
                surface = Color(0xFFE8E8E8),
                surfaceVariant = Color(0xFFCDCDCD),
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                onSurfaceVariant = Color(0xFF333333),
                outline = Color(0xFF999999)
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
                primary = Color(0xFF456085),
                secondary = Color(0xFF81A1C1),
                background = Color(0xFFDDE4EC),
                surface = Color(0xFFD0D8E2),
                surfaceVariant = Color(0xFFC0CAD8),
                onBackground = Color(0xFF2E3440),
                onSurface = Color(0xFF2E3440),
                onSurfaceVariant = Color(0xFF2E3440),
                outline = Color(0xFF8A96A8)
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
                primary = Color(0xFF9A70C8),
                secondary = Color(0xFFFF79C6),
                background = Color(0xFFEAEAE0),
                surface = Color(0xFFE0E0D8),
                surfaceVariant = Color(0xFFD0D0C8),
                onBackground = Color(0xFF282A36),
                onSurface = Color(0xFF282A36),
                onSurfaceVariant = Color(0xFF333340),
                outline = Color(0xFF9E9E98)
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
                primary = Color(0xFF991815),
                secondary = Color(0xFFFE8019),
                background = Color(0xFFFBF1C7),
                surface = Color(0xFFEBDBB2),
                surfaceVariant = Color(0xFFD5C4A1),
                onBackground = Color(0xFF3C3836),
                onSurface = Color(0xFF3C3836),
                onSurfaceVariant = Color(0xFF504038),
                outline = Color(0xFFA89880)
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
                primary = Color(0xFFB04540),
                secondary = Color(0xFF9C6ADE),
                background = Color(0xFFF4EEE4),
                surface = Color(0xFFE8E2D8),
                surfaceVariant = Color(0xFFDED6C8),
                onBackground = Color(0xFF57534E),
                onSurface = Color(0xFF57534E),
                onSurfaceVariant = Color(0xFF4A4540),
                outline = Color(0xFFADA8A0)
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
                primary = Color(0xFF1848C0),
                secondary = Color(0xFF8839EF),
                background = Color(0xFFE3E5EA),
                surface = Color(0xFFD5D8E0),
                surfaceVariant = Color(0xFFBEC2CC),
                onBackground = Color(0xFF4C4F69),
                onSurface = Color(0xFF4C4F69),
                onSurfaceVariant = Color(0xFF3C4058),
                outline = Color(0xFF9498A8)
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
                primary = Color(0xFF6A7800),
                secondary = Color(0xFF35A77C),
                background = Color(0xFFEBE5D0),
                surface = Color(0xFFDDD6C0),
                surfaceVariant = Color(0xFFD4CBB4),
                onBackground = Color(0xFF4F5B62),
                onSurface = Color(0xFF4F5B62),
                onSurfaceVariant = Color(0xFF404A50),
                outline = Color(0xFF959088)
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
                primary = Color(0xFF4A80B8),
                secondary = Color(0xFFC678DD),
                background = Color(0xFFE5E5E5),
                surface = Color(0xFFDADADA),
                surfaceVariant = Color(0xFFCACACA),
                onBackground = Color(0xFF282C34),
                onSurface = Color(0xFF282C34),
                onSurfaceVariant = Color(0xFF323640),
                outline = Color(0xFFA0A0A0)
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
                primary = Color(0xFF2090B0),
                secondary = Color(0xFFF7768E),
                background = Color(0xFFE0E4EC),
                surface = Color(0xFFD4D8E0),
                surfaceVariant = Color(0xFFC4C8D4),
                onBackground = Color(0xFF1A1B26),
                onSurface = Color(0xFF1A1B26),
                onSurfaceVariant = Color(0xFF2A2C40),
                outline = Color(0xFF9094A8)
            )
        ),
        ThemePreset(
            name = "earthsong",
            displayName = "Earthsong",
            dark = ThemeColors(
                primary = Color(0xFF6B8E4E),
                secondary = Color(0xFFF5AE2E),
                background = Color(0xFF1A1612),
                surface = Color(0xFF282520),
                surfaceVariant = Color(0xFF3A3530),
                onBackground = Color(0xFFE5C7A9),
                onSurface = Color(0xFFE5C7A9),
                onSurfaceVariant = Color(0xFFA89880),
                outline = Color(0xFF4A4540)
            ),
            light = ThemeColors(
                primary = Color(0xFF4A6B38),
                secondary = Color(0xFF8B6914),
                background = Color(0xFFD8D0C0),
                surface = Color(0xFFCCC4B4),
                surfaceVariant = Color(0xFFB8B0A0),
                onBackground = Color(0xFF2A2420),
                onSurface = Color(0xFF2A2420),
                onSurfaceVariant = Color(0xFF4A4030),
                outline = Color(0xFF908880)
            )
        ),
        ThemePreset(
            name = "neonnight",
            displayName = "Neon Night",
            dark = ThemeColors(
                primary = Color(0xFFDD92F6),
                secondary = Color(0xFF8CE8FF),
                background = Color(0xFF20242D),
                surface = Color(0xFF282E3A),
                surfaceVariant = Color(0xFF353E4C),
                onBackground = Color(0xFFC7C8FF),
                onSurface = Color(0xFFC7C8FF),
                onSurfaceVariant = Color(0xFF9AA0C0),
                outline = Color(0xFF404858)
            ),
            light = ThemeColors(
                primary = Color(0xFF285088),
                secondary = Color(0xFF006070),
                background = Color(0xFFCDD2E0),
                surface = Color(0xFFBEC4D4),
                surfaceVariant = Color(0xFFA8B0C4),
                onBackground = Color(0xFF1E2030),
                onSurface = Color(0xFF1E2030),
                onSurfaceVariant = Color(0xFF383848),
                outline = Color(0xFF8890A0)
            )
        ),
        ThemePreset(
            name = "srcery",
            displayName = "Srcery",
            dark = ThemeColors(
                primary = Color(0xFF2C78BF),
                secondary = Color(0xFF519F50),
                background = Color(0xFF1C1B19),
                surface = Color(0xFF262424),
                surfaceVariant = Color(0xFF303030),
                onBackground = Color(0xFFBAA67F),
                onSurface = Color(0xFFBAA67F),
                onSurfaceVariant = Color(0xFF918175),
                outline = Color(0xFF3A3A3A)
            ),
            light = ThemeColors(
                primary = Color(0xFF225A9A),
                secondary = Color(0xFF407A40),
                background = Color(0xFFD4CFC0),
                surface = Color(0xFFC8C2B4),
                surfaceVariant = Color(0xFFB4AFA0),
                onBackground = Color(0xFF1C1B19),
                onSurface = Color(0xFF1C1B19),
                onSurfaceVariant = Color(0xFF5A5548),
                outline = Color(0xFF989088)
            )
        ),
        ThemePreset(
            name = "kanagawa",
            displayName = "Kanagawa",
            dark = ThemeColors(
                primary = Color(0xFFD27E2A),
                secondary = Color(0xFF7E9AAA),
                background = Color(0xFF1F1F2E),
                surface = Color(0xFF2A2A3E),
                surfaceVariant = Color(0xFF363650),
                onBackground = Color(0xFFC8C8D0),
                onSurface = Color(0xFFC8C8D0),
                onSurfaceVariant = Color(0xFF9090A8),
                outline = Color(0xFF484868)
            ),
            light = ThemeColors(
                primary = Color(0xFFB27018),
                secondary = Color(0xFF5B7080),
                background = Color(0xFFE8E4DC),
                surface = Color(0xFFDCD8D0),
                surfaceVariant = Color(0xFFCAC6BC),
                onBackground = Color(0xFF2A2838),
                onSurface = Color(0xFF2A2838),
                onSurfaceVariant = Color(0xFF4A4858),
                outline = Color(0xFFA0A0A8)
            )
        ),
        ThemePreset(
            name = "ashen",
            displayName = "Ashen",
            dark = ThemeColors(
                primary = Color(0xFFE07850),
                secondary = Color(0xFFB87860),
                background = Color(0xFF1A1815),
                surface = Color(0xFF282420),
                surfaceVariant = Color(0xFF3A3530),
                onBackground = Color(0xFFB8B0A0),
                onSurface = Color(0xFFA8A090),
                onSurfaceVariant = Color(0xFF787068),
                outline = Color(0xFF484440)
            ),
            light = ThemeColors(
                primary = Color(0xFFB05830),
                secondary = Color(0xFF8B5848),
                background = Color(0xFFE8E4DC),
                surface = Color(0xFFD8D4CC),
                surfaceVariant = Color(0xFFC8C4BC),
                onBackground = Color(0xFF38342C),
                onSurface = Color(0xFF38342C),
                onSurfaceVariant = Color(0xFF58544C),
                outline = Color(0xFF908880)
            )
        ),
        ThemePreset(
            name = "miasma",
            displayName = "Miasma",
            dark = ThemeColors(
                primary = Color(0xFF8ABF78),
                secondary = Color(0xFFCD9766),
                background = Color(0xFF1A1816),
                surface = Color(0xFF2A2824),
                surfaceVariant = Color(0xFF3A3834),
                onBackground = Color(0xFFB8B4A8),
                onSurface = Color(0xFFA8A498),
                onSurfaceVariant = Color(0xFF787068),
                outline = Color(0xFF4A4840)
            ),
            light = ThemeColors(
                primary = Color(0xFF5A8A4A),
                secondary = Color(0xFF8B6030),
                background = Color(0xFFE0DCD4),
                surface = Color(0xFFD0CCC4),
                surfaceVariant = Color(0xFFB8B4AC),
                onBackground = Color(0xFF38342C),
                onSurface = Color(0xFF38342C),
                onSurfaceVariant = Color(0xFF58544C),
                outline = Color(0xFF908880)
            )
        ),
        ThemePreset(
            name = "emerald",
            displayName = "Emerald",
            dark = ThemeColors(
                primary = Color(0xFF50C878),
                secondary = Color(0xFF98FB98),
                background = Color(0xFF1A1D1A),
                surface = Color(0xFF252A25),
                surfaceVariant = Color(0xFF353D35),
                onBackground = Color(0xFFD4E5D4),
                onSurface = Color(0xFFD4E5D4),
                onSurfaceVariant = Color(0xFF9CB09C),
                outline = Color(0xFF404D44)
            ),
            light = ThemeColors(
                primary = Color(0xFF2E8B57),
                secondary = Color(0xFF3CB371),
                background = Color(0xFFE0E8E0),
                surface = Color(0xFFD0D8D0),
                surfaceVariant = Color(0xFFB8C4B8),
                onBackground = Color(0xFF1A2A1C),
                onSurface = Color(0xFF1A2A1C),
                onSurfaceVariant = Color(0xFF2A3A2C),
                outline = Color(0xFF889888)
            )
        ),
        ThemePreset(
            name = "amethyst",
            displayName = "Amethyst",
            dark = ThemeColors(
                primary = Color(0xFF9966CC),
                secondary = Color(0xFFDA70D6),
                background = Color(0xFF1D1A24),
                surface = Color(0xFF282433),
                surfaceVariant = Color(0xFF383248),
                onBackground = Color(0xFFE0D8F0),
                onSurface = Color(0xFFE0D8F0),
                onSurfaceVariant = Color(0xFFA898C0),
                outline = Color(0xFF444058)
            ),
            light = ThemeColors(
                primary = Color(0xFF7B4BA8),
                secondary = Color(0xFFB04DAD),
                background = Color(0xFFE8E4F0),
                surface = Color(0xFFD8D4E0),
                surfaceVariant = Color(0xFFC8C4D0),
                onBackground = Color(0xFF2A2838),
                onSurface = Color(0xFF2A2838),
                onSurfaceVariant = Color(0xFF3A3848),
                outline = Color(0xFF9890A8)
            )
        ),
        ThemePreset(
            name = "ruby",
            displayName = "Ruby",
            dark = ThemeColors(
                primary = Color(0xFFE0115F),
                secondary = Color(0xFFFF6B6B),
                background = Color(0xFF1D1618),
                surface = Color(0xFF2A2024),
                surfaceVariant = Color(0xFF3A2830),
                onBackground = Color(0xFFF0D8E0),
                onSurface = Color(0xFFF0D8E0),
                onSurfaceVariant = Color(0xFFB898A0),
                outline = Color(0xFF4A3840)
            ),
            light = ThemeColors(
                primary = Color(0xFFB00B3A),
                secondary = Color(0xFFDD4455),
                background = Color(0xFFF8E8EC),
                surface = Color(0xFFE8D8E0),
                surfaceVariant = Color(0xFFD8C8D0),
                onBackground = Color(0xFF2A1820),
                onSurface = Color(0xFF2A1820),
                onSurfaceVariant = Color(0xFF4A2838),
                outline = Color(0xFF9890A0)
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
