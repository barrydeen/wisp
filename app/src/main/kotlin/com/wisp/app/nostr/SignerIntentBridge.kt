package com.wisp.app.nostr

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SignerBridge"

sealed class SignResult {
    data class Success(val result: String, val event: String? = null) : SignResult()
    data object Rejected : SignResult()
    data object Cancelled : SignResult()
}

data class SignRequest(
    val intent: Intent,
    val deferred: CompletableDeferred<SignResult>
)

/**
 * Bridges the domain-layer RemoteSigner (which can't launch activities) with the
 * Compose UI layer (which owns the ActivityResultLauncher). Concurrent signing
 * requests are serialized by a mutex so only one Amber prompt is shown at a time.
 */
object SignerIntentBridge {
    private val _pendingRequest = MutableStateFlow<SignRequest?>(null)
    val pendingRequest: StateFlow<SignRequest?> = _pendingRequest

    private val mutex = Mutex()

    /**
     * Posts an intent-based signing request and suspends until the UI delivers a result.
     * Only one request is active at a time (mutex-serialized).
     */
    suspend fun requestSign(intent: Intent): SignResult {
        val type = intent.getStringExtra("type") ?: "unknown"
        Log.d(TAG, "requestSign($type) — waiting for mutex (locked=${mutex.isLocked})")
        return mutex.withLock {
            Log.d(TAG, "requestSign($type) — mutex acquired, posting request")
            postAndAwait(intent)
        }
    }

    /**
     * Acquires the mutex, runs [retryBlock] first (e.g. retry ContentResolver after a prior
     * intent may have granted permissions). If [retryBlock] returns non-null, returns that
     * without launching an intent. Otherwise posts the intent and awaits the result.
     */
    suspend fun requestSignWithRetry(
        intent: Intent,
        retryBlock: suspend () -> String?
    ): SignResult {
        val type = intent.getStringExtra("type") ?: "unknown"
        Log.d(TAG, "requestSignWithRetry($type) — waiting for mutex (locked=${mutex.isLocked})")
        return mutex.withLock {
            val retryResult = retryBlock()
            if (retryResult != null) {
                Log.d(TAG, "requestSignWithRetry($type) — CR retry succeeded, skipping intent")
                return@withLock SignResult.Success(retryResult)
            }
            Log.d(TAG, "requestSignWithRetry($type) — CR retry failed, posting intent")
            postAndAwait(intent)
        }
    }

    private suspend fun postAndAwait(intent: Intent): SignResult {
        val type = intent.getStringExtra("type") ?: "unknown"
        val deferred = CompletableDeferred<SignResult>()
        val request = SignRequest(intent, deferred)
        _pendingRequest.value = request
        try {
            val result = deferred.await()
            Log.d(TAG, "requestSign($type) — got result: ${result::class.simpleName}")
            return result
        } finally {
            _pendingRequest.value = null
            Log.d(TAG, "requestSign($type) — cleared pending request")
        }
    }

    /**
     * Called from the UI layer when the ActivityResultLauncher receives a result.
     */
    fun deliverResult(result: SignResult) {
        val pending = _pendingRequest.value
        Log.d(TAG, "deliverResult(${result::class.simpleName}) — pending=${pending != null}")
        pending?.deferred?.complete(result)
    }
}
