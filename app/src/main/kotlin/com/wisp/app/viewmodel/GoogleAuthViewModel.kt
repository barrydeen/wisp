package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.auth.GoogleAccountDerivation
import com.wisp.app.auth.GoogleSignInException
import com.wisp.app.auth.GoogleSignInManager
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * "Continue with Google" flow with deterministic account derivation — no
 * backup events, no encryption, no Drive.
 *
 *   1. Sign in via Credential Manager → get the user's Google `sub`.
 *   2. Derive candidate keypairs for indices 0..MAX-1 from that `sub`.
 *   3. Query a set of public relays for any kind 0 / 3 / 10002 events from
 *      those derived pubkeys. A pubkey with any activity = an "in use"
 *      account that should appear in the chooser.
 *   4. The chooser shows discovered accounts (avatar + display name come
 *      from the kind-0 metadata, no separate fetch needed) plus "Create
 *      another account" — which derives the next unused index.
 *
 * Nothing is published, encrypted, or stored on relays. The user's own
 * normal Nostr activity is what makes their derived accounts discoverable.
 */
class GoogleAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    data class AccountSummary(
        val accountIndex: Int,
        val pubkeyHex: String,
        val displayName: String? = null,
        val picture: String? = null
    )

    sealed class State {
        object Idle : State()
        object SigningIn : State()
        object CheckingRelays : State()
        data class Choose(
            val accounts: List<AccountSummary>,
            val nextNewIndex: Int
        ) : State()
        object Working : State()
        data class Done(val isNewAccount: Boolean) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var pendingSub: String? = null

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
                val sub = manager.signIn(activity)
                Log.d(TAG, "signIn returned sub-len=${sub.length}")
                pendingSub = sub

                _state.value = State.CheckingRelays
                Log.d(TAG, "state -> CheckingRelays")

                val accounts = probeForActiveAccounts(sub)
                if (accounts.isEmpty()) {
                    Log.d(TAG, "no accounts found — auto-creating account at index 0")
                    createAccountAt(sub, accountIndex = 0)
                } else {
                    val nextNew = accounts.maxOf { it.accountIndex } + 1
                    _state.value = State.Choose(accounts, nextNew)
                    Log.d(TAG, "state -> Choose with ${accounts.size} active account(s), nextNew=$nextNew")
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

    fun selectAccount(accountIndex: Int) {
        Log.d(TAG, "selectAccount tapped, index=$accountIndex")
        val sub = pendingSub ?: return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val keypair = GoogleAccountDerivation.deriveAccountKeypair(sub, accountIndex)
                keyRepo.saveKeypair(keypair)
                keyRepo.reloadPrefs(keypair.pubkey.toHex())
                _state.value = State.Done(isNewAccount = false)
                Log.d(TAG, "state -> Done(isNewAccount=false)")
            } catch (e: Exception) {
                Log.w(TAG, "selectAccount failed", e)
                _state.value = State.Error(e.message ?: "Failed to log in.")
            }
        }
    }

    fun createNewAccount() {
        Log.d(TAG, "createNewAccount tapped")
        val sub = pendingSub ?: return
        val current = _state.value as? State.Choose ?: return
        val newIndex = current.nextNewIndex
        viewModelScope.launch {
            createAccountAt(sub, newIndex)
        }
    }

    /**
     * Shared helper: derives the keypair at [accountIndex], saves it locally,
     * and transitions to Done(isNewAccount = true). Called both from the user
     * tapping "Create another account" and from the auto-create-at-zero path
     * when no existing accounts are discovered on sign-in.
     */
    private suspend fun createAccountAt(sub: String, accountIndex: Int) {
        _state.value = State.Working
        try {
            val keypair = GoogleAccountDerivation.deriveAccountKeypair(sub, accountIndex)
            keyRepo.saveKeypair(keypair)
            keyRepo.reloadPrefs(keypair.pubkey.toHex())
            val fiatPrefs = FiatPreferences.get(getApplication())
            fiatPrefs.setFiatMode(true)
            fiatPrefs.setCurrency("USD")
            _state.value = State.Done(isNewAccount = true)
            Log.d(TAG, "state -> Done(isNewAccount=true), accountIndex=$accountIndex")
        } catch (e: Exception) {
            Log.w(TAG, "createAccountAt failed", e)
            _state.value = State.Error(e.message ?: "Failed to create account.")
        }
    }

    fun reset() {
        pendingSub = null
        _state.value = State.Idle
    }

    /**
     * Derives candidate keypairs for indices 0..MAX_INDEX-1, then queries a
     * set of widely-used relays for any kind 0 / 3 / 10002 events from those
     * pubkeys. Returns one [AccountSummary] per pubkey that has activity,
     * with profile data populated when a kind-0 was found in the same probe.
     */
    private suspend fun probeForActiveAccounts(sub: String): List<AccountSummary> = withContext(Dispatchers.IO) {
        data class Candidate(
            val accountIndex: Int,
            val pubkeyHex: String,
            var hasActivity: Boolean = false,
            var displayName: String? = null,
            var picture: String? = null
        )

        val candidates = (0 until MAX_INDEX).map { idx ->
            val keypair = GoogleAccountDerivation.deriveAccountKeypair(sub, idx)
            Candidate(accountIndex = idx, pubkeyHex = keypair.pubkey.toHex())
        }
        val byPubkey = candidates.associateBy { it.pubkeyHex }

        val authorList = candidates.joinToString(",") { "\"${it.pubkeyHex}\"" }
        val subId = "wisp-google-probe"
        val req = """["REQ","$subId",{"kinds":[0,3,10002],"authors":[$authorList]}]"""

        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val eoseCount = java.util.concurrent.atomic.AtomicInteger(0)
        val sockets = PROBE_RELAYS.map { url ->
            try {
                client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(req)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val arr = try { json.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null }
                                ?: return
                            if (arr.size < 2) return
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> {
                                    if (arr.size < 3 || arr[1].jsonPrimitive.content != subId) return
                                    val event = arr[2] as? JsonObject ?: return
                                    val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                                    val candidate = byPubkey[pubkey] ?: return
                                    candidate.hasActivity = true
                                    if (event["kind"]?.jsonPrimitive?.content == "0") {
                                        val content = event["content"]?.jsonPrimitive?.content
                                        if (content != null) {
                                            try {
                                                val profile = json.parseToJsonElement(content).jsonObject
                                                val name = profile["display_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                                    ?: profile["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                                val picture = profile["picture"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                                if (candidate.displayName == null) candidate.displayName = name
                                                if (candidate.picture == null) candidate.picture = picture
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                "EOSE" -> {
                                    if (arr[1].jsonPrimitive.content == subId) {
                                        eoseCount.incrementAndGet()
                                    }
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.w(TAG, "probe relay $url failed", t)
                            eoseCount.incrementAndGet()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open probe relay $url", e)
                eoseCount.incrementAndGet()
                null
            }
        }

        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (eoseCount.get() >= PROBE_RELAYS.size) break
            delay(100)
        }

        for (socket in sockets.filterNotNull()) {
            try {
                socket.send("""["CLOSE","$subId"]""")
                socket.close(1000, null)
            } catch (_: Exception) {}
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()

        candidates
            .filter { it.hasActivity }
            .map {
                AccountSummary(
                    accountIndex = it.accountIndex,
                    pubkeyHex = it.pubkeyHex,
                    displayName = it.displayName,
                    picture = it.picture
                )
            }
            .sortedBy { it.accountIndex }
    }

    override fun onCleared() {
        super.onCleared()
        pendingSub = null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_INDEX = 16
        private const val PROBE_TIMEOUT_MS = 6_000L
        private val PROBE_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://nostr.wine",
            "wss://relay.wisp.talk",
            "wss://relay.ditto.pub"
        )
    }
}
