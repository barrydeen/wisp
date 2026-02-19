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
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

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
        context.getSharedPreferences(prefsName(getKeypair()?.pubkey?.toHex()), Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun saveKeypair(keypair: Keys.Keypair) {
        encPrefs.edit()
            .putString("privkey", keypair.privkey.toHex())
            .putString("pubkey", keypair.pubkey.toHex())
            .apply()
    }

    fun getKeypair(): Keys.Keypair? {
        val privHex = encPrefs.getString("privkey", null) ?: return null
        val pubHex = encPrefs.getString("pubkey", null) ?: return null
        return Keys.Keypair(privHex.hexToByteArray(), pubHex.hexToByteArray())
    }

    fun clearKeypair() {
        encPrefs.edit().clear().apply()
    }

    fun hasKeypair(): Boolean = encPrefs.getString("privkey", null) != null

    fun getNpub(): String? {
        val pubHex = encPrefs.getString("pubkey", null) ?: return null
        return Nip19.npubEncode(pubHex.hexToByteArray())
    }

    fun reloadPrefs(pubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    }

    fun saveRelays(relays: List<RelayConfig>) {
        prefs.edit().putString("relays", json.encodeToString(relays)).apply()
    }

    fun getRelays(): List<RelayConfig> {
        val str = prefs.getString("relays", null) ?: return RelayConfig.DEFAULTS
        return try {
            json.decodeFromString<List<RelayConfig>>(str)
        } catch (_: Exception) {
            RelayConfig.DEFAULTS
        }
    }

    fun saveDmRelays(urls: List<String>) {
        prefs.edit().putString("dm_relays", json.encodeToString(urls)).apply()
    }

    fun getDmRelays(): List<String> {
        val str = prefs.getString("dm_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun saveSearchRelays(urls: List<String>) {
        prefs.edit().putString("search_relays", json.encodeToString(urls)).apply()
    }

    fun getSearchRelays(): List<String> {
        val str = prefs.getString("search_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun saveBlockedRelays(urls: List<String>) {
        prefs.edit().putString("blocked_relays", json.encodeToString(urls)).apply()
    }

    fun getBlockedRelays(): List<String> {
        val str = prefs.getString("blocked_relays", null) ?: return emptyList()
        return try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }

    fun isOnboardingComplete(): Boolean {
        if (prefs.getBoolean("onboarding_done", false)) return true
        // Migration for existing users who had the app before onboarding was added:
        // They'll have contacts data saved from previous sessions. New key users won't.
        val pubkeyHex = getKeypair()?.pubkey?.toHex() ?: return false
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
