package com.wisp.app.repo

import android.content.Context

enum class WalletMode { NONE, NWC, SPARK }

class WalletModeRepository(context: Context, pubkeyHex: String? = null) {

    private val prefs = context.getSharedPreferences(
        if (pubkeyHex != null) "wisp_wallet_mode_$pubkeyHex" else "wisp_wallet_mode",
        Context.MODE_PRIVATE
    )

    fun getMode(): WalletMode {
        val name = prefs.getString("wallet_mode", null) ?: return WalletMode.NONE
        return try { WalletMode.valueOf(name) } catch (_: Exception) { WalletMode.NONE }
    }

    fun setMode(mode: WalletMode) {
        prefs.edit().putString("wallet_mode", mode.name).apply()
    }
}
