package com.wisp.app.relay

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip11
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class ProbeResult(val url: String, val passed: Boolean, val latencyMs: Long, val reason: String)

enum class OnboardingPhase(val display: String) {
    CONNECTING("Connecting to bootstrap relays..."),
    DISCOVERING("Discovering relays..."),
    SELECTING("Selecting the perfect relays..."),
    TESTING("Testing relays..."),
    BROADCASTING("Broadcasting relay list..."),
    DONE("Done!"),
    FAILED("Using default relays")
}

object RelayProber {
    private const val TAG = "RelayProber"
    val BOOTSTRAP = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://indexer.coracle.social", "wss://relay.nos.social")
    const val HARVEST_LIMIT = 500
    const val MIN_FREQUENCY = 3
    const val TOP_EXCLUDE = 5
    const val CANDIDATES_TO_PROBE = 15
    const val TARGET_COUNT = 8
    const val PROBE_TIMEOUT_MS = 8000L
    const val NIP11_TIMEOUT_MS = 5000L
    private const val HARVEST_TIMEOUT_MS = 3000L

    suspend fun discoverAndSelect(
        keypair: Keys.Keypair,
        onPhase: (OnboardingPhase) -> Unit,
        onProbing: (String) -> Unit = {}
    ): List<RelayConfig> = withContext(Dispatchers.IO) {
        try {
            // 1. Connect to bootstrap relays and harvest kind 10002
            onPhase(OnboardingPhase.CONNECTING)
            val harvested = harvestRelayLists { onPhase(OnboardingPhase.DISCOVERING) }
            if (harvested.isEmpty()) {
                Log.w(TAG, "No relay lists harvested, using defaults")
                onPhase(OnboardingPhase.FAILED)
                return@withContext RelayConfig.DEFAULTS
            }
            Log.d(TAG, "Harvested ${harvested.size} relay list events")

            // 2. Tally relay URL frequency and filter to middle tier
            onPhase(OnboardingPhase.SELECTING)
            val tally = tallyRelayUrls(harvested)
            Log.d(TAG, "Tallied ${tally.size} unique relay URLs")

            val candidates = filterMiddleTier(tally)
            if (candidates.isEmpty()) {
                Log.w(TAG, "No middle-tier candidates, using defaults")
                onPhase(OnboardingPhase.FAILED)
                return@withContext RelayConfig.DEFAULTS
            }
            Log.d(TAG, "Selected ${candidates.size} candidates for probing: $candidates")

            // 3. Probe candidates concurrently
            onPhase(OnboardingPhase.TESTING)
            val results = probeCandidates(candidates, keypair, onProbing)
            val passed = results.filter { it.passed }.sortedBy { it.latencyMs }
            Log.d(TAG, "${passed.size} relays passed probing")

            // 4. Select top 8
            val selected = if (passed.isEmpty()) {
                Log.w(TAG, "No relays passed probing, using defaults")
                onPhase(OnboardingPhase.FAILED)
                return@withContext RelayConfig.DEFAULTS
            } else {
                passed.take(TARGET_COUNT).map { RelayConfig(it.url, read = true, write = true) }
            }

            Log.d(TAG, "Selected ${selected.size} relays: ${selected.map { it.url }}")
            onPhase(OnboardingPhase.DONE)
            selected
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed: ${e.message}", e)
            onPhase(OnboardingPhase.FAILED)
            RelayConfig.DEFAULTS
        }
    }

    private suspend fun harvestRelayLists(onFirstEvent: () -> Unit = {}): List<NostrEvent> {
        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val events = mutableListOf<NostrEvent>()

        for (bootstrapUrl in BOOTSTRAP) {
            try {
                val relay = Relay(RelayConfig(bootstrapUrl, read = true, write = false), client)
                relay.autoReconnect = false
                relay.connect()

                val connected = withTimeoutOrNull(5000L) {
                    while (!relay.isConnected) delay(100)
                    true
                } ?: false

                if (!connected) {
                    relay.disconnect()
                    continue
                }

                val subId = "harvest-${System.currentTimeMillis()}"
                val filter = Filter(kinds = listOf(10002), limit = HARVEST_LIMIT)
                relay.send(ClientMessage.req(subId, filter))

                // Collect events until EOSE or timeout.
                // Use a CompletableDeferred to break out of collect on EOSE.
                val done = CompletableDeferred<Unit>()
                val collectJob = coroutineScope {
                    launch {
                        relay.messages.collect { msg ->
                            when (msg) {
                                is RelayMessage.EventMsg -> {
                                    if (msg.subscriptionId == subId) {
                                        if (events.isEmpty()) onFirstEvent()
                                        events.add(msg.event)
                                    }
                                }
                                is RelayMessage.Eose -> {
                                    if (msg.subscriptionId == subId) {
                                        relay.send(ClientMessage.close(subId))
                                        done.complete(Unit)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }.also { job ->
                        // Cancel collection on EOSE or timeout
                        launch {
                            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { done.await() }
                            job.cancel()
                        }
                    }
                }

                relay.disconnect()
                // If we got enough from the first relay, skip the second
                if (events.size >= HARVEST_LIMIT / 2) break
            } catch (e: Exception) {
                Log.w(TAG, "Failed to harvest from $bootstrapUrl: ${e.message}")
            }
        }

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        return events
    }

    private fun tallyRelayUrls(events: List<NostrEvent>): Map<String, Int> {
        val tally = HashMap<String, Int>()
        for (event in events) {
            val relays = Nip65.parseRelayList(event)
            for (relay in relays) {
                val url = relay.url.trimEnd('/')
                if (RelayConfig.isAcceptableUrl(url)) {
                    tally[url] = (tally[url] ?: 0) + 1
                }
            }
        }
        return tally
    }

    private fun filterMiddleTier(tally: Map<String, Int>): List<String> {
        val sorted = tally.entries.sortedByDescending { it.value }
        return sorted
            .drop(TOP_EXCLUDE)                    // skip mega-relays
            .filter { it.value >= MIN_FREQUENCY } // skip too-obscure
            .take(CANDIDATES_TO_PROBE)
            .map { it.key }
    }

    private suspend fun probeCandidates(
        candidates: List<String>,
        keypair: Keys.Keypair,
        onProbing: (String) -> Unit
    ): List<ProbeResult> = coroutineScope {
        val nip11Client = OkHttpClient.Builder()
            .connectTimeout(NIP11_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(NIP11_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val wsClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val results = candidates.map { url ->
            async(Dispatchers.IO) {
                onProbing(url)
                probe(url, keypair, nip11Client, wsClient)
            }
        }.awaitAll()

        nip11Client.dispatcher.executorService.shutdown()
        nip11Client.connectionPool.evictAll()
        wsClient.dispatcher.executorService.shutdown()
        wsClient.connectionPool.evictAll()

        results
    }

    private suspend fun probe(
        url: String,
        keypair: Keys.Keypair,
        nip11Client: OkHttpClient,
        wsClient: OkHttpClient
    ): ProbeResult {
        val startTime = System.currentTimeMillis()

        // NIP-11 check — null (no doc) is OK, restrictive fields cause rejection
        val relayInfo = Nip11.fetchRelayInfo(url, nip11Client)
        if (relayInfo != null && !relayInfo.isOpenPublicRelay()) {
            return ProbeResult(url, false, 0, "NIP-11: restricted")
        }

        // Ephemeral write test: connect, send kind 20242, await OK
        return try {
            val relay = Relay(RelayConfig(url, read = true, write = true), wsClient)
            relay.autoReconnect = false
            relay.connect()

            val connected = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                while (!relay.isConnected) delay(100)
                true
            } ?: false

            if (!connected) {
                relay.disconnect()
                return ProbeResult(url, false, 0, "Connection timeout")
            }

            val event = NostrEvent.create(
                privkey = keypair.privkey,
                pubkey = keypair.pubkey,
                kind = 20242,
                content = ""
            )
            relay.send(ClientMessage.event(event))

            // Use CompletableDeferred to get the result out of collect
            val resultDeferred = CompletableDeferred<ProbeResult>()
            val collectJob = coroutineScope {
                launch {
                    relay.messages.collect { msg ->
                        if (msg is RelayMessage.Ok && msg.eventId == event.id) {
                            val latency = System.currentTimeMillis() - startTime
                            val result = if (msg.accepted) {
                                ProbeResult(url, true, latency, "OK")
                            } else {
                                ProbeResult(url, false, latency, "Rejected: ${msg.message}")
                            }
                            resultDeferred.complete(result)
                        }
                    }
                }.also { job ->
                    launch {
                        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                            resultDeferred.await()
                        }
                        if (result == null) {
                            resultDeferred.complete(ProbeResult(url, false, 0, "OK timeout"))
                        }
                        job.cancel()
                    }
                }
            }

            relay.disconnect()
            resultDeferred.await()
        } catch (e: Exception) {
            ProbeResult(url, false, 0, "Error: ${e.message}")
        }
    }
}
