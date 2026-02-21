package com.wisp.app.repo

import com.wisp.app.nostr.Nip05
import com.wisp.app.nostr.Nip05Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class Nip05Status {
    UNKNOWN,
    VERIFYING,
    /** Pubkey matched — permanent, never rechecked. */
    VERIFIED,
    /** Server returned a different pubkey — impersonation. Permanent. */
    IMPERSONATOR,
    /** Server unreachable or parse error — temporary, can retry. */
    ERROR
}

class Nip05Repository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statusCache = ConcurrentHashMap<String, Nip05Status>()
    private val identifierCache = ConcurrentHashMap<String, String>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun clear() {
        statusCache.clear()
        identifierCache.clear()
        _version.value++
    }

    fun getStatus(pubkey: String): Nip05Status = statusCache[pubkey] ?: Nip05Status.UNKNOWN

    /**
     * Called at render time. Returns cached result immediately or kicks off
     * a background fetch if this pubkey hasn't been checked yet.
     * VERIFIED and IMPERSONATOR are permanent. ERROR can be retried via [retry].
     */
    fun checkOrFetch(pubkey: String, nip05Identifier: String) {
        identifierCache[pubkey] = nip05Identifier
        val current = statusCache[pubkey]
        if (current != null) return

        launchVerification(pubkey, nip05Identifier)
    }

    /**
     * Retry verification for a pubkey currently in ERROR state.
     */
    fun retry(pubkey: String) {
        val current = statusCache[pubkey]
        if (current != Nip05Status.ERROR) return
        val identifier = identifierCache[pubkey] ?: return

        launchVerification(pubkey, identifier)
    }

    private fun launchVerification(pubkey: String, nip05Identifier: String) {
        statusCache[pubkey] = Nip05Status.VERIFYING
        _version.value++

        scope.launch {
            val result = Nip05.verify(nip05Identifier, pubkey, httpClient)
            statusCache[pubkey] = when (result) {
                Nip05Result.VERIFIED -> Nip05Status.VERIFIED
                Nip05Result.MISMATCH -> Nip05Status.IMPERSONATOR
                Nip05Result.ERROR -> Nip05Status.ERROR
            }
            _version.value++
        }
    }
}
