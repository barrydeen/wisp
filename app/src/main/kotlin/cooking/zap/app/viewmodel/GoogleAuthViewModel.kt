package cooking.zap.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.auth.BackupCrypto
import cooking.zap.app.auth.DriveAuthorizationExpiredException
import cooking.zap.app.auth.DriveBackupService
import cooking.zap.app.auth.GoogleSignInException
import cooking.zap.app.auth.GoogleSignInManager
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.toHex
import cooking.zap.app.repo.FiatPreferences
import cooking.zap.app.repo.KeyBackupPreferences
import cooking.zap.app.repo.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "GoogleAuth"

/**
 * Orchestrates the "Continue with Google" flow:
 *   1. Sign in via GoogleSignInManager → keep the JWT `sub` claim around.
 *   2. List backups in the user's appData folder. Filenames are opaque
 *      (`wisp_bk_<uuid>.bin`); the npub is recovered by decrypting.
 *   3. Branch on what we find:
 *        - Files exist → prompt for the user's PIN, try to decrypt each file,
 *          and show the recovered accounts in the chooser. Files that fail to
 *          decrypt are treated as belonging to a different (or wrong) PIN.
 *        - No files → walk the user through setting a new PIN (enter, then
 *          confirm) and create their first account.
 *   4. From the chooser the user can restore one of the listed accounts or
 *      add another account under the same Google login (PIN already known).
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

    enum class SetupStep { Enter, Confirm }

    sealed class State {
        object Idle : State()
        object SigningIn : State()
        object CheckingDrive : State()

        /** Backups were found; the user must enter their existing PIN. */
        data class EnterPinForRestore(val attemptFailed: Boolean = false) : State()

        /** No backups found; walk the user through choosing a new PIN. */
        data class SetupPin(val step: SetupStep, val mismatch: Boolean = false) : State()

        data class Choose(val backups: List<BackupSummary>) : State()
        object Working : State()
        data class Done(val isNewAccount: Boolean) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var pendingSub: String? = null
    private var pendingAccessToken: String? = null
    private var pendingBackupKey: ByteArray? = null
    private var pendingFiles: List<DriveBackupService.BackupFile> = emptyList()
    private var pendingSetupFirstPin: String? = null
    private var signInManager: GoogleSignInManager? = null
    private var profileFetchJob: Job? = null

    fun beginSignIn(activity: ComponentActivity, webClientId: String) {
        Log.d(TAG, "beginSignIn called, current state=${_state.value::class.simpleName}, webClientId.length=${webClientId.length}")
        if (_state.value !is State.Idle && _state.value !is State.Error) {
            Log.d(TAG, "beginSignIn early-return: state is not Idle/Error")
            return
        }
        val manager = GoogleSignInManager(activity.applicationContext, webClientId)
        signInManager = manager
        _state.value = State.SigningIn
        viewModelScope.launch {
            try {
                val result = manager.signIn(activity)
                pendingSub = result.sub
                pendingAccessToken = result.accessToken

                _state.value = State.CheckingDrive
                val files = listBackupsWithRefresh(activity)
                pendingFiles = files
                Log.d(TAG, "listBackups returned ${files.size} file(s)")

                _state.value = if (files.isEmpty()) {
                    State.SetupPin(step = SetupStep.Enter)
                } else {
                    State.EnterPinForRestore()
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

    fun submitRestorePin(pin: String, activity: ComponentActivity) {
        if (!BackupCrypto.isValidPin(pin)) return
        val sub = pendingSub ?: return
        val files = pendingFiles
        if (files.isEmpty()) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.Default) { BackupCrypto.deriveBackupKey(sub, pin) }

                val summaries = files.mapNotNull { file ->
                    try {
                        val payload = downloadWithRefresh(activity, file.fileId)
                        val nsec = withContext(Dispatchers.Default) {
                            BackupCrypto.decryptNsec(payload, key)
                        }
                        val pubkey = Keys.xOnlyPubkey(nsec)
                        val npub = Nip19.npubEncode(pubkey)
                        BackupSummary(
                            fileId = file.fileId,
                            npub = npub,
                            pubkeyHex = pubkey.toHex()
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "decrypt failed for ${file.name} (likely wrong PIN or unrelated file)", e)
                        null
                    }
                }.distinctBy { it.npub }

                if (summaries.isEmpty()) {
                    _state.value = State.EnterPinForRestore(attemptFailed = true)
                    return@launch
                }

                pendingBackupKey = key
                _state.value = State.Choose(summaries)
                fetchProfilesInBackground(summaries.map { it.pubkeyHex })
            } catch (e: Exception) {
                Log.w(TAG, "submitRestorePin failed", e)
                _state.value = State.Error(e.message ?: "Failed to check PIN.")
            }
        }
    }

    fun submitSetupPinEntry(pin: String) {
        if (!BackupCrypto.isValidPin(pin)) return
        pendingSetupFirstPin = pin
        _state.value = State.SetupPin(step = SetupStep.Confirm)
    }

    fun submitSetupPinConfirm(pin: String, activity: ComponentActivity) {
        if (!BackupCrypto.isValidPin(pin)) return
        val first = pendingSetupFirstPin
        if (first == null || first != pin) {
            pendingSetupFirstPin = null
            _state.value = State.SetupPin(step = SetupStep.Enter, mismatch = true)
            return
        }
        val sub = pendingSub ?: return
        pendingSetupFirstPin = null
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.Default) { BackupCrypto.deriveBackupKey(sub, pin) }
                pendingBackupKey = key
                createAndStoreNewAccount(activity)
            } catch (e: Exception) {
                Log.w(TAG, "submitSetupPinConfirm failed", e)
                _state.value = State.Error(e.message ?: "Failed to set up PIN.")
            }
        }
    }

    fun backToSetupEntry() {
        pendingSetupFirstPin = null
        _state.value = State.SetupPin(step = SetupStep.Enter)
    }

    fun restoreAccount(fileId: String) {
        Log.d(TAG, "restoreAccount tapped, fileId=$fileId")
        val key = pendingBackupKey ?: return
        val accessToken = pendingAccessToken ?: return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val payload = driveService.downloadBackup(accessToken, fileId)
                val nsec = withContext(Dispatchers.Default) { BackupCrypto.decryptNsec(payload, key) }
                val keypair = Keys.fromPrivkey(nsec)
                keyRepo.saveKeypair(keypair)
                keyRepo.reloadPrefs(keypair.pubkey.toHex())
                _state.value = State.Done(isNewAccount = false)
            } catch (e: Exception) {
                Log.w(TAG, "restoreAccount failed", e)
                _state.value = State.Error(e.message ?: "Failed to restore account.")
            }
        }
    }

    /** Called from the Choose screen when the user wants to add another account
     *  to a Google login that already has backups. PIN is already known. */
    fun createAnotherAccount(activity: ComponentActivity) {
        if (pendingBackupKey == null) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                createAndStoreNewAccount(activity)
            } catch (e: Exception) {
                Log.w(TAG, "createAnotherAccount failed", e)
                _state.value = State.Error(e.message ?: "Failed to create account.")
            }
        }
    }

    private suspend fun createAndStoreNewAccount(activity: ComponentActivity) {
        val key = pendingBackupKey ?: error("backup key not derived")
        val keypair = Keys.generate()
        val payload = withContext(Dispatchers.Default) {
            BackupCrypto.encryptNsec(keypair.privkey, key)
        }
        uploadWithRefresh(activity, payload)
        keyRepo.saveKeypair(keypair)
        keyRepo.reloadPrefs(keypair.pubkey.toHex())
        // Drive backup above is the convenience floor; the manual reveal is additive.
        // Flag the new key as needing backup so the universal nudge fires once
        // AuthViewModel.refreshAfterExternalLogin() reloads this account's prefs.
        KeyBackupPreferences.markBackupNeededFor(getApplication(), keypair.pubkey.toHex())
        val fiatPrefs = FiatPreferences.get(getApplication())
        fiatPrefs.setFiatMode(true)
        fiatPrefs.setCurrency("USD")
        _state.value = State.Done(isNewAccount = true)
    }

    fun reset() {
        profileFetchJob?.cancel()
        profileFetchJob = null
        pendingSub = null
        pendingBackupKey = null
        pendingAccessToken = null
        pendingFiles = emptyList()
        pendingSetupFirstPin = null
        signInManager = null
        _state.value = State.Idle
    }

    private suspend fun listBackupsWithRefresh(
        activity: ComponentActivity
    ): List<DriveBackupService.BackupFile> {
        val token = pendingAccessToken ?: error("no pending access token")
        return try {
            driveService.listBackups(token)
        } catch (e: DriveAuthorizationExpiredException) {
            Log.w(TAG, "Drive returned 401 on list; refreshing token", e)
            val fresh = refreshToken(activity, e.staleToken)
            driveService.listBackups(fresh)
        }
    }

    private suspend fun downloadWithRefresh(activity: ComponentActivity, fileId: String): String {
        val token = pendingAccessToken ?: error("no pending access token")
        return try {
            driveService.downloadBackup(token, fileId)
        } catch (e: DriveAuthorizationExpiredException) {
            Log.w(TAG, "Drive returned 401 on download; refreshing token", e)
            val fresh = refreshToken(activity, e.staleToken)
            driveService.downloadBackup(fresh, fileId)
        }
    }

    private suspend fun uploadWithRefresh(activity: ComponentActivity, payload: String) {
        val token = pendingAccessToken ?: error("no pending access token")
        try {
            driveService.uploadBackup(token, payload)
        } catch (e: DriveAuthorizationExpiredException) {
            Log.w(TAG, "Drive returned 401 on upload; refreshing token", e)
            val fresh = refreshToken(activity, e.staleToken)
            driveService.uploadBackup(fresh, payload)
        }
    }

    private suspend fun refreshToken(activity: ComponentActivity, staleToken: String): String {
        val manager = signInManager ?: error("no sign-in manager — was beginSignIn called?")
        val fresh = manager.refreshDriveAccessToken(activity, staleToken)
        pendingAccessToken = fresh
        return fresh
    }

    /**
     * Pulls decoy profiles from a popular relay first, then issues one combined
     * REQ for (real + decoys) against the profile relays. From a relay
     * operator's perspective the real backup pubkeys are mixed in with random
     * recent profiles, blunting the "this Google account corresponds to these
     * npubs" linkage. UI updates filter to only the real pubkeys.
     */
    private fun fetchProfilesInBackground(realPubkeys: List<String>) {
        profileFetchJob?.cancel()
        profileFetchJob = viewModelScope.launch(Dispatchers.IO) {
            val real = realPubkeys.distinct()
            if (real.isEmpty()) return@launch

            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val decoys = withTimeoutOrNull(DECOY_FETCH_TIMEOUT_MS) {
                fetchDecoyPubkeys(client, DECOY_COUNT)
            }.orEmpty().filter { it !in real }
            Log.d(TAG, "decoys fetched: ${decoys.size}")

            val combined = (real + decoys).shuffled()
            val authorsJson = combined.joinToString(",") { "\"$it\"" }
            val reqMessage = """["REQ","wisp-google-profiles",{"kinds":[0],"authors":[$authorsJson]}]"""
            val realSet = real.toSet()

            val sockets = PROFILE_RELAYS.map { url ->
                try {
                    client.newWebSocket(
                        Request.Builder().url(url).build(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(reqMessage)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                handleProfileMessage(text, realSet)
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
                delay(PROFILE_FETCH_TIMEOUT_MS)
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

    private suspend fun fetchDecoyPubkeys(client: OkHttpClient, count: Int): List<String> =
        suspendCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val collected = Collections.synchronizedSet(mutableSetOf<String>())
            val subId = "wisp-google-decoys"
            val req = """["REQ","$subId",{"kinds":[0],"limit":$count}]"""

            fun resumeOnce(result: List<String>, ws: WebSocket?) {
                if (!resumed.compareAndSet(false, true)) return
                try {
                    ws?.send("""["CLOSE","$subId"]""")
                    ws?.close(1000, null)
                } catch (_: Exception) {}
                cont.resume(result)
            }

            client.newWebSocket(
                Request.Builder().url(DECOY_RELAY).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(req)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val arr = try {
                            profileJson.parseToJsonElement(text) as? JsonArray
                        } catch (_: Exception) { null } ?: return
                        if (arr.size < 2) return
                        val tag = try { arr[0].jsonPrimitive.content } catch (_: Exception) { return }
                        when (tag) {
                            "EVENT" -> {
                                if (arr.size < 3) return
                                val event = arr[2] as? JsonObject ?: return
                                val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                                collected.add(pubkey)
                                if (collected.size >= count) {
                                    resumeOnce(collected.toList(), webSocket)
                                }
                            }
                            "EOSE" -> resumeOnce(collected.toList(), webSocket)
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "decoy relay failed", t)
                        resumeOnce(emptyList(), null)
                    }
                }
            )
        }

    private fun handleProfileMessage(text: String, realPubkeys: Set<String>) {
        val arr = try { profileJson.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { return } ?: return
        if (arr.size < 3) return
        if (arr[0].jsonPrimitive.content != "EVENT") return
        val event = arr[2] as? JsonObject ?: return
        if (event["kind"]?.jsonPrimitive?.content != "0") return
        val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
        if (pubkey !in realPubkeys) return
        val content = event["content"]?.jsonPrimitive?.content ?: return
        val profile = try { profileJson.parseToJsonElement(content).jsonObject } catch (_: Exception) { return }

        val name = profile["display_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: profile["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val picture = profile["picture"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        if (name == null && picture == null) return

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
        pendingSub = null
        pendingBackupKey = null
        pendingAccessToken = null
        pendingFiles = emptyList()
        pendingSetupFirstPin = null
        signInManager = null
    }

    companion object {
        private val profileJson = Json { ignoreUnknownKeys = true }
        private val PROFILE_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net"
        )
        private const val DECOY_RELAY = "wss://relay.primal.net"
        private const val DECOY_COUNT = 10
        private const val DECOY_FETCH_TIMEOUT_MS = 4_000L
        private const val PROFILE_FETCH_TIMEOUT_MS = 8_000L
    }
}
