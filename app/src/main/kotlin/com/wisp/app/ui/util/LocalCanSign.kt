package com.wisp.app.ui.util

import androidx.compose.runtime.compositionLocalOf

/** True when the active account has a private key and can sign events. */
val LocalCanSign = compositionLocalOf { true }
