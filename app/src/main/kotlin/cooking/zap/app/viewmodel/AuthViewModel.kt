package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.Nip49
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.nostr.toHex
import cooking.zap.app.nostr.wipe
import cooking.zap.app.repo.AccountInfo
import cooking.zap.app.repo.KeyBackupPreferences
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.SigningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    val keyRepo = KeyRepository(app)

    // Durable per-account "have you backed up your key?" state for the active account.
    private val keyBackupPrefs = KeyBackupPreferences(app, keyRepo.getPubkeyHex())

    /** True while the active account has a freshly generated key it hasn't backed up. */
    val keyBackupNudge: StateFlow<Boolean> = keyBackupPrefs.nudgeRequired

    init {
        // Count this process start once, for whichever account is currently active.
        keyBackupPrefs.onColdLaunch()
    }

    private val _nsecInput = MutableStateFlow("")
    val nsecInput: StateFlow<String> = _nsecInput

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _npub = MutableStateFlow<String?>(keyRepo.getNpub())
    val npub: StateFlow<String?> = _npub

    private val _signingMode = MutableStateFlow(if (keyRepo.isLoggedIn()) keyRepo.getSigningMode() else null)
    val signingModeFlow: StateFlow<SigningMode?> = _signingMode

    /**
     * An `ncryptsec1…` the user entered that is waiting on its password. Set by [logIn],
     * cleared by [unlockPendingNcryptsec] on success or by [cancelPendingNcryptsec];
     * login surfaces show their password prompt while it is non-null.
     */
    private val _pendingNcryptsec = MutableStateFlow<String?>(null)
    val pendingNcryptsec: StateFlow<String?> = _pendingNcryptsec

    /** True while scrypt is running for [unlockPendingNcryptsec] — drives the prompt's spinner. */
    private val _unlockingNcryptsec = MutableStateFlow(false)
    val unlockingNcryptsec: StateFlow<Boolean> = _unlockingNcryptsec

    val accountsFlow: StateFlow<List<AccountInfo>> = keyRepo.accountsFlow

    var isAddingAccount: Boolean = false
    var previousAccountPubkey: String? = null

    val isLoggedIn: Boolean get() = keyRepo.isLoggedIn()

    fun getCurrentNsec(): String? {
        val keypair = keyRepo.getKeypair() ?: return null
        return Nip19.nsecEncode(keypair.privkey)
    }

    fun updateNsecInput(value: String) {
        _nsecInput.value = value
        _error.value = null
    }

    fun signUp(): Boolean {
        return try {
            val keypair = Keys.generate()
            keyRepo.saveKeypair(keypair)
            keyRepo.reloadPrefs(keypair.pubkey.toHex())
            // Brand-new key generated on-device → it must be backed up. This is the
            // sole gate for the nudge; login/restore paths never set it.
            keyBackupPrefs.reload(keypair.pubkey.toHex())
            keyBackupPrefs.markBackupNeeded()
            _npub.value = Nip19.npubEncode(keypair.pubkey)
            _signingMode.value = SigningMode.LOCAL
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Failed to generate keys: ${e.message}"
            false
        }
    }

    fun logIn(): Boolean {
        val input = _nsecInput.value.trim()
        if (input.isBlank()) {
            _error.value = "Please enter your key"
            return false
        }
        return when {
            input.startsWith("nsec1") -> loginWithNsec(input)
            // An ncryptsec needs a password before it becomes a key: park it and let the
            // login surface prompt, rather than reporting a failure the user can't act on.
            Nip49.isNcryptsec(input) -> {
                _pendingNcryptsec.value = input
                false
            }
            input.startsWith("npub1") -> loginWithNpub(input)
            input.startsWith("nprofile1") -> loginWithNprofile(input)
            input.length == 64 && input.all { it in '0'..'9' || it in 'a'..'f' } -> loginWithPubkeyHex(input)
            else -> {
                _error.value = "Invalid key format — enter an nsec, ncryptsec, or npub"
                false
            }
        }
    }

    private fun loginWithNsec(nsec: String): Boolean {
        return try {
            val privkey = Nip19.nsecDecode(nsec)
            try {
                loginWithPrivkey(privkey)
            } finally {
                privkey.wipe()
            }
            true
        } catch (e: Exception) {
            _error.value = "Invalid nsec key: ${e.message}"
            false
        }
    }

    /**
     * Decrypt the pending `ncryptsec` with [password] and log in with the recovered key.
     *
     * scrypt is deliberately slow and memory-hard (64 MiB, hundreds of ms at the usual
     * log_n of 16), so it runs on [Dispatchers.Default] with [unlockingNcryptsec] set for
     * the duration. [onResult] fires on the main thread once the attempt settles.
     */
    fun unlockPendingNcryptsec(password: String, onResult: (Boolean) -> Unit) {
        val ncryptsec = _pendingNcryptsec.value
        if (ncryptsec == null || _unlockingNcryptsec.value) {
            onResult(false)
            return
        }
        if (password.isEmpty()) {
            _error.value = "Enter the password for this key"
            onResult(false)
            return
        }

        _unlockingNcryptsec.value = true
        _error.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { Nip49.decrypt(ncryptsec, password) }
            }
            _unlockingNcryptsec.value = false
            result.fold(
                onSuccess = { privkey ->
                    val loggedIn = try {
                        loginWithPrivkey(privkey)
                        true
                    } catch (e: Exception) {
                        _error.value = "Couldn't use this key: ${e.message}"
                        false
                    } finally {
                        privkey.wipe()
                    }
                    if (loggedIn) _pendingNcryptsec.value = null
                    onResult(loggedIn)
                },
                onFailure = { e ->
                    _error.value = ncryptsecFailureMessage(e)
                    onResult(false)
                },
            )
        }
    }

    /** Dismiss the password prompt without unlocking; the typed key stays in the field. */
    fun cancelPendingNcryptsec() {
        if (_unlockingNcryptsec.value) return
        _pendingNcryptsec.value = null
        _error.value = null
    }

    private fun ncryptsecFailureMessage(e: Throwable): String = when (e) {
        is Nip49.Nip49Error.WrongPassword -> "Wrong password — check it and try again"
        is Nip49.Nip49Error.TooExpensive -> e.message ?: "This key needs more memory than this device has"
        is Nip49.Nip49Error.Malformed -> "This isn't a valid ncryptsec key"
        else -> "Couldn't unlock this key: ${e.message}"
    }

    /**
     * Adopt a raw 32-byte private key as the active LOCAL account. Throws if the key is
     * invalid; callers wipe their copy afterwards (KeyRepository hex-encodes on save and
     * keeps no reference to the array).
     */
    private fun loginWithPrivkey(privkey: ByteArray) {
        val keypair = Keys.fromPrivkey(privkey)
        val pubkeyHex = keypair.pubkey.toHex()
        keyRepo.saveKeypair(keypair)
        keyRepo.reloadPrefs(pubkeyHex)
        keyBackupPrefs.reload(pubkeyHex)
        _npub.value = Nip19.npubEncode(keypair.pubkey)
        _signingMode.value = SigningMode.LOCAL
        _nsecInput.value = ""
        _error.value = null
    }

    private fun loginWithNpub(npub: String): Boolean {
        return try {
            val pubkey = Nip19.npubDecode(npub)
            val pubkeyHex = pubkey.toHex()
            keyRepo.savePubkeyReadOnly(pubkeyHex)
            keyRepo.reloadPrefs(pubkeyHex)
            keyBackupPrefs.reload(pubkeyHex)
            _npub.value = Nip19.npubEncode(pubkey)
            _signingMode.value = SigningMode.READ_ONLY
            _nsecInput.value = ""
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Invalid npub: ${e.message}"
            false
        }
    }

    private fun loginWithNprofile(nprofile: String): Boolean {
        return try {
            val profile = Nip19.nprofileDecode(nprofile)
            keyRepo.savePubkeyReadOnly(profile.pubkey)
            keyRepo.reloadPrefs(profile.pubkey)
            keyBackupPrefs.reload(profile.pubkey)
            _npub.value = Nip19.npubEncode(profile.pubkey.hexToByteArray())
            _signingMode.value = SigningMode.READ_ONLY
            _nsecInput.value = ""
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Invalid nprofile: ${e.message}"
            false
        }
    }

    private fun loginWithPubkeyHex(hex: String): Boolean {
        return try {
            keyRepo.savePubkeyReadOnly(hex)
            keyRepo.reloadPrefs(hex)
            keyBackupPrefs.reload(hex)
            _npub.value = Nip19.npubEncode(hex.hexToByteArray())
            _signingMode.value = SigningMode.READ_ONLY
            _nsecInput.value = ""
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Invalid pubkey: ${e.message}"
            false
        }
    }

    fun loginWithSigner(pubkeyHex: String, signerPackage: String?) {
        try {
            keyRepo.savePubkeyOnly(pubkeyHex, signerPackage)
            keyRepo.reloadPrefs(pubkeyHex)
            _npub.value = Nip19.npubEncode(pubkeyHex.hexToByteArray())
            _signingMode.value = SigningMode.REMOTE
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Signer login failed: ${e.message}"
        }
    }

    /**
     * Re-sync npub/signing-mode flows after another component (e.g. GoogleAuthViewModel)
     * has saved a keypair directly through KeyRepository. Without this, the
     * AuthViewModel's flows still reflect the pre-login state.
     */
    fun refreshAfterExternalLogin() {
        keyRepo.refreshAccounts()
        // Picks up backup_needed written by GoogleAuthViewModel for a new account.
        keyBackupPrefs.reload(keyRepo.getPubkeyHex())
        _npub.value = keyRepo.getNpub()
        _signingMode.value = if (keyRepo.isLoggedIn()) keyRepo.getSigningMode() else null
        _error.value = null
    }

    fun switchAccount(pubkeyHex: String) {
        keyRepo.switchToAccount(pubkeyHex)
        keyRepo.reloadPrefs(pubkeyHex)
        keyBackupPrefs.reload(pubkeyHex)
        _npub.value = Nip19.npubEncode(pubkeyHex.hexToByteArray())
        _signingMode.value = keyRepo.getSigningMode()
    }

    /** Reorder the account list in the switcher — offset -1 moves it up, +1 moves it down. */
    fun moveAccount(pubkeyHex: String, offset: Int) {
        keyRepo.moveAccount(pubkeyHex, offset)
    }

    // --- Key-backup nudge control (used by Navigation) ---

    /** "I've saved it" — confirms backup and stops nudging this account. */
    fun markKeyBackedUp() = keyBackupPrefs.markBackedUp()

    /** "Skip for now" — defers; keeps the need alive and backs off the re-prompt. */
    fun recordKeyBackupSkip() = keyBackupPrefs.recordSkip()

    /** Whether this cold launch is due to actively re-show the backup screen. */
    fun shouldRepromptKeyBackup(): Boolean = keyBackupPrefs.shouldRepromptOnLaunch()

    /**
     * Logs out the current account. Returns true if other accounts remain
     * (caller should switch to the next one), false if no accounts left
     * (caller should navigate to AUTH).
     */
    fun logOut(): Boolean {
        val currentPubkey = keyRepo.getPubkeyHex()
        if (currentPubkey != null) {
            keyRepo.removeAccount(currentPubkey)
        } else {
            keyRepo.clearKeypair()
        }
        _npub.value = null
        _signingMode.value = null

        // If other accounts remain, switch to the first one
        val remaining = keyRepo.getAccountList()
        if (remaining.isNotEmpty()) {
            switchAccount(remaining.first().pubkeyHex)
            return true
        }
        return false
    }
}
