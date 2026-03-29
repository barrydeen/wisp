package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.LocalRelayConfig
import com.wisp.app.relay.RelayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

enum class SigningMode { LOCAL, REMOTE, READ_ONLY }

@Serializable
data class AccountInfo(
    val pubkeyHex: String,
    val signingMode: SigningMode,
    val displayName: String? = null,
    val picture: String? = null
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

    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(getPubkeyHex()), Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    // Observable flows so UI (RelayViewModel) sees updates immediately.
    // SharedPreferences instances are cached per file name within the same process,
    // so the listener fires for writes from ANY KeyRepository instance (e.g. EventRouter's).
    private val _relays = MutableStateFlow(loadRelays())
    val relaysFlow: StateFlow<List<RelayConfig>> = _relays

    private val _dmRelays = MutableStateFlow(loadDmRelays())
    val dmRelaysFlow: StateFlow<List<String>> = _dmRelays

    private val _searchRelays = MutableStateFlow(loadSearchRelays())
    val searchRelaysFlow: StateFlow<List<String>> = _searchRelays

    private val _blockedRelays = MutableStateFlow(loadBlockedRelays())
    val blockedRelaysFlow: StateFlow<List<String>> = _blockedRelays

    private val _localRelay = MutableStateFlow(loadLocalRelay())
    val localRelayFlow: StateFlow<LocalRelayConfig?> = _localRelay

    // Strong reference to prevent GC — listener syncs flows when prefs change from any instance
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "relays" -> _relays.value = loadRelays()
            "dm_relays" -> _dmRelays.value = loadDmRelays()
            "search_relays" -> _searchRelays.value = loadSearchRelays()
            "blocked_relays" -> _blockedRelays.value = loadBlockedRelays()
            "local_relay" -> _localRelay.value = loadLocalRelay()
        }
    }

    // --- Multi-account registry ---

    private val _accounts = MutableStateFlow(loadAccountList())
    val accountsFlow: StateFlow<List<AccountInfo>> = _accounts

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        migrateToMultiAccount()
    }

    /**
     * One-time migration: if legacy keys exist but no account registry,
     * wrap the current single account into the new format.
     */
    private fun migrateToMultiAccount() {
        if (encPrefs.contains("accounts")) return
        val pubkey = encPrefs.getString("pubkey", null) ?: return
        val mode = getSigningMode()
        val privkey = encPrefs.getString("privkey", null)
        val signerPkg = encPrefs.getString("signer_package", null)

        // Store indexed credentials
        if (privkey != null) {
            encPrefs.edit().putString("privkey_$pubkey", privkey).apply()
        }
        if (signerPkg != null) {
            encPrefs.edit().putString("signer_package_$pubkey", signerPkg).apply()
        }

        val account = AccountInfo(pubkey, mode)
        encPrefs.edit()
            .putString("accounts", json.encodeToString(listOf(account)))
            .putString("active_pubkey", pubkey)
            .apply()
        _accounts.value = listOf(account)
    }

    fun getAccountList(): List<AccountInfo> = _accounts.value

    fun getAccountCount(): Int = _accounts.value.size

    fun getActivePubkey(): String? = encPrefs.getString("active_pubkey", null)

    fun addAccount(pubkeyHex: String, signingMode: SigningMode, privkeyHex: String? = null, signerPackage: String? = null) {
        val accounts = loadAccountList().toMutableList()
        // Remove existing entry for this pubkey (re-login scenario)
        accounts.removeAll { it.pubkeyHex == pubkeyHex }
        accounts.add(AccountInfo(pubkeyHex, signingMode))

        val editor = encPrefs.edit()
            .putString("accounts", json.encodeToString(accounts))
            .putString("active_pubkey", pubkeyHex)

        // Store indexed credentials
        if (privkeyHex != null) {
            editor.putString("privkey_$pubkeyHex", privkeyHex)
        } else {
            editor.remove("privkey_$pubkeyHex")
        }
        if (signerPackage != null) {
            editor.putString("signer_package_$pubkeyHex", signerPackage)
        } else {
            editor.remove("signer_package_$pubkeyHex")
        }
        editor.apply()
        _accounts.value = accounts
    }

    fun switchToAccount(pubkeyHex: String) {
        val accounts = loadAccountList()
        val account = accounts.find { it.pubkeyHex == pubkeyHex } ?: return

        // Sync legacy keys from indexed storage
        val editor = encPrefs.edit()
            .putString("pubkey", pubkeyHex)
            .putString("active_pubkey", pubkeyHex)
            .putString("signing_mode", account.signingMode.name)

        when (account.signingMode) {
            SigningMode.LOCAL -> {
                val privkey = encPrefs.getString("privkey_$pubkeyHex", null)
                editor.putString("privkey", privkey)
                editor.remove("signer_package")
            }
            SigningMode.REMOTE -> {
                val signerPkg = encPrefs.getString("signer_package_$pubkeyHex", null)
                editor.remove("privkey")
                editor.putString("signer_package", signerPkg)
            }
            SigningMode.READ_ONLY -> {
                editor.remove("privkey")
                editor.remove("signer_package")
            }
        }
        editor.apply()
    }

    fun removeAccount(pubkeyHex: String) {
        val accounts = loadAccountList().toMutableList()
        accounts.removeAll { it.pubkeyHex == pubkeyHex }

        val editor = encPrefs.edit()
            .putString("accounts", json.encodeToString(accounts))

        // Remove indexed credentials
        editor.remove("privkey_$pubkeyHex")
        editor.remove("signer_package_$pubkeyHex")

        // If removing the active account, clear legacy keys
        if (getPubkeyHex() == pubkeyHex) {
            editor.remove("pubkey")
                .remove("privkey")
                .remove("signing_mode")
                .remove("signer_package")
                .remove("active_pubkey")
        }
        editor.apply()
        _accounts.value = accounts
    }

    fun updateAccountMetadata(pubkeyHex: String, displayName: String?, picture: String?) {
        val accounts = loadAccountList().toMutableList()
        val index = accounts.indexOfFirst { it.pubkeyHex == pubkeyHex }
        if (index < 0) return
        val current = accounts[index]
        if (current.displayName == displayName && current.picture == picture) return
        accounts[index] = current.copy(displayName = displayName, picture = picture)
        encPrefs.edit().putString("accounts", json.encodeToString(accounts)).apply()
        _accounts.value = accounts
    }

    private fun loadAccountList(): List<AccountInfo> {
        val str = encPrefs.getString("accounts", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<AccountInfo>>(str)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- Existing key methods (write to legacy keys + register in account list) ---

    fun saveKeypair(keypair: Keys.Keypair) {
        val pubHex = keypair.pubkey.toHex()
        val privHex = keypair.privkey.toHex()
        encPrefs.edit()
            .putString("privkey", privHex)
            .putString("pubkey", pubHex)
            .putString("signing_mode", SigningMode.LOCAL.name)
            .remove("signer_package")
            .apply()
        addAccount(pubHex, SigningMode.LOCAL, privkeyHex = privHex)
    }

    fun getKeypair(): Keys.Keypair? {
        val privHex = encPrefs.getString("privkey", null) ?: return null
        val pubHex = encPrefs.getString("pubkey", null) ?: return null
        return Keys.Keypair(privHex.hexToByteArray(), pubHex.hexToByteArray())
    }

    fun savePubkeyOnly(pubkeyHex: String, signerPackage: String?) {
        encPrefs.edit()
            .putString("pubkey", pubkeyHex)
            .putString("signing_mode", SigningMode.REMOTE.name)
            .putString("signer_package", signerPackage)
            .remove("privkey")
            .apply()
        addAccount(pubkeyHex, SigningMode.REMOTE, signerPackage = signerPackage)
    }

    fun savePubkeyReadOnly(pubkeyHex: String) {
        encPrefs.edit()
            .putString("pubkey", pubkeyHex)
            .putString("signing_mode", SigningMode.READ_ONLY.name)
            .remove("privkey")
            .remove("signer_package")
            .apply()
        addAccount(pubkeyHex, SigningMode.READ_ONLY)
    }

    fun getSigningMode(): SigningMode {
        val mode = encPrefs.getString("signing_mode", null) ?: return SigningMode.LOCAL
        return try { SigningMode.valueOf(mode) } catch (_: Exception) { SigningMode.LOCAL }
    }

    fun isReadOnly(): Boolean = getSigningMode() == SigningMode.READ_ONLY

    fun getSignerPackage(): String? = encPrefs.getString("signer_package", null)

    fun getPubkeyHex(): String? = encPrefs.getString("pubkey", null)

    fun clearKeypair() {
        encPrefs.edit().clear().apply()
        _accounts.value = emptyList()
    }

    fun isLoggedIn(): Boolean = encPrefs.getString("pubkey", null) != null

    fun hasKeypair(): Boolean = encPrefs.getString("privkey", null) != null

    fun getNpub(): String? {
        val pubHex = encPrefs.getString("pubkey", null) ?: return null
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
        _localRelay.value = loadLocalRelay()
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

    fun saveLocalRelay(config: LocalRelayConfig?) {
        if (config != null) {
            prefs.edit().putString("local_relay", json.encodeToString(config)).apply()
        } else {
            prefs.edit().remove("local_relay").apply()
        }
        _localRelay.value = config
    }

    fun getLocalRelay(): LocalRelayConfig? = _localRelay.value

    private fun loadLocalRelay(): LocalRelayConfig? {
        val str = prefs.getString("local_relay", null) ?: return null
        return try { json.decodeFromString(str) } catch (_: Exception) { null }
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
