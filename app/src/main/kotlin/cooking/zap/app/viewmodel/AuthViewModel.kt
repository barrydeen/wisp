package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.nostr.toHex
import cooking.zap.app.repo.AccountInfo
import cooking.zap.app.repo.FiatPreferences
import cooking.zap.app.repo.KeyBackupPreferences
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.SigningMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
            // Brand-new accounts default to Fiat Mode (USD). Existing-account
            // logins do not touch these prefs, so a returning user's choice is
            // preserved and unknown-preference logins stay in Bitcoin mode.
            val fiatPrefs = FiatPreferences.get(getApplication())
            fiatPrefs.setFiatMode(true)
            fiatPrefs.setCurrency("USD")
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
            input.startsWith("npub1") -> loginWithNpub(input)
            input.startsWith("nprofile1") -> loginWithNprofile(input)
            input.length == 64 && input.all { it in '0'..'9' || it in 'a'..'f' } -> loginWithPubkeyHex(input)
            else -> {
                _error.value = "Invalid key format — enter an nsec or npub"
                false
            }
        }
    }

    private fun loginWithNsec(nsec: String): Boolean {
        return try {
            val privkey = Nip19.nsecDecode(nsec)
            val keypair = Keys.fromPrivkey(privkey)
            keyRepo.saveKeypair(keypair)
            keyRepo.reloadPrefs(keypair.pubkey.toHex())
            keyBackupPrefs.reload(keypair.pubkey.toHex())
            _npub.value = Nip19.npubEncode(keypair.pubkey)
            _signingMode.value = SigningMode.LOCAL
            _nsecInput.value = ""
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Invalid nsec key: ${e.message}"
            false
        }
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
