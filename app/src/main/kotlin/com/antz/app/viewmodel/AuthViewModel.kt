package com.antz.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.antz.app.nostr.Keys
import com.antz.app.nostr.Nip19
import com.antz.app.nostr.hexToByteArray
import com.antz.app.nostr.toHex
import com.antz.app.repo.AccountInfo
import com.antz.app.repo.KeyRepository
import com.antz.app.repo.SigningMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    val keyRepo = KeyRepository(app)

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

    val isLoggedIn: Boolean get() = keyRepo.isLoggedIn()

    fun updateNsecInput(value: String) {
        _nsecInput.value = value
        _error.value = null
    }

    fun signUp(): Boolean {
        return try {
            val keypair = Keys.generate()
            keyRepo.saveKeypair(keypair)
            keyRepo.reloadPrefs(keypair.pubkey.toHex())
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
            input.startsWith("npub1") -> loginWithNpub(input)
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

    private fun loginWithPubkeyHex(hex: String): Boolean {
        return try {
            keyRepo.savePubkeyReadOnly(hex)
            keyRepo.reloadPrefs(hex)
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
        keyRepo.savePubkeyOnly(pubkeyHex, signerPackage)
        keyRepo.reloadPrefs(pubkeyHex)
        _npub.value = Nip19.npubEncode(pubkeyHex.hexToByteArray())
        _signingMode.value = SigningMode.REMOTE
        _error.value = null
    }

    fun switchAccount(pubkeyHex: String) {
        keyRepo.switchToAccount(pubkeyHex)
        keyRepo.reloadPrefs(pubkeyHex)
        _npub.value = Nip19.npubEncode(pubkeyHex.hexToByteArray())
        _signingMode.value = keyRepo.getSigningMode()
    }

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
