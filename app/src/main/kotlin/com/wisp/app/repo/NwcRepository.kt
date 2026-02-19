package com.wisp.app.repo

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip47
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.relay.Relay
import com.wisp.app.relay.RelayConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class NwcRepository(private val context: Context, pubkeyHex: String? = null) {
    private val TAG = "NwcRepository"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var encPrefs = createEncPrefs(pubkeyHex)

    private var connection: Nip47.NwcConnection? = null
    private var relay: Relay? = null
    private var scope: CoroutineScope? = null

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<Nip47.NwcResponse>>()
    private var requestCounter = 0

    private val _balance = MutableStateFlow<Long?>(null)
    val balance: StateFlow<Long?> = _balance

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

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
        val conn = Nip47.parseConnectionString(uri) ?: return
        connection = conn

        val client = Relay.createClient()
        val r = Relay(RelayConfig(conn.relayUrl), client)
        relay = r

        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        // Collect messages — route response events to pending request deferreds
        newScope.launch {
            r.messages.collect { message ->
                when (message) {
                    is RelayMessage.EventMsg -> {
                        if (message.event.kind == 23195) {
                            handleResponse(message.event)
                        }
                    }
                    is RelayMessage.Eose -> {
                        // When a per-request subscription gets EOSE, the sub is live
                        val subId = message.subscriptionId
                        if (subId.startsWith("nwc-req-")) {
                            // EOSE means the relay has confirmed the subscription;
                            // the actual request EVENT is sent after this (see sendRequest)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Track relay connection state
        newScope.launch {
            r.connectionState.collect { connected ->
                _isConnected.value = connected
            }
        }

        r.connect()
    }

    private fun handleResponse(event: com.wisp.app.nostr.NostrEvent) {
        val conn = connection ?: return
        try {
            val response = Nip47.parseResponse(conn, event)
            // Match by "e" tag pointing to request event id
            val requestId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            if (requestId != null) {
                Log.d(TAG, "Received NWC response for request $requestId")
                pendingRequests.remove(requestId)?.complete(response)
            } else {
                Log.w(TAG, "NWC response has no 'e' tag, cannot match to request")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NWC response: ${e.message}")
        }
    }

    /**
     * Send a NWC request using the per-request subscribe-then-publish pattern:
     * 1. Subscribe for kind 23195 responses filtered by our request event ID
     * 2. Wait for EOSE (relay confirms subscription is active)
     * 3. Publish the kind 23194 request event
     * 4. Await the response on the deferred
     * 5. Close the per-request subscription
     */
    suspend fun sendRequest(
        request: Nip47.NwcRequest,
        timeoutMs: Long = 30_000
    ): Result<Nip47.NwcResponse> {
        val conn = connection ?: return Result.failure(Exception("Not connected"))
        val r = relay ?: return Result.failure(Exception("No relay"))
        if (!r.isConnected) return Result.failure(Exception("Relay not connected"))

        // Build the request event
        val event = Nip47.buildRequest(conn, request)
        val subId = "nwc-req-${requestCounter++}"

        // Register deferred BEFORE subscribing so we don't miss anything
        val deferred = CompletableDeferred<Nip47.NwcResponse>()
        pendingRequests[event.id] = deferred

        // Step 1: Start EOSE listener BEFORE sending REQ to avoid race condition
        val eoseDeferred = CompletableDeferred<Unit>()
        val eoseScope = scope ?: return Result.failure(Exception("No scope"))

        val eoseJob = eoseScope.launch {
            r.messages.collect { message ->
                if (message is RelayMessage.Eose && message.subscriptionId == subId) {
                    eoseDeferred.complete(Unit)
                    return@collect
                }
            }
        }

        // Step 2: Subscribe for responses to THIS specific request
        val filter = Filter(
            kinds = listOf(23195),
            eTags = listOf(event.id),
            limit = 1
        )
        r.send(ClientMessage.req(subId, filter))

        return try {
            withTimeout(timeoutMs) {
                // Wait for EOSE
                eoseDeferred.await()
                eoseJob.cancel()

                // Step 3: NOW publish the request event
                r.send(ClientMessage.event(event))
                Log.d(TAG, "Sent NWC request: ${event.id}")

                // Step 4: Await response
                val response = deferred.await()

                // Step 5: Close per-request subscription
                r.send(ClientMessage.close(subId))

                if (response is Nip47.NwcResponse.Error) {
                    Result.failure(Exception("${response.code}: ${response.message}"))
                } else {
                    Result.success(response)
                }
            }
        } catch (e: Exception) {
            pendingRequests.remove(event.id)
            eoseJob.cancel()
            r.send(ClientMessage.close(subId))
            Result.failure(e)
        }
    }

    suspend fun fetchBalance(): Result<Long> {
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
