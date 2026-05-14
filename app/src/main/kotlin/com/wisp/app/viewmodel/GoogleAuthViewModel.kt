package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.auth.BackupCrypto
import com.wisp.app.auth.DriveBackupService
import com.wisp.app.auth.GoogleSignInException
import com.wisp.app.auth.GoogleSignInManager
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "GoogleAuth"

/**
 * Orchestrates the "Continue with Google" flow:
 *   1. Sign in via GoogleSignInManager → derive backup key from sub claim.
 *   2. List every backup in the user's Drive appDataFolder. Each filename
 *      embeds the npub (`wisp_nsec_<npub>.bin`) so we can show the chooser
 *      without downloading every file.
 *   3. UI shows a list of restorable accounts (if any) plus a "Create new
 *      account" option that's always available — users can keep adding new
 *      Nostr identities to the same Google account's backup space.
 *
 * The plaintext nsec only leaves Drive when the user actually picks Restore;
 * generation only happens when they pick Create.
 */
class GoogleAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val driveService = DriveBackupService()

    /** One restorable account entry surfaced in the chooser. */
    data class BackupSummary(
        val fileId: String,
        val npub: String,
        val pubkeyHex: String,
        val displayName: String? = null,
        val picture: String? = null
    )

    sealed class State {
        object Idle : State()
        object SigningIn : State()
        object CheckingDrive : State()
        data class Choose(val backups: List<BackupSummary>) : State()
        object Working : State()
        data class Done(val isNewAccount: Boolean) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var pendingBackupKey: ByteArray? = null
    private var pendingAccessToken: String? = null
    private var profileFetchJob: Job? = null

    fun beginSignIn(activity: ComponentActivity, webClientId: String) {
        Log.d(TAG, "beginSignIn called, current state=${_state.value::class.simpleName}, webClientId.length=${webClientId.length}")
        if (_state.value !is State.Idle && _state.value !is State.Error) {
            Log.d(TAG, "beginSignIn early-return: state is not Idle/Error")
            return
        }
        val manager = GoogleSignInManager(activity.applicationContext, webClientId)
        _state.value = State.SigningIn
        Log.d(TAG, "state -> SigningIn")
        viewModelScope.launch {
            try {
                Log.d(TAG, "calling manager.signIn(activity)…")
                val result = manager.signIn(activity)
                Log.d(TAG, "signIn returned: sub-len=${result.sub.length}, hasToken=${result.accessToken.isNotEmpty()}")
                val backupKey = BackupCrypto.deriveBackupKey(result.sub)
                pendingBackupKey = backupKey
                pendingAccessToken = result.accessToken

                _state.value = State.CheckingDrive
                Log.d(TAG, "state -> CheckingDrive")

                val files = driveService.listBackups(result.accessToken)
                Log.d(TAG, "listBackups returned ${files.size} file(s)")

                val summaries = files.mapNotNull { file ->
                    val npub = file.npubFromName ?: try {
                        // Legacy file with no npub in the filename — decrypt to learn it.
                        val payload = driveService.downloadBackup(result.accessToken, file.fileId)
                        val nsec = BackupCrypto.decryptNsec(payload, backupKey)
                        Nip19.npubEncode(Keys.xOnlyPubkey(nsec))
                    } catch (e: Exception) {
                        Log.w(TAG, "couldn't resolve npub for ${file.name}; skipping", e)
                        null
                    }
                    npub?.let {
                        val pubkeyHex = try {
                            Nip19.npubDecode(it).toHex()
                        } catch (e: Exception) {
                            Log.w(TAG, "couldn't decode npub $it", e)
                            return@let null
                        }
                        BackupSummary(fileId = file.fileId, npub = it, pubkeyHex = pubkeyHex)
                    }
                }.distinctBy { it.npub }

                _state.value = State.Choose(summaries)
                Log.d(TAG, "state -> Choose with ${summaries.size} restorable account(s)")
                if (summaries.isNotEmpty()) {
                    fetchProfilesInBackground(summaries.map { it.pubkeyHex })
                }
            } catch (e: GoogleSignInException) {
                Log.w(TAG, "GoogleSignInException", e)
                _state.value = State.Error(e.message ?: "Google sign-in failed.")
            } catch (e: Exception) {
                Log.w(TAG, "Exception during sign-in flow", e)
                _state.value = State.Error(e.message ?: "Something went wrong.")
            }
        }
    }

    fun restoreAccount(fileId: String) {
        Log.d(TAG, "restoreAccount tapped, fileId=$fileId")
        val key = pendingBackupKey ?: return
        val accessToken = pendingAccessToken ?: return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val payload = driveService.downloadBackup(accessToken, fileId)
                val nsec = BackupCrypto.decryptNsec(payload, key)
                val keypair = Keys.fromPrivkey(nsec)
                keyRepo.saveKeypair(keypair)
                keyRepo.reloadPrefs(keypair.pubkey.toHex())
                _state.value = State.Done(isNewAccount = false)
                Log.d(TAG, "state -> Done(isNewAccount=false)")
            } catch (e: Exception) {
                Log.w(TAG, "restoreAccount failed", e)
                _state.value = State.Error(e.message ?: "Failed to restore account.")
            }
        }
    }

    fun createNewAccount() {
        Log.d(TAG, "createNewAccount tapped")
        val key = pendingBackupKey ?: return
        val accessToken = pendingAccessToken ?: return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val keypair = Keys.generate()
                val npub = Nip19.npubEncode(keypair.pubkey)
                val payload = BackupCrypto.encryptNsec(keypair.privkey, key)
                driveService.uploadBackup(accessToken, npub, payload)
                keyRepo.saveKeypair(keypair)
                keyRepo.reloadPrefs(keypair.pubkey.toHex())
                val fiatPrefs = FiatPreferences.get(getApplication())
                fiatPrefs.setFiatMode(true)
                fiatPrefs.setCurrency("USD")
                _state.value = State.Done(isNewAccount = true)
                Log.d(TAG, "state -> Done(isNewAccount=true)")
            } catch (e: Exception) {
                Log.w(TAG, "createNewAccount failed", e)
                _state.value = State.Error(e.message ?: "Failed to create account.")
            }
        }
    }

    fun reset() {
        profileFetchJob?.cancel()
        profileFetchJob = null
        pendingBackupKey = null
        pendingAccessToken = null
        _state.value = State.Idle
    }

    /**
     * Opens ephemeral WebSocket connections to a couple of widely-used relays,
     * requests kind-0 profile metadata for each backup's pubkey, and merges the
     * parsed display name + picture into the Choose state as results arrive.
     * Cancelled when the user moves past the chooser.
     */
    private fun fetchProfilesInBackground(pubkeyHexList: List<String>) {
        profileFetchJob?.cancel()
        profileFetchJob = viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val pubkeys = pubkeyHexList.distinct()
            if (pubkeys.isEmpty()) return@launch

            val pubkeyJsonArray = pubkeys.joinToString(",") { "\"$it\"" }
            val reqMessage = """["REQ","wisp-google-profiles",{"kinds":[0],"authors":[$pubkeyJsonArray]}]"""

            val sockets = PROFILE_RELAYS.map { url ->
                try {
                    client.newWebSocket(
                        Request.Builder().url(url).build(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(reqMessage)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                handleProfileMessage(text)
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                Log.w(TAG, "profile relay $url failed", t)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't open profile relay $url", e)
                    null
                }
            }

            try {
                kotlinx.coroutines.delay(PROFILE_FETCH_TIMEOUT_MS)
            } finally {
                for (socket in sockets.filterNotNull()) {
                    try {
                        socket.send("""["CLOSE","wisp-google-profiles"]""")
                        socket.close(1000, null)
                    } catch (_: Exception) {}
                }
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun handleProfileMessage(text: String) {
        val arr = try { profileJson.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { return } ?: return
        if (arr.size < 3) return
        if (arr[0].jsonPrimitive.content != "EVENT") return
        val event = arr[2] as? JsonObject ?: return
        if (event["kind"]?.jsonPrimitive?.content != "0") return
        val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
        val content = event["content"]?.jsonPrimitive?.content ?: return
        val profile = try { profileJson.parseToJsonElement(content).jsonObject } catch (_: Exception) { return }

        val name = profile["display_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: profile["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val picture = profile["picture"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        if (name == null && picture == null) return

        // Merge into current Choose state if still active.
        val current = _state.value
        if (current !is State.Choose) return
        val updated = current.backups.map { backup ->
            if (backup.pubkeyHex == pubkey && (backup.displayName == null || backup.picture == null)) {
                backup.copy(
                    displayName = backup.displayName ?: name,
                    picture = backup.picture ?: picture
                )
            } else backup
        }
        _state.value = State.Choose(updated)
    }

    override fun onCleared() {
        super.onCleared()
        profileFetchJob?.cancel()
        pendingBackupKey = null
        pendingAccessToken = null
    }

    companion object {
        private val profileJson = Json { ignoreUnknownKeys = true }
        private val PROFILE_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net"
        )
        private const val PROFILE_FETCH_TIMEOUT_MS = 8_000L
    }
}
