package com.wisp.app.nostr

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    suspend fun requestSign(intent: Intent): SignResult = mutex.withLock {
        val deferred = CompletableDeferred<SignResult>()
        val request = SignRequest(intent, deferred)
        _pendingRequest.value = request
        try {
            deferred.await()
        } finally {
            _pendingRequest.value = null
        }
    }

    /**
     * Called from the UI layer when the ActivityResultLauncher receives a result.
     */
    fun deliverResult(result: SignResult) {
        _pendingRequest.value?.deferred?.complete(result)
    }
}
