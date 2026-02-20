package com.wisp.app.repo

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip47
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.Relay
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class NwcRepository(private val context: Context, private val relayPool: RelayPool? = null, pubkeyHex: String? = null) {
    private val TAG = "NwcRepository"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var encPrefs = createEncPrefs(pubkeyHex)

    private var connection: Nip47.NwcConnection? = null
    private var relay: Relay? = null
    private var scope: CoroutineScope? = null

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<Nip47.NwcResponse>>()

    private val _balance = MutableStateFlow<Long?>(null)
    val balance: StateFlow<Long?> = _balance

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /** Granular status updates emitted during connect flow */
    private val _statusLog = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val statusLog: SharedFlow<String> = _statusLog

    private fun emitStatus(msg: String) {
        Log.d(TAG, msg)
        _statusLog.tryEmit(msg)
    }

    fun hasConnection(): Boolean = encPrefs.getString("nwc_uri", null) != null

    fun saveConnectionString(uri: String) {
        encPrefs.edit().putString("nwc_uri", uri).apply()
    }

    fun getConnectionString(): String? = encPrefs.getString("nwc_uri", null)

    fun clearConnection() {
        encPrefs.edit().remove("nwc_uri").apply()
        _balance.value = null
        _isConnected.value = false
    }

    fun reload(pubkeyHex: String?) {
        disconnect()
        encPrefs = createEncPrefs(pubkeyHex)
        _balance.value = null
    }

    fun connect() {
        val uri = getConnectionString() ?: return
        val conn = Nip47.parseConnectionString(uri) ?: run {
            emitStatus("Failed to parse connection string")
            return
        }
        connection = conn

        // Disconnect old relay if any
        relay?.disconnect()

        // Drop matching relay from the pool to avoid duplicate connections
        relayPool?.disconnectRelay(conn.relayUrl)

        val client = Relay.createClient()
        val r = Relay(RelayConfig(conn.relayUrl), client)
        relay = r

        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        emitStatus("Connecting to relay ${conn.relayUrl}...")

        // Collect messages — route response events to pending request deferreds
        newScope.launch {
            r.messages.collect { message ->
                when (message) {
                    is RelayMessage.EventMsg -> {
                        if (message.event.kind == 23195) {
                            handleResponse(message.event)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Track relay connection state, fetch info event, and set up subscription
        newScope.launch {
            r.connectionState.collect { connected ->
                if (connected) {
                    emitStatus("Relay connected")

                    // Negotiate encryption before subscribing for responses
                    negotiateEncryption(r, conn)

                    // Subscribe for NWC response events
                    val filter = Filter(
                        kinds = listOf(23195),
                        pTags = listOf(conn.clientPubkey.toHex()),
                        since = System.currentTimeMillis() / 1000
                    )
                    r.send(ClientMessage.req("nwc-responses", filter))
                    emitStatus("Subscribed for responses")
                }
                _isConnected.value = connected
            }
        }

        r.connect()
    }

    /**
     * Fetch the wallet service's info event (kind 13194) to determine
     * supported encryption. Updates the connection's encryption accordingly.
     */
    private suspend fun negotiateEncryption(relay: Relay, conn: Nip47.NwcConnection) {
        val wsPubkeyHex = conn.walletServicePubkey.toHex()

        emitStatus("Fetching wallet info event...")

        // Request the info event
        val infoFilter = Filter(
            kinds = listOf(13194),
            authors = listOf(wsPubkeyHex),
            limit = 1
        )
        relay.send(ClientMessage.req("nwc-info", infoFilter))

        // Wait for the info event or EOSE (wallet may not publish one)
        val encryption = withTimeoutOrNull(5_000) {
            var result: Nip47.NwcEncryption? = null
            relay.messages.first { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> {
                        if (msg.subscriptionId == "nwc-info" && msg.event.kind == 13194) {
                            result = Nip47.parseInfoEncryption(msg.event)
                            true
                        } else false
                    }
                    is RelayMessage.Eose -> msg.subscriptionId == "nwc-info"
                    else -> false
                }
            }
            relay.send(ClientMessage.close("nwc-info"))
            result
        }

        val enc = encryption ?: Nip47.NwcEncryption.NIP04
        connection = conn.withEncryption(enc)
        emitStatus("Encryption: ${if (enc == Nip47.NwcEncryption.NIP44) "NIP-44" else "NIP-04"}")
    }

    private fun handleResponse(event: com.wisp.app.nostr.NostrEvent) {
        val conn = connection ?: return
        try {
            val response = Nip47.parseResponse(conn, event)
            emitStatus("Response decrypted")
            // Match by "e" tag pointing to request event id
            val requestId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            if (requestId != null) {
                pendingRequests.remove(requestId)?.complete(response)
            } else {
                Log.w(TAG, "NWC response has no 'e' tag, cannot match to request")
            }
        } catch (e: Exception) {
            emitStatus("Decrypt failed: ${e.message}")
            Log.e(TAG, "Failed to parse NWC response: ${e.message}")
        }
    }

    /**
     * Send a NWC request: publish the event and await the response via the
     * persistent subscription set up in [connect].
     */
    suspend fun sendRequest(
        request: Nip47.NwcRequest,
        timeoutMs: Long = 10_000
    ): Result<Nip47.NwcResponse> {
        val conn = connection ?: return Result.failure(Exception("Not connected"))
        val r = relay ?: return Result.failure(Exception("No relay"))
        if (!_isConnected.value) {
            emitStatus("Waiting for relay connection...")
            val connected = withTimeoutOrNull(10_000) { _isConnected.first { it } }
            if (connected == null) {
                emitStatus("Relay connection timed out")
                return Result.failure(Exception("Relay not connected"))
            }
        }

        val event = Nip47.buildRequest(conn, request)

        // Register deferred BEFORE publishing so we don't miss the response
        val deferred = CompletableDeferred<Nip47.NwcResponse>()
        pendingRequests[event.id] = deferred

        r.send(ClientMessage.event(event))
        emitStatus("Request sent, waiting for response...")

        return try {
            withTimeout(timeoutMs) {
                val response = deferred.await()
                if (response is Nip47.NwcResponse.Error) {
                    emitStatus("Wallet error: ${response.code}")
                    Result.failure(Exception("${response.code}: ${response.message}"))
                } else {
                    emitStatus("Success")
                    Result.success(response)
                }
            }
        } catch (e: Exception) {
            pendingRequests.remove(event.id)
            emitStatus("Timed out waiting for response")
            Result.failure(e)
        }
    }

    suspend fun fetchBalance(): Result<Long> {
        emitStatus("Fetching balance...")
        val result = sendRequest(Nip47.NwcRequest.GetBalance)
        return result.map { response ->
            val balance = (response as Nip47.NwcResponse.Balance).balanceMsats
            _balance.value = balance
            balance
        }
    }

    suspend fun payInvoice(bolt11: String): Result<String> {
        val result = sendRequest(Nip47.NwcRequest.PayInvoice(bolt11))
        return result.map { (it as Nip47.NwcResponse.PayInvoiceResult).preimage }
    }

    suspend fun makeInvoice(amountMsats: Long, description: String): Result<String> {
        val result = sendRequest(Nip47.NwcRequest.MakeInvoice(amountMsats, description))
        return result.map { (it as Nip47.NwcResponse.MakeInvoiceResult).invoice }
    }

    suspend fun listTransactions(limit: Int = 50): Result<List<Nip47.Transaction>> {
        val result = sendRequest(Nip47.NwcRequest.ListTransactions(limit = limit))
        return result.map { (it as Nip47.NwcResponse.ListTransactionsResult).transactions }
    }

    fun disconnect() {
        scope?.cancel()
        scope = null
        relay?.disconnect()
        relay = null
        connection = null
        _isConnected.value = false
    }

    private fun createEncPrefs(pubkeyHex: String?) = EncryptedSharedPreferences.create(
        context,
        if (pubkeyHex != null) "wisp_nwc_$pubkeyHex" else "wisp_nwc",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
