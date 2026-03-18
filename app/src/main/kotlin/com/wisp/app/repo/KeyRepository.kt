package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

enum class SigningMode { LOCAL, REMOTE, READ_ONLY }

@Serializable
data class Account(
    val pubkey: String,
    val signingMode: String,
    val signerPackage: String? = null,
    val privkey: String? = null
)

class KeyRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "wisp_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private var prefs: SharedPreferences = context.getSharedPreferences(prefsName(null), Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _accounts = MutableStateFlow<List<Account>>(loadAccounts())
    val accountsFlow: StateFlow<List<Account>> = _accounts

    private val _activePubkey = MutableStateFlow<String?>(encPrefs.getString("active_pubkey", null))
    val activePubkeyFlow: StateFlow<String?> = _activePubkey

    // Observable flows so UI (RelayViewModel) sees updates immediately.
    // SharedPreferences instances are cached per file name within the same process,
    // so the listener fires for writes from ANY KeyRepository instance (e.g. EventRouter's).
    private val _relays = MutableStateFlow<List<RelayConfig>>(emptyList())
    val relaysFlow: StateFlow<List<RelayConfig>> = _relays

    private val _dmRelays = MutableStateFlow<List<String>>(emptyList())
    val dmRelaysFlow: StateFlow<List<String>> = _dmRelays

    private val _searchRelays = MutableStateFlow<List<String>>(emptyList())
    val searchRelaysFlow: StateFlow<List<String>> = _searchRelays

    private val _blockedRelays = MutableStateFlow<List<String>>(emptyList())
    val blockedRelaysFlow: StateFlow<List<String>> = _blockedRelays

    // Strong reference to prevent GC — listener syncs flows when prefs change from any instance
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "relays" -> _relays.value = loadRelays()
            "dm_relays" -> _dmRelays.value = loadDmRelays()
            "search_relays" -> _searchRelays.value = loadSearchRelays()
            "blocked_relays" -> _blockedRelays.value = loadBlockedRelays()
        }
    }

    init {
        migrateFromSingleAccount()
        reloadPrefs(getPubkeyHex())
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun migrateFromSingleAccount() {
        val existingPubkey = encPrefs.getString("pubkey", null)
        if (existingPubkey != null && _accounts.value.isEmpty()) {
            val mode = encPrefs.getString("signing_mode", SigningMode.LOCAL.name) ?: SigningMode.LOCAL.name
            val signerPackage = encPrefs.getString("signer_package", null)
            val privkey = encPrefs.getString("privkey", null)
            addAccount(Account(existingPubkey, mode, signerPackage, privkey))
            _activePubkey.value = existingPubkey
        }
    }

    fun saveKeypair(keypair: Keys.Keypair) {
        val pubkeyHex = keypair.pubkey.toHex()
        encPrefs.edit()
            .putString("active_pubkey", pubkeyHex)
            .putString("privkey", keypair.privkey.toHex())
            .putString("pubkey", pubkeyHex)
            .putString("signing_mode", SigningMode.LOCAL.name)
            .remove("signer_package")
            .apply()
        _activePubkey.value = pubkeyHex
        addAccount(Account(pubkeyHex, SigningMode.LOCAL.name, null, keypair.privkey.toHex()))
    }

    fun getKeypair(): Keys.Keypair? {
        val account = getActiveAccount() ?: return null
        val privHex = account.privkey ?: return null
        return Keys.Keypair(privHex.hexToByteArray(), account.pubkey.hexToByteArray())
    }

    fun savePubkeyOnly(pubkeyHex: String, signerPackage: String?) {
        encPrefs.edit()
            .putString("active_pubkey", pubkeyHex)
            .putString("pubkey", pubkeyHex)
            .putString("signing_mode", SigningMode.REMOTE.name)
            .putString("signer_package", signerPackage)
            .remove("privkey")
            .apply()
        _activePubkey.value = pubkeyHex
        addAccount(Account(pubkeyHex, SigningMode.REMOTE.name, signerPackage, null))
    }

    fun savePubkeyReadOnly(pubkeyHex: String) {
        encPrefs.edit()
            .putString("active_pubkey", pubkeyHex)
            .putString("pubkey", pubkeyHex)
            .putString("signing_mode", SigningMode.READ_ONLY.name)
            .remove("privkey")
            .remove("signer_package")
            .apply()
        _activePubkey.value = pubkeyHex
        addAccount(Account(pubkeyHex, SigningMode.READ_ONLY.name, null, null))
    }

    fun getSigningMode(): SigningMode {
        val mode = encPrefs.getString("signing_mode", SigningMode.LOCAL.name) ?: return SigningMode.LOCAL
        return try { SigningMode.valueOf(mode) } catch (_: Exception) { SigningMode.LOCAL }
    }

    fun isReadOnly(): Boolean = getSigningMode() == SigningMode.READ_ONLY

    fun getPubkeyHex(): String? = encPrefs.getString("active_pubkey", null)

    fun getSignerPackage(): String? = encPrefs.getString("signer_package", null)

    fun clearKeypair() {
        encPrefs.edit().clear().apply()
        _accounts.value = emptyList()
        _activePubkey.value = null
    }

    fun isLoggedIn(): Boolean = encPrefs.getString("active_pubkey", null) != null

    private fun loadAccounts(): List<Account> {
        val accountsJson = encPrefs.getString("accounts", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Account>>(accountsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAccounts(accounts: List<Account>) {
        encPrefs.edit().putString("accounts", json.encodeToString(accounts)).apply()
        _accounts.value = accounts
    }

    fun getAccounts(): List<Account> = _accounts.value

    fun addAccount(account: Account) {
        val currentAccounts = _accounts.value.toMutableList()
        val existingIndex = currentAccounts.indexOfFirst { it.pubkey == account.pubkey }
        if (existingIndex >= 0) {
            currentAccounts[existingIndex] = account
        } else {
            currentAccounts.add(account)
        }
        saveAccounts(currentAccounts)
    }

    fun setActiveAccount(pubkey: String) {
        val account = _accounts.value.find { it.pubkey == pubkey } ?: return
        encPrefs.edit()
            .putString("active_pubkey", pubkey)
            .putString("pubkey", pubkey)
            .putString("signing_mode", account.signingMode)
            .putString("signer_package", account.signerPackage)
            .putString("privkey", account.privkey)
            .apply()
        _activePubkey.value = pubkey
        reloadPrefs(pubkey)
    }

    fun removeAccount(pubkey: String) {
        val currentAccounts = _accounts.value.toMutableList()
        currentAccounts.removeAll { it.pubkey == pubkey }
        saveAccounts(currentAccounts)
        
        if (_activePubkey.value == pubkey) {
            val nextAccount = currentAccounts.firstOrNull()
            if (nextAccount != null) {
                setActiveAccount(nextAccount.pubkey)
            } else {
                clearKeypair()
            }
        }
    }

    fun getActiveAccount(): Account? {
        val pubkey = encPrefs.getString("active_pubkey", null) ?: return null
        return loadAccounts().find { it.pubkey == pubkey }
    }

    fun hasKeypair(): Boolean {
        val pubkey = encPrefs.getString("active_pubkey", null) ?: return false
        return loadAccounts().find { it.pubkey == pubkey }?.privkey != null
    }

    fun getNpub(): String? {
        val pubHex = encPrefs.getString("active_pubkey", null) ?: return null
        return Nip19.npubEncode(pubHex.hexToByteArray())
    }

    fun reloadPrefs(pubkeyHex: String?) {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        _relays.value = loadRelays()
        _dmRelays.value = loadDmRelays()
        _searchRelays.value = loadSearchRelays()
        _blockedRelays.value = loadBlockedRelays()
    }

    fun saveRelays(relays: List<RelayConfig>) {
        prefs.edit().putString("relays", json.encodeToString(relays)).apply()
        _relays.value = relays
    }

    fun getRelays(): List<RelayConfig> = _relays.value

    private fun loadRelays(): List<RelayConfig> {
        val str = prefs.getString("relays", null) ?: return RelayConfig.DEFAULTS
        return try {
            json.decodeFromString<List<RelayConfig>>(str)
        } catch (_: Exception) {
            RelayConfig.DEFAULTS
        }
    }

    fun saveDmRelays(urls: List<String>) {
        prefs.edit().putString("dm_relays", json.encodeToString(urls)).apply()
        _dmRelays.value = urls
    }

    fun getDmRelays(): List<String> = _dmRelays.value

    private fun loadDmRelays(): List<String> {
        val str = prefs.getString("dm_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun saveSearchRelays(urls: List<String>) {
        prefs.edit().putString("search_relays", json.encodeToString(urls)).apply()
        _searchRelays.value = urls
    }

    fun getSearchRelays(): List<String> = _searchRelays.value

    private fun loadSearchRelays(): List<String> {
        val str = prefs.getString("search_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun saveBlockedRelays(urls: List<String>) {
        prefs.edit().putString("blocked_relays", json.encodeToString(urls)).apply()
        _blockedRelays.value = urls
    }

    fun getBlockedRelays(): List<String> = _blockedRelays.value

    private fun loadBlockedRelays(): List<String> {
        val str = prefs.getString("blocked_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun isOnboardingComplete(): Boolean {
        if (prefs.getBoolean("onboarding_done", false)) return true
        // Migration for existing users who had the app before onboarding was added:
        // They'll have contacts data saved from previous sessions. New key users won't.
        val pubkeyHex = getPubkeyHex() ?: return false
        val contactPrefs = context.getSharedPreferences("wisp_contacts_$pubkeyHex", Context.MODE_PRIVATE)
        if (contactPrefs.contains("follows")) {
            markOnboardingComplete()
            return true
        }
        return false
    }

    fun markOnboardingComplete() =
        prefs.edit().putBoolean("onboarding_done", true).apply()

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_prefs_$pubkeyHex" else "wisp_prefs"
    }
}
