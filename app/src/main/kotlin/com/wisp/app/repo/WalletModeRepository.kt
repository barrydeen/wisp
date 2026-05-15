package com.wisp.app.repo

import android.content.Context

enum class WalletMode { NONE, NWC, SPARK }

enum class BalanceUnit { BITCOIN, LIGHTNING, SATS }

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

    fun getBalanceUnit(): BalanceUnit {
        val name = prefs.getString("balance_unit", null) ?: return BalanceUnit.SATS
        return try { BalanceUnit.valueOf(name) } catch (_: Exception) { BalanceUnit.SATS }
    }

    fun setBalanceUnit(unit: BalanceUnit) {
        prefs.edit().putString("balance_unit", unit.name).apply()
    }

    /**
     * True once the user has explicitly disconnected a wallet on this account.
     * Disables the first-entry auto-creation of the default Spark wallet so the
     * user isn't surprised by it reappearing after they deliberately removed it.
     */
    fun isAutoCreateSkipped(): Boolean = prefs.getBoolean("auto_create_skipped", false)

    fun setAutoCreateSkipped(skipped: Boolean) {
        prefs.edit().putBoolean("auto_create_skipped", skipped).apply()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?) =
            if (pubkeyHex != null) "wisp_wallet_mode_$pubkeyHex" else "wisp_wallet_mode"
    }
}
