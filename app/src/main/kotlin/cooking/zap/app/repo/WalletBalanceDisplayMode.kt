package cooking.zap.app.repo

import android.content.SharedPreferences

/**
 * Tri-state balance display for the wallet dashboard. Tapping the
 * balance cycles `SATS → FIAT → HIDDEN → SATS`. Persisted per wallet
 * pubkey under the `walletBalanceDisplay_<pubkey>` key in the
 * `wisp_settings` SharedPreferences file.
 *
 * `FIAT` renders the balance in the user's selected display currency
 * ([CurrencyPreferences]). This is a wallet-only convenience — there is
 * no app-wide "fiat mode"; sats remain the standard everywhere else.
 *
 * `HIDDEN` masks the dashboard balance AND every per-row amount + fee
 * in the transaction history view — useful for screenshots / shoulder-
 * surfing scenarios.
 *
 * Legacy Android global `balance_hidden` Bool is read once per pubkey
 * when no per-pubkey entry exists, and the per-pubkey key is written
 * from it (true → HIDDEN, false → SATS).
 */
enum class WalletBalanceDisplayMode {
    SATS, FIAT, HIDDEN;

    /** Next state in the tap cycle. */
    fun next(): WalletBalanceDisplayMode = when (this) {
        SATS -> FIAT
        FIAT -> HIDDEN
        HIDDEN -> SATS
    }

    companion object {
        private const val KEY_PREFIX = "walletBalanceDisplay_"
        private const val LEGACY_HIDDEN_KEY = "balance_hidden"

        fun storageKey(pubkey: String): String = "$KEY_PREFIX$pubkey"

        /**
         * Read the persisted mode for [pubkey]. Falls back to legacy
         * global `balance_hidden` Bool for the first read of a given
         * pubkey, then writes the migrated value so subsequent reads
         * don't depend on the legacy key staying in place. The legacy
         * key itself is left untouched — older builds rolled back keep
         * the prior preference intact.
         *
         * When [pubkey] is null (no signed-in account yet), returns
         * the legacy global state (SATS / HIDDEN only) without
         * touching storage.
         */
        fun read(prefs: SharedPreferences, pubkey: String?): WalletBalanceDisplayMode {
            if (pubkey.isNullOrBlank()) {
                return if (prefs.getBoolean(LEGACY_HIDDEN_KEY, false)) HIDDEN else SATS
            }
            val key = storageKey(pubkey)
            val raw = prefs.getString(key, null)
            if (raw != null) {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SATS
            }
            val initial = if (prefs.getBoolean(LEGACY_HIDDEN_KEY, false)) HIDDEN else SATS
            prefs.edit().putString(key, initial.name.lowercase()).apply()
            return initial
        }

        /** Persist [mode] for [pubkey]. No-op when [pubkey] is null. */
        fun write(prefs: SharedPreferences, pubkey: String?, mode: WalletBalanceDisplayMode) {
            if (pubkey.isNullOrBlank()) return
            prefs.edit().putString(storageKey(pubkey), mode.name.lowercase()).apply()
        }
    }
}
