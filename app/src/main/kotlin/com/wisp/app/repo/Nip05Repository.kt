package com.wisp.app.repo

import com.wisp.app.nostr.Nip05
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class Nip05Status { UNKNOWN, VERIFYING, VERIFIED, FAILED }

class Nip05Repository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statusCache = ConcurrentHashMap<String, Nip05Status>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getStatus(pubkey: String): Nip05Status = statusCache[pubkey] ?: Nip05Status.UNKNOWN

    /**
     * Called at render time. Returns cached result immediately or kicks off
     * a background fetch if this pubkey hasn't been checked yet.
     * Results are cached forever — pubkeys don't change.
     */
    fun checkOrFetch(pubkey: String, nip05Identifier: String) {
        val current = statusCache[pubkey]
        // Already resolved or in-flight — nothing to do
        if (current != null) return

        statusCache[pubkey] = Nip05Status.VERIFYING
        _version.value++

        scope.launch {
            val result = Nip05.verify(nip05Identifier, pubkey, httpClient)
            statusCache[pubkey] = if (result) Nip05Status.VERIFIED else Nip05Status.FAILED
            _version.value++
        }
    }
}
