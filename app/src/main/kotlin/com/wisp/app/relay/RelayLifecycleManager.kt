package com.wisp.app.relay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages relay lifecycle in response to app lifecycle events and network changes.
 *
 * Extracted from FeedViewModel so that relay reconnection works regardless of
 * which screen is active. Previously, the lifecycle observer lived in FeedScreen's
 * DisposableEffect — meaning pauses/resumes while on Notifications, DMs, or any
 * other screen were silently ignored.
 *
 * Call [start] once after relays are initialized, and [stop] on account switch.
 * Call [onAppPause] / [onAppResume] from the Activity lifecycle.
 */
class RelayLifecycleManager(
    private val context: Context,
    private val relayPool: RelayPool,
    private val scope: CoroutineScope,
    private val onPreReconnect: (() -> Unit)? = null,
    private val onReconnected: (force: Boolean) -> Unit
) {
    private var connectivityJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var started = false
    @Volatile private var lastReconnectMs = 0L
    @Volatile private var lastReconnectForce = false

    /** Suppress network-change reconnects until this time (prevents double reconnect after resume). */
    @Volatile private var resumeReconnectUntilMs = 0L

    companion object {
        private const val TAG = "RelayLifecycleMgr"
        private const val FORCE_THRESHOLD_MS = 30_000L
        private const val DEBOUNCE_MS = 5_000L
        /** How long after a resume to suppress network-change-triggered reconnects. */
        private const val RESUME_SUPPRESSION_MS = 10_000L
    }

    /**
     * Begin observing network connectivity changes. Call once after relay pool
     * is initialized (e.g. after initRelays).
     */
    fun start() {
        if (started) return
        started = true

        connectivityJob = scope.launch {
            var lastNetworkId: Long? = null
            var initialEmission = true
            ConnectivityFlow.observe(context).collect { status ->
                when (status) {
                    is ConnectivityStatus.Active -> {
                        if (initialEmission) {
                            // Record initial network state without triggering reconnect —
                            // initRelays() already handles initial relay connections.
                            Log.d(TAG, "Initial network state: handle=${status.networkId}")
                            lastNetworkId = status.networkId
                            initialEmission = false
                        } else if (lastNetworkId != null && lastNetworkId != status.networkId) {
                            // Suppress network-change reconnects shortly after resume —
                            // ConnectivityFlow often fires 2-5s after resume, causing a
                            // second reconnect that tears down connections just established.
                            val now = System.currentTimeMillis()
                            if (now < resumeReconnectUntilMs) {
                                Log.d(TAG, "Network changed ($lastNetworkId → ${status.networkId}), suppressed (within resume window)")
                                lastNetworkId = status.networkId
                            } else {
                                Log.d(TAG, "Network changed ($lastNetworkId → ${status.networkId}), requesting reconnect")
                                lastNetworkId = status.networkId
                                reconnect(force = true)
                            }
                        } else if (lastNetworkId == null) {
                            Log.d(TAG, "Network restored, requesting reconnect")
                            lastNetworkId = status.networkId
                            reconnect(force = false)
                        }
                    }
                    is ConnectivityStatus.Off -> {
                        Log.d(TAG, "Network lost")
                        lastNetworkId = null
                        initialEmission = false
                    }
                }
            }
        }
    }

    /**
     * App moved to background. Records health sessions and marks pool inactive.
     */
    fun onAppPause() {
        if (!started) return
        Log.d("RLC", "[Lifecycle] onAppPause — connectedCount=${relayPool.connectedCount.value}")
        relayPool.appIsActive = false
        relayPool.healthTracker?.closeAllSessions()
    }

    /**
     * App returned to foreground. Reconnects relays based on pause duration.
     * Long pause (≥30s): force reconnect all + full re-subscribe.
     * Short pause (<30s): lightweight reconnect + resume existing subscriptions.
     */
    fun onAppResume(pausedMs: Long) {
        if (!started) return
        val force = pausedMs >= FORCE_THRESHOLD_MS
        Log.d("RLC", "[Lifecycle] onAppResume — paused ${pausedMs/1000}s, force=$force, connectedCount=${relayPool.connectedCount.value}")
        // Set suppression window to prevent network-change reconnects from
        // firing shortly after this resume and causing a double reconnect.
        resumeReconnectUntilMs = System.currentTimeMillis() + RESUME_SUPPRESSION_MS
        reconnect(force = force)
    }

    /**
     * Central reconnect entry point. Debounces rapid calls (e.g. ON_RESUME +
     * ConnectivityFlow firing within milliseconds of each other) and cancels
     * any in-flight reconnect job before starting a new one.
     *
     * Owns appIsActive lifecycle: sets false before reconnect, restores true
     * in finally block to guarantee it's restored even on cancellation.
     */
    private fun reconnect(force: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastReconnectMs < DEBOUNCE_MS) {
            // Allow force to upgrade a debounced non-force reconnect.
            // If the in-flight reconnect was non-force and this one is force,
            // cancel it and proceed with force. Otherwise, drop as before.
            if (force && !lastReconnectForce) {
                Log.d("RLC", "[Lifecycle] upgrading debounced reconnect to force")
                reconnectJob?.cancel()
            } else {
                Log.d("RLC", "[Lifecycle] reconnect debounced (${now - lastReconnectMs}ms since last, force=$force, lastForce=$lastReconnectForce)")
                return
            }
        }
        lastReconnectMs = now
        lastReconnectForce = force

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Lifecycle manager owns appIsActive — set false before reconnect,
            // restore in finally to guarantee it's set even on cancellation.
            relayPool.appIsActive = false
            if (force) {
                relayPool.healthTracker?.closeAllSessions()
            } else {
                relayPool.healthTracker?.discardAllSessions()
            }
            try {
                if (force) {
                    Log.d("RLC", "[Lifecycle] → forceReconnectAll()")
                    relayPool.forceReconnectAll()
                } else {
                    Log.d("RLC", "[Lifecycle] → reconnectAll()")
                    relayPool.reconnectAll()
                }
                // Let callers start priority ephemeral connections (e.g. trending
                // relay) so they connect in parallel with persistent relays instead
                // of waiting until after awaitAnyConnected.
                onPreReconnect?.invoke()
                val minCount = if (force) 3 else 1
                Log.d("RLC", "[Lifecycle] awaiting $minCount relays...")
                relayPool.awaitAnyConnected(minCount = minCount, timeoutMs = 5_000)
                Log.d("RLC", "[Lifecycle] await done — setting appIsActive=true, connectedCount=${relayPool.connectedCount.value}")
            } finally {
                relayPool.appIsActive = true
            }
            Log.d("RLC", "[Lifecycle] → onReconnected(force=$force)")
            onReconnected(force)
            Log.d("RLC", "[Lifecycle] onReconnected complete, connectedCount=${relayPool.connectedCount.value}")
        }
    }

    /**
     * Handle Tor on/off switch. Swaps the OkHttpClient, reconnects all relays,
     * and re-establishes subscriptions via [onReconnected].
     * Pass the full saved relay list so .onion relays are included when Tor turns on.
     */
    fun onTorSwitch(
        savedConfigs: List<RelayConfig>? = null,
        savedDmUrls: List<String>? = null
    ) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            relayPool.swapClientAndReconnect(savedConfigs, savedDmUrls)
            relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 10_000)
            relayPool.appIsActive = true
            onReconnected(true)
        }
    }

    /**
     * Stop observing. Call on account switch or cleanup.
     */
    fun stop() {
        connectivityJob?.cancel()
        connectivityJob = null
        started = false
    }
}
