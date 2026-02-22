package com.wisp.app.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Connectivity status representing the current network state.
 */
sealed class ConnectivityStatus {
    /** Network is available. [networkId] changes when the underlying transport changes. */
    data class Active(val networkId: Long, val isMobile: Boolean) : ConnectivityStatus()
    /** No network available. */
    data object Off : ConnectivityStatus()
}

/**
 * Wraps Android's ConnectivityManager.NetworkCallback as a Flow.
 *
 * Emits [ConnectivityStatus.Active] when a network becomes available or changes,
 * and [ConnectivityStatus.Off] when all networks are lost.
 *
 * The flow is debounced (100ms) and deduplicated so rapid WiFi↔cellular handoffs
 * don't cause reconnection storms.
 */
@OptIn(FlowPreview::class)
object ConnectivityFlow {
    private const val TAG = "ConnectivityFlow"

    fun observe(context: Context): Flow<ConnectivityStatus> = callbackFlow {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val status = ConnectivityStatus.Active(network.networkHandle, isMobile)
                Log.d(TAG, "Network available: handle=${network.networkHandle}, mobile=$isMobile")
                trySend(status)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val isMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val status = ConnectivityStatus.Active(network.networkHandle, isMobile)
                trySend(status)
            }

            override fun onLost(network: Network) {
                // Check if there's still another active network
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork == null) {
                    Log.d(TAG, "All networks lost")
                    trySend(ConnectivityStatus.Off)
                } else {
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    Log.d(TAG, "Network lost but fallback available: handle=${activeNetwork.networkHandle}")
                    trySend(ConnectivityStatus.Active(activeNetwork.networkHandle, isMobile))
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        Log.d(TAG, "Registered network callback")

        // Emit initial state
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            trySend(ConnectivityStatus.Active(activeNetwork.networkHandle, isMobile))
        } else {
            trySend(ConnectivityStatus.Off)
        }

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            Log.d(TAG, "Unregistered network callback")
        }
    }
        .distinctUntilChanged()
        .debounce(100)
}
