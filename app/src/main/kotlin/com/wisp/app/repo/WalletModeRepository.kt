package com.wisp.app.repo

import android.content.Context

enum class WalletMode { NONE, NWC, SPARK }

class WalletModeRepository(private val context: Context, pubkeyHex: String? = null) {

    private var prefs = context.getSharedPreferences(
        prefsName(pubkeyHex),
        Context.MODE_PRIVATE
    )

    fun reload(pubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    }

    fun getMode(): WalletMode {
        val name = prefs.getString("wallet_mode", null) ?: return WalletMode.NONE
        return try { WalletMode.valueOf(name) } catch (_: Exception) { WalletMode.NONE }
    }

    fun setMode(mode: WalletMode) {
        prefs.edit().putString("wallet_mode", mode.name).apply()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?) =
            if (pubkeyHex != null) "wisp_wallet_mode_$pubkeyHex" else "wisp_wallet_mode"
    }
}
