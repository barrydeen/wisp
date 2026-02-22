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
    private val onReconnected: (force: Boolean) -> Unit
) {
    private var connectivityJob: Job? = null
    @Volatile private var started = false

    companion object {
        private const val TAG = "RelayLifecycleMgr"
        private const val FORCE_THRESHOLD_MS = 30_000L
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
            ConnectivityFlow.observe(context).collect { status ->
                when (status) {
                    is ConnectivityStatus.Active -> {
                        if (lastNetworkId != null && lastNetworkId != status.networkId) {
                            // Network transport changed (e.g. WiFi → cellular)
                            Log.d(TAG, "Network changed ($lastNetworkId → ${status.networkId}), force reconnecting")
                            relayPool.forceReconnectAll()
                            launch {
                                relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                                relayPool.appIsActive = true
                                onReconnected(true)
                            }
                        } else if (lastNetworkId == null) {
                            // Network restored after being lost
                            Log.d(TAG, "Network restored, reconnecting relays")
                            relayPool.reconnectAll()
                            launch {
                                relayPool.awaitAnyConnected(minCount = 3, timeoutMs = 5_000)
                                relayPool.appIsActive = true
                                onReconnected(false)
                            }
                        }
                        lastNetworkId = status.networkId
                    }
                    is ConnectivityStatus.Off -> {
                        Log.d(TAG, "Network lost")
                        lastNetworkId = null
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
        relayPool.appIsActive = false
        relayPool.healthTracker?.closeAllSessions()
        Log.d(TAG, "App paused — sessions closed")
    }

    /**
     * App returned to foreground. Reconnects relays based on pause duration.
     * Long pause (≥30s): force reconnect all + full re-subscribe.
     * Short pause (<30s): lightweight reconnect + resume existing subscriptions.
     */
    fun onAppResume(pausedMs: Long) {
        if (!started) return
        val force = pausedMs >= FORCE_THRESHOLD_MS
        if (force) {
            Log.d(TAG, "Long pause (${pausedMs / 1000}s) — force reconnecting all relays")
            relayPool.forceReconnectAll()
        } else {
            Log.d(TAG, "Short pause (${pausedMs / 1000}s) — lightweight reconnect")
            relayPool.reconnectAll()
        }
        scope.launch {
            val minCount = if (force) 3 else 1
            relayPool.awaitAnyConnected(minCount = minCount, timeoutMs = 5_000)
            relayPool.appIsActive = true
            onReconnected(force)
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
