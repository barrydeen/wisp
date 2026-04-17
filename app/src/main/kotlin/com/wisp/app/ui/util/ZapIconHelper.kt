package com.wisp.app.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.wisp.app.repo.FiatPreferences

/**
 * True when the lightning-bolt drawable should be used in place of the Bitcoin
 * (₿) symbol anywhere a zap icon is rendered. Respects the user's explicit
 * preference, but Fiat Mode always forces the bolt so the ₿ symbol never
 * appears while the app is showing fiat amounts.
 */
@Composable
fun useBoltIcon(): Boolean {
    val context = LocalContext.current
    val fiatMode by FiatPreferences.get(context).fiatMode.collectAsState()
    if (fiatMode) return true
    val prefs = remember(context) {
        context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
    }
    return prefs.getBoolean("zap_bolt_icon", false)
}
