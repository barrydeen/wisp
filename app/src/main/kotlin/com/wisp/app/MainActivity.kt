package com.wisp.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.LocaleRepository
import com.wisp.app.ui.component.LocalMediaSettings
import com.wisp.app.ui.component.MediaSettings
import com.wisp.app.ui.component.PipController
import com.wisp.app.ui.theme.WispTheme

class MainActivity : FragmentActivity() {
    var deepLinkUri = mutableStateOf<String?>(null)
        private set

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "system") ?: "system"
        super.attachBaseContext(LocaleRepository.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkUri.value = intent?.data?.toString()
        setContent {
            val prefs = remember { getSharedPreferences("wisp_settings", Context.MODE_PRIVATE) }
            val interfacePrefs = remember { InterfacePreferences(this@MainActivity) }
            var isDarkTheme by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var accentColor by remember { mutableStateOf(Color(interfacePrefs.getAccentColor())) }
            var isLargeText by remember { mutableStateOf(interfacePrefs.isLargeText()) }
            var themeName by remember { mutableStateOf(interfacePrefs.getTheme()) }
            var mediaSettings by remember {
                mutableStateOf(MediaSettings(
                    autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                    videoAutoPlay = interfacePrefs.isVideoAutoPlay()
                ))
            }

            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }

            val pipActive by PipController.pipState.collectAsState()
            val effectiveMediaSettings = if (pipActive != null) {
                mediaSettings.copy(videoAutoPlay = false)
            } else {
                mediaSettings
            }

            WispTheme(isDarkTheme = isDarkTheme, accentColor = accentColor, isLargeText = isLargeText, themeName = themeName) {
                CompositionLocalProvider(LocalMediaSettings provides effectiveMediaSettings) {
                    WispNavHost(
                        deepLinkUri = deepLinkUri.value,
                        onDeepLinkConsumed = { deepLinkUri.value = null },
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            isDarkTheme = !isDarkTheme
                            prefs.edit().putBoolean("dark_theme", isDarkTheme).apply()
                        },
                        accentColor = accentColor,
                        isLargeText = isLargeText,
                        onInterfaceChanged = {
                            accentColor = Color(interfacePrefs.getAccentColor())
                            isLargeText = interfacePrefs.isLargeText()
                            themeName = interfacePrefs.getTheme()
                            mediaSettings = MediaSettings(
                                autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                                videoAutoPlay = interfacePrefs.isVideoAutoPlay()
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUri.value = intent.data?.toString()
    }
}
