package com.antz.app.repo

import android.content.Context

enum class WalletMode { NONE, NWC, SPARK }

enum class BalanceUnit { BITS, BITCOIN, LIGHTNING, SATS }

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

    companion object {
        private fun prefsName(pubkeyHex: String?) =
            if (pubkeyHex != null) "antz_wallet_mode_$pubkeyHex" else "antz_wallet_mode"
    }
}
