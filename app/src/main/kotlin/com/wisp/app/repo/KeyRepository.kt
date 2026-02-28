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
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

enum class SigningMode { LOCAL, REMOTE }

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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun saveKeypair(keypair: Keys.Keypair) {
        encPrefs.edit()
            .putString("privkey", keypair.privkey.toHex())
            .putString("pubkey", keypair.pubkey.toHex())
            .putString("signing_mode", SigningMode.LOCAL.name)
            .remove("signer_package")
            .apply()
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
    }

    fun getSigningMode(): SigningMode {
        val mode = encPrefs.getString("signing_mode", null)
        return if (mode == SigningMode.REMOTE.name) SigningMode.REMOTE else SigningMode.LOCAL
    }

    fun getSignerPackage(): String? = encPrefs.getString("signer_package", null)

    fun getPubkeyHex(): String? = encPrefs.getString("pubkey", null)

    fun clearKeypair() {
        encPrefs.edit().clear().apply()
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
