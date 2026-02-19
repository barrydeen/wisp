package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip19
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

    val isLoggedIn: Boolean get() = keyRepo.hasKeypair()

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
        val nsec = _nsecInput.value.trim()
        if (nsec.isBlank()) {
            _error.value = "Please enter your nsec key"
            return false
        }
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

    fun logOut() {
        keyRepo.clearKeypair()
        _npub.value = null
    }
}
