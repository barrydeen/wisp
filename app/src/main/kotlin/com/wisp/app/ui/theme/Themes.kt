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
                outline = Color(0xFF343338),
                zapColor = Color(0xFFFF9800),
                repostColor = Color(0xFF4CAF50),
                bookmarkColor = Color(0xFFFF9800),
                paidColor = Color(0xFFFFD54F)
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
                outline = Color(0xFF999999),
                zapColor = Color(0xFFB85C00),
                repostColor = Color(0xFF2E7D32),
                bookmarkColor = Color(0xFFB85C00),
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
                outline = Color(0xFF585B70),
                zapColor = Color(0xFFFAB387),
                repostColor = Color(0xFFA6E3A1),
                bookmarkColor = Color(0xFFFAB387),
                paidColor = Color(0xFFF9E2AF)
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
                outline = Color(0xFF9498A8),
                zapColor = Color(0xFFCB7030),
                repostColor = Color(0xFF3A7A40),
                bookmarkColor = Color(0xFFCB7030),
                paidColor = Color(0xFFA09000)
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
                outline = Color(0xFF4B5263),
                zapColor = Color(0xFFE5C07B),
                repostColor = Color(0xFF98C379),
                bookmarkColor = Color(0xFFE5C07B),
                paidColor = Color(0xFFE5C07B)
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
                outline = Color(0xFFA0A0A0),
                zapColor = Color(0xFFB5862E),
                repostColor = Color(0xFF5B8A3A),
                bookmarkColor = Color(0xFFB5862E),
                paidColor = Color(0xFFA09000)
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
                outline = Color(0xFF365A77),
                zapColor = Color(0xFFE0AF68),
                repostColor = Color(0xFF9ECE6A),
                bookmarkColor = Color(0xFFE0AF68),
                paidColor = Color(0xFFE0AF68)
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
                outline = Color(0xFF9094A8),
                zapColor = Color(0xFFB07030),
                repostColor = Color(0xFF4A7A3A),
                bookmarkColor = Color(0xFFB07030),
                paidColor = Color(0xFF907030)
            )
        ),
        ThemePreset(
            name = "srcery",
            displayName = "Srcery",
            dark = ThemeColors(
                primary = Color(0xFF7CB860),
                secondary = Color(0xFF6CA0D0),
                background = Color(0xFF1C1B19),
                surface = Color(0xFF262424),
                surfaceVariant = Color(0xFF303030),
                onBackground = Color(0xFFBAA67F),
                onSurface = Color(0xFFBAA67F),
                onSurfaceVariant = Color(0xFF918175),
                outline = Color(0xFF3A3A3A),
                zapColor = Color(0xFFFF5F00),
                repostColor = Color(0xFF6CA0D0),
                bookmarkColor = Color(0xFF7CB860),
                paidColor = Color(0xFFFBC000)
            ),
            light = ThemeColors(
                primary = Color(0xFF508040),
                secondary = Color(0xFF4A80B0),
                background = Color(0xFFD4CFC0),
                surface = Color(0xFFC8C2B4),
                surfaceVariant = Color(0xFFB4AFA0),
                onBackground = Color(0xFF1C1B19),
                onSurface = Color(0xFF1C1B19),
                onSurfaceVariant = Color(0xFF5A5548),
                outline = Color(0xFF989088),
                zapColor = Color(0xFFB84800),
                repostColor = Color(0xFF4A80B0),
                bookmarkColor = Color(0xFF508040),
                paidColor = Color(0xFFA08000)
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
        ),
        ThemePreset(
            name = "ayu",
            displayName = "Ayu",
            dark = ThemeColors(
                primary = Color(0xFFFFB454),
                secondary = Color(0xFF5CCFE6),
                background = Color(0xFF0A0E14),
                surface = Color(0xFF141B22),
                surfaceVariant = Color(0xFF1E262F),
                onBackground = Color(0xFFD9D7CE),
                onSurface = Color(0xFFD9D7CE),
                onSurfaceVariant = Color(0xFF8B8F8B),
                outline = Color(0xFF3D4551),
                zapColor = Color(0xFFFFB454),
                repostColor = Color(0xFF87D68D),
                bookmarkColor = Color(0xFFFFB454),
                paidColor = Color(0xFFFFE99D)
            ),
            light = ThemeColors(
                primary = Color(0xFFE86A33),
                secondary = Color(0xFF1497D6),
                background = Color(0xFFFAFAFA),
                surface = Color(0xFFF0F0F0),
                surfaceVariant = Color(0xFFE8E8E8),
                onBackground = Color(0xFF434343),
                onSurface = Color(0xFF434343),
                onSurfaceVariant = Color(0xFF6B6B6B),
                outline = Color(0xFFB0B0B0),
                zapColor = Color(0xFFE86A33),
                repostColor = Color(0xFF5BA055),
                bookmarkColor = Color(0xFFE86A33),
                paidColor = Color(0xFFC0A000)
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
                outline = Color(0xFF404D44),
                zapColor = Color(0xFF50C878),
                repostColor = Color(0xFF98FB98),
                bookmarkColor = Color(0xFF50C878),
                paidColor = Color(0xFFF0E080)
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
                outline = Color(0xFF889888),
                zapColor = Color(0xFF2E8B57),
                repostColor = Color(0xFF3CB371),
                bookmarkColor = Color(0xFF2E8B57),
                paidColor = Color(0xFF807010)
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
                outline = Color(0xFF444058),
                zapColor = Color(0xFFBB88DD),
                repostColor = Color(0xFFDA70D6),
                bookmarkColor = Color(0xFFBB88DD),
                paidColor = Color(0xFFF0E080)
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
                outline = Color(0xFF9890A8),
                zapColor = Color(0xFF9040A0),
                repostColor = Color(0xFF6A4A8A),
                bookmarkColor = Color(0xFF9040A0),
                paidColor = Color(0xFF7850A0)
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
                outline = Color(0xFF4A3840),
                zapColor = Color(0xFFFF6B6B),
                repostColor = Color(0xFFFF6B6B),
                bookmarkColor = Color(0xFFFF6B6B),
                paidColor = Color(0xFFF0E080)
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
                outline = Color(0xFF9890A0),
                zapColor = Color(0xFFB00B3A),
                repostColor = Color(0xFF8B3A5A),
                bookmarkColor = Color(0xFFB00B3A),
                paidColor = Color(0xFF805060)
            )
        ),
        ThemePreset(
            name = "sapphire",
            displayName = "Sapphire",
            dark = ThemeColors(
                primary = Color(0xFF4A90D9),
                secondary = Color(0xFF6AEFFA),
                background = Color(0xFF1A1D24),
                surface = Color(0xFF252A35),
                surfaceVariant = Color(0xFF353D4A),
                onBackground = Color(0xFFD0D8E8),
                onSurface = Color(0xFFD0D8E8),
                onSurfaceVariant = Color(0xFF88A0B8),
                outline = Color(0xFF404858),
                zapColor = Color(0xFF4A90D9),
                repostColor = Color(0xFF6AEFFA),
                bookmarkColor = Color(0xFF4A90D9),
                paidColor = Color(0xFFE0E8FF)
            ),
            light = ThemeColors(
                primary = Color(0xFF2A68A8),
                secondary = Color(0xFF3080A0),
                background = Color(0xFFE4E8F0),
                surface = Color(0xFFD0D8E4),
                surfaceVariant = Color(0xFFB8C4D0),
                onBackground = Color(0xFF2A3038),
                onSurface = Color(0xFF2A3038),
                onSurfaceVariant = Color(0xFF4A5868),
                outline = Color(0xFF8898A8),
                zapColor = Color(0xFF2A68A8),
                repostColor = Color(0xFF208090),
                bookmarkColor = Color(0xFF2A68A8),
                paidColor = Color(0xFF506088)
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
