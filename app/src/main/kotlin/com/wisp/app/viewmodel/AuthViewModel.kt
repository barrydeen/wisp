package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.KeyRepository
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
        _error.value = null
    }

    fun logOut() {
        keyRepo.clearKeypair()
        _npub.value = null
    }

    fun switchAccount(pubkeyHex: String) {
        keyRepo.setActiveAccount(pubkeyHex)
        _npub.value = keyRepo.getNpub()
    }

    fun removeAccount(pubkeyHex: String) {
        keyRepo.removeAccount(pubkeyHex)
        _npub.value = keyRepo.getNpub()
    }

    fun getAccounts() = keyRepo.getAccounts()

    fun getActivePubkey() = keyRepo.getPubkeyHex()
}
