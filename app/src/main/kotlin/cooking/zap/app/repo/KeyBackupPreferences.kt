package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-account, durable record of whether the user has backed up their freshly
 * generated private key. Mirrors [SafetyPreferences]: one SharedPreferences file
 * per pubkey ("wisp_keybackup_<pubkeyHex>").
 *
 * Flag lifecycle:
 *  - backup_needed: set true ONLY when a brand-new key is GENERATED on this
 *    device (signUp / Google new-account). Login/restore paths never set it, so a
 *    user who arrives with an existing key is never nudged.
 *  - backed_up: set true ONLY when the user confirms "I've saved it" or
 *    successfully downloads the backup file. Clears the nudge for good.
 *  - skip_count / last_reminded_launch: advanced when the user defers ("Skip for
 *    now"). They NEVER touch backed_up, so the need survives a skip and the
 *    cold-launch re-prompt backs off as skips accumulate.
 *
 * "Show the nudge?" is simply [nudgeRequired] = backup_needed && !backed_up.
 */
class KeyBackupPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _backupNeeded = MutableStateFlow(prefs.getBoolean(KEY_NEEDED, false))
    private val _backedUp = MutableStateFlow(prefs.getBoolean(KEY_BACKED_UP, false))

    /** True while the active account has an unconfirmed key backup. Drives the feed banner + drawer dot. */
    private val _nudgeRequired = MutableStateFlow(computeNudge())
    val nudgeRequired: StateFlow<Boolean> = _nudgeRequired

    private fun computeNudge() = _backupNeeded.value && !_backedUp.value
    private fun refreshNudge() { _nudgeRequired.value = computeNudge() }

    val skipCount: Int get() = prefs.getInt(KEY_SKIP_COUNT, 0)
    private val launchCount: Int get() = prefs.getInt(KEY_LAUNCH_COUNT, 0)
    private val lastRemindedLaunch: Int get() = prefs.getInt(KEY_LAST_REMINDED, 0)

    /** Mark that a freshly generated key has not yet been backed up. Generation sites only. */
    fun markBackupNeeded() {
        prefs.edit().putBoolean(KEY_NEEDED, true).apply()
        _backupNeeded.value = true
        refreshNudge()
    }

    /** The user saved their key ("I've saved it" or a successful download) — stops nudging this account. */
    fun markBackedUp() {
        prefs.edit().putBoolean(KEY_BACKED_UP, true).apply()
        _backedUp.value = true
        refreshNudge()
    }

    /** The user deferred. Records the skip and resets the re-prompt clock; never clears the need. */
    fun recordSkip() {
        prefs.edit()
            .putInt(KEY_SKIP_COUNT, skipCount + 1)
            .putInt(KEY_LAST_REMINDED, launchCount)
            .apply()
    }

    /** Increment the per-account launch counter. Call once per cold launch for the active account. */
    fun onColdLaunch() {
        prefs.edit().putInt(KEY_LAUNCH_COUNT, launchCount + 1).apply()
    }

    /**
     * Whether to actively re-show the backup screen on this launch. True once the
     * deferred reminder is due: 1st skip → next launch, then backing off
     * (2, 4, 8, … capped at 16 launches) as skips accumulate.
     */
    fun shouldRepromptOnLaunch(): Boolean {
        if (!computeNudge()) return false
        val skips = skipCount
        if (skips < 1) return false
        val backoff = (1 shl (skips - 1)).coerceAtMost(16)
        return (launchCount - lastRemindedLaunch) >= backoff
    }

    /** Re-point to another account's prefs file and refresh the flows. */
    fun reload(newPubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(newPubkeyHex), Context.MODE_PRIVATE)
        _backupNeeded.value = prefs.getBoolean(KEY_NEEDED, false)
        _backedUp.value = prefs.getBoolean(KEY_BACKED_UP, false)
        refreshNudge()
    }

    companion object {
        private const val KEY_NEEDED = "backup_needed"
        private const val KEY_BACKED_UP = "backed_up"
        private const val KEY_SKIP_COUNT = "skip_count"
        private const val KEY_LAST_REMINDED = "last_reminded_launch"
        private const val KEY_LAUNCH_COUNT = "launch_count"

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_keybackup_$pubkeyHex" else "wisp_keybackup"

        /**
         * Mark backup-needed for a specific account without holding a long-lived
         * instance. Used by the Google new-account path, which owns a separate
         * KeyRepository from AuthViewModel.
         */
        fun markBackupNeededFor(context: Context, pubkeyHex: String) {
            KeyBackupPreferences(context, pubkeyHex).markBackupNeeded()
        }
    }
}
